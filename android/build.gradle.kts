import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.thetimep.screentimer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thetimep.screentimer"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            storeFile = file(props.getProperty("storeFile", "keystore/release.keystore"))
            storePassword = props.getProperty("storePassword", "Sleeptimer2024")
            keyAlias = props.getProperty("keyAlias", "sleeptimer")
            keyPassword = props.getProperty("keyPassword", "Sleeptimer2024")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
