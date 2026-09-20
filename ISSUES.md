# Backlog / Issues

Format conseillé : labels `P0`, `P1`, `P2`, `android`, `rfb`, `render`, `input`, `security`, `test`, `docs`.

## Epic E0 — Initialisation

### SS-001 — Créer le projet Android Kotlin compatible API 17 — P0
**Critères :** projet compilable ; `minSdk=17` ; application installable sur GT-P5110 ; orientation paysage disponible ; aucune dépendance inutile.

### SS-002 — Ajouter CI build/lint/tests — P1
**Critères :** build debug automatisé ; tests unitaires exécutés ; artefact APK produit.

### SS-003 — Créer écran de diagnostic matériel — P1
**Critères :** version Android, API, résolution, mémoire et ABI affichées sans donnée sensible.

## Epic E1 — Transport et handshake RFB

### SS-010 — Implémenter RfbSocket — P0
Connexion TCP, timeouts, fermeture idempotente, erreurs typées.

### SS-011 — Négocier ProtocolVersion — P0
Support au minimum de la version nécessaire aux serveurs de test.

### SS-012 — Implémenter négociation de sécurité — P0
Commencer par `None` pour environnement de développement local ; prévoir l'interface permettant d'ajouter VNC Authentication.

### SS-013 — Implémenter ClientInit / ServerInit — P0
Lire largeur, hauteur, pixel format et desktop name avec validation des tailles.

### SS-014 — Implémenter authentification VNC classique — P1
Aucune trace du mot de passe ; tests avec mot de passe valide/invalide.

## Epic E2 — Framebuffer

### SS-020 — Créer Framebuffer 1280×800 — P0
Allocation stable ; API de mise à jour rectangulaire ; contrôle des limites.

### SS-021 — Implémenter SetPixelFormat — P0
Format 32 bits true-color documenté et testé.

### SS-022 — Implémenter SetEncodings — P0
RAW initialement, puis CopyRect/Hextile.

### SS-023 — Décoder FramebufferUpdate — P0
Support multi-rectangles ; gestion des messages incomplets/EOF.

### SS-024 — Implémenter RAW — P0
Affichage exact d'un bureau de test ; tests de rectangles partiels.

### SS-025 — Implémenter CopyRect — P1
Copie sans corruption, y compris zones se chevauchant.

### SS-026 — Implémenter Hextile — P1
Tests unitaires par sous-encodage et comparaison avec image attendue.

### SS-027 — Implémenter FramebufferUpdateRequest — P0
Message de 10 octets (incrémental ou complet, zone U16), zone non vide validée, testé octet par octet. Issue ajoutée après SS-023 : sans ce message le client ne peut pas demander de mise à jour, et SS-031 en dépend. Le message de battement de SS-064 en dérive.

## Epic E3 — Rendu

### SS-030 — Créer RemoteSurfaceView — P0
Surface plein écran et rendu d'un framebuffer statique.

### SS-031 — Relier framebuffer et SurfaceView — P0
Mises à jour visibles sans allocation massive par frame.

### SS-032 — Rendu 1:1 1280×800 — P0
Aucun scaling sur résolution native.

### SS-033 — Ajouter scaling letterbox — P2
Ratio conservé pour serveur non 1280×800.

### SS-034 — Mode immersif compatible API 17 — P1
Masquer au maximum le chrome système sans bloquer la sortie de l'application. *(Devenu un **mode plein écran explicite**, bouton de la barre de commandes, faux par défaut : voir ARCHITECTURE.md, « Plein écran ». Sur Android 4.2 le mode immersif perd le premier toucher après quelques secondes d'inactivité.)*

## Epic E4 — Entrées

### SS-040 — Envoyer PointerEvent — P0
Coordonnées exactes et état bouton. *(Fait : `ClientMessages.pointerEvent`, `PointerMapper`, `PointerSender` ; le branchement à la vraie connexion est SS-054.)*

### SS-041 — Tap = clic gauche — P0
Down/up fiables sans double événement. **À prendre en compte (mesuré en SS-034 ; ne concerne plus que le mode plein écran, non imposé) :** sur Android 4.2, quand la barre système est masquée, le premier toucher qui la fait réapparaître n'est pas transmis à l'application ; voir ARCHITECTURE.md, « Mode immersif ».

