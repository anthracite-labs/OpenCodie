plugins {
    id("com.android.application")
}

android {
    namespace = "com.termux"
    // Pinned in runtime.lock; CI overrides it when the SDK repository does not
    // offer that platform yet.
    compileSdk = System.getenv("OPENCODIE_COMPILE_SDK")?.toIntOrNull() ?: 37

    defaultConfig {
        // Deliberately the Termux package identity: the bundled Termux prefix is
        // /data/data/com.termux/files, and APT metadata contains absolute paths.
        applicationId = "com.termux"
        minSdk = 29
        // Termux executes binaries from its private data directory, which Android
        // only allows for apps targeting SDK 28 or lower.
        targetSdk = 28
        versionCode = (System.getenv("OPENCODIE_VERSION_CODE") ?: "1").toInt()
        versionName = "2.2.2-1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    lint {
        // targetSdk 28 is required so Android keeps allowing exec() from the
        // app's private data directory (Termux). The Play-store target-API
        // check does not apply to a sideloaded APK.
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources { noCompress += "zip" }

    // Signing credentials come from the environment; never commit a keystore.
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
