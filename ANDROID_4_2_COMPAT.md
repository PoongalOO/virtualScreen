# Compatibilité Android 4.2.2 / API 17

## Base

Android 4.2 et 4.2.2 correspondent à l'API level 17. Le projet doit donc fixer `minSdk = 17`.

## Politique API

- toute API Android utilisée doit être disponible en API 17 ou protégée par un test de version ;
- Android Studio Lint doit rester activé ;
- aucune dépendance ne doit imposer un minSdk supérieur ;
- pas de Jetpack Compose ;
- préférer Android Views / SurfaceView ;
- tester systématiquement sur le matériel réel.

## Build moderne vs runtime ancien

`compileSdk` peut être supérieur au `minSdk` : compiler avec un SDK récent ne rend pas automatiquement les nouvelles API disponibles sur Android 4.2.2. C'est `minSdk` et le code réellement exécuté qui déterminent la compatibilité runtime.

## Choix de prudence

Pour réduire les incompatibilités :

- UI Android classique ;
- `java.net.Socket` et streams ;
- `SurfaceView`, `Canvas`, `Bitmap` ;
- `SharedPreferences` ;
- éviter une pile AndroidX lourde tant qu'elle n'est pas nécessaire ;
- vérifier les versions Kotlin/AGP choisies par un build + installation réelle dès SS-001.

## Critère bloquant

Aucune décision d'architecture ne doit être considérée validée tant qu'un APK minimal compilé avec la toolchain retenue n'a pas été installé et lancé sur la GT-P5110 Android 4.2.2.
