# SecondScreen GT-P5110

Application Android en Kotlin transformant une Samsung Galaxy Tab 2 10.1 GT-P5110 (Android 4.2.2 / API 17) en écran secondaire 1280×800 pour un PC Ubuntu ou Windows, via RFB/VNC sur réseau local.

## Objectif

Le PC expose un écran virtuel 1280×800 au travers d'un serveur VNC. La tablette se connecte au serveur, affiche le framebuffer en plein écran et transmet les interactions tactiles/clavier sous forme d'événements RFB.

## Installation

L'application n'est pas publiée sur un magasin d'applications : c'est un APK à installer soi-même, ce qu'Android 4.2.2 permet nativement.

**Récupérer l'APK** :

- le plus simple : onglet **Actions** de ce dépôt GitHub → dernière exécution réussie du workflow *Android CI* → section *Artifacts* → télécharger `app-debug` (contient `app-debug.apk`). Nécessite d'être connecté à GitHub ; l'artefact n'est conservé que quelques mois ;
- ou le compiler soi-même (voir [DEVELOPMENT.md](DEVELOPMENT.md)) : `./gradlew assembleDebug`, l'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

C'est un APK de débogage : il n'existe pas encore d'APK de release signé (SS-093). Fonctionnellement identique, seulement non optimisé.

**Autoriser son installation, une fois**, sur la GT-P5110 (Android 4.2.2) : *Paramètres → Sécurité → Sources inconnues* → cocher. Android affiche un avertissement générique sur le risque d'installer hors d'un magasin d'applications : attendu pour un APK qu'on installe soi-même.

**Installer** :

- par USB : `adb install -r app-debug.apk` ;
- sans câble : copier `app-debug.apk` sur la tablette (transfert USB simple, e-mail, stockage cloud…), l'ouvrir depuis les téléchargements ou un gestionnaire de fichiers, accepter l'installation.

L'application s'appelle **SecondScreen** dans le tiroir d'applications.

## Première connexion

1. **Configurer le PC en premier** : le PC doit exposer un écran virtuel 1280×800 via un serveur VNC **avant** de connecter la tablette — voir [PC_SETUP.md](PC_SETUP.md), puis [GUIDE_UBUNTU.md](GUIDE_UBUNTU.md) ou [GUIDE_WINDOWS.md](GUIDE_WINDOWS.md) selon le système. Noter l'adresse IP (ou le nom) du PC sur le réseau local, le port choisi (5900 par défaut) et le mot de passe VNC si le serveur en demande un.
2. **Ouvrir SecondScreen** sur la tablette, appuyer sur **Ajouter**.
3. Un avertissement rappelle que VNC classique circule **en clair** : à réserver à un réseau local de confiance (voir [SECURITY.md](SECURITY.md)).
4. Renseigner un nom (libre, pour se souvenir de ce PC), l'adresse et le port du PC, le mot de passe s'il y en a un. Le mot de passe n'est **jamais enregistré**, seuls le nom/l'adresse/le port peuvent l'être (case « Enregistrer cette connexion »).
5. Appuyer sur **Connexion**. En cas d'échec, le message affiché explique quoi vérifier (adresse, pare-feu, serveur non démarré, mot de passe…).
6. Une fois connectée, l'écran du PC s'affiche en plein cadre. Faire réapparaître la barre de commandes par la touche Retour ou un appui à trois doigts :

   | Bouton | Effet |
   |---|---|
   | Clavier | ouvre le clavier virtuel pour taper du texte |
   | Pointeur : direct / touchpad | toucher direct (le doigt vise l'endroit touché) ou mode pavé tactile relatif, avec réglage de sensibilité |
   | Échelle | 1:1, ajustée, ou ajustée automatiquement si le serveur n'est pas en 1280×800 |
   | Plein écran | masque la barre système d'Android (le premier toucher après une pause peut alors se perdre, limite d'Android 4.2) |
   | Diagnostic | informations sur l'appareil, mesures de performance optionnelles |
   | Déconnexion | ferme la session et revient à l'écran d'accueil |

   Tap = clic gauche, glissement = déplacement avec bouton maintenu, appui long = clic droit, deux doigts = molette, trois doigts = afficher/masquer la barre.

Une connexion coupée se rétablit automatiquement si la case correspondante est cochée (le mot de passe reste alors en mémoire, jamais enregistré, jusqu'à la fin de la session), sinon un bouton **Reconnecter** est proposé.

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
- [Guide Ubuntu](GUIDE_UBUNTU.md)
- [Guide Windows](GUIDE_WINDOWS.md)
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
