plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build installs as an update over the last one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ssymba0.flashlight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssymba0.flashlight"
        minSdk = 31
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed key, so each new APK can be installed over the previous one.
    signingConfigs {
        create("release") {
            storeFile = file("flashlight.keystore")
            storePassword = "flashlight"
            keyAlias = "flashlight"
            keyPassword = "flashlight"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
