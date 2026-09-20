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
