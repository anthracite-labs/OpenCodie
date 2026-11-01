plugins {
    id("com.android.application")
}

fun envInt(name: String, default: String): Int {
    val raw = System.getenv(name) ?: default
    return raw.toIntOrNull() ?: error("$name must be an integer, got '$raw'")
}

fun openCodeVersion(): String {
    val line = rootProject.file("runtime.lock").useLines { lines ->
        lines.firstOrNull { it.startsWith("OPENCODE_VERSION=") }
    } ?: error("OPENCODE_VERSION missing in runtime.lock")
    return line.substringAfter("=").removePrefix("v").ifEmpty {
        error("OPENCODE_VERSION is empty in runtime.lock")
    }
}

val openCodeVersion = openCodeVersion()

android {
    namespace = "com.termux"
    // Use the pinned stable SDK; never auto-select beta platforms from CI.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.termux"
        minSdk = 29
        // Kept at 29 (not latest) so requestLegacyExternalStorage keeps working
        // for the Termux storage bridge; min <= target is required by AGP.
        targetSdk = 29
        versionCode = envInt("OPENCODIE_VERSION_CODE", "1")
        versionName = openCodeVersion
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    lint {
        // Target 29 is intentionally expired (see above); keep the build green
        // while the legacy storage bridge is still required.
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources { noCompress += "zip" }

    signingConfigs {
        // Created only when the keystore env is present so debug/lint/unit-test
        // configuration (which never signs) still works without secrets.
        // Release builds fail fast at execution time if signing is missing.
        if (System.getenv("OPENCODIE_KEYSTORE_FILE") != null) {
            create("release") {
                storeFile = file(System.getenv("OPENCODIE_KEYSTORE_FILE"))
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

// Note: release signing stays lenient here on purpose. CodeQL autobuild,
// dependency-submission and lint configure `assemble` without secrets.
// Fail-closed happens one layer up: scripts/build-release.sh preflights the
// keystore file, and .github/workflows/build-and-release.yml verifies the
// signature with apksigner before publishing.

dependencies {
    // Minimal unit-test framework for timeout/regression tests.
    // Instrumentation tests are intentionally not added: pure-JVM tests with
    // injected fakes keep the suite runnable without a device or emulator.
    testImplementation("junit:junit:4.13.2")
}
