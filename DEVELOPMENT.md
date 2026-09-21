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

## Session de référence (SS-061)

Mesure les allocations et détecte une fuite sur la tablette réelle, contre une charge serveur variée (horloge, deux zones éparses, terminal qui défile, fenêtres qui s'ouvrent et se ferment, repos ; 60 s par phase, en boucle) avec des entrées tactiles et clavier toutes les 15 s. Prérequis : la tablette en USB (`adb`), l'APK debug installé, `docker`, Python 3.

```bash
scripts/reference-server.sh up                # TigerVNC 1280x800 SANS mot de passe dans un conteneur : réseau local seulement
python3 scripts/reference_session.py --minutes 30 --out /chemin/session30 --host <ip-lan-du-pc>
python3 scripts/analyze_session.py /chemin/session30
scripts/reference-server.sh down
```

Options : `--no-perf` (mesures désactivées, configuration de production : seuls le ramasse-miettes et `dumpsys meminfo` sont enregistrés), `--fit` (rendu ajusté), `--phase-s`, `--input-every-s`. Le script garde l'écran allumé pendant la session et restaure le réglage à la fin. Il n'utilise aucun mot de passe VNC.

Pièges rencontrés : `KEYCODE_WAKEUP` n'existe pas sur Android 4.2 (le script utilise la touche Marche) ; les réglages de l'application sont stockés comme **chaînes** (`<string name="show_performance">true</string>`), un booléen XML est ignoré sans erreur ; le clavier logiciel décale les champs du formulaire. `reference_session.py` réessaie une fois après `adb kill-server` si la tablette disparaît, puis s'arrête proprement en marquant la session « interrompue » (données partielles conservées, à ne pas confondre avec une session complète).
