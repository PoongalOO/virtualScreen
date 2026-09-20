# SecondScreen GT-P5110

Application Android en Kotlin transformant une Samsung Galaxy Tab 2 10.1 GT-P5110 (Android 4.2.2 / API 17) en écran secondaire 1280×800 pour un PC Ubuntu ou Windows, via RFB/VNC sur réseau local.

## Objectif

Le PC expose un écran virtuel 1280×800 au travers d'un serveur VNC. La tablette se connecte au serveur, affiche le framebuffer en plein écran et transmet les interactions tactiles/clavier sous forme d'événements RFB.

## Documents

- [Cahier des charges](CAHIER_DES_CHARGES.md)
- [Architecture](ARCHITECTURE.md)
- [Spécification RFB](RFB_SPEC.md)
- [Backlog / issues](ISSUES.md)
- [Roadmap](ROADMAP.md)
- [Plan de tests](TESTS.md)
- [Sécurité](SECURITY.md)
- [Performance](PERFORMANCE.md)
- [Compatibilité Android 4.2.2](ANDROID_4_2_COMPAT.md)
- [Guide de développement](DEVELOPMENT.md)
- [Configuration PC](PC_SETUP.md)
- [Définition of Done](DEFINITION_OF_DONE.md)
- [Guide IA / AGENTS.md](AGENTS.md)

## Contraintes principales

- appareil cible : Samsung GT-P5110 ;
- Android 4.2.2, donc `minSdk = 17` ;
- résolution native : 1280×800 ;
- Kotlin ;
- Android Views classiques, pas Jetpack Compose ;
- dépendances minimales ;
- fonctionnement LAN sans cloud ni compte utilisateur ;
- protocole RFB/VNC implémenté dans l'application ;
- priorité à la stabilité et à la faible consommation CPU/RAM plutôt qu'aux animations UI.

## MVP

Le MVP doit se connecter à un serveur VNC sans chiffrement sur un LAN de confiance, négocier RFB, décoder l'encodage RAW 32 bits, afficher un bureau 1280×800 en plein écran et envoyer les événements pointeur de base.

Le chiffrement n'est pas requis pour le MVP : l'application doit explicitement avertir que le VNC classique ne doit être utilisé que sur un réseau local de confiance. Une version ultérieure pourra ajouter un tunnel ou une couche de sécurité compatible avec le matériel.
