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
