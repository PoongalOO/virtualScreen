# Roadmap

## M0 — Faisabilité

- APK Kotlin API 17 sur GT-P5110 ;
- SurfaceView 1280×800 ;
- socket TCP ;
- connexion à un serveur de test.

**Go/No-Go :** l'application tourne de manière stable sur Android 4.2.2.

## M1 — Proof of Concept VNC

- handshake RFB ;
- sécurité None sur LAN de test ;
- ServerInit ;
- RAW ;
- affichage 1280×800.

**Livrable :** bureau distant visible, sans interaction.

## M2 — MVP second écran

- mises à jour incrémentales ;
- pointeur ;
- tap/drag ;
- écran de connexion ;
- erreurs/reconnexion manuelle.

**Livrable :** tablette utilisable comme deuxième écran pour terminal/logs/documentation.

## M3 — V1

- VNC Authentication ;
- CopyRect ;
- Hextile ;
- clic droit/scroll ;
- clavier ;
- profils ;
- diagnostic ;
- tests Ubuntu + Windows ;
- soak test 2 h.

## M4 — Optimisation

Uniquement guidée par mesures : réduction des copies, ajustement encodage, fréquence des requests, mode touchpad, reconnexion automatique.
