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
- **Session** (`secondscreen-session`, un par tentative de connexion) : socket, handshake, lecture des messages RFB et rendu (`ConnectionController`). Deux threads démons l'accompagnent : `secondscreen-keepalive` (battement) et `secondscreen-input` (envoi des entrées), tous arrêtés avec la session.
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

**Décision (après les essais de SS-054) : le mode immersif n'est plus imposé.** Avec une vraie session, la perte du premier toucher après quelques secondes d'inactivité gênait toute l'utilisation, y compris les boutons Reconnecter et Fermer. Il est devenu un **mode plein écran explicite** (voir « Plein écran ») : par défaut la barre système reste visible et tous les touchers comptent. Le drapeau `IMMERSIVE_STICKY` (API 19) n'est toujours pas implémenté (aucun appareil pour le tester).

### Vérifié sur la GT-P5110

| Vérification | Résultat |
|---|---|
| Écran distant statique en 1:1 (comparaison pixel à pixel d'une capture) | **0 différence sur 1 024 000 pixels** (SS-030 seul : 962 560 pixels, 48 lignes sous la barre) |
| 600 images de mises à jour partielles (2 rectangles de 64×64 chacune), image finale | **0 différence sur 1 024 000 pixels** |
| Ramasse-miettes pendant ~22 s d'animation en régime établi | **0 événement** |
| Surface détruite puis recréée (Accueil puis retour), 3 fois | 0 différence, aucun plantage |
| Toucher puis Retour sur la barre réapparue / touche Retour barre cachée / Accueil | l'application se ferme ou passe en arrière-plan à chaque fois |

**Non vérifié** : le retournement de la tablette d'un paysage à l'autre. `sensorLandscape` suit le capteur et ignore le réglage de rotation forcée : mon essai avec `user_rotation` n'a jamais changé l'orientation, il n'établit donc rien.

## Entrées (SS-040 à SS-044)

```text
thread UI :  MotionEvent -> TouchInput -> TouchGestureDetector -> PointerActions -> PointerSender.send() / sendMove() (file bornée, non bloquant)
             (deux doigts : centre des doigts -> Scroll -> crans de molette ; un doigt : tap / glissement / appui long)
                                                          (PointerMapper : pixel de la vue -> pixel du framebuffer)
thread secondscreen-input :  file -> RfbSocket.write()  (un message entier par appel)
```

- **`PointerEvent`** (`ClientMessages.pointerEvent`, 6 octets : type 5, masque des boutons, x et y en U16 big-endian) : l'état des boutons est **absolu**, le serveur déduit appuis et relâchements en le comparant au précédent. Les valeurs hors plage sont refusées, jamais tronquées.
- **Coordonnées** (`PointerMapper`) : le rendu est 1:1 ancré en (0, 0), donc `pixel = floor(coordonnée)` (arrondir au plus proche décalerait la cible d'un pixel une fois sur deux). Un toucher hors du framebuffer, ou non fini, n'est **pas** envoyé (pas de clic sur le pixel du bord). SS-033 (letterbox) devra ajouter ici décalage et rapport.
- **Tap = clic gauche** (`TouchGestureDetector`) : le clic n'est émis qu'**au relâchement**, jamais à l'appui, afin qu'un appui qui devient un geste ne produise pas de clic gauche parasite. Pas de clic si le doigt bouge de plus du seuil de la plateforme (`scaledTouchSlop` : c'est alors un glissement), si le contact atteint le seuil d'appui long (c'est alors un clic droit, voir plus bas), si un deuxième doigt se pose (c'est alors un défilement, voir plus bas), ou si le système annule le geste. Un relâchement dupliqué ne clique pas deux fois.
- **Appui long = clic droit** (SS-043, `LongPress` + `TouchGestureDetector`). Un doigt qui reste dans le seuil de mouvement pendant le seuil d'appui long envoie **un seul clic droit** (`ClientMessages.rightClick` : survol, appui du bouton droit — masque 4 —, relâchement, un seul message de 18 octets), **puis le reste du geste est ignoré** : ni tap ni glissement, donc **aucun clic gauche parasite** au relâchement, même si le doigt bouge ensuite.
  - **Seuil configurable** : `LongPress.thresholdMs`, de 200 à 3 000 ms. Par défaut celui de la plateforme (`ViewConfiguration.getLongPressTimeout()`, 500 ms), donc le réglage d'accessibilité « délai d'appui prolongé » de l'utilisateur s'applique. Il est lu à la création de l'activité : un changement de réglage demande de rouvrir l'écran distant. Un réglage propre à l'application (SS-05x) n'existe pas encore.
  - **Se déclenche doigt encore posé**, par un minuteur de la vue (thread UI, `DelayScheduler`), comme partout dans Android. La décision repose pourtant sur l'**horodatage** des événements : si le fil UI est occupé et que l'`ACTION_UP` arrive avec une durée ≥ seuil avant que le minuteur ait tourné, c'est quand même un appui long. Un contact de durée `< seuil` est un tap, `>= seuil` un appui long : **aucune zone morte**, même pour un seuil supérieur à 500 ms.
  - Annulé par : un mouvement au-delà du seuil de mouvement (c'est un glissement), un deuxième doigt, une annulation. Le minuteur est annulé à chaque fin de geste et un minuteur en retard ne peut rien déclencher.
  - **Retour haptique** (`performHapticFeedback(LONG_PRESS)`, aucune permission) : sans effet sur la GT-P5110, qui n'a pas de vibreur. **Il n'y a donc aucun retour au doigt** sur cette tablette : l'utilisateur ne sait pas que le seuil est atteint. Un retour visuel reste à décider.
- **Défilement à deux doigts = molette** (SS-044, `Scroll` + `TouchGestureDetector`). Un deuxième doigt posé alors que le premier n'a ni bougé de plus du seuil ni déclenché l'appui long ouvre un défilement. Le **centre des deux doigts** est converti en crans de molette : **un cran par 40 px** de chemin (`Scroll.stepPx`, 8 à 400 px), le reste étant conservé pour le déplacement suivant (le total de crans ne dépend pas de la finesse des événements : testé de 1 à 97 px par événement).
  - **VNC transporte la molette comme des boutons** (`ClientMessages.wheel`) : un cran = appui puis relâchement du bouton 4 (haut, masque 8), 5 (bas, 16), 6 (gauche, 32) ou 7 (droite, 64). Les N crans d'un événement tiennent en **un seul message** de 12·N octets (au plus 16 crans) : la molette ne peut pas rester « enfoncée » côté serveur, et aucun bouton ordinaire n'est touché. Les boutons 6 et 7 (molette horizontale) sont ceux de X11 et de TigerVNC ; un serveur qui ne les gère pas les ignore.
  - **Sens « naturel » par défaut** : le contenu suit les doigts (doigts vers le haut = molette vers le bas, comme sur un téléphone). `Scroll.natural = false` donne le sens d'une molette de souris.
  - **Un seul axe à la fois** : à chaque déplacement on ne garde que l'axe au plus grand chemin cumulé et on oublie l'autre, donc un léger dérapage latéral pendant un défilement vertical ne produit jamais de cran horizontal. Un événement ne porte jamais les deux axes.
  - **Position des crans = centre des doigts au début du geste**, borné au framebuffer : c'est la fenêtre sous les doigts au départ qui défile, même si le centre traverse d'autres fenêtres.
  - **Jamais de clic** : lever les doigts après un défilement n'envoie ni tap, ni clic, ni relâchement de glissement ; deux doigts posés sans bouger, même 1 s, n'envoient rien. Le défilement se termine dès que le nombre de doigts change (un levé ou un troisième posé) et ne reprend pas avant le prochain premier doigt. Un deuxième doigt pendant un glissement relâche le bouton sans ouvrir de défilement ; après un appui long il est ignoré.
  - **Remplaçable** : les messages de molette passent par `PointerSender.sendMove` : sur une liaison lente on perd des crans entiers, jamais le relâchement d'un bouton ni la place réservée aux messages d'état. Un saut énorme est plafonné (8 crans par événement) et son reste oublié.
  - **Le pas de 40 px n'est pas ajusté sur une vraie application distante** : c'est un premier réglage, il faudra l'essayer avec un navigateur ou un éditeur sur le PC ; il n'y a pas encore de réglage utilisateur.
- **Glissement = déplacement avec le bouton gauche maintenu** (SS-042, `TouchGestureDetector` + `PointerActions`). Quand le doigt dépasse le seuil, le bouton est enfoncé **là où le doigt s'est posé** (`ClientMessages.dragStart` : survol puis appui, 12 octets, un seul `write`), puis chaque déplacement envoie un `PointerEvent` bouton enfoncé, et le relâchement (`PointerEvent` masque 0) part à la fin. Garde-fous :
  - **le bouton n'est jamais laissé enfoncé** : fin normale, `ACTION_CANCEL`, deuxième doigt, nouveau toucher après un relâchement perdu, perte du focus et pause de l'activité relâchent tous le bouton (`onDragEnd` est appelé exactement une fois par glissement, propriété vérifiée sur des suites d'événements aléatoires) ;
  - **le relâchement se fait à la dernière position de déplacement, pas à celle de `ACTION_UP`** : le pointeur distant y est déjà. Mesuré sur la GT-P5110 : l'émulation `input swipe` d'Android 4.2 envoie un `ACTION_UP` à la position de départ, ce qui aurait ramené le pointeur au départ à chaque relâchement ;
  - **pas d'appui fantôme** : si l'appui n'a pas pu être mis en file (départ hors du framebuffer, file pleine), tout le glissement est ignoré, car un déplacement bouton enfoncé serait interprété par le serveur comme un appui n'importe où ;
  - **le doigt qui sort du cadre** ne casse pas le glissement : la position est **bornée** au framebuffer (`PointerMapper.mapClamped`) et le pointeur distant suit le bord ;
  - un déplacement qui ne change pas le pixel visé n'envoie rien.
- **Un clic = un seul message de 18 octets** (`ClientMessages.leftClick` : survol sans bouton, appui, relâchement, au même pixel), écrit en un seul `write` : l'appui n'est jamais envoyé sans son relâchement (bouton coincé côté serveur), ni entrelacé avec le battement Wi-Fi ou un autre message.
- **`PointerSender`** : aucun accès réseau sur le thread UI. La file est bornée (64) : si la liaison se bloque, `send` refuse des messages **entiers** sans jamais attendre (mémoire bornée) ; une erreur d'écriture arrête l'envoyeur et est signalée une fois ; à l'arrêt, les messages en attente sont abandonnés (un clic tardif serait pire qu'un clic perdu). **Deux priorités** : les messages d'état (appui, relâchement, clic, via `send`) ne doivent pas se perdre, les déplacements (`sendMove`) sont remplaçables ; ces derniers ne sont acceptés que tant qu'il reste de la place pour les premiers (un quart de la file est réservé). Sur une liaison lente on perd des déplacements, jamais le relâchement.

Les entrées partent vers la session courante du `ConnectionController` (`controller.input`, un `PointerSender` par session) : voir « Connexion et session ».

**Conséquence du plein écran** (voir « Plein écran ») : en plein écran seulement, sur Android 4.2, le premier toucher après quelques secondes d'inactivité est perdu ; hors plein écran (défaut) aucun toucher n'est perdu.

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
| Vrai serveur TigerVNC + `xev`, défilement à deux doigts : 130 px vers le haut ; 85 px vers le bas ; 45 px vers la gauche ; 45 px vers la droite ; puis un tap | **3 × bouton 5**, **2 × bouton 4**, **1 × bouton 7**, **1 × bouton 6**, chacun appui + relâchement à la position exacte du centre initial ; **aucun bouton 1** pendant les défilements ; le tap donne 1 appui et 1 relâchement du bouton 1 |
| GT-P5110, **deux vrais doigts** (`sendevent`, deux emplacements), 120 px vers le haut, centre (400,400) | **3 crans molette bas** (masque 16) en (400,400) |
| Idem, 120 px vers le bas / 120 px vers la gauche | 3 crans molette haut (masque 8) / 3 crans molette droite (masque 64) |
| Idem, deux doigts posés puis levés sans bouger, avec ou sans attente de 1 s ; trois doigts | **rien envoyé** (ni clic, ni appui long, ni molette) |
| Idem, défilement de 60 px puis tap immédiat | 1 cran, puis exactement un clic gauche pour le tap |
| GT-P5110 : glissement puis deuxième doigt posé pendant le glissement | bouton relâché à la dernière position (422,300) ; les déplacements suivants sont ignorés |
| GT-P5110 : seuil de la plateforme réglé à 1 500 ms (`settings put secure long_press_timeout`), écran rouvert | tenu 1 s : **tap** (clic gauche) ; tenu 2 s : **clic droit**. Réglage remis à 500 ms ensuite |

**Méthode des toucher bruts** : `adb shell input` d'Android 4.2 ne sait ni tenir un appui ni poser deux doigts, mais `sendevent` sur `/dev/input/event0` (protocole multi-touch B, dalle 1280×800) le permet. **La dalle est retournée de 180° par rapport à l'affichage** (toucher brut (x, y) = affichage (1279−x, 799−y)) ; les coordonnées ont été converties. Le `sleep` de la tablette n'accepte que des secondes entières.

**Non vérifié sur l'appareil** : le confort réel du défilement (pas de 40 px, vitesse, inertie : il n'y en a pas, un défilement s'arrête avec les doigts), qu'on ne peut juger qu'avec une vraie application distante ; la perte de focus en plein glissement, appui long ou défilement (l'appel à `cancelGesture` n'a été exercé que par les tests unitaires). Le glissement de la tablette n'a été fait qu'avec `input swipe` (300 ms, ~10 déplacements) ou quelques déplacements bruts : la cadence d'un vrai doigt (60 à 120 déplacements par seconde) n'a pas été mesurée ; côté file d'envoi, la charge est testée (2 000 déplacements sur une liaison bloquée) mais pas avec un serveur réel qui ralentit. Depuis SS-054 la chaîne complète (tablette -> serveur) est vérifiée sur un tap (voir « Connexion et session »).

## Connexion et session (SS-050 à SS-054)

```text
MainActivity (liste des profils)
   └─ ConnectActivity (formulaire, progression, erreurs)  ──connect(params, mot de passe)──┐
                                                                                            ▼
                                     SessionManager.controller : ConnectionController (thread secondscreen-session)
                                                                                            │ CONNECTED
   RemoteActivity (surface, entrées, barre, panneau d'état)  ◄── état, framebuffer ─────────┘
```

- **Un seul propriétaire de la socket** : `ConnectionController` (`session/`). Il tourne sur un thread dédié, publie ses changements d'état à des écouteurs (appelés sur un thread quelconque : l'interface se remet sur le thread UI) et n'expose jamais la socket. `connect`, `reconnect` et `disconnect` ne bloquent pas ; `disconnect` ferme la socket pour débloquer le thread.
- **États** (`ConnectionState`) : `DISCONNECTED`, `CONNECTING`, `NEGOTIATING`, `CONNECTED`, `RECONNECTING`, `ERROR`. Les transitions autorisées sont **une table unique** (`canTransitionTo`), vérifiée par un test qui la compare à la liste documentée ; une transition illégale est ignorée, pas appliquée.
- **Générations** : chaque tentative a un numéro. Un ancien thread encore en train de se terminer ne peut plus rien publier (état, rendu, framebuffer) dès qu'un `disconnect` ou une nouvelle tentative a eu lieu ; l'établissement de la session (état CONNECTED + session + envoyeur d'entrées) est atomique par rapport à `disconnect`. L'envoyeur d'entrées est **propre à chaque session** : un ancien thread ne peut pas arrêter celui de la suivante.
- **Déroulement** : TCP -> version -> sécurité (+ mot de passe) -> `ServerInit` -> `SetPixelFormat`, `SetEncodings`, `FramebufferUpdateRequest` complète -> boucle de lecture ; après chaque `FramebufferUpdate` : rendu (une fois par mise à jour, SS-031) puis nouvelle requête incrémentale de l'écran entier (allouée une fois par session).
- **Signe de vie** : le battement Wi-Fi (SS-064, une requête incrémentale d'un pixel toutes les 100 ms) ne prouve pas que le serveur vit, il ne provoque aucune réponse. Toutes les 5 s le même thread envoie donc une requête **non incrémentale** d'un pixel, à laquelle le serveur répond toujours. Aucune nouvelle pendant 15 s -> `NETWORK_LOST` (« réseau coupé ou PC en veille »), sans attendre les minutes que TCP met à s'en apercevoir. Un écran distant statique ne déclenche donc pas de fausse coupure.
- **Session = tant que l'écran distant est visible** : pas de service Android. Accueil, autre application, écran éteint : `RemoteActivity.onStop` ferme la session, donc aucun trafic ni thread en arrière-plan (exception : l'ouverture du diagnostic depuis la barre garde la session). Au retour, le panneau d'état propose **Reconnecter**. Le contrôleur vit autant que le processus (`SessionManager`) : une rotation ou la navigation entre écrans ne coupe pas la session (`configChanges` sur l'écran distant).

### Erreurs compréhensibles (SS-053)
Toute exception du transport ou du protocole est classée par `ConnectionFailure.classify` en une **cause** (`FailureKind`, 17 catégories) et une **phase** (connexion, négociation, session) : le même symptôme n'a pas le même sens partout (un délai de lecture est « le serveur ne répond pas à la négociation » ou « réseau coupé » selon la phase ; « ce serveur veut un mot de passe » et « mot de passe refusé » se distinguent selon qu'un mot de passe a été saisi). L'interface transforme chaque cause en une phrase qui dit **quoi faire** (`FailureMessages`, `when` exhaustif : une cause sans message ne compile pas). Rien d'autre que la raison assainie du serveur n'est affiché ; ni `toString()` ni journal ne contiennent de secret ni de texte serveur.

### Secrets
Le mot de passe n'existe que dans un `CharArray`. `ConnectActivity` le copie, **vide le champ** (`saveEnabled=false` : pas d'état d'instance), et le confie à `connect`, qui l'**efface dans tous les cas** (succès, échec, abandon, connexion refusée d'emblée ; un test par chemin). Il n'est ni dans un `Intent`, ni dans un profil, ni journalisé, ni **conservé pour la reconnexion** : `reconnect` le redemande (boîte de dialogue) si le serveur en exigeait un (`reconnectNeedsPassword`). Limite : c'est du « meilleur effort » (la JVM et l'`EditText` peuvent garder des copies non effaçables), voir SECURITY.md.

### Profils (SS-051)
`ProfileStore` (nom, hôte, port) sur `SharedPreferences` (`PreferencesStore`), **sans aucun champ de mot de passe** (un test vérifie que ni les classes ni les clés écrites n'ont de place pour un secret). Les données lues sont traitées comme non fiables : un profil incomplet ou invalide est ignoré. Un profil n'est enregistré **qu'une fois la connexion établie** (une faute de frappe qui échoue n'écrase pas un profil qui marchait) ; même nom = même profil ; 50 profils au plus. Le dernier profil utilisé est proposé en tête de liste (« Reconnecter : ... », F08). `android:allowBackup="false"` : rien n'est sauvegardé dans un cloud.

### Barre de commandes (SS-052)
Clavier, Pointeur, Diagnostic, **Plein écran**, Déconnexion. Elle s'affiche par la touche **Retour** (toujours fiable) ou un **tap à trois doigts** (`ThreeFingerTap`). Quand elle est visible, Retour quitte l'écran : la sortie reste à deux gestes. **Clavier et Pointeur sont présents mais désactivés** : ils dépendent de SS-046/SS-047 et SS-045, non faits.

**Hors plein écran** (défaut), Retour ouvre la barre et le tap à trois doigts la ferme **du premier coup**, y compris après une longue inactivité (vérifié). Retour quand la barre est visible quitte l'écran.

### Plein écran
Bouton **Plein écran** / **Quitter le plein écran** de la barre de commandes, mémorisé d'une session à l'autre (`DisplaySettings`, `SharedPreferences`, faux par défaut). Il remplace le mode immersif imposé :

| | Hors plein écran (défaut) | Plein écran |
|---|---|---|
| Barre système | visible | masquée |
| Fenêtre de l'écran distant (mesurée) | 1280×752 : **les 48 lignes du bas sont rognées** (la barre des tâches d'un PC Windows) | **1280×800**, tout l'écran distant |
| Touchers | tous délivrés | **le premier toucher après quelques secondes d'inactivité est perdu** (limite d'Android 4.2) |

