# OpenCodie

One Android APK containing the OpenCode v2 WebUI and a real Termux userspace:
the app unpacks a bundled Termux prefix on first launch, starts `opencode serve`
on loopback and shows OpenCode's own WebUI in a platform WebView. `pkg install`
in the OpenCode terminal installs real Termux packages, and OpenCode's shell
tool runs in that same prefix.

## Build

```bash
curl -fsSL https://bun.sh/install | bash -s "bun-v1.4.4"
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME=/opt/android-ndk            # NDK r28b
bash scripts/build-release.sh                       # -> app/build/outputs/apk/release/*.apk
```

Requirements: Linux x86_64, JDK 17, Android SDK (compileSdk 37), Bun 1.4.4,
cargo and the Android NDK. Versions are pinned in `runtime.lock`.

Individual steps: `scripts/build-opencode.sh` (Android OpenCode executable with
the WebUI embedded), `scripts/build-runtime.sh` (Termux bootstrap + OpenCode,
packaged as `runtime-aarch64.zip` and embedded in the APK), `scripts/build-release.sh`
(both, then `./gradlew --no-daemon :app:assembleRelease`).

If the toolchain is unavailable locally, run the **android** workflow
(`.github/workflows/android.yml`, manual dispatch) and download the APK artifact.

## Install

- arm64-v8a device, Android 10 (API 29) or newer, sideloaded.
- Application ID `com.termux`, target SDK 28: this keeps the Termux package
  identity and allows executing binaries from the app's private data directory.
  It cannot coexist with another app signed with a different key under the same
  package name; uninstall an existing `com.termux` app first.
- First launch extracts the bundled runtime (no network). Later launches reuse it;
  APK upgrades replace only the OpenCode files and keep packages, projects and
  sessions.
- Signing: set `OPENCODIE_KEYSTORE_FILE`, `OPENCODIE_KEYSTORE_PASSWORD`,
  `OPENCODIE_KEY_ALIAS`, `OPENCODIE_KEY_PASSWORD` (or the matching repository
  secrets) to produce a signed release APK; otherwise the release APK is
  unsigned and `./gradlew :app:assembleDebug` gives an installable debug APK.

## Known limitations

- The bundled `@opentui/core` native renderer is upstream's musl build (its only
  `NEEDED` is bare `libc.so`). No bionic rebuild of the OpenTUI Zig library is
  performed, so the terminal UI front-end (`opencode` TUI) is not supported; the
  WebUI, server, PTY sessions and shell are.
- OpenCode's native file finder (fff) has no bionic build here, so
  `OPENCODE_DISABLE_FFF=1` selects the ripgrep backend.
- WebUI downloads do not save files (file upload/chooser works).
- `arm64-v8a` only.

## Credits

The Android build pipeline (`scripts/check-elf.sh`, `scripts/patch-opentui-loader.py`,
`patches/bun-pty-portable09.patch`, and the structure of the OpenCode build) is
adapted from [Astreele/opencode-android](https://github.com/Astreele/opencode-android)
(MIT). OpenCode, Termux packages and Bun are used unmodified where noted in
`runtime.lock`.
