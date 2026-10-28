import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "pl.iskri.pocketlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.iskri.pocketlock"
        minSdk = 27
        targetSdk = 34
        versionCode = 92
        versionName = "1.4-novalock"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Allow installing beside the original Pocket Lock.
            applicationIdSuffix = ".nova"
            versionNameSuffix = "-v6.6"
        }
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Text recognition model is bundled inside the APK: offline inference on Cocoon only.
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
