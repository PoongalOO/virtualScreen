# Notices tierces (SS-094)

SecondScreen est sous licence MIT (voir [LICENSE](LICENSE)). Cette page documente les dépendances tierces, conformément à la politique de dépendances d'ARCHITECTURE.md (« zéro dépendance réseau/protocole pour le MVP », API standard Java/Android). Liste établie à partir de `./gradlew :app:dependencies` (arbre résolu réel, pas une supposition) le 2026-09-28, `build.gradle.kts` / `app/build.gradle.kts`.

## Livré dans l'APK

| Dépendance | Version | Licence | Rôle |
|---|---|---|---|
| [Kotlin Standard Library](https://github.com/JetBrains/kotlin) (`org.jetbrains.kotlin:kotlin-stdlib`) | 1.9.24 | [Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/LICENSE) | Bibliothèque standard du langage, ajoutée automatiquement par le plugin `org.jetbrains.kotlin.android` ; le code de l'application est écrit en Kotlin (AGENTS.md). |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) (`org.jetbrains:annotations`) | 13.0 | [Apache License 2.0](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) | Dépendance transitive de `kotlin-stdlib` (annotations `@Nullable`/`@NotNull` internes au compilateur). Le code de l'application ne l'utilise pas directement. |

Ni RxJava, ni OkHttp, ni AndroidX, ni aucune autre bibliothèque réseau ou d'interface : le protocole RFB, le rendu (`SurfaceView`/`Bitmap`/`Canvas`) et les vues sont écrits dans ce dépôt, sur les seules API standard d'Android et de Kotlin (voir ARCHITECTURE.md, « Dépendances »).

## Utilisé seulement pour compiler et tester (absent de l'APK)

| Dépendance | Version | Licence | Rôle |
|---|---|---|---|
| [JUnit 4](https://junit.org/junit4/) (`junit:junit`) | 4.13.2 | [Eclipse Public License 1.0](https://junit.org/junit4/license.html) | Exécute les tests unitaires JVM (`app/src/test`). |
| [Hamcrest Core](http://hamcrest.org/JavaHamcrest/) (`org.hamcrest:hamcrest-core`) | 1.3 | [BSD 3-Clause License](https://github.com/hamcrest/JavaHamcrest/blob/master/LICENSE.txt) | Dépendance transitive de JUnit 4 (comparateurs `Matcher`), non utilisée directement. |

Ces deux bibliothèques ne sont déclarées qu'en `testImplementation` : elles ne font partie ni du classpath d'exécution (`releaseRuntimeClasspath`), ni de l'APK installé sur la tablette.

**Outils de construction** (Gradle, Android Gradle Plugin, JDK) ne sont pas des dépendances du logiciel : ils compilent le projet mais n'en font pas partie une fois l'APK produit ; leurs propres licences n'ont donc pas à être redistribuées avec l'application.

## Vérifier soi-même

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath   # livré dans l'APK
./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath   # tests seulement
```

Toute nouvelle dépendance doit être ajoutée à cette page (et respecter les critères d'ARCHITECTURE.md, dont « licence compatible ») avant d'être fusionnée.
