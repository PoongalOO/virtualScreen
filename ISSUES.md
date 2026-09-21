# Backlog / Issues

Format conseillé : labels `P0`, `P1`, `P2`, `android`, `rfb`, `render`, `input`, `security`, `test`, `docs`.

## État d'avancement

Mis à jour le 2026-09-21 d'après le code, les tests et l'historique git (dernier commit : SS-060/SS-062 ; SS-061 en cours). Légende : ✅ Fait · 🟡 Partiel (ce qui manque est indiqué) · ⬜ À faire. « Fait » veut dire que les critères ont été vérifiés comme décrit dans la note de l'issue, pas que tout a été testé sur toute la matrice matérielle.

| Epic | Fait | Partiel | À faire |
|---|---|---|---|
| E0 — Initialisation | 3/3 | 0 | 0 |
| E1 — Transport et handshake RFB | 5/5 | 0 | 0 |
| E2 — Framebuffer | 8/8 | 0 | 0 |
| E3 — Rendu | 5/5 | 0 | 0 |
| E4 — Entrées | 8/8 | 0 | 0 |
| E5 — UX et profils | 6/6 | 0 | 0 |
| E6 — Performance | 3/5 | 1 | 1 |
| E7 — Sécurité | 0/4 | 3 | 1 |
| E8 — Tests et compatibilité | 4/8 | 2 | 2 |
| E9 — Documentation et release | 0/5 | 0 | 5 |
| **Total** | **42/57** | **6** | **9** |

## Epic E0 — Initialisation

### SS-001 — Créer le projet Android Kotlin compatible API 17 — P0
**Statut : ✅ Fait** — Installé et lancé sur la GT-P5110 (Android 4.2.2), `minSdk = 17`.
**Critères :** projet compilable ; `minSdk=17` ; application installable sur GT-P5110 ; orientation paysage disponible ; aucune dépendance inutile.

### SS-002 — Ajouter CI build/lint/tests — P1
**Statut : ✅ Fait** — Workflow GitHub Actions : build, lint, tests unitaires, APK debug.
**Critères :** build debug automatisé ; tests unitaires exécutés ; artefact APK produit.

### SS-003 — Créer écran de diagnostic matériel — P1
**Statut : ✅ Fait** — `DiagnosticActivity`.
**Critères :** version Android, API, résolution, mémoire et ABI affichées sans donnée sensible.

## Epic E1 — Transport et handshake RFB

### SS-010 — Implémenter RfbSocket — P0
**Statut : ✅ Fait**
Connexion TCP, timeouts, fermeture idempotente, erreurs typées.

### SS-011 — Négocier ProtocolVersion — P0
**Statut : ✅ Fait**
Support au minimum de la version nécessaire aux serveurs de test.

### SS-012 — Implémenter négociation de sécurité — P0
**Statut : ✅ Fait**
Commencer par `None` pour environnement de développement local ; prévoir l'interface permettant d'ajouter VNC Authentication.

### SS-013 — Implémenter ClientInit / ServerInit — P0
**Statut : ✅ Fait**
Lire largeur, hauteur, pixel format et desktop name avec validation des tailles.

### SS-014 — Implémenter authentification VNC classique — P1
**Statut : ✅ Fait**
Aucune trace du mot de passe ; tests avec mot de passe valide/invalide.

## Epic E2 — Framebuffer

### SS-020 — Créer Framebuffer 1280×800 — P0
**Statut : ✅ Fait**
Allocation stable ; API de mise à jour rectangulaire ; contrôle des limites.

### SS-021 — Implémenter SetPixelFormat — P0
**Statut : ✅ Fait**
Format 32 bits true-color documenté et testé.

### SS-022 — Implémenter SetEncodings — P0
**Statut : ✅ Fait**
RAW initialement, puis CopyRect/Hextile.

### SS-023 — Décoder FramebufferUpdate — P0
**Statut : ✅ Fait**
Support multi-rectangles ; gestion des messages incomplets/EOF.

### SS-024 — Implémenter RAW — P0
**Statut : ✅ Fait**
Affichage exact d'un bureau de test ; tests de rectangles partiels.

