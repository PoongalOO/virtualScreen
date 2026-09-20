# Instructions pour les assistants IA

## Contexte

Projet Android Kotlin transformant une Samsung Galaxy Tab 2 10.1 GT-P5110 sous Android 4.2.2 en écran secondaire VNC 1280×800.

## Contraintes non négociables

- Kotlin ;
- `minSdk = 17` ;
- Android Views classiques ;
- pas de Jetpack Compose ;
- aucune API Android > 17 sans garde de compatibilité ;
- dépendances minimales et compatibles API 17 ;
- SurfaceView/Bitmap/Canvas pour le rendu initial ;
- RFB/VNC implémenté localement ;
- aucun réseau sur le thread UI ;
- aucune allocation réseau non bornée ;
- aucun secret dans les logs ;
- optimisation pour CPU/RAM anciens ;
- résolution nominale 1280×800 ;
- fonctionnement LAN hors cloud.

## Avant de proposer du code

1. Vérifier la disponibilité de chaque API Android sur API 17.
2. Ne pas introduire une bibliothèque moderne sans vérifier son minSdk.
3. Pour le protocole, considérer les données serveur comme non fiables.
4. Ne jamais supposer qu'un `InputStream.read()` remplit le buffer demandé.
5. Vérifier endianness, overflow et limites framebuffer.
6. Préférer une solution simple mesurable à une abstraction complexe.

## Tests obligatoires

Tout parseur RFB doit avoir des tests unitaires. Tout décodeur d'encodage doit avoir des fixtures déterministes. Toute optimisation de performance doit être justifiée par une mesure sur la GT-P5110.

## Interdictions

- Compose ;
- coroutines/libraries imposant un minSdk incompatible sans validation explicite ;
- exposition du port VNC à Internet ;
- stockage silencieux du mot de passe en clair ;
- réécriture massive sans issue correspondante ;
- optimisation prématurée avant le MVP RAW.
