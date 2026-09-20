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

## Rendu (SS-030)

`render/RemoteSurfaceView` (un `SurfaceView`) copie le `Framebuffer` dans un `Bitmap` ARGB_8888 alloué une fois, puis le dessine en (0, 0) **sans mise à l'échelle** : filtrage désactivé, `Bitmap.DENSITY_NONE` (sans quoi Android peut redimensionner selon la densité), surface en `RGBX_8888` (le tampon par défaut d'un `SurfaceView` ancien est en 16 bits). La vue garde l'écran allumé. Vérifié sur la GT-P5110 par comparaison pixel à pixel d'une capture : les 752 lignes visibles (962 560 pixels) sont identiques au motif ; les 48 lignes du bas sont sous la barre système (SS-034). `RemoteActivity` l'héberge en plein écran ; elle affiche pour l'instant un motif de test (`RenderTestPattern`). La copie plein écran, le suivi des zones modifiées et la synchronisation avec le décodage relèvent de SS-031.

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
