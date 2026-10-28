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
        versionCode = 97
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
            // V6.11 test installs beside V6.10: CI debug signatures are not
            // stable between fresh GitHub runners. Never require uninstalling
            // the user's working V6.10 app to test launch detection.
            applicationIdSuffix = ".nova11"
            versionNameSuffix = "-v6.11"
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


// Run identity-normalization tests in CI, without any device data.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
