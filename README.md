# OpenCodie

One Android APK containing the OpenCode v2 WebUI and a real Termux userspace:
the app unpacks a bundled Termux prefix on first launch, starts `opencode serve`
on loopback and shows OpenCode's own WebUI in a platform WebView. `pkg install`
in the OpenCode terminal installs real Termux packages, and OpenCode's shell tool
runs in that same prefix.

## Build

```bash
python3 scripts/fetch_verified.py bun-linux-x64.zip work/bun.zip
unzip -q work/bun.zip -d work/bun
export PATH="$PWD/work/bun/bun-linux-x64:$PATH"
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME=/opt/android-ndk            # NDK r28b
bash scripts/build-release.sh                       # -> dist/OpenCodie.apk (signed)
```

Requirements: Linux x86_64, JDK 17, Android SDK (compileSdk 37), Bun 1.4.4,
cargo and the Android NDK. OpenCode (`v2.2.2`), the Termux bootstrap
(`bootstrap-2026.10.25-r1+apt.android-7`) and the native dependency versions are
pinned by commit/tag in `runtime.lock` and checked out by the build scripts.

Individual steps: `scripts/build-opencode.sh` (Android/Bionic OpenCode with the
WebUI embedded), `scripts/build-runtime.sh` (Termux bootstrap + OpenCode,
packaged as `runtime-aarch64.zip` and embedded in the APK),
`scripts/build-release.sh` (both, then `./gradlew --no-daemon :app:assembleRelease`).

CI: `.github/workflows/build-and-release.yml` runs on PRs, `main` and version
tags. PRs compile a debug APK for verification but do not publish it. Main
builds and version tags require the permanent signing secret, and upload only
`OpenCodie.apk`. GitHub Actions artifacts are ZIP downloads by design;
each signed `main` build publishes a versioned GitHub prerelease with the
raw `OpenCodie.apk` for direct download. Matching version tags publish the
stable release. Users should download the APK from GitHub Releases, not CI
artifacts.

The Android `versionName` is read from `OPENCODE_VERSION` in `runtime.lock`
(e.g. OpenCode `v2.2.2` = Android `2.2.2`). The independent Android
`versionCode` increases per CI build so updates are installable.

## Install

- arm64-v8a device, Android 10 (API 29) or newer, sideloaded.
- Application ID `com.termux`, target SDK 28: this keeps the Termux package
  identity and allows executing binaries from the app's private data directory.
  It cannot coexist with another app signed with a different key under the same
  package name; uninstall an existing `com.termux` app first.
- The default workspace is `~/projects/workspace`, not the Termux home directory.
  The bundled bootstrap includes Git for project initialization on first launch,
  and selects the official Termux default package mirror rather than scanning
  worldwide mirrors on every `pkg install`.
  New installations start in `~/projects/workspace` so the Git button
  creates a project repository, not a Git repository over the whole home directory.
  OpenCode's shell tool is noninteractive; when requesting package installation,
  use `pkg install -y <package>` to avoid the confirmation prompt.
- On the first launch, after the bundled Termux runtime finishes its bootstrap,
  OpenCodie automatically asks for Android storage access: a permission prompt
  on Android 10 or the **All files access** settings screen on Android 11+.
  The user can decline; the WebUI still works and is not blocked. Permission is
  requested automatically only once per install, not on every launch.
  After approval, `~/storage/shared`, `~/storage/downloads`,
  `~/storage/documents` and other standard Termux links are created without
  deleting user files. If permission was declined, `termux-setup-storage`
  explicitly retries the request. Android-protected areas such as other apps'
  `Android/data` remain restricted. The native file picker remains usable
  without broad storage access.
- First launch extracts the bundled runtime (no network). An app-owned
  completion marker is created **after** the Termux second stage succeeds;
  interrupted fresh installations retry from clean bundled files. An APK upgrade
  replaces only the OpenCode executables and keeps packages, projects,
  configuration and sessions. Unknown existing prefixes without the new
  completion marker are **preserved and rejected**, never silently deleted.
  Back up any installation created by an older pre-marker build before upgrading.

## Mobile shell output

OpenCode's native mobile WebUI retains its existing command/output cards.
On narrow screens the shell card has **Wrap** and **Expand** controls, with
horizontal scrolling available for unwrapped terminal tables. System font
scaling is applied through Android WebView, and the original copy controls
remain available. This is a small patch against the pinned upstream WebUI,
applied at build time; it adds no runtime dependencies.

## Signing and release

The one-time GitHub Actions signing job generated a stable PKCS12 signing key and
committed **only encrypted** `.github/signing/release.p12.cms`,
`.github/signing/password.txt.cms`, and the public release certificate.
The temporary provisioning workflow has been removed.

**Required one-time setup:** store the corresponding private *decryption*
key (backed up outside GitHub) as the repository Actions secret
`OPENCODIE_SIGNING_DECRYPT_KEY`. Never commit or paste this private key
into a pull request, log, or repository file.

Using the GitHub CLI from a machine with repository admin rights:

```bash
gh secret set OPENCODIE_SIGNING_DECRYPT_KEY < /secure/path/OPENCODIE_ENCRYPTION_PRIVATE_KEY.pem
```

Pull requests build the debug APK without release signing credentials.
Pushes to main and release tags **require** the signing secret and produce a
release APK signed with the stable key. To publish a GitHub Release, push a
`v*` tag on a tested commit on main. Tagged releases **fail closed** if
the signing secret has not been configured. Preserve the decryption key
offline: losing it prevents signing future updates with the same key.

The signing certificate fingerprint is recorded in
`.github/signing/release-public.pem`; the build verifies it before signing.
Build and packaging CI checks do not replace testing the WebUI, PTY, package
management and upgrade behavior on an Android device.

## Known limitations

- The bundled `@opentui/core` native renderer is upstream's musl build (its only
  `NEEDED` is bare `libc.so`). No bionic rebuild of the OpenTUI Zig library is
  performed, so the terminal UI front-end (`opencode` TUI) is not supported; the
  WebUI, server, persistent PTY sessions and shell are.
- OpenCode's native file finder (fff) has no bionic build here, so
  `OPENCODE_DISABLE_FFF=1` selects the ripgrep backend.
- `arm64-v8a` only.
- WebUI and native Termux package installation have been observed on an ARM64
  Android 16 device. Upgrade persistence and all interactive PTY operations
  still require device-level regression testing.
- **Play Protect:** a valid APK signature does not make a sideloaded app
  automatically trusted by Google. Unknown-app scanning and old-target warnings
  may appear because this build intentionally retains `targetSdk 28` for
  Termux execution. Do not disable Play Protect as a workaround; inspect the
  exact warning, verify the APK's signing certificate and distribute from a
  trusted release channel.

## Credits

The Android build pipeline (`scripts/check-elf.sh`, `scripts/patch-opentui-loader.py`,
`patches/bun-pty-portable09.patch`, and the structure of the OpenCode build) is
adapted from [Astreele/opencode-android](https://github.com/Astreele/opencode-android)
(MIT). OpenCode, Termux packages and Bun are used unmodified where noted in
`runtime.lock`.
