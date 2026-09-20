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

`render/RemoteSurfaceView` (un `SurfaceView`) implémente `RenderTarget` : le décodeur écrit ses pixels **puis** signale le rectangle, et demande le rendu **une fois par `FramebufferUpdate`**. `DirtyRegion` retient la boîte englobante des rectangles **et jusqu'à 8 rectangles distincts** (sans allocation, thread-safe ; SS-062). Le rendu se fait sur le thread appelant (le thread I/O), sans thread de rendu supplémentaire, conformément à « mesurer avant d'ajouter un thread ».

- **Aucun verrou sur les pixels** : la visibilité des écritures est garantie par le verrou du `DirtyRegion` (le décodeur écrit puis appelle `add`, le rendu appelle `take` puis lit). Un rectangle en cours de décodage qui recouvre une zone déjà signalée peut donc être visible à moitié pendant une image, corrigé à la suivante.
- **`renderLock`** sérialise le dessin avec `surfaceDestroyed` : après le retour de ce callback plus aucun dessin n'est en cours.
- Bitmap, `Rect` de zone et `Paint` sont alloués **une fois** ; un Bitmap neuf (vide) impose un rendu complet, décidé *avant* de choisir la zone à dessiner.
- **Copie par rectangles distincts (SS-062)** : `Bitmap.setPixels` coûte ~35 ns par pixel sur la GT-P5110, alors que `lockCanvas` + dessin + `unlockCanvasAndPost` est un coût **à peu près fixe** (~18 à 23 ms par rendu). Avec la seule boîte englobante, deux petites zones éloignées (deux coins, par exemple) faisaient copier ~644 000 pixels (~22 ms) pour en avoir modifié ~4 000. `DirtyRegion` garde donc jusqu'à `MAX_RECTS` = 8 rectangles distincts : deux rectangles qui se recouvrent ou se touchent fusionnent, deux autres fusionnent si la boîte qui les contient ne gaspille pas plus de 2 fois leur surface cumulée (deux lignes de texte voisines = une copie), et au-delà de 8 on fusionne la paire la moins coûteuse. `take(bounds, rects)` rend la boîte englobante (à **redessiner** : `lockCanvas` exige de repeindre toute la zone qu'on lui donne) et les rectangles (à **copier**). La surface copiée n'est jamais supérieure à celle de la boîte. Un rendu complet (Bitmap neuf, surface recréée) copie tout, comme avant. Le chemin mis à l'échelle utilise la même copie ; son dessin reste découpé à la boîte.
- **Ce qui n'est pas fait, volontairement** : regrouper plusieurs `FramebufferUpdate` en un seul rendu, ou rendre sur un thread séparé. Le coût de dessin est fixe (~20 ms), donc le plafond est d'environ 50 rendus/s ; aucune mesure n'a montré que ce plafond était atteint en usage réel, et regrouper ajouterait de la latence.

### Fidélité 1:1 (SS-032)