### SS-025 — Implémenter CopyRect — P1
**Statut : ✅ Fait**
Copie sans corruption, y compris zones se chevauchant.

### SS-026 — Implémenter Hextile — P1
**Statut : ✅ Fait**
Tests unitaires par sous-encodage et comparaison avec image attendue.

### SS-027 — Implémenter FramebufferUpdateRequest — P0
**Statut : ✅ Fait**
Message de 10 octets (incrémental ou complet, zone U16), zone non vide validée, testé octet par octet. Issue ajoutée après SS-023 : sans ce message le client ne peut pas demander de mise à jour, et SS-031 en dépend. Le message de battement de SS-064 en dérive.

## Epic E3 — Rendu

### SS-030 — Créer RemoteSurfaceView — P0
**Statut : ✅ Fait**
Surface plein écran et rendu d'un framebuffer statique.

### SS-031 — Relier framebuffer et SurfaceView — P0
**Statut : ✅ Fait**
Mises à jour visibles sans allocation massive par frame.

### SS-032 — Rendu 1:1 1280×800 — P0
**Statut : ✅ Fait**
Aucun scaling sur résolution native.

### SS-033 — Ajouter scaling letterbox — P2
**Statut : ✅ Fait**
Ratio conservé pour serveur non 1280×800. *(Fait : `RenderGeometry` (échelle, décalage, bandes), rendu partiel sans couture dans `RemoteSurfaceView`, conversion des touchers dans `PointerMapper`, option « Échelle » de la barre de commandes qui ajuste aussi un 1280×800 (voir les 48 lignes du bas sans plein écran). Le 1:1 exact reste la règle pour 1280×800. Coût mesuré sur la tablette : ~25 ms par petite mise à jour, ~55 ms par grande zone, ~200 ms plein écran.)*

### SS-034 — Mode immersif compatible API 17 — P1
**Statut : ✅ Fait** — Le mode immersif forcé a été remplacé par un mode plein écran **explicite** (désactivé par défaut) : Android 4.2 avale le premier toucher en immersif.
Masquer au maximum le chrome système sans bloquer la sortie de l'application. *(Devenu un **mode plein écran explicite**, bouton de la barre de commandes, faux par défaut : voir ARCHITECTURE.md, « Plein écran ». Sur Android 4.2 le mode immersif perd le premier toucher après quelques secondes d'inactivité.)*

## Epic E4 — Entrées

### SS-040 — Envoyer PointerEvent — P0
**Statut : ✅ Fait**
Coordonnées exactes et état bouton. *(Fait : `ClientMessages.pointerEvent`, `PointerMapper`, `PointerSender` ; le branchement à la vraie connexion est SS-054.)*

### SS-041 — Tap = clic gauche — P0
**Statut : ✅ Fait**
Down/up fiables sans double événement. **À prendre en compte (mesuré en SS-034 ; ne concerne plus que le mode plein écran, non imposé) :** sur Android 4.2, quand la barre système est masquée, le premier toucher qui la fait réapparaître n'est pas transmis à l'application ; voir ARCHITECTURE.md, « Mode immersif ».

