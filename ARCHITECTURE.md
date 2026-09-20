# Architecture technique

## Principes

L'application est un client spécialisé et non un client VNC universel. L'architecture doit rester simple et compatible API 17.

```text
UI
├── MainActivity
├── ConnectionActivity
└── RemoteActivity
      │
      ├── RemoteSurfaceView
      ├── InputController
      └── ConnectionController
             │
             ▼
          RfbClient
          ├── transport
          │   └── RfbSocket
          ├── protocol
          │   ├── Handshake
          │   ├── PixelFormat
          │   └── messages
          ├── encoding
          │   ├── RawDecoder
          │   ├── CopyRectDecoder
          │   └── HextileDecoder
          └── framebuffer
              └── Framebuffer
```

## Threads

- **UI thread** : Activity, SurfaceView, événements utilisateur.
- **I/O worker** : socket, handshake, lecture des messages RFB.
- **Envoi des entrées** (`secondscreen-input`) : écrit les `PointerEvent` ; le thread UI ne fait que les poser dans une file bornée.
- **Rendu** : privilégier une stratégie simple SurfaceView ; mesurer avant d'ajouter un thread supplémentaire.

Aucune lecture bloquante de socket sur le thread UI.

## Kotlin et API 17

Le projet peut être écrit en Kotlin tout en ciblant `minSdk = 17`. Toute API Android appelée doit être disponible en API 17 ou protégée par une vérification de version. Éviter les bibliothèques dont le `minSdk` dépasse 17.

## Framebuffer

Résolution nominale : 1280×800.

Un buffer ARGB_8888 brut représente environ 4,1 Mio (`1280 × 800 × 4`). Il faut éviter les copies multiples. La conception cible :

1. un stockage principal des pixels ;
2. un Bitmap mutable de rendu ou une stratégie équivalente ;
3. mise à jour des rectangles modifiés ;
4. invalidation/rendu ciblé.

Tester RGB_565 uniquement si la mémoire devient un problème et si la perte de qualité est acceptable.

**Implémentation (SS-020)** : le stockage principal est un `IntArray` ARGB_8888 (`rfb.framebuffer.Framebuffer`), alloué une fois, en JVM pur donc testable sans appareil. Les mises à jour (`fillRect`, `writeRect`) valident le rectangle avant d'écrire et lèvent `RectangleOutOfBounds` sinon. Le rendu (SS-031) copiera le seul rectangle modifié dans le Bitmap avec `Bitmap.setPixels(pixels, offset, stride, x, y, w, h)` (API 1), sans copie intermédiaire. Le suivi des rectangles modifiés et la synchronisation entre le thread I/O (décodage) et le rendu restent à définir avec SS-031.

## Rendu (SS-030, SS-031, SS-032, SS-034)

### Pipeline (SS-031)

```text
thread I/O : décodeur écrit les pixels -> rectangleListener (zone modifiée) -> onFramebufferUpdated (1 fois par update)
                                                                                  |
                copie de la SEULE zone modifiée dans le Bitmap (setPixels offset/stride), dessin via lockCanvas(dirty)
```

`render/RemoteSurfaceView` (un `SurfaceView`) implémente `RenderTarget` : le décodeur écrit ses pixels **puis** signale le rectangle, et demande le rendu **une fois par `FramebufferUpdate`**. `DirtyRegion` retient la boîte englobante des rectangles (sans allocation, thread-safe). Le rendu se fait sur le thread appelant (le thread I/O), sans thread de rendu supplémentaire, conformément à « mesurer avant d'ajouter un thread ».

- **Aucun verrou sur les pixels** : la visibilité des écritures est garantie par le verrou du `DirtyRegion` (le décodeur écrit puis appelle `add`, le rendu appelle `take` puis lit). Un rectangle en cours de décodage qui recouvre une zone déjà signalée peut donc être visible à moitié pendant une image, corrigé à la suivante.
- **`renderLock`** sérialise le dessin avec `surfaceDestroyed` : après le retour de ce callback plus aucun dessin n'est en cours.
- Bitmap, `Rect` de zone et `Paint` sont alloués **une fois** ; un Bitmap neuf (vide) impose un rendu complet, décidé *avant* de choisir la zone à dessiner.
- **Le compromis de la boîte englobante** : deux petites zones éloignées donnent une grande zone à copier. Choix simple et sans allocation ; un suivi plus fin serait une optimisation à justifier par mesure (SS-062).

