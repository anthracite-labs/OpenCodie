// Buildscript classpath forcing covers the AGP plugin itself (resolved via
// the `plugins` block below). Project configurations are forced separately
// in `allprojects` further down.
buildscript {
    configurations.named("classpath") {
        resolutionStrategy {
            force(
                "com.google.guava:guava:33.7.2-jre",
                "org.bouncycastle:bcprov-jdk18on:1.86",
                "org.bouncycastle:bcpkix-jdk18on:1.86",
                "org.bouncycastle:bcutil-jdk18on:1.86",
                "org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21",
                "org.apache.commons:commons-lang3:3.21.0",
                "org.apache.httpcomponents:httpclient:4.5.14",
                "org.jdom:jdom2:2.0.6.1",
                "org.bitbucket.b_c:jose4j:0.9.7"
            )
        }
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}

// Centralized remediation for GitHub Dependabot alerts (1 critical, 4 high,
// 5 moderate). AGP 9.5.0 is the latest stable, so its vulnerable transitives
// are forced to patched versions here until upstream releases include them.
// Each `because` cites the CVE closed by the forced version.
val forcedVersions = mapOf(
    "com.google.guava:guava" to ("33.7.2-jre" to "CVE-2026-102554 fixed in 33.7.2"),
    "org.bouncycastle:bcprov-jdk18on" to ("1.86" to "CVE-2026-0636 fixed in 1.84; CVE-2026-8763 (critical) and CVE-2026-13506 fixed in 1.85"),
    "org.bouncycastle:bcpkix-jdk18on" to ("1.86" to "CVE-2026-5588 fixed in 1.84"),
    "org.bouncycastle:bcutil-jdk18on" to ("1.86" to "Keep BouncyCastle modules aligned with bcprov/bcpkix 1.86"),
    "org.jetbrains.kotlin:kotlin-gradle-plugin" to ("2.4.21" to "CVE-2026-53914 fixed in 2.4.20-Beta1; use latest stable 2.4.x"),
    "org.apache.commons:commons-lang3" to ("3.21.0" to "CVE-2025-48924 fixed in 3.21.0"),
    "org.apache.httpcomponents:httpclient" to ("4.5.14" to "CVE-2020-13956 fixed in 4.5.13"),
    "org.jdom:jdom2" to ("2.0.6.1" to "CVE-2021-33813 fixed in 2.0.6.1"),
    "org.bitbucket.b_c:jose4j" to ("0.9.7" to "CVE-2024-29371 fixed in 0.9.7")
)
allprojects {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                forcedVersions["${requested.group}:${requested.name}"]?.let { (version, reason) ->
                    useVersion(version)
                    because(reason)
                }
            }
        }
    }
}
