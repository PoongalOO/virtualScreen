# Plan de tests

## Stratégie

Trois niveaux : tests unitaires JVM, tests d'intégration protocole avec faux serveur, tests matériels sur GT-P5110.

## Unitaires

- conversions big-endian/little-endian ;
- lecture `U8/U16/U32` ;
- PixelFormat ;
- validation rectangles ;
- RAW ;
- CopyRect ;
- Hextile ;
- mapping coordonnées tactiles ;
- keysyms.

## Fragmentation TCP

Aucun code ne doit supposer qu'un `read()` retourne la totalité d'un message. Les tests doivent découper les mêmes fixtures en fragments de 1, 2, 3, N octets.

## Cas négatifs

- serveur ferme pendant handshake ;
- bannière invalide ;
- security type inconnu ;
- auth refusée ;
- dimensions nulles/excessives ;
- rectangle hors écran ;
- taille provoquant overflow ;
- **ces cas sont couverts systématiquement par `rfb/robustness` (SS-070)** : valeurs limites de toutes les longueurs et géométries, flux aléatoires, mutés et tronqués à chaque position, voir SECURITY.md ;
- type message inconnu ;
- Wi-Fi coupé puis restauré.

## Journaux et fuites (SS-071)

Package `hygiene` : `LogHygieneTest` (analyse du code : un seul appel de journal, aucune interpolation sensible dans un message, aucune classe de données à champ sensible, champs de mot de passe, clavier, sauvegarde), `SecretCanaryTest` (un mot de passe et un texte distinctifs cherchés dans tout ce qui est observable), `perf/PerfLogLineTest` (la ligne de journal n'est que des nombres). Voir SECURITY.md. Vérification sur la tablette : `scripts/check_logcat_secrets.py` (mot de passe d'essai jetable et texte tapé cherchés dans les quatre tampons de `logcat` après une connexion authentifiée ; code de retour 1 si une trace est trouvée).

## Tests matériels (SS-084)

Campagne formelle sur GT-P5110 Android 4.2.2, menée le 2026-09-25, TigerVNC 1280×800 protégé par mot de passe VNC (authentification réelle, pas `None`), adb par Wi-Fi (tablette sur chargeur secteur). Vérité serveur par `xwd -root` + `convert` (ImageMagick), indépendante du client, comme dans RFB_SPEC.md.

| # | Test | Résultat |
|---|---|---|
| 1 | Installation APK | `pm uninstall` puis installation propre ; écran d'accueil affiche « Aucune connexion enregistrée » (aucune donnée résiduelle). **OK** |
| 2 | Démarrage à froid | `am start -W` juste après l'installation (processus jamais lancé) : `TotalTime` **2 119 ms**, aucun plantage ni ANR dans le journal. **OK** |
| 3 | Connexion Wi-Fi LAN | Connexion authentifiée (mot de passe VNC) à un serveur du LAN : **1,45 s** entre le tap sur « Connexion » et l'écran distant affiché. **OK** |
| 4 | 1280×800 1:1 | Comparaison pixel à pixel avec le `xwd` du serveur : **0 pixel de différence** (hors bandeau de mesures propre à l'application). **OK** |
| 5 | Terminal avec défilement | Défilement figé puis comparé au `xwd` : **0 pixel de différence** en dehors d'une petite zone (~25×30 px, centre de l'écran) — le curseur que **TigerVNC compose lui-même** dans les pixels envoyés, déjà documenté (RFB_SPEC.md, SS-025/026) ; absent du `xwd`, qui ne le capture pas. **OK** |
| 6 | Déplacement d'une fenêtre | Fenêtre déplacée avec `xdotool windowmove` (sans gestionnaire de fenêtres) : ancienne zone effacée, nouvelle position exacte, **0 pixel de différence** (même réserve qu'au n° 5). **OK** |
| 7 | Saisie clavier | Texte tapé reçu **exactement** par le serveur ; touche « Effacer » retire bien les derniers caractères avant validation (`Hello` après avoir tapé `HelloXXX` + 3 fois Retour arrière). **OK** |
| 8 | Déconnexion/reconnexion | (a) Coupure du serveur : dialogue d'erreur + compte à rebours (SS-053/055) ; (b) serveur relancé : reconnexion **automatique** réussie, session vérifiée fonctionnelle (texte tapé après reconnexion bien reçu) ; (c) déconnexion **manuelle** puis reconnexion **manuelle** depuis l'accueil, mot de passe ressaisi (jamais conservé, comme prévu) : **4,53 s**. **OK** |
| 9a | Retour application | Mise en arrière-plan (Accueil) puis retour **par l'icône** (équivalent d'un vrai réappui) : la tâche existante reprend au premier plan sans redémarrer, session intacte. Une coupure de connexion **s'est produite pendant les manipulations** (~2 min en arrière-plan) : détectée et affichée correctement au retour, sans plantage ; reconnexion manuelle immédiate. **OK** |
| 9b | Rotation | **Non vérifié par ce système** : l'app est en `sensorLandscape`, qui suit le seul capteur physique — `settings put system user_rotation` n'a aucun effet (vérifié), ce qui est attendu et écarte un contournement logiciel accidentel. Une rotation physique de la tablette est nécessaire. |
| 10 | Session 30 min | SS-061 : `scripts/reference_session.py`, résultats dans PERFORMANCE.md. **OK** |
| 11 | Session 2 h | Idem. **OK** |

**Aucun défaut trouvé.** Deux essais initiaux invalides, corrigés en cours de campagne : un décalage vertical de 48 px dans mon script de comparaison (l'application n'affiche pas de barre système par-dessus l'écran distant, contrairement à mon hypothèse) et une confusion d'index de champ (mot de passe vs port) dans le scénario de reconnexion manuelle — deux erreurs de méthode, aucune du côté de l'application.

## Matrice PC

| OS | Écran virtuel | Serveur VNC | RAW | Hextile | Input | 2 h |
|---|---|---|---|---|---|---|
| Ubuntu | à documenter | à sélectionner | ☐ | ☐ | ☐ | ☐ |
| Windows | Virtual Display Driver ou équivalent | à sélectionner | ☐ | ☐ | ☐ | ☐ |

## Performance

Mesurer sur appareil réel :

- temps connexion ;
- FPS ;
- Mbit/s ;
- CPU si accessible ;
- mémoire ;
- latence interaction ;
- température subjective/appareil ;
- batterie sur 30 min si non alimenté.
