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
- type message inconnu ;
- Wi-Fi coupé puis restauré.

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
10. session 30 min ;
11. session 2 h.

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
