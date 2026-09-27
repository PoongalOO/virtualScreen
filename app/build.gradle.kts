// SS-001 : module applicatif minimal.
// Politique de dépendances (ARCHITECTURE.md) : aucune bibliothèque réseau/protocole
// pour le MVP ; API standard Java/Android uniquement. Ce module ne dépend donc
// d'aucune bibliothèque runtime tierce, seulement de la stdlib Kotlin (ajoutée par
// le plugin kotlin-android) et de JUnit pour les tests.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.webinfoconcept.secondscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.webinfoconcept.secondscreen"
        // Android 4.2 / 4.2.2 (GT-P5110) == API 17. Contrainte non négociable (AGENTS.md).
        minSdk = 17
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            // SS-093 : décision explicite (2026-09-28), pas un oubli. Pas de signingConfig : l'APK produit par
            // `assembleRelease` est donc NON SIGNÉ (`app-release-unsigned.apk`) et ne s'installe sur aucun appareil
            // tel quel — Android exige une signature, même auto-signée, pour tout APK. Signer est reporté à une
            // décision ultérieure sur la garde d'un keystore (voir CHANGELOG.md et .github/workflows/release.yml).
            // Pas de minification non plus (aucune règle ProGuard écrite ni vérifiée sur la GT-P5110 à ce jour :
            // AGENTS.md interdit l'optimisation prématurée) : le contenu de l'APK release est donc identique au debug.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        // Ni ViewBinding ni Compose pour ce squelette (SS-001). Compose est de toute
        // façon exclu du projet (AGENTS.md).
        viewBinding = false
        compose = false
    }

    lint {
        // Lint doit rester actif (ANDROID_4_2_COMPAT.md) ; on échoue le build sur erreur.
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
