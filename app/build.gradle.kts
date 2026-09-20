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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
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
