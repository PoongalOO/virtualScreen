# Guide de développement

## Environnement

- Android Studio ;
- SDK Android ;
- Kotlin ;
- Gradle/AGP compatibles avec la version d'Android Studio choisie ;
- ADB ;
- GT-P5110 avec débogage USB activé.

## Première étape obligatoire

Créer une application minimale Kotlin avec `minSdk=17`, la compiler puis l'installer :

```bash
./gradlew assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ne commencer le moteur RFB qu'après validation de ce smoke test.

## Conventions

- package suggéré : `fr.webinfoconcept.secondscreen` (à confirmer) ;
- Kotlin idiomatique mais sans dépendance aux API Android récentes ;
- pas de `!!` sauf justification ;
- exceptions réseau converties en erreurs de domaine ;
- constantes protocolaires regroupées ;
- tailles réseau en types permettant de contrôler les dépassements ;
- fonctions de parsing testées.

## Branches

- `main` : stable ;
- branches `feature/SS-xxx-description` ;
- une issue par changement cohérent.

## Commits

Exemple :

```text
feat(rfb): implement protocol version negotiation (SS-011)
```

## Revue

Toute PR protocolaire doit vérifier : bounds, fragmentation TCP, endianness, fermeture des ressources, absence d'allocation non bornée et tests associés.