### Fidélité 1:1 (SS-032)

L'image est dessinée en (0, 0), **sans mise à l'échelle** : filtrage désactivé, `Bitmap.DENSITY_NONE` (sans quoi Android peut redimensionner selon la densité), surface en `RGBX_8888` (le tampon par défaut d'un `SurfaceView` ancien est en 16 bits). `RenderGeometry` dit si le rendu est natif, tronqué ou entouré de noir. `RemoteActivity` est en `sensorLandscape` (les deux paysages).

### Mode immersif (SS-034)

`ui/ImmersiveController` masque la barre d'état et la barre de navigation avec des drapeaux compatibles API 17 (`HIDE_NAVIGATION`, `FULLSCREEN`, `LOW_PROFILE` et les trois `LAYOUT_*`, qui font que la barre **recouvre** l'image au lieu de redimensionner la surface). La logique est testable sans Android (`SystemUiHost`).

**Limites d'Android 4.2, mesurées sur la GT-P5110 :**
- il n'existe pas de mode immersif « collant » (API 19) : un toucher fait réapparaître la barre, qui recouvre alors les **48 lignes du bas** de l'écran distant (là où Windows place sa barre des tâches) pendant 3 s, puis est remasquée ;
- **le toucher qui fait réapparaître la barre n'est pas transmis à l'application.** Comme la barre est remasquée après 3 s, **le premier toucher après chaque remasquage est perdu** ;
- le remasquage est volontairement **différé** (3 s) pour laisser le temps d'appuyer sur Retour ou Accueil : ne jamais le rendre immédiat, l'utilisateur ne pourrait plus quitter.

Conséquence pour SS-041 (tap = clic) et SS-052 (barre de commandes) : la décision de garder ou non le remasquage automatique reste à prendre. Pistes : ne remasquer qu'après une période d'inactivité tactile plus longue, ne masquer que sur action explicite de l'utilisateur, ou accepter la perte du premier toucher. Le drapeau `IMMERSIVE_STICKY` (API 19), qui règlerait cela sur un Android plus récent, n'est **pas** implémenté : je n'ai aucun appareil pour le tester.

### Vérifié sur la GT-P5110