### SS-042 — Drag — P0
Déplacement avec bouton maintenu. *(Fait : `TouchGestureDetector` + `DragListener`, `PointerActions`, `ClientMessages.dragStart`, `PointerSender.sendMove` ; le bouton n'est jamais laissé enfoncé. Le branchement à la vraie connexion est SS-054.)*

### SS-043 — Appui long = clic droit — P1
Seuil configurable et absence de clic gauche parasite. *(Fait : `LongPress`, `TouchGestureDetector`, `ClientMessages.rightClick`, `PointerActions.rightClick`. Seuil = celui de la plateforme ; pas encore de réglage propre à l'application. Pas de retour au doigt sur la GT-P5110, sans vibreur : voir ARCHITECTURE.md.)*

### SS-044 — Scroll deux doigts — P1
Conversion en événements de molette VNC. *(Fait : `Scroll`, `ScrollListener`, `TouchGestureDetector.onTwoFingersDown/Move`, `ClientMessages.wheel`, `PointerActions`. Pas de 40 px et sens naturel par défaut, non ajustés sur une vraie application ; pas de réglage utilisateur ni d'inertie.)*

### SS-045 — Mode touchpad relatif — P2
Sensibilité réglable.

### SS-046 — KeyEvent texte — P1
Saisie ASCII/latin de base.

### SS-047 — Touches spéciales — P1
Ctrl, Alt, Shift, Tab, Esc, Enter, Backspace.

## Epic E5 — UX et profils

### SS-050 — Écran de connexion — P0
Hôte, port, sécurité, connexion. *(Fait : `ConnectActivity`, `ProfileForm`, `ConnectionParams`. « Sécurité » = mot de passe VNC (types Aucun et VNC Authentication) ; le mot de passe n'est jamais enregistré.)*

### SS-051 — Enregistrer profils — P1
Nom/hôte/port, sans fuite de secret. *(Fait : `ProfileStore`, `MainActivity` (liste, suppression par appui long, dernier profil en tête).)*

### SS-052 — Barre de commandes distante — P1
Clavier, mode pointeur, diagnostic, déconnexion. *(Fait pour Diagnostic et Déconnexion ; **Clavier et Pointeur sont des boutons désactivés** en attendant SS-046/SS-047 et SS-045. Affichage : touche Retour ou tap à trois doigts.)*

### SS-053 — États et erreurs compréhensibles — P0
Connexion, négociation, auth, timeout, réseau coupé. *(Fait : `ConnectionState`, `ConnectionFailure` (17 causes), `FailureMessages`, panneau d'état de l'écran distant, signe de vie pour détecter un réseau coupé.)*

### SS-054 — Reconnexion manuelle — P0
Pas de redémarrage de l'application. *(Fait : `ConnectionController`, bouton Reconnecter. Le mot de passe n'étant pas conservé, il est redemandé si le serveur en exigeait un. Branche `KeepAlive`, `FramebufferUpdateRequest`, rendu et entrées sur la connexion réelle : SS-027, SS-031, SS-040 à SS-044, SS-064.)*

### SS-055 — Reconnexion automatique — P2
Backoff borné, désactivable.

## Epic E6 — Performance

### SS-060 — Instrumenter FPS et débit — P1
Mesures désactivables et peu coûteuses.

### SS-061 — Mesurer allocations — P1
Session de référence de 30 min puis 2 h.

### SS-062 — Réduire copies framebuffer — P1
Objectif : aucune copie plein écran inutile par update.

### SS-063 — Benchmark RAW vs Hextile — P2
Mesurer CPU, réseau, FPS et latence sur GT-P5110 réelle.

### SS-064 — Supprimer la latence de réveil Wi-Fi — P1
Sur liaison silencieuse, un paquet entrant attend en médiane ~700 ms (jusqu'à ~1,9 s) avant d'être délivré à la tablette. **Critères :** cause identifiée par mesure ; correctif validé sur GT-P5110 réelle contre un vrai serveur ; aucune allocation par battement ; arrêt propre à la fin de session. Livré : battement applicatif `KeepAlive` (une requête incrémentale d'un pixel toutes les 100 ms), latence des mises à jour spontanées ramenée à moins de 4 ms. Reste à brancher sur la connexion réelle (SS-054/SS-031).

## Epic E7 — Sécurité

### SS-070 — Validation stricte des tailles réseau — P0
Overflow, tailles négatives/interprétées, rectangles hors limites.

### SS-071 — Nettoyer les logs — P0
Aucun mot de passe ou contenu sensible.

### SS-072 — Avertissement connexion non chiffrée — P1
Message explicite pour VNC classique.

### SS-073 — Documenter pare-feu et LAN — P1
Ne jamais recommander d'exposer 5900 sur Internet.

## Epic E8 — Tests et compatibilité

### SS-080 — Tests unitaires endian/pixel format — P0
Fixtures déterministes.

### SS-081 — Tests RAW — P0
Rectangles complets/partiels et limites.

### SS-082 — Tests Hextile — P1
Cas de sous-encodages.

### SS-083 — Faux serveur RFB de tests — P1
Flux déterministes, fragmentation TCP simulée.

### SS-084 — Test GT-P5110 Android 4.2.2 — P0
Installation, connexion, rendu, tactile.

### SS-085 — Test Ubuntu — P0
Écran virtuel 1280×800 + serveur VNC documentés.

### SS-086 — Test Windows — P0
Écran virtuel 1280×800 + serveur VNC documentés.

### SS-087 — Soak test 2 h — P1
Pas de crash/fuite croissante significative.

## Epic E9 — Documentation et release

### SS-090 — README utilisateur — P0
Installation et première connexion.

### SS-091 — Guide Ubuntu — P0
Procédure reproductible et dépannage.

### SS-092 — Guide Windows — P0
Procédure reproductible et dépannage.

### SS-093 — Générer APK release — P1
Versionnement, checksum et notes de version.

### SS-094 — Licence et notices — P1
Licence du projet et dépendances documentées.
