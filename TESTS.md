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

## Tests matériels

Sur GT-P5110 Android 4.2.2 :

1. installation APK ;
2. démarrage à froid ;
3. connexion Wi-Fi LAN ;
4. 1280×800 1:1 ;
5. terminal avec défilement ;
6. déplacement d'une fenêtre ;
7. saisie clavier ;
8. déconnexion/reconnexion ;
9. rotation/retour application ;
10. session 30 min (SS-061 : `scripts/reference_session.py`, résultats dans PERFORMANCE.md) ;
11. session 2 h (idem).

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