### SS-042 — Drag — P0
**Statut : ✅ Fait**
Déplacement avec bouton maintenu. *(Fait : `TouchGestureDetector` + `DragListener`, `PointerActions`, `ClientMessages.dragStart`, `PointerSender.sendMove` ; le bouton n'est jamais laissé enfoncé. Le branchement à la vraie connexion est SS-054.)*

### SS-043 — Appui long = clic droit — P1
**Statut : ✅ Fait**
Seuil configurable et absence de clic gauche parasite. *(Fait : `LongPress`, `TouchGestureDetector`, `ClientMessages.rightClick`, `PointerActions.rightClick`. Seuil = celui de la plateforme ; pas encore de réglage propre à l'application. Pas de retour au doigt sur la GT-P5110, sans vibreur : voir ARCHITECTURE.md.)*

### SS-044 — Scroll deux doigts — P1
**Statut : ✅ Fait**
Conversion en événements de molette VNC. *(Fait : `Scroll`, `ScrollListener`, `TouchGestureDetector.onTwoFingersDown/Move`, `ClientMessages.wheel`, `PointerActions`. Pas de 40 px et sens naturel par défaut, non ajustés sur une vraie application ; pas de réglage utilisateur ni d'inertie.)*

### SS-045 — Mode touchpad relatif — P2
**Statut : ✅ Fait**
Sensibilité réglable. *(Fait : `TouchpadDetector`, `TouchpadActions`, `PointerPosition`, `InputSettings`, bouton Pointeur et curseur de sensibilité (0,3× à 4,0×) de la barre de commandes. Gestes : déplacer, tap = clic, tap-glisser, appui long = clic droit, deux doigts = molette. Pas d'accélération ni de curseur local : le pointeur est dessiné par le serveur.)*

### SS-046 — KeyEvent texte — P1
**Statut : ✅ Fait**
Saisie ASCII/latin de base. *(Fait : `ClientMessages.keyEvent/keyPress/keyPresses`, `Keysyms`, `KeyboardInput`, `KeyboardInputView`, `ComposingDiff`, `KeyForwarder`. ASCII et Latin-1 directs, keysyms Unicode au-delà ; clavier virtuel et touches physiques.)*

### SS-047 — Touches spéciales — P1
**Statut : ✅ Fait**
Ctrl, Alt, Shift, Tab, Esc, Enter, Backspace. *(Fait : rangée de touches de l'écran distant (Échap, Tab, Ctrl, Alt, Maj, Suppr, Effacer, Entrée, flèches) ; Ctrl/Alt/Maj « à un coup », jamais coincés. Pas de répétition automatique d'une touche maintenue.)*

## Epic E5 — UX et profils

### SS-050 — Écran de connexion — P0
**Statut : ✅ Fait**
Hôte, port, sécurité, connexion. *(Fait : `ConnectActivity`, `ProfileForm`, `ConnectionParams`. « Sécurité » = mot de passe VNC (types Aucun et VNC Authentication) ; le mot de passe n'est jamais enregistré.)*

### SS-051 — Enregistrer profils — P1
**Statut : ✅ Fait**
Nom/hôte/port, sans fuite de secret. *(Fait : `ProfileStore`, `MainActivity` (liste, suppression par appui long, dernier profil en tête).)*

### SS-052 — Barre de commandes distante — P1
**Statut : ✅ Fait**
Clavier, mode pointeur, diagnostic, déconnexion. *(Fait pour Diagnostic et Déconnexion ; **Clavier (SS-046/SS-047) et Pointeur direct/touchpad (SS-045) faits.** Affichage : touche Retour ou tap à trois doigts.)*

### SS-053 — États et erreurs compréhensibles — P0
**Statut : ✅ Fait**
Connexion, négociation, auth, timeout, réseau coupé. *(Fait : `ConnectionState`, `ConnectionFailure` (17 causes), `FailureMessages`, panneau d'état de l'écran distant, signe de vie pour détecter un réseau coupé.)*

### SS-054 — Reconnexion manuelle — P0
**Statut : ✅ Fait**
Pas de redémarrage de l'application. *(Fait : `ConnectionController`, bouton Reconnecter. Le mot de passe n'étant pas conservé, il est redemandé si le serveur en exigeait un. Branche `KeepAlive`, `FramebufferUpdateRequest`, rendu et entrées sur la connexion réelle : SS-027, SS-031, SS-040 à SS-044, SS-064.)*

### SS-055 — Reconnexion automatique — P2
**Statut : ✅ Fait** — Décision à confirmer : mot de passe gardé en mémoire (voir SECURITY.md).
Backoff borné, désactivable. *(Fait : `ConnectionController` (boucle de reconnexion, `retryNow`, `stopAutoReconnect`), `ReconnectStatus`, `FailureKind.isTransient`, `ConnectionSettings`, case de l'écran de connexion, panneau avec compte à rebours. Délais 1, 2, 4, 8, 15, 30, 30, 30 s puis abandon. **Garde une copie du mot de passe en mémoire** (jamais sur le stockage) tant qu'elle est active : décision à confirmer, voir SECURITY.md. Cochée par défaut.)*

## Epic E6 — Performance

### SS-060 — Instrumenter FPS et débit — P1
**Statut : ✅ Fait** — Coût des mesures activées : environ +4 points de CPU (46,0 % contre 41,9 % d'un cœur, 3 mesures chacune, plages qui se recouvrent) sur une charge fixe.
Mesures désactivables et peu coûteuses. *(Fait : package `perf/` (`PerfStats`, `TrafficCounter`, `PerfSampler`, `PerfSnapshot`), crochets dans `ServerMessageReader`, `RfbSocket`, `ConnectionController`, `RemoteSurfaceView`, bandeau + ligne de journal `SecondScreenPerf` (nombres seulement), case dans Diagnostic. **Désactivé par défaut** ; désactivé = une lecture de booléen par point de mesure. 26 tests, 3 mutations détectées, vérifié sur la tablette. **Limite : coût des mesures activées estimé à +4 points de CPU sur 3 séries seulement** (PERFORMANCE.md). Le temps de « décodage » inclut l'attente réseau. Voir PERFORMANCE.md.)*

### SS-061 — Mesurer allocations — P1
**Statut : 🟡 Partiel** — session de 30 min faite ; **session de 2 h en cours** (lancée le 2026-09-21 à 08:37), résultats à ajouter.
Session de référence de 30 min puis 2 h. *(En place : comptage d'allocations Dalvik dans `perf/` (thread de session, processus, thread UI ; tas Java et natif), `ThreadMeter` (interface, pas de lambda qui boxerait), `scripts/reference-server.sh`, `reference_session.py`, `analyze_session.py`. 30 min sur la GT-P5110 : chemin chaud de 0 à ~1,7 Ko/s, aucune croissance du tas après GC, du PSS, des threads, des descripteurs ni des objets d'interface. **Limites :** `Debug.getGlobalGcInvocationCount()` rend toujours 0 sur l'appareil (les ramasse-miettes sont lus dans le journal) ; charge synthétique, un seul appareil ; les allocations « de production » (mesures désactivées) ne sont pas mesurées directement. Voir PERFORMANCE.md.)*

### SS-062 — Réduire copies framebuffer — P1
**Statut : ✅ Fait**
Objectif : aucune copie plein écran inutile par update. *(Fait : `DirtyRegion` garde jusqu'à 8 rectangles distincts, `RemoteSurfaceView.copyIntoBitmap` ne copie que ceux-là, la boîte englobante restant seule redessinée. Mesuré sur la GT-P5110 : copie 22,4 → 0,2 ms par rendu pour des zones éparses ; aucun gain ni perte pour une grande zone ; 0 copie plein écran. Image identique au pixel près (client RFB indépendant), mutation détectée. **Limites** : le coût de dessin (~20 ms, fixe) devient le plafond (~50 rendus/s) et n'a pas baissé ; la baisse de CPU est indicative ; regrouper les mises à jour n'a pas été fait faute de mesure qui le justifie. Voir PERFORMANCE.md.)*

### SS-063 — Benchmark RAW vs Hextile — P2
**Statut : ⬜ À faire** — RAW et Hextile sont implémentés, mais aucune mesure comparative sur la tablette n'a été faite.
Mesurer CPU, réseau, FPS et latence sur GT-P5110 réelle.

### SS-064 — Supprimer la latence de réveil Wi-Fi — P1
**Statut : ✅ Fait** — Branché sur la connexion réelle dans `ConnectionController` (via SS-054).
Sur liaison silencieuse, un paquet entrant attend en médiane ~700 ms (jusqu'à ~1,9 s) avant d'être délivré à la tablette. **Critères :** cause identifiée par mesure ; correctif validé sur GT-P5110 réelle contre un vrai serveur ; aucune allocation par battement ; arrêt propre à la fin de session. Livré : battement applicatif `KeepAlive` (une requête incrémentale d'un pixel toutes les 100 ms), latence des mises à jour spontanées ramenée à moins de 4 ms. Branché sur la connexion réelle par `ConnectionController` (SS-054).

## Epic E7 — Sécurité

### SS-070 — Validation stricte des tailles réseau — P0
**Statut : 🟡 Partiel** — Bornes et validations posées dans les lecteurs et décodeurs au fil des issues (dimensions ≤ 4096 et pixels bornés, nom du bureau, texte du presse-papiers, sous-rectangles Hextile, rectangles hors framebuffer), avec tests négatifs. Pas de revue transverse dédiée ni de test de robustesse sur flux aléatoires.
Overflow, tailles négatives/interprétées, rectangles hors limites.

### SS-071 — Nettoyer les logs — P0
**Statut : 🟡 Partiel** — Le code ne journalise que la ligne de mesures (nombres, tag `SecondScreenPerf`) ; le mot de passe est effacé après usage. Aucun test ni contrôle automatique n'empêche d'ajouter un journal sensible plus tard.
Aucun mot de passe ou contenu sensible.

### SS-072 — Avertissement connexion non chiffrée — P1
**Statut : ⬜ À faire** — Documenté dans README.md et SECURITY.md, mais **aucun avertissement dans l'application**.
Message explicite pour VNC classique.

### SS-073 — Documenter pare-feu et LAN — P1
**Statut : 🟡 Partiel** — SECURITY.md et PC_SETUP.md interdisent d'exposer 5900 et de le rediriger (NAT), sans consignes de pare-feu concrètes (ufw, pare-feu Windows).
Ne jamais recommander d'exposer 5900 sur Internet.

## Epic E8 — Tests et compatibilité

### SS-080 — Tests unitaires endian/pixel format — P0
**Statut : ✅ Fait** — `PixelFormatTest`, `PixelFormatEncodingTest`, `RfbBytesTest`.
Fixtures déterministes.

### SS-081 — Tests RAW — P0
**Statut : ✅ Fait** — `RawDecoderTest`.
Rectangles complets/partiels et limites.

### SS-082 — Tests Hextile — P1
**Statut : ✅ Fait** — `HextileDecoderTest` avec un encodeur de test.
Cas de sous-encodages.

### SS-083 — Faux serveur RFB de tests — P1
**Statut : ✅ Fait** — `FakeRfbServer`, `LoopbackPair` (fragmentation TCP simulée).
Flux déterministes, fragmentation TCP simulée.

### SS-084 — Test GT-P5110 Android 4.2.2 — P0
**Statut : 🟡 Partiel** — Installation, connexion, rendu 1:1, tactile, clavier, défilement, reconnexion vérifiés sur la GT-P5110 au fil des issues. Pas de campagne formelle (TESTS.md), ni session de 30 min / 2 h.
Installation, connexion, rendu, tactile.

### SS-085 — Test Ubuntu — P0
**Statut : 🟡 Partiel** — Tout a été testé contre TigerVNC 1280×800 dans un conteneur Docker sur un hôte Ubuntu. Ce n'est pas un écran virtuel **étendu** du PC, et rien n'est documenté (PC_SETUP.md ne donne que le principe).
Écran virtuel 1280×800 + serveur VNC documentés.

### SS-086 — Test Windows — P0
**Statut : ⬜ À faire** — Jamais testé avec un serveur Windows.
Écran virtuel 1280×800 + serveur VNC documentés.

### SS-087 — Soak test 2 h — P1
**Statut : ⬜ À faire**
Pas de crash/fuite croissante significative.

## Epic E9 — Documentation et release

### SS-090 — README utilisateur — P0
**Statut : ⬜ À faire** — Le README actuel présente le projet ; il n'explique ni l'installation ni la première connexion.
Installation et première connexion.

### SS-091 — Guide Ubuntu — P0
**Statut : ⬜ À faire** — PC_SETUP.md ne donne que le principe.
Procédure reproductible et dépannage.

### SS-092 — Guide Windows — P0
**Statut : ⬜ À faire** — PC_SETUP.md ne donne que le principe.
Procédure reproductible et dépannage.

### SS-093 — Générer APK release — P1
**Statut : ⬜ À faire** — Seul l'APK debug est produit (CI). `versionName` 0.1.0, ni minification, ni signature release, ni checksum, ni notes de version.
Versionnement, checksum et notes de version.

### SS-094 — Licence et notices — P1
**Statut : ⬜ À faire** — Aucun fichier LICENSE ni NOTICE.
Licence du projet et dépendances documentées.
