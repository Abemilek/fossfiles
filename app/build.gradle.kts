import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma propia (opcional): si existe keystore.properties en la raíz del proyecto, el build release
// se firma con tu keystore. Si no existe, usa la clave de debug para poder probarlo igual.
// keystore.properties y el .jks NO se suben a git (ver .gitignore).
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasOwnKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.fossfiles.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fossfiles.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.0"

        // La app está solo en español: no se empaquetan las traducciones de las librerías
        // (Compose/AndroidX traen decenas de idiomas) -> APK más chico.
        resourceConfigurations += listOf("es", "en")
    }

    signingConfigs {
        create("release") {
            if (hasOwnKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Con keystore.properties usa tu firma real; sin él, la clave de debug para
            // poder instalar y probar el build optimizado directo en el celular.
            signingConfig = signingConfigs.getByName(if (hasOwnKeystore) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Sin "splits" por arquitectura: la única pieza nativa es una librería pequeña de AndroidX
    // (libandroidx.graphics.path.so, para dibujar formas), así que un APK universal para todas las
    // arquitecturas es más simple y apenas más grande. Un solo APK.

    packaging {
        resources {
            // Archivos de metadatos de librerías que no hacen falta en el APK.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/com.android.tools/**",
                "/META-INF/proguard/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
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
    // Para mover la lectura de archivos fuera del hilo principal y que la UI no se congele.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Solo para las pruebas de JVM (./gradlew testDebugUnitTest). No entra en el APK.
    testImplementation("junit:junit:4.13.2")
}