| Vérification | Résultat |
|---|---|
| Écran distant statique en 1:1 (comparaison pixel à pixel d'une capture) | **0 différence sur 1 024 000 pixels** (SS-030 seul : 962 560 pixels, 48 lignes sous la barre) |
| 600 images de mises à jour partielles (2 rectangles de 64×64 chacune), image finale | **0 différence sur 1 024 000 pixels** |
| Ramasse-miettes pendant ~22 s d'animation en régime établi | **0 événement** |
| Surface détruite puis recréée (Accueil puis retour), 3 fois | 0 différence, aucun plantage |
| Toucher puis Retour sur la barre réapparue / touche Retour barre cachée / Accueil | l'application se ferme ou passe en arrière-plan à chaque fois |

**Non vérifié** : le retournement de la tablette d'un paysage à l'autre. `sensorLandscape` suit le capteur et ignore le réglage de rotation forcée : mon essai avec `user_rotation` n'a jamais changé l'orientation, il n'établit donc rien.

## Entrées (SS-040 à SS-043)

```text
thread UI :  MotionEvent -> TouchInput -> TouchGestureDetector -> PointerActions -> PointerSender.send() / sendMove() (file bornée, non bloquant)
                                                          (PointerMapper : pixel de la vue -> pixel du framebuffer)
thread secondscreen-input :  file -> RfbSocket.write()  (un message entier par appel)
```

- **`PointerEvent`** (`ClientMessages.pointerEvent`, 6 octets : type 5, masque des boutons, x et y en U16 big-endian) : l'état des boutons est **absolu**, le serveur déduit appuis et relâchements en le comparant au précédent. Les valeurs hors plage sont refusées, jamais tronquées.
- **Coordonnées** (`PointerMapper`) : le rendu est 1:1 ancré en (0, 0), donc `pixel = floor(coordonnée)` (arrondir au plus proche décalerait la cible d'un pixel une fois sur deux). Un toucher hors du framebuffer, ou non fini, n'est **pas** envoyé (pas de clic sur le pixel du bord). SS-033 (letterbox) devra ajouter ici décalage et rapport.
- **Tap = clic gauche** (`TouchGestureDetector`) : le clic n'est émis qu'**au relâchement**, jamais à l'appui, afin qu'un appui qui devient un geste ne produise pas de clic gauche parasite. Pas de clic si le doigt bouge de plus du seuil de la plateforme (`scaledTouchSlop` : c'est alors un glissement), si le contact atteint le seuil d'appui long (c'est alors un clic droit, voir plus bas), si un deuxième doigt se pose (SS-044), ou si le système annule le geste. Un relâchement dupliqué ne clique pas deux fois.
- **Appui long = clic droit** (SS-043, `LongPress` + `TouchGestureDetector`). Un doigt qui reste dans le seuil de mouvement pendant le seuil d'appui long envoie **un seul clic droit** (`ClientMessages.rightClick` : survol, appui du bouton droit — masque 4 —, relâchement, un seul message de 18 octets), **puis le reste du geste est ignoré** : ni tap ni glissement, donc **aucun clic gauche parasite** au relâchement, même si le doigt bouge ensuite.
  - **Seuil configurable** : `LongPress.thresholdMs`, de 200 à 3 000 ms. Par défaut celui de la plateforme (`ViewConfiguration.getLongPressTimeout()`, 500 ms), donc le réglage d'accessibilité « délai d'appui prolongé » de l'utilisateur s'applique. Il est lu à la création de l'activité : un changement de réglage demande de rouvrir l'écran distant. Un réglage propre à l'application (SS-05x) n'existe pas encore.
  - **Se déclenche doigt encore posé**, par un minuteur de la vue (thread UI, `DelayScheduler`), comme partout dans Android. La décision repose pourtant sur l'**horodatage** des événements : si le fil UI est occupé et que l'`ACTION_UP` arrive avec une durée ≥ seuil avant que le minuteur ait tourné, c'est quand même un appui long. Un contact de durée `< seuil` est un tap, `>= seuil` un appui long : **aucune zone morte**, même pour un seuil supérieur à 500 ms.
  - Annulé par : un mouvement au-delà du seuil de mouvement (c'est un glissement), un deuxième doigt, une annulation. Le minuteur est annulé à chaque fin de geste et un minuteur en retard ne peut rien déclencher.
  - **Retour haptique** (`performHapticFeedback(LONG_PRESS)`, aucune permission) : sans effet sur la GT-P5110, qui n'a pas de vibreur. **Il n'y a donc aucun retour au doigt** sur cette tablette : l'utilisateur ne sait pas que le seuil est atteint. Un retour visuel reste à décider.
- **Glissement = déplacement avec le bouton gauche maintenu** (SS-042, `TouchGestureDetector` + `PointerActions`). Quand le doigt dépasse le seuil, le bouton est enfoncé **là où le doigt s'est posé** (`ClientMessages.dragStart` : survol puis appui, 12 octets, un seul `write`), puis chaque déplacement envoie un `PointerEvent` bouton enfoncé, et le relâchement (`PointerEvent` masque 0) part à la fin. Garde-fous :
  - **le bouton n'est jamais laissé enfoncé** : fin normale, `ACTION_CANCEL`, deuxième doigt, nouveau toucher après un relâchement perdu, perte du focus et pause de l'activité relâchent tous le bouton (`onDragEnd` est appelé exactement une fois par glissement, propriété vérifiée sur des suites d'événements aléatoires) ;
  - **le relâchement se fait à la dernière position de déplacement, pas à celle de `ACTION_UP`** : le pointeur distant y est déjà. Mesuré sur la GT-P5110 : l'émulation `input swipe` d'Android 4.2 envoie un `ACTION_UP` à la position de départ, ce qui aurait ramené le pointeur au départ à chaque relâchement ;
  - **pas d'appui fantôme** : si l'appui n'a pas pu être mis en file (départ hors du framebuffer, file pleine), tout le glissement est ignoré, car un déplacement bouton enfoncé serait interprété par le serveur comme un appui n'importe où ;
  - **le doigt qui sort du cadre** ne casse pas le glissement : la position est **bornée** au framebuffer (`PointerMapper.mapClamped`) et le pointeur distant suit le bord ;
  - un déplacement qui ne change pas le pixel visé n'envoie rien.
- **Un clic = un seul message de 18 octets** (`ClientMessages.leftClick` : survol sans bouton, appui, relâchement, au même pixel), écrit en un seul `write` : l'appui n'est jamais envoyé sans son relâchement (bouton coincé côté serveur), ni entrelacé avec le battement Wi-Fi ou un autre message.
- **`PointerSender`** : aucun accès réseau sur le thread UI. La file est bornée (64) : si la liaison se bloque, `send` refuse des messages **entiers** sans jamais attendre (mémoire bornée) ; une erreur d'écriture arrête l'envoyeur et est signalée une fois ; à l'arrêt, les messages en attente sont abandonnés (un clic tardif serait pire qu'un clic perdu). **Deux priorités** : les messages d'état (appui, relâchement, clic, via `send`) ne doivent pas se perdre, les déplacements (`sendMove`) sont remplaçables ; ces derniers ne sont acceptés que tant qu'il reste de la place pour les premiers (un quart de la file est réservé). Sur une liaison lente on perd des déplacements, jamais le relâchement.

**Provisoire** : `RemoteActivity` n'est pas encore reliée à un serveur (SS-054). Elle emprunte le chemin réel jusqu'à la file d'envoi, mais l'écriture finale ne fait que compter les messages ; SS-054 y branchera `PointerSender.forSocket`.

**Conséquence du mode immersif** (voir plus haut) : sur Android 4.2, le premier toucher après chaque remasquage de la barre est perdu, et la barre réapparue intercepte les touchers des 48 lignes du bas pendant 3 s. Ce n'est pas un défaut de l'envoi des entrées, mais l'utilisateur le verra comme un clic manquant : la décision sur le remasquage automatique reste ouverte.

### Vérifié

| Vérification | Résultat |
|---|---|
| Vrai serveur TigerVNC (Xtigervnc 1280×800), `xev` comme témoin indépendant, 8 clics dont les coins (0,0) et (1279,799), et 3 clics d'affilée sans pause | **8 appuis et 8 relâchements bouton 1**, positions exactes, aucun événement en trop ; 2 touchers hors cadre : aucun événement |
| GT-P5110, vrais `MotionEvent` (`adb shell input`) : tap | **1 clic**, coordonnées exactes, écrit par le thread `secondscreen-input` (barre non interposée) |
| GT-P5110 : balayage court (60 px) et long (200 px) | **0 clic** (c'est un glissement, voir plus bas) |
| GT-P5110 : tap, balayage, tap | 2 clics (celui du balayage n'existe pas) |
| GT-P5110 : deux taps simultanés au même point | 2 clics |
| Barre système réapparue : toucher dans les 48 lignes du bas | intercepté par la barre, 0 clic (limite d'Android 4.2 ci-dessus) |

| Vrai serveur TigerVNC + `xev`, glissement (200,300)→(600,500) en 20 pas | 1 appui bouton 1 en (200,300), 20 déplacements **état bouton 1 maintenu** (`state 0x100`) aux positions exactes, 1 relâchement en (600,500) |
| Idem, glissement qui sort du cadre par la droite, relâché à x=1500 | pointeur **borné à x=1279**, relâchement en (1279,100) |
| Idem, glissement annulé par le système (`onCancel`) | relâchement à la dernière position (220,140) : **bouton non coincé** |
| Idem, tap juste après le glissement annulé | exactement 1 appui et 1 relâchement |
| GT-P5110, vrais `MotionEvent`, `input swipe` (horizontal 200 px, diagonal 1100×600, vers le bord droit) | 1 appui au point de départ exact, 10 à 11 déplacements bouton enfoncé, 1 relâchement (à la dernière position de déplacement), envoyés par le thread `secondscreen-input` |
| GT-P5110 : tap, glissement, tap enchaînés | clic, glissement complet, clic ; aucun message en trop |
| Vrai serveur TigerVNC + `xev`, appui long en (640,400) suivi d'un déplacement puis relâché | **bouton 3** enfoncé puis relâché en (640,400), **aucun événement bouton 1**, aucun déplacement (le reste du geste est ignoré) |
| Idem, tap en (100,100) ; appuis longs aux coins (0,0) et (1279,799) | bouton 1 pour le tap, bouton 3 pour chaque appui long, positions exactes |
| GT-P5110, **vrais toucher bruts** (`sendevent`, doigt tenu 1 s) à (400,300) | **un seul clic droit** (masque 4), rien au relâchement |
| Idem, tap rapide | un clic gauche (masque 1) |
| Idem, appui long avec frémissement de 3 px, puis avec un déplacement de 300 px avant le relâchement | un seul clic droit chaque fois : **aucun glissement, aucun clic gauche parasite** |
| Idem, le clic droit part-il avant le relâchement ? (marqueur écrit dans le log avant l'`ACTION_UP`) | **oui, doigt encore posé** |
| GT-P5110 : deux doigts posés 1 s ; deux doigts posés et levés avant le seuil | **rien envoyé** |
| GT-P5110 : glissement puis deuxième doigt posé pendant le glissement | bouton relâché à la dernière position (422,300) ; les déplacements suivants sont ignorés |
| GT-P5110 : seuil de la plateforme réglé à 1 500 ms (`settings put secure long_press_timeout`), écran rouvert | tenu 1 s : **tap** (clic gauche) ; tenu 2 s : **clic droit**. Réglage remis à 500 ms ensuite |

**Méthode des toucher bruts** : `adb shell input` d'Android 4.2 ne sait ni tenir un appui ni poser deux doigts, mais `sendevent` sur `/dev/input/event0` (protocole multi-touch B, dalle 1280×800) le permet. **La dalle est retournée de 180° par rapport à l'affichage** (toucher brut (x, y) = affichage (1279−x, 799−y)) ; les coordonnées ont été converties. Le `sleep` de la tablette n'accepte que des secondes entières.

**Non vérifié sur l'appareil** : la perte de focus en plein glissement ou en plein appui long (l'appel à `cancelGesture` n'a été exercé que par les tests unitaires). Le glissement de la tablette n'a été fait qu'avec `input swipe` (300 ms, ~10 déplacements) ou quelques déplacements bruts : la cadence d'un vrai doigt (60 à 120 déplacements par seconde) n'a pas été mesurée ; côté file d'envoi, la charge est testée (2 000 déplacements sur une liaison bloquée) mais pas avec un serveur réel qui ralentit. Rien n'a non plus été mesuré côté serveur depuis la tablette : la chaîne serveur a été vérifiée depuis la JVM avec le même code, la chaîne tactile depuis la tablette sans serveur.

## Réseau (SS-064)

`net/KeepAlive` envoie un message toutes les 100 ms sur un thread démon dédié pour que la liaison Wi-Fi ne devienne jamais silencieuse : sinon la radio de la tablette s'endort et la latence d'un paquet entrant atteint ~1,9 s (mesures dans PERFORMANCE.md). Le battement est un `FramebufferUpdateRequest` incrémental d'un pixel. Il s'arrête de lui-même si l'envoi échoue. Le contrôleur de connexion le démarrera avec la session et l'arrêtera à sa fin.

## Dépendances

Politique : zéro dépendance réseau/protocole pour le MVP. Utiliser les API standard Java/Android (`Socket`, streams, `Bitmap`, `Canvas`, `SurfaceView`). Une dépendance ne peut être ajoutée que si elle :

- supporte API 17 ;
- a une licence compatible ;
- réduit réellement le risque ou la complexité ;
- n'ajoute pas un runtime disproportionné.

## Persistance

`SharedPreferences` suffit pour les profils simples. Ne pas stocker le mot de passe en clair par défaut. Compte tenu de l'ancienneté d'Android 4.2.2, documenter clairement les limites de stockage sécurisé disponibles sur cette plateforme.

## Gestion d'état

États explicites :

```text
DISCONNECTED
CONNECTING
NEGOTIATING
CONNECTED
RECONNECTING
ERROR
```

Les transitions doivent être centralisées afin d'éviter que l'Activity manipule directement la socket.
