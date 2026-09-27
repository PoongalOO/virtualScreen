# Journal des modifications

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/). Les versions avant 0.2.0 n'ont jamais été publiées (développement uniquement) et n'ont donc pas d'entrée ici — voir [ISSUES.md](ISSUES.md) pour l'historique complet, issue par issue.

## [0.2.0] - 2026-09-28

Première version publiée (APK de release, voir « Limites connues »). Transforme une Samsung Galaxy Tab 2 10.1 GT-P5110 (Android 4.2.2) en écran secondaire 1280×800 pour un PC, via un client RFB/VNC écrit dans ce dépôt.

### Ajouté

- protocole RFB : versions 3.3/3.8, sécurité `None` et authentification VNC classique (mot de passe), encodages RAW, CopyRect et Hextile (choix automatique, ou forcé pour comparer, Diagnostic) ;
- rendu 1:1 natif en 1280×800, et ajusté avec bandes noires pour tout autre format d'écran (jusqu'à 1920×1200) ;
- toucher direct (tap, glissement, appui long = clic droit, deux doigts = molette) et mode pavé tactile relatif à sensibilité réglable ;
- clavier virtuel (texte, touches spéciales) et touches physiques ;
- écran de connexion avec profils enregistrés (jamais le mot de passe), reconnexion manuelle et automatique (recul progressif borné) ;
- mode plein écran explicite, écran de diagnostic (informations matérielles, mesures de performance optionnelles, choix de l'encodage) ;
- avertissement permanent sur l'écran de connexion : VNC classique circule en clair, à réserver à un réseau local de confiance ;
- guides d'installation, de première connexion et de configuration du PC (Ubuntu, Windows).

### Sécurité

- revue systématique de toutes les valeurs reçues du serveur (bornes, débordements, plus de 120 000 cas testés, voir SECURITY.md) ;
- aucune donnée sensible journalisée (mot de passe, texte tapé, contenu d'écran), vérifié par des tests qui détectent une fuite injectée et par une campagne sur l'appareil réel ;
- mot de passe jamais enregistré ; gardé en mémoire (jamais sur le disque) seulement le temps d'une session avec reconnexion automatique.

### Limites connues

- **cet APK n'est pas signé** : Android refuse de l'installer tel quel (aucun signingConfig de release n'est encore configuré, décision explicite en attente d'une stratégie de garde du keystore) — utiliser l'APK de débogage (voir README.md, « Installation ») ou signer celui-ci soi-même (`apksigner sign`) en attendant ;
- pas de test complet sur un vrai PC Ubuntu (écran étendu) ni Windows (voir GUIDE_UBUNTU.md, GUIDE_WINDOWS.md : une partie de chaque guide n'a pas pu être vérifiée faute de matériel) ;
- un défaut de toucher a été trouvé puis corrigé pour une orientation précise de la GT-P5110 (SS-088) ; testé sur cet appareil précis, pas sur d'autres modèles ;
- Tight, ZRLE et les autres encodages RFB ne sont pas implémentés (RAW et Hextile suffisent aux usages mesurés, voir PERFORMANCE.md) ;
- pas de minification (ProGuard/R8) : aucune règle n'a encore été écrite ni vérifiée sur l'appareil.
