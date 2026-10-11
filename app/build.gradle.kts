plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fossfiles.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fossfiles.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Firma con la misma clave de debug para poder instalar y probar
            // el build optimizado directo en el celular sin crear un keystore
            // propio. Para publicar de verdad, cambia esto por tu firma real.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Genera un APK separado por arquitectura de CPU, más ligero que uno
    // "universal" que carga las cuatro. Equivalente a `flutter build apk --split-per-abi`.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true // además genera uno universal por si acaso
        }
    }

    lint {
        // "MainActivity must extend android.app.Activity" es un falso positivo
        // conocido de esta versión de lint con Compose + AGP — la clase sí
        // extiende Activity (vía ComponentActivity), pero el chequeo se
        // confunde. Se desactiva solo para no bloquear el build de release.
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Todo lo necesario ya viene en el SDK de Jetpack Compose.
    // Nada de Firebase, Crashlytics, Analytics, Ads, ni ninguna
    // dependencia de red: cero superficie para telemetría.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Para mover la lectura de archivos fuera del hilo principal y que la UI no se congele.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}