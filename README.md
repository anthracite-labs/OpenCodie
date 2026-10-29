# OpenCodie

One Android APK containing the OpenCode v2 WebUI and a real Termux userspace:
the app unpacks a bundled Termux prefix on first launch, starts `opencode serve`
on loopback and shows OpenCode's own WebUI in a platform WebView. `pkg install`
in the OpenCode terminal installs real Termux packages, and OpenCode's shell tool
runs in that same prefix.

## Build

```bash
curl -fsSL https://bun.sh/install | bash -s "bun-v1.4.4"
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME=/opt/android-ndk            # NDK r28b
bash scripts/build-release.sh                       # -> app/build/outputs/apk/release/*.apk
```

Requirements: Linux x86_64, JDK 17, Android SDK (compileSdk 37), Bun 1.4.4,
cargo and the Android NDK. OpenCode (`v2.2.2`), the Termux bootstrap
(`bootstrap-2026.10.25-r1+apt.android-7`) and the native dependency versions are
pinned by commit/tag in `runtime.lock` and checked out by the build scripts.

Individual steps: `scripts/build-opencode.sh` (Android/Bionic OpenCode with the
WebUI embedded), `scripts/build-runtime.sh` (Termux bootstrap + OpenCode,
packaged as `runtime-aarch64.zip` and embedded in the APK),
`scripts/build-release.sh` (both, then `./gradlew --no-daemon :app:assembleRelease`).

CI: the **android** workflow runs on every pull request and on manual dispatch,
builds the whole pipeline, verifies the APK (package id, SDK levels, ABI,
embedded runtime, signature) and uploads `opencodie-apk` plus
`opencodie-build-log`.

## Install

- arm64-v8a device, Android 10 (API 29) or newer, sideloaded.
- Application ID `com.termux`, target SDK 28: this keeps the Termux package
  identity and allows executing binaries from the app's private data directory.
  It cannot coexist with another app signed with a different key under the same
  package name; uninstall an existing `com.termux` app first.
- First launch extracts the bundled runtime (no network). Later launches reuse it.
  An APK upgrade replaces only the OpenCode executables and keeps packages,
  projects, configuration and sessions.

## Signing

CI signs with a keystore from these repository secrets:

| Secret | Meaning |
| --- | --- |
| `OPENCODIE_KEYSTORE_BASE64` | base64 of a PKCS12/JKS keystore |
| `OPENCODIE_KEYSTORE_PASSWORD` | store password |
| `OPENCODIE_KEY_ALIAS` | key alias |
| `OPENCODIE_KEY_PASSWORD` | key password (defaults to the store password) |

Without them the workflow generates a throwaway key so the artifact is still
installable, but such builds cannot upgrade each other — set the secrets for a
stable signing identity. Locally the same variables (`OPENCODIE_KEYSTORE_FILE`
instead of the base64 secret) drive `app/build.gradle.kts`.

## Known limitations

- The bundled `@opentui/core` native renderer is upstream's musl build (its only
  `NEEDED` is bare `libc.so`). No bionic rebuild of the OpenTUI Zig library is
  performed, so the terminal UI front-end (`opencode` TUI) is not supported; the
  WebUI, server, persistent PTY sessions and shell are.
- OpenCode's native file finder (fff) has no bionic build here, so
  `OPENCODE_DISABLE_FFF=1` selects the ripgrep backend.
- `arm64-v8a` only.
- Device behaviour (WebUI, terminal, `pkg install`, upgrade persistence) has not
  been exercised on hardware from this environment.

## Credits

The Android build pipeline (`scripts/check-elf.sh`, `scripts/patch-opentui-loader.py`,
`patches/bun-pty-portable09.patch`, and the structure of the OpenCode build) is
adapted from [Astreele/opencode-android](https://github.com/Astreele/opencode-android)
(MIT). OpenCode, Termux packages and Bun are used unmodified where noted in
`runtime.lock`.
