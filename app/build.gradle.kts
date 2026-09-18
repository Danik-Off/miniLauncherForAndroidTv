plugins {
    id("com.android.application")
}

android {
    namespace = "com.minilauncher.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minilauncher.tv"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `adb install` works out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

// No dependencies on purpose: pure platform APIs keep the APK tiny and the process light.