L'image est dessinée en (0, 0), **sans mise à l'échelle** : filtrage désactivé, `Bitmap.DENSITY_NONE` (sans quoi Android peut redimensionner selon la densité), surface en `RGBX_8888` (le tampon par défaut d'un `SurfaceView` ancien est en 16 bits). `RenderGeometry` dit si le rendu est natif, tronqué ou entouré de noir. `RemoteActivity` est en `sensorLandscape` (les deux paysages).

### Mise à l'échelle avec bandes noires (SS-033)

Le rendu 1:1 ci-dessus reste **la règle pour la résolution cible** : on ne met à l'échelle que quand il le faut, parce que toute mise à l'échelle floute un peu le texte.

| Écran distant | Surface | Rendu |
|---|---|---|
| 1280×800 | 1280×800 | **natif 1:1**, rien de perdu |
| 1280×800 | plus petite (barre système, 1280×752) | 1:1 **rogné** (les 48 lignes du bas), *sauf* si l'utilisateur choisit « Échelle : ajustée » |
| **pas** 1280×800 (1920×1080, 1024×768...) | quelconque, sauf de la même taille | **ajusté**, ratio conservé, centré, **bandes noires** ; rien n'est rogné |
| n'importe quelle taille identique à la surface | | natif 1:1 |

- **Géométrie** (`RenderGeometry`, pure) : `scale = min(surface.w / image.w, surface.h / image.h)`, image de `round(taille × scale)` centrée. Vérifié sur 5 000 tailles aléatoires : l'image tient dans la surface, son ratio est conservé à un pixel près, elle est centrée à un pixel près et touche un bord au moins ; un écran petit est agrandi, un grand réduit. Exemples : 1920×1080 dans 1280×800 → échelle 0,667, image 1280×720, bandes de 40 lignes ; 1024×768 → 1,0417, image 1067×800, bandes de 106 colonnes.
- **Option « Échelle »** (bouton de la barre de commandes, mémorisé : `DisplaySettings.fitToScreen`) : ajuste **aussi** un écran distant en 1280×800, par exemple pour voir ses 48 dernières lignes sans passer en plein écran (échelle 0,94, image 1203×752). Pour un écran distant qui n'est pas en 1280×800 le bouton affiche « Échelle : ajustée (auto) » et est désactivé : l'ajustement y est toujours actif.
- **Rendu** (`RemoteSurfaceView`) : le Bitmap contient toujours le framebuffer entier à jour ; seule la zone modifiée y est recopiée. Une mise à jour partielle redessine **la zone modifiée à l'écran** (boîte englobante élargie d'un pixel du framebuffer pour le filtre, arrondie vers l'extérieur) en dessinant le Bitmap entier avec la même `Matrix` **découpé** à cette zone : chaque pixel est calculé comme lors d'un rendu complet, donc **sans couture**. Filtrage bilinéaire pour ce chemin seulement ; le chemin 1:1 n'interpole jamais et n'a pas changé.
- **Touchers** (`PointerMapper`) : la conversion relit la géométrie à chaque appel (sans verrou : `RemoteSurfaceView.geometry` est lisible sans prendre le verrou de rendu, sinon un dessin en cours bloquerait le thread UI à chaque événement tactile). Un toucher dans une bande noire n'est pas converti ; pendant un glissement, `mapClamped` le ramène au bord de l'image.
- **Touchpad** : la sensibilité s'entend **à l'écran** : le déplacement en pixels du framebuffer est divisé par l'échelle, pour que le pointeur parcoure visuellement la même distance quelle que soit l'échelle.

**Vérifié**

| Vérification | Résultat |
|---|---|
| JVM, 21 nouveaux tests (géométrie 9 dont une propriété sur 5 000 tailles, conversion des touchers 8, touchpad 2, réglage 2) ; trois mutations de la conversion (décalage ignoré, bandes acceptées, échelle inversée) | échouent |
| GT-P5110, vrai TigerVNC **1920×1080** (fenêtre rouge en haut à gauche 316×82, bleue en bas à droite, fond blanc), surface 1280×752 : mesure de la capture (pixel à pixel) | image **1280×720 décalée de 16 lignes** ; bandes noires nettes ; rouge (1..210, 17..70) et bleu (1069..1278, 681..734) exactement aux positions calculées (316×82 → 210×54) |
| Idem, touchers à 6 points | (100,100) → **(150,126)**, (700,300) → (1050,426), (1200,650) → (1800,951), (1279,16) → (1918,0), (300,17) → (450,1) : exacts ; toucher dans la bande du haut ou du bas : **aucun événement** |
| Idem, image après ~8 mises à jour partielles **comparée** à l'image après recréation de la surface (rendu complet) | **0 pixel de différence** : pas de couture |
| GT-P5110, serveur 1280×800, bouton « Échelle » | 1:1 : fenêtre bleue rognée en bas (717..751) ; ajusté : entière (944..1239 × 674..750, échelle 0,94) ; toucher (700,300) → **(704,319)** ; bande gauche : aucun événement ; le réglage est mémorisé (nouvelle session ajustée d'emblée) |
| Idem, touchpad en image ajustée | +164 px du framebuffer pour 100 px de doigt à 1,5× et l'échelle 0,94 (~160 attendu) |

**Coût mesuré sur la GT-P5110** (sonde temporaire, retirée) — rendu ajusté d'un écran 1920×1080 dans 1280×752 :

| Cas | Temps par rendu |
|---|---|
| petites mises à jour (~30 000 px, une horloge) | **~25 ms** (≈ 40 rendus/s) |
| grande zone en continu (terminal de ~400 000 px qui défile) | **~55 ms** (≈ 18 rendus/s) |
| rendu complet (surface entière) | **~200 ms** |

Suffisant pour du texte et des applications de bureau, pas pour de la vidéo plein écran. Aucune optimisation n'a été tentée (AGENTS.md : pas d'optimisation sans mesure justifiée) ; ces chiffres sont le point de départ de SS-060/SS-062 pour le chemin ajusté. **Non mesuré** : le coût du chemin 1:1 en plein écran, et l'ajustement d'un serveur 1920×1200 (la taille maximale acceptée), qui pèse 9,2 Mio de Bitmap plus 9,2 Mio de framebuffer.

**Non vérifié** : un serveur Windows ; la netteté du texte réduit (jugée à l'œil seulement sur les captures : bilinéaire sans pré-filtrage, un facteur inférieur à 0,5 crénèlerait) ; le retournement physique de la tablette avec une image ajustée.

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

## Mesures de performance (SS-060)

Package `perf/`, **désactivé par défaut** (case « Afficher FPS et débit » de l'écran Diagnostic, mémorisée dans `DisplaySettings.showPerformance`).

- `PerfStats` : compteurs `AtomicLong` (mises à jour, rectangles, pixels décodés, temps de décodage, rendus, temps de copie/dessin, pixels copiés, copies plein écran, octets reçus/envoyés, CPU du thread de session). Chaque point de mesure commence par **une lecture d'un booléen `@Volatile`** ; désactivé, il ne fait rien d'autre (pas d'appel à `nanoTime`, aucune allocation). L'horloge CPU du thread est **injectée** (`Debug.threadCpuTimeNanos`, API 1) pour que la logique reste testable sur la JVM.
- `TrafficCounter` : octets reçus (via un `FilterInputStream` posé **sous** le `BufferedInputStream`, donc les octets réels du socket) et envoyés (`RfbSocket.write`). Nombres seulement : jamais de contenu, donc rien de secret.
- `PerfSampler` / `PerfSnapshot` : calcul **pur** des débits par seconde à partir de deux relevés de compteurs (testé sans Android).
- `RemoteActivity` échantillonne **une fois par seconde** sur le thread UI (aucun réseau) : bandeau `remote_perf` (4 lignes : mises à jour, réseau, rendu, système) et une ligne de journal `SecondScreenPerf` faite **de nombres uniquement**.
- « Copies plein écran » : un rendu **partiel** qui copie ≥ 90 % du framebuffer. Doit rester à 0 en usage normal ; un rendu complet légitime (première image, surface recréée) n'y est pas compté.
- Le temps de « décodage » d'une mise à jour **inclut l'attente réseau** du reste du message : c'est un temps de réception + décodage, pas du CPU pur (le CPU du thread de session, lui, est mesuré à part).

## Entrées (SS-040 à SS-044)

```text
thread UI :  MotionEvent -> TouchInput -> TouchGestureDetector -> PointerActions -> PointerSender.send() / sendMove() (file bornée, non bloquant)
             (deux doigts : centre des doigts -> Scroll -> crans de molette ; un doigt : tap / glissement / appui long)
                                                          (PointerMapper : pixel de la vue -> pixel du framebuffer)
thread secondscreen-input :  file -> RfbSocket.write()  (un message entier par appel)
```

- **`PointerEvent`** (`ClientMessages.pointerEvent`, 6 octets : type 5, masque des boutons, x et y en U16 big-endian) : l'état des boutons est **absolu**, le serveur déduit appuis et relâchements en le comparant au précédent. Les valeurs hors plage sont refusées, jamais tronquées.
- **Coordonnées** (`PointerMapper`) : le rendu est 1:1 ancré en (0, 0), donc `pixel = floor(coordonnée)` (arrondir au plus proche décalerait la cible d'un pixel une fois sur deux). Un toucher hors du framebuffer, ou non fini, n'est **pas** envoyé (pas de clic sur le pixel du bord). Avec une image mise à l'échelle (SS-033), `pixel = floor((coordonnée − décalage) / échelle)` et un toucher dans une bande noire n'est pas envoyé non plus (voir « Mise à l'échelle »).
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

### Reconnexion automatique (SS-055)
Quand une session **établie** est coupée pour une cause **passagère** (`FailureKind.isTransient` : réseau ou PC injoignable, délai, connexion coupée, serveur qui redémarre, ancienne connexion pas encore libérée), le contrôleur passe en `RECONNECTING`, **attend puis retente**, sans intervention :

- **Bornée** : `ConnectionConfig.reconnectDelaysMs` = **1, 2, 4, 8, 15, 30, 30, 30 s**, soit **8 tentatives** sur 2 minutes 30 environ, puis **abandon** (`ERROR`, `gaveUpAfter`, message « abandonnée après 8 tentatives »). Le délai ne dépasse jamais 30 s et le nombre de tentatives est plafonné (validé à la construction : 1 ms à 5 min par délai, 32 tentatives au plus).
- **Pas de boucle sans fin** : une session qui a tenu au moins 30 s (`reconnectStableMs`) remet le compteur à zéro ; une session qui retombe aussitôt (liaison instable) ne le remet **pas** et finit par être abandonnée (testé).
- **Ce qui n'est pas retenté** : un échec **non passager** (mot de passe refusé ou changé, pas un serveur VNC, version ou sécurité non prises en charge, écran trop grand, données incohérentes, erreur locale) arrête tout de suite ; un **premier échec de connexion**, avant toute session établie, n'est pas retenté (l'utilisateur est devant l'écran). `SERVER_REJECTED` est retenté : après une coupure le serveur peut refuser tant qu'il n'a pas libéré l'ancienne connexion.
- **Désactivable** : case « Se reconnecter automatiquement » de l'écran de connexion (mémorisée, `ConnectionSettings`, **cochée par défaut**) ; liste de délais vide = désactivé ; sans la case, comportement inchangé (SS-054).
- **Commandes** (écran distant) : « Réessayer maintenant » (`retryNow`, saute l'attente), « Arrêter » (`stopAutoReconnect` : `ERROR` avec la cause, l'écran propose alors Reconnecter), « Fermer » ; `disconnect()` arrête tout, y compris pendant l'attente (l'attente est interruptible : sortie immédiate, pas d'attente des 60 s d'un délai).
- **Affichage** : la dernière image reste visible ; le panneau dit la cause, « Nouvelle tentative dans N s (k/8)… » avec un compte à rebours à la seconde, puis « Reconnexion en cours (k/8)… » pendant la tentative. `ConnectionController.reconnectStatus` (`ReconnectStatus` : tentative, maximum, délai, attente restante) alimente l'écran.
- **États** : `CONNECTED -> RECONNECTING` et `NEGOTIATING -> RECONNECTING` deviennent des transitions légales (la table unique et son test sont mis à jour) ; `RECONNECTING` sert aussi à la reconnexion manuelle, distinguée par un `reconnectStatus` nul.
- **Mot de passe** : voir « Secrets » plus bas et SECURITY.md. Pour se reconnecter à un serveur protégé sans l'utilisateur, une **copie est gardée en mémoire seulement** tant que la reconnexion automatique est active ; elle est **effacée de façon synchrone** par `disconnect()`, à l'abandon, à l'arrêt et en fin de session. Chaque tentative reçoit **sa propre copie**, effacée par `VncAuthentication`.

### Erreurs compréhensibles (SS-053)
Toute exception du transport ou du protocole est classée par `ConnectionFailure.classify` en une **cause** (`FailureKind`, 17 catégories) et une **phase** (connexion, négociation, session) : le même symptôme n'a pas le même sens partout (un délai de lecture est « le serveur ne répond pas à la négociation » ou « réseau coupé » selon la phase ; « ce serveur veut un mot de passe » et « mot de passe refusé » se distinguent selon qu'un mot de passe a été saisi). L'interface transforme chaque cause en une phrase qui dit **quoi faire** (`FailureMessages`, `when` exhaustif : une cause sans message ne compile pas). Rien d'autre que la raison assainie du serveur n'est affiché ; ni `toString()` ni journal ne contiennent de secret ni de texte serveur.

### Secrets
**Exception (SS-055)** : avec la reconnexion automatique, une copie du mot de passe est gardée **en mémoire** jusqu'à la fin de la session (voir SECURITY.md). Sans elle (case décochée, ou serveur sans mot de passe), ce qui suit s'applique tel quel.

Le mot de passe n'existe que dans un `CharArray`. `ConnectActivity` le copie, **vide le champ** (`saveEnabled=false` : pas d'état d'instance), et le confie à `connect`, qui l'**efface dans tous les cas** (succès, échec, abandon, connexion refusée d'emblée ; un test par chemin). Il n'est ni dans un `Intent`, ni dans un profil, ni journalisé, ni **conservé pour la reconnexion** : `reconnect` le redemande (boîte de dialogue) si le serveur en exigeait un (`reconnectNeedsPassword`). Limite : c'est du « meilleur effort » (la JVM et l'`EditText` peuvent garder des copies non effaçables), voir SECURITY.md.

### Profils (SS-051)
`ProfileStore` (nom, hôte, port) sur `SharedPreferences` (`PreferencesStore`), **sans aucun champ de mot de passe** (un test vérifie que ni les classes ni les clés écrites n'ont de place pour un secret). Les données lues sont traitées comme non fiables : un profil incomplet ou invalide est ignoré. Un profil n'est enregistré **qu'une fois la connexion établie** (une faute de frappe qui échoue n'écrase pas un profil qui marchait) ; même nom = même profil ; 50 profils au plus. Le dernier profil utilisé est proposé en tête de liste (« Reconnecter : ... », F08). `android:allowBackup="false"` : rien n'est sauvegardé dans un cloud.

### Barre de commandes (SS-052)
Clavier, Pointeur, Diagnostic, **Plein écran**, Déconnexion. Elle s'affiche par la touche **Retour** (toujours fiable) ou un **tap à trois doigts** (`ThreeFingerTap`). Quand elle est visible, Retour quitte l'écran : la sortie reste à deux gestes. **Clavier** ouvre le mode clavier (voir « Clavier ») ; **Pointeur** bascule entre le mode direct et le mode touchpad (voir « Mode touchpad »).

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

**Reconnexion automatique (SS-055) — vérifié**

| Vérification | Résultat |
|---|---|
| JVM, 26 nouveaux tests (22 de reconnexion contre le faux serveur, 4 de réglage) ; six mutations (borne supprimée, effacement par `disconnect` supprimé, compteur jamais remis à zéro, attente sourde à `disconnect`, échec d'authentification jugé passager, premier échec retenté) | chacune fait échouer des tests |
| Réussite : session coupée puis serveur qui accepte à nouveau (avec et sans mot de passe) | reconnectée seule ; la cause est affichée pendant l'attente ; nouvel écran distant ; compteur remis à zéro ; le mot de passe gardé sert (le serveur vérifie la réponse DES) puis est effacé à la déconnexion |
| Bornes : le serveur ne revient jamais | exactement autant de tentatives que de délais, dans l'ordre 40, 80, 160 ms (configuration de test), puis `ERROR` avec `gaveUpAfter`, mot de passe effacé |
| Instabilité : coupures répétées | une session qui tient assez remet le compteur à zéro (5 reconnexions sans abandon) ; une session qui tombe aussitôt est abandonnée (1 + 3 connexions au plus) |
| Contrôle : disconnect pendant une attente de 60 s ; `retryNow` ; `stopAutoReconnect` ; connexion manuelle refusée pendant la reconnexion | sortie immédiate et mot de passe effacé ; reconnecté sans attendre ; `ERROR` avec la cause ; refusé (mot de passe du refus effacé) |
| GT-P5110 contre un vrai TigerVNC **protégé par mot de passe**, coupure du serveur puis relance ~10 s plus tard | « Connexion refusée… Nouvelle tentative dans 1 s (3/8)… » avec compte à rebours, dernière image visible ; **reconnecté tout seul avec authentification, sans boîte de mot de passe**, horloge du bureau distant repartie |
| Idem, « Réessayer maintenant » pendant une attente de 16 s (6/8) | l'attente est sautée, la tentative part aussitôt |
| Idem, « Arrêter » | `ERROR` avec la cause, boutons Reconnecter et Fermer, plus aucune tentative |
| Idem, serveur coupé jusqu'au bout | après ~2 minutes : « La reconnexion automatique a été abandonnée après 8 tentatives. » |
| Idem, case décochée puis coupure | seulement Reconnecter, comme avant SS-055 |

**Non vérifié (SS-055)** : une **vraie coupure Wi-Fi de la tablette** (les coupures sont des arrêts du serveur ; le cas du réseau qui revient au bout de 15 s de silence n'a été vu qu'en JVM) ; un PC Windows ; la reconnexion pendant une longue durée (SS-061) ; le compte à rebours sur une tablette qui met l'écran en veille (la session se ferme alors, voir « Session = tant que l'écran distant est visible »).

**Non vérifié** : un PC Windows (seul TigerVNC sous Linux, dans un conteneur, a été essayé) ; une vraie coupure Wi-Fi de la tablette (elle a été simulée en arrêtant ou en gelant le serveur) ; le comportement à long terme (SS-061) ; le retournement physique de la tablette avec `configChanges` ; le tap à trois doigts sur une tablette laissée inactive (voir la limite du mode immersif : le premier toucher après quelques secondes est annulé par le système, donc le geste ne marche qu'une fois l'écran touché depuis moins de 3 s ; la touche Retour est la voie fiable).

**Remarques de test** : la tablette d'essai était posée à l'envers (rotation 180°, capture d'écran retournée) ; `uiautomator` d'Android 4.2 ne montre pas les fenêtres de dialogue (la boîte de mot de passe existait bien, la capture d'écran le prouve) ; le serveur de test était joignable par Wi-Fi sur le réseau local seulement, le temps des essais.

## Clavier (SS-046, SS-047)

```text
clavier virtuel Android ─► KeyboardInputView (InputConnection) ─┐
touches physiques (USB, Bluetooth) ─► RemoteActivity.dispatchKeyEvent ─► KeyForwarder ─┤
rangée de touches spéciales (boutons) ─────────────────────────────────────────────────┴─► KeyboardInput ─► controller.input ─► KeyEvent RFB
```

- **Message** (`ClientMessages.keyEvent`, 8 octets : type 4, flag appui/relâchement, 2 octets de padding, keysym U32). Le serveur suit l'état de chaque touche : comme pour la souris, **une frappe = appui + relâchement dans le même message** (`keyPress`, `keyPresses`, jusqu'à 32 frappes / 512 octets), donc une touche n'est jamais laissée enfoncée par un message perdu.
- **Keysyms** (`Keysyms`) : RFB désigne le **symbole**, pas la touche physique. ASCII (U+0020..007E) et Latin-1 (U+00A0..00FF) : le point de code lui-même (définition des keysyms Latin-1) ; `\n` = Entrée, `\t` = Tab ; au-delà de U+00FF : keysym Unicode `0x01000000 + point de code` (euro, œ, grec, emoji : à la charge du serveur, vérifié avec TigerVNC) ; contrôles sans touche, moitiés de paires de substitution et valeurs invalides : **ignorés**, jamais envoyés. Un point de code hors plan de base est **une** frappe, pas deux.
- **Clavier virtuel** (`KeyboardInputView`) : une `SurfaceView` n'est pas un éditeur de texte, une vue invisible de 1 dp se déclare donc éditeur et fournit une `InputConnection` **qui ne garde aucun texte** : tout ce que le clavier valide, propose ou supprime part au serveur. Options demandées au clavier : pas de suggestions (`NO_SUGGESTIONS`, mot de passe visible), pas d'édition plein écran, Entrée = vraie touche Entrée. Le clavier se pose **par-dessus** l'écran distant (`adjustNothing` : redimensionner la surface rognerait l'image).
- **Texte provisoire** (`ComposingDiff`) : beaucoup de claviers proposent « bonjoru » puis corrigent en « bonjour » avant de valider. Le serveur ne connaît que des frappes : on envoie la partie nouvelle et, quand le clavier **corrige**, autant de Retour arrière que de caractères retirés (comptés en points de code, sur le texte **réellement envoyé**). Une suppression demandée par le clavier fige le texte provisoire.
- **Touches spéciales** (rangée affichée par le bouton Clavier) : Échap, Tab, Ctrl, Alt, Maj, Suppr, Effacer, Entrée, ← ↑ ↓ →, Masquer. **Ctrl, Alt et Maj sont des bascules « à un coup »** : la touche est enfoncée tout de suite côté serveur et **relâchée après la prochaine frappe non modificatrice** (Ctrl puis `c` = Ctrl+C) ; dans un texte de plusieurs caractères le modificateur ne s'applique qu'au **premier**. Maj enfoncé transforme a-z en A-Z : sans cela les serveurs relâchent Maj pour un keysym minuscule. Un modificateur n'est mémorisé que si son message est parti.
- **Jamais de modificateur coincé** : ils sont relâchés à la fermeture du mode clavier (bouton Masquer, Retour) et à la mise en pause de l'écran ; à la perte de session ils sont simplement oubliés (le serveur relâche de lui-même à la déconnexion).
- **Retour** : le clavier virtuel prend le premier Retour pour se masquer, le second ferme le mode clavier, le suivant ouvre la barre de commandes comme d'habitude. La sortie de l'application reste à deux gestes.
- **Touches physiques** (`KeyForwarder`) : disponibles en permanence pendant une session, sans ouvrir le mode clavier. Les touches système (Retour, Accueil, Menu, volume, marche/arrêt) restent à Android. Limite : un Maj/Ctrl/Alt **physique** gauche et la bascule à l'écran ont le même keysym ; les mélanger peut produire un relâchement superflu, que le serveur ignore.
- **Confidentialité** : aucune frappe n'est journalisée ni conservée (un mot de passe peut être saisi à distance) ; elles circulent **non chiffrées** comme le reste de la session VNC (SECURITY.md).
- **Immersif** : le mode plein écran est suspendu tant que le mode clavier est actif (la rangée de touches doit répondre du premier coup).

### Vérifié
| Vérification | Résultat |
|---|---|
| JVM, 70 tests (message, table de keysyms, composition, `KeyboardInput`) | vecteurs hexadécimaux du message ; toutes les valeurs de `keysymdef.h` recopiées à la main ; propriété sur des suites aléatoires : **aucune touche restée enfoncée** et **aucun relâchement d'une touche non enfoncée** ; propriété de composition : le texte distant égale toujours la dernière proposition |
| Mutations : Ctrl non relâché, Maj sans majuscule, modificateurs non relâchés par `pressKey`, modificateur mémorisé sans message parti, calcul des effacements faussé | chacune fait échouer des tests |
| Vrai TigerVNC + `xev` (témoin indépendant), depuis la JVM | `Hello, é€` + Entrée : keysyms `H e l l o comma space eacute U20AC Return` ; **Ctrl+c** (état `0x4`, Ctrl relâché après) ; **Maj+a** -> `A` (état `0x1`) ; **Alt+x** (état `0x8`) ; Échap, Tab, Retour arrière, Entrée, ← ; « bonjoru » corrigé en « bonjour » : `b o n j o r u BackSpace BackSpace u r` |
| GT-P5110, **vrai clavier virtuel AZERTY**, touches a z e r | le serveur reçoit `a z e r` |
| Idem, Ctrl / Maj / Alt (boutons) puis une touche du clavier virtuel | `Control_L` enfoncé, `c` avec l'état Ctrl, `Control_L` relâché ; idem `Shift_L` + `A` (état Maj) et `Alt_L` + `x` (état Alt) |
| Idem, boutons Échap, Tab, Effacer, Entrée, ← de la rangée | `Escape`, `Tab`, `BackSpace`, `Return`, `Left` |
| Idem, touches Effacer et Entrée **du clavier virtuel lui-même** (envoyées comme `KeyEvent` par l'IME) | `BackSpace`, `Return` |
| Idem, touches physiques injectées (`adb shell input keyevent`) : Échap, ↑, Tab, F5, Page suiv. ; `input text "Ab1"` | `Escape Up Tab F5 Next` ; `Shift_L`+`A`, `b`, `1` |
| Idem, Ctrl enfoncé puis Masquer | `Control_L` relâché à la fermeture du mode clavier |
| Idem, Retour x3 ; Accueil avec Ctrl enfoncé | masque le clavier / ferme le mode / ouvre la barre ; Ctrl relâché côté serveur et connexion fermée |

**Non vérifié** : un vrai clavier physique USB ou Bluetooth (les touches ont été injectées par `adb`, qui emprunte le même chemin `dispatchKeyEvent` mais pas un vrai périphérique) ; la saisie d'un caractère accentué ou de l'euro **au clavier virtuel** (la table est vérifiée depuis la JVM contre TigerVNC, pas depuis l'IME ; le clavier de la tablette valide chaque lettre au lieu de proposer un texte provisoire, donc le suivi des corrections n'a été vu que contre le serveur) ; AltGr (les caractères des couches AltGr comme `@` d'un clavier AZERTY passent comme keysyms, le serveur choisit la touche) ; un serveur Windows ; la répétition d'une touche de la rangée maintenue appuyée (chaque appui envoie une frappe, pas de répétition automatique) ; les claviers virtuels tiers (seul celui de la tablette a été essayé).

## Mode touchpad (SS-045)

Le bouton **Pointeur** de la barre de commandes bascule entre deux façons d'interpréter le doigt, mémorisées d'une session à l'autre (`InputSettings`) :

- **Direct** (défaut) : le doigt *désigne* un point de l'écran distant (SS-040 à SS-044).
- **Touchpad** : le doigt *déplace* le pointeur, comme sur un ordinateur portable. Utile pour viser précisément, ou quand le pointeur distant n'est pas sous le doigt.

| Geste en touchpad | Effet |
|---|---|
| glisser un doigt | déplace le pointeur de `déplacement × sensibilité`, aucun bouton |
| toucher et lever vite, sans bouger | clic gauche **où est le pointeur** (pas sous le doigt) |
| deux touchers rapides de suite (300 ms) | double clic |
| toucher, lever, **retoucher et glisser** | glissement : bouton gauche enfoncé pendant le déplacement |
| doigt posé sans bouger jusqu'au délai d'appui long | clic droit au pointeur (le reste du geste est ignoré) |
| deux doigts | défilement (molette) **au pointeur**, comme en mode direct |
| trois doigts | barre de commandes (comme en mode direct) |

```text
MotionEvent -> TouchInput ─┬─ TouchGestureDetector (direct)  ─► PointerActions  ─┐
                           └─ TouchpadDetector (touchpad)    ─► TouchpadActions ─┴─► controller.input
                                          PointerPosition (dernière position du pointeur, partagée)
```

- **`TouchpadDetector`** (pure, sans Android) : les déplacements sont émis **avant sensibilité**, en pixels de la vue. **Pas de saut au démarrage** : tant que le doigt n'a pas dépassé le seuil de mouvement le pointeur ne bouge pas (un tap ne doit pas faire dériver le pointeur avant de cliquer), puis le chemin **déjà parcouru est appliqué en entier** : aucun mouvement n'est perdu (la distance totale égale le trajet du doigt, quelle que soit la finesse des événements, testé de 1 à 40 px par événement). Le toucher qui suit un tap de près n'arme pas d'appui long (c'est un second tap ou le début d'un glissement) ; un défilement efface la fenêtre de double tap.
- **`TouchpadActions`** : tient la **position du pointeur distant**, décimale (un déplacement de 0,4 pixel s'ajoute au suivant, on n'envoie que quand le pixel change), bornée au framebuffer (le pointeur s'arrête au bord, sans zone morte pour repartir). Les déplacements sont **remplaçables** (`sendMove`), l'appui et le relâchement d'un glissement sont des messages d'état : sur une liaison lente on perd des déplacements, jamais le relâchement. Si l'appui n'a pas pu partir, les déplacements ne portent pas le bouton (ce serait un appui fantôme).
- **Sensibilité** : facteur de 0,3× à 4,0× (défaut 1,5×), **réglable par un curseur** affiché sous la barre de commandes en mode touchpad, relue à chaque déplacement (le réglage agit immédiatement) et mémorisée au relâchement du curseur. Pas d'accélération : le pointeur suit le doigt proportionnellement. Les valeurs illisibles ou hors bornes du stockage redonnent le défaut ou la borne, jamais une exception.
- **Position partagée** (`PointerPosition`) : les deux modes tiennent la même dernière position ; passer de l'un à l'autre ne fait pas sauter le pointeur. Elle n'est mise à jour **que quand un message part** (deux doigts posés sans défiler ne déplacent pas le pointeur distant : défaut trouvé pendant les essais sur la tablette). Une nouvelle session la remet à zéro ; sans position connue le pointeur part du **centre** de l'écran distant (le serveur ne dit pas où il est).
- **Le pointeur est dessiné par le serveur** (TigerVNC et la plupart des serveurs l'incluent dans l'image quand le client ne demande pas d'encodage de curseur, ce qui est le cas ici). Un serveur qui ne le dessinerait pas rendrait le mode touchpad aveugle : il n'y a pas de curseur local.

### Vérifié
| Vérification | Résultat |
|---|---|
| JVM, 63 tests (`TouchpadDetector` 31 dont une propriété aléatoire, `TouchpadActions` 23 dont une propriété aléatoire, `InputSettings` 7, position partagée 2) | états, seuil sans perte, appui long horodaté, tap-glisser, double tap, bouton jamais coincé, bornes, sous-pixel, sensibilité relue, liaison bloquée (appui et relâchement passent, 2 000 déplacements abandonnés) ; quatre mutations (sensibilité ignorée, bornes supprimées, déplacement sans bouton, synchro de position supprimée) font échouer des tests |
| Vrai TigerVNC + `xev`, depuis la JVM | glisser 100 px à sensibilité 2 : pointeur du centre (640,400) à (840,500) ; tap : clic bouton 1 en (840,500) ; appui long : bouton 3 au même point ; toucher-glisser : bouton 1 enfoncé (état `0x100`) sur 100 px puis relâché ; double tap ; molette : 2 × bouton 5 en (940,500) ; énorme déplacement : arrêt en (1279,799) |
| GT-P5110, vrais doigts (`sendevent`), sensibilité 1,5× : 100 px à droite | pointeur de (640,400) à **(790,400)** |
| Idem : tap, appui long 1 s, double tap | clic bouton 1, clic bouton 3, deux clics, tous en (790,400) alors que le doigt est ailleurs |
| Idem : toucher, lever, retoucher et glisser (événements minimaux) | clic puis appui bouton 1, 3 déplacements avec l'état `0x100`, relâchement |
| Idem : deux doigts vers le haut | molette bas (bouton 5) au pointeur |
| Idem : curseur de sensibilité glissé au maximum (4,0×) puis −50 px de doigt | pointeur −201 px (de 973 à 772) |
| Idem : nouvelle session | mode touchpad et 4,0× mémorisés |
| Idem : retour au mode direct, tap en (200,300) | clic absolu exact en (200,300) |
| Idem : passage direct -> touchpad après ce clic | le pointeur part de (200,300), sans saut, et arrive à ~(402,300) pour 50 px de doigt à 4,0× |

**Non vérifié** : l'effet à la main sur une durée d'usage (confort, choix du défaut de 1,5×, absence d'accélération) ; un serveur qui ne dessine pas le pointeur (Windows) ; un tap-glisser à la main plus lent que l'injection (le délai de double tap est celui d'Android, 300 ms, non réglable) ; le mode touchpad avec le mode plein écran (le premier toucher après inactivité y est perdu, comme en mode direct).

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