En plein écran le mode ne s'applique que **barre de commandes masquée** et **session établie** : sur le panneau d'état (Reconnecter, Fermer) et avec la barre de commandes affichée, où chaque bouton doit répondre du premier coup, la barre système est rendue. Un message (`Toast`) rappelle la limite au moment où l'utilisateur choisit le plein écran. Sortir du plein écran ne laisse pas la barre masquée : `ImmersiveController.disable()` **rend la barre système** (un défaut de la première version : il ne faisait qu'arrêter le remasquage, la barre restait cachée et le premier appui sur un bouton était annulé ; corrigé et couvert par des tests).

### Vérifié
| Vérification | Résultat |
|---|---|
| Faux serveur RFB sur loopback (JVM), 35 scénarios | états dans l'ordre et légaux ; handshake, `SetPixelFormat`/`SetEncodings`/requête complète envoyés dans l'ordre exact ; mises à jour décodées et rendu notifié une fois par mise à jour ; VNC Auth ; mauvais mot de passe, mot de passe requis/invalide, port fermé, pas du VNC, version ancienne, refus, serveur muet, taille absurde, message inconnu, serveur qui ferme, serveur qui se tait ; signe de vie ; entrées ; arrêt sans thread restant ; reconnexion ; tentatives qui se chevauchent |
| Mutations (contrôleur) : signe de vie rendu incrémental / effacement du mot de passe supprimé / numéro de génération ignoré / nouvelle requête supprimée | chacune fait échouer au moins un test (l'effacement n'était pas couvert : deux tests ajoutés) |
| Vrai TigerVNC (`Xtigervnc` 1280×800, mot de passe VNC) depuis la GT-P5110 par Wi-Fi, parcours complet : Ajouter -> saisie -> Connexion | connexion établie, bureau affiché (horloge d'un terminal qui avance) |
| Idem, mauvais mot de passe | « Mot de passe refusé par le serveur. », champ mot de passe vidé, formulaire conservé |
| Idem, port filtré par le pare-feu / port fermé sur la tablette / adresse `http://pc` | « Le PC ne répond pas… pare-feu » / « Connexion refusée… » / erreur sur le champ Adresse |
| Idem, tap sur l'écran distant | `xev` voit `ButtonPress` + `ButtonRelease` bouton 1 en (200,300) exactement |
| Idem, arrêt du serveur en pleine session | « Connexion interrompue par le serveur ou le réseau. », dernière image conservée, boutons Reconnecter / Fermer |
| Idem, Reconnecter avec le serveur relancé | boîte de mot de passe, nouvelle connexion acceptée, écran de nouveau à jour |
| Idem, Retour -> barre ; bouton Diagnostic ; Retour ; Déconnexion | barre affichée ; diagnostic ouvert **sans fermer la session** (aucune fermeture côté serveur) ; retour à la session ; Déconnexion ferme la connexion (vue côté serveur) et revient à la liste |
| Idem, tap à trois doigts (deux doigts en plus via `sendevent`) | la barre s'affiche / se masque |
| Plein écran, GT-P5110 : session par défaut | barre système visible, fenêtre **1280×752**, la fenêtre placée aux lignes 752-800 du bureau distant n'est pas visible |
| Idem, bouton Plein écran de la barre | fenêtre **1280×800**, la fenêtre des lignes 752-800 apparaît, message d'information affiché |
| Idem, nouvelle session (autre activité) | s'ouvre directement en plein écran : le choix est mémorisé |
| Idem, Retour puis « Quitter le plein écran » du premier toucher | barre système rendue (752), bouton devenu « Plein écran » : plus de toucher perdu |
| Idem, tap à trois doigts après 5 s d'inactivité, hors plein écran | la barre se masque du premier coup |
| Profil enregistré, relancé après réinstallation | présent, proposé en « Reconnecter : ... » |
| Idem, **écran distant statique** pendant ~60 s (le terminal qui affichait l'heure est fermé) | toujours connecté, aucune fermeture côté serveur : le signe de vie est bien répondu par TigerVNC |
| Idem, serveur **gelé** (`docker pause` : connexion TCP ouverte, plus aucune réponse) | encore connecté à 8 s ; à ~22 s : « Plus aucune nouvelle du PC : réseau coupé ou PC en veille. » |
| Idem, **Accueil** pendant une session, puis retour dans l'application | le serveur voit la connexion fermée (4 acceptées, 4 fermées) ; au retour : « Déconnecté » avec Reconnecter |

**Non vérifié** : un PC Windows (seul TigerVNC sous Linux, dans un conteneur, a été essayé) ; une vraie coupure Wi-Fi de la tablette (elle a été simulée en arrêtant ou en gelant le serveur) ; le comportement à long terme (SS-061) ; le retournement physique de la tablette avec `configChanges` ; le tap à trois doigts sur une tablette laissée inactive (voir la limite du mode immersif : le premier toucher après quelques secondes est annulé par le système, donc le geste ne marche qu'une fois l'écran touché depuis moins de 3 s ; la touche Retour est la voie fiable).

**Remarques de test** : la tablette d'essai était posée à l'envers (rotation 180°, capture d'écran retournée) ; `uiautomator` d'Android 4.2 ne montre pas les fenêtres de dialogue (la boîte de mot de passe existait bien, la capture d'écran le prouve) ; le serveur de test était joignable par Wi-Fi sur le réseau local seulement, le temps des essais.

## Réseau (SS-064)

`net/KeepAlive` envoie un message toutes les 100 ms sur un thread démon dédié pour que la liaison Wi-Fi ne devienne jamais silencieuse : sinon la radio de la tablette s'endort et la latence d'un paquet entrant atteint ~1,9 s (mesures dans PERFORMANCE.md). Le battement est un `FramebufferUpdateRequest` incrémental d'un pixel. Il s'arrête de lui-même si l'envoi échoue. Le `ConnectionController` (SS-054) le démarre avec chaque session et l'arrête à sa fin ; il y ajoute le signe de vie (voir « Connexion et session »).

## Dépendances

Politique : zéro dépendance réseau/protocole pour le MVP. Utiliser les API standard Java/Android (`Socket`, streams, `Bitmap`, `Canvas`, `SurfaceView`). Une dépendance ne peut être ajoutée que si elle :

- supporte API 17 ;
- a une licence compatible ;
- réduit réellement le risque ou la complexité ;
- n'ajoute pas un runtime disproportionné.

## Persistance

(Réalisé en SS-051, voir « Connexion et session ».) `SharedPreferences` suffit pour les profils simples. Ne pas stocker le mot de passe en clair par défaut. Compte tenu de l'ancienneté d'Android 4.2.2, documenter clairement les limites de stockage sécurisé disponibles sur cette plateforme.

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

Les transitions doivent être centralisées afin d'éviter que l'Activity manipule directement la socket. (Réalisé : `ConnectionState` et `ConnectionController`, voir « Connexion et session ».)
