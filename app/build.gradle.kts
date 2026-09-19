import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: keystore/keystore.properties locally (git-ignored) or environment variables in CI.
// Without either, the release build falls back to the debug key so `adb install` keeps working.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? = System.getenv(key) ?: keystoreProps.getProperty(key)
val releaseStoreFile: File? = signingValue("KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Version comes from the git tag in CI (-PversionName=1.2.0 -PversionCode=42); local builds use these defaults.
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.minilauncher.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minilauncher.tv"
        minSdk = 21
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
