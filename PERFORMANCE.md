# Budget de performance

## Contraintes

La GT-P5110 est un matériel ancien. Le projet optimise d'abord la stabilité, la latence et les allocations.

## Framebuffer

1280 × 800 × 4 octets ≈ 4,1 Mio pour ARGB_8888. Plusieurs copies plein écran peuvent rapidement augmenter la pression mémoire. Éviter les conversions globales à chaque update.

## Objectifs initiaux

- 10–20 FPS pour un bureau peu animé ;
- pas d'objectif vidéo 30/60 FPS ;
- < 80 Mio de mémoire applicative comme cible initiale à confirmer ;
- pas de croissance mémoire continue ;
- traitement incrémental des rectangles.

## Mesures sur la GT-P5110 (Android 4.2.2)

| Mesure | Résultat | Source |
|---|---|---|
| Tas Java maximum d'une application | **48 Mio** | SS-003 (écran de diagnostic) |
| `Framebuffer` 1280×800 (SS-020) | ~4 000 Kio de tas | sonde SS-020 |
| Deux buffers 1920×1200 (framebuffer + équivalent Bitmap) | ~17 Mio alloués sans `OutOfMemoryError` | sonde SS-020, VM limitée à 48 Mio |
| Croissance du tas après 4 000 mises à jour | 0 Kio | sonde SS-020 |
| `fillRect` plein écran 1280×800 | ~10,1 ms | sonde SS-020 |
| `writeRect` plein écran 1280×800 | ~14,6 ms | sonde SS-020 |
| `writeRect` 64×64 / `fillRect` 16×16 | ~42 µs / ~6 µs | sonde SS-020 |
| Décodage RAW plein écran 1280×800 (4 Mio), loopback local, **sans Wi-Fi** | ~100–105 ms (médiane de 5 essais ; min 97, max 140) | sonde SS-023/024 |
| Réception RAW plein écran par **Wi-Fi** (4 Mio) | ~1,4 s (≈ 2,9 Mio/s), 3 essais : 1 406 / 1 386 / 1 438 ms | sonde SS-023/024 |
| Rejet d'un rectangle hors écran, d'un encodage, d'un type de message ou d'un `ServerCutText` invalides (loopback local) | 0–1 ms | sonde SS-023/024 |
| Écran complet 1280×800 d'un vrai bureau (TigerVNC, terminaux à l'écran), **RAW seul**, par Wi-Fi, demande → écran décodé | ~1 700 ms | sonde SS-025/026, 1 essai |
| Même écran, **Hextile**, par Wi-Fi | ~90 ms (90 et 92 ms sur 2 essais) | sonde SS-025/026 |

À interpréter avec prudence : ces valeurs viennent d'une sonde ponctuelle exécutée avec `app_process -Xmx48m` (VM du shell, pas le processus de l'application), une seule série de mesures. Elles servent de base de comparaison, pas de garantie ; elles seront refaites dans l'application avec l'instrumentation de SS-060.

Observations de SS-023/024 :

- **Le décodage plein écran plafonne à ~10 mises à jour/s hors réseau** (~100 ms par 4 Mio). La mesure inclut la copie locale par la socket, donc le coût de la seule conversion d'octets en ARGB n'est pas isolé : c'est le point de départ de SS-062, pas une conclusion. Les mises à jour partielles coûtent proportionnellement à leur surface.
- **Sur ce Wi-Fi, c'est le réseau qui limite, pas le CPU** : un écran complet en RAW met ~1,4 s à arriver, soit 14 fois le temps de décodage. C'est un argument pour Hextile (SS-026/063) et pour éviter les mises à jour plein écran ; à confirmer avec un vrai serveur.
- **Latence de première réception non expliquée** : après un moment d'inactivité, l'arrivée de données a parfois pris de ~0,2 s à ~2,2 s (variable d'un essai à l'autre) alors que le serveur envoyait immédiatement, et jamais en loopback local (0–1 ms) pour les mêmes cas. Hypothèse, **non vérifiée** : économie d'énergie du Wi-Fi de la tablette (l'AP retient les paquets quand la radio dort). À investiguer avec le test matériel (SS-084) car cela pèse directement sur la latence perçue ; pistes : `WifiLock`, réglage de veille Wi-Fi.

Observation de SS-025/026 :

- **Hextile change l'ordre de grandeur pour un bureau d'applications** : sur un vrai bureau (TigerVNC, fond uni et terminaux de texte), l'écran complet arrive en ~90 ms en Hextile contre ~1 700 ms en RAW, soit environ 19 fois plus vite, décodage inclus, avec un écran reconstruit identique au serveur. **Portée limitée** : un seul bureau de test, surtout uni ou en texte, quelques essais. Hextile est très favorable à ce type de contenu et le serait beaucoup moins pour de la vidéo ou des photos (tuiles brutes). C'est un premier indice en faveur du choix d'encodage, pas le benchmark de SS-063, qui devra comparer RAW et Hextile sur plusieurs types de contenu.
- **CopyRect n'a pas de coût de réseau** : un défilement de terminal envoie 4 octets par rectangle au lieu des pixels ; pendant le test, ~26 millions de pixels ont été reconstruits par copie interne (`copyRect`, sans allocation) sur ~270 mises à jour.

Conséquences :

- La cible « < 80 Mio » ne peut concerner que la mémoire totale du processus : le **tas Java plafonne à 48 Mio**. Le budget des buffers d'écran (`Framebuffer.MAX_PIXELS`) est dimensionné sur cette limite.
- Une mise à jour plein écran coûte ~15 ms rien que pour copier les pixels, avant conversion de format et transfert vers le Bitmap. Sur un bureau peu animé les mises à jour sont des petits rectangles (quelques dizaines de µs) : c'est ce qu'il faut préserver (SS-062, aucune copie plein écran inutile).

## Mesures avant optimisation

Toujours mesurer RAW avant d'implémenter un encodage plus complexe. Hextile économise potentiellement du réseau mais consomme du CPU. Le meilleur compromis doit être établi sur la tablette réelle.

## Anti-patterns

- créer un nouveau Bitmap pour chaque update ;
- `ByteArray` plein écran alloué en boucle ;
- convertir chaque pixel via objets Kotlin ;
- collections temporaires dans les boucles pixels ;
- logging par rectangle/pixel en release ;
- redessiner tout l'écran lorsqu'un petit rectangle change sans nécessité.
