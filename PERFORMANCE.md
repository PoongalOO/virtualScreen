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

## Mesures avant optimisation

Toujours mesurer RAW avant d'implémenter un encodage plus complexe. Hextile économise potentiellement du réseau mais consomme du CPU. Le meilleur compromis doit être établi sur la tablette réelle.

## Anti-patterns

- créer un nouveau Bitmap pour chaque update ;
- `ByteArray` plein écran alloué en boucle ;
- convertir chaque pixel via objets Kotlin ;
- collections temporaires dans les boucles pixels ;
- logging par rectangle/pixel en release ;
- redessiner tout l'écran lorsqu'un petit rectangle change sans nécessité.
