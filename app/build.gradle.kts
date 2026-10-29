plugins {
    id("com.android.application")
}

android {
    namespace = "com.termux"
    // Use the pinned stable SDK; never auto-select beta platforms from CI.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.termux"
        minSdk = 29
        targetSdk = 28
        versionCode = (System.getenv("OPENCODIE_VERSION_CODE") ?: "1").toInt()
        versionName = "2.2.2-1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources { noCompress += "zip" }

    signingConfigs {
        val keystore = System.getenv("OPENCODIE_KEYSTORE_FILE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("OPENCODIE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENCODIE_KEY_ALIAS")
                keyPassword = System.getenv("OPENCODIE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
