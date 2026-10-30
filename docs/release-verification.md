# OpenCodie release gate

The security/reliability implementation is **not a production release approval**.
Device evidence, the permanent signer and named maintainer/security sign-off are
required. Tag pushes now create **draft** stable releases; main still publishes
prereleases after its signed build and attestation succeed. Nothing is pushed or
published by local verification.

## Reproducible local checks

Use JDK 17, SDK 37.0 and the pinned toolchain described in README. The current
managed environment uses `source /workspace/.opencodie/env.sh`.

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
for file in scripts/*.sh; do bash -n "$file"; done
bash -n runtime.lock
python3 -m py_compile scripts/*.py
bash scripts/build-opencode.sh
bun scripts/test-permissions.ts
bun scripts/test-web-policy.ts
bun scripts/test-web-redirects.ts
bash scripts/build-runtime.sh
OPENCODIE_RUNTIME_ARCHIVE="$PWD/app/src/main/assets/runtime-aarch64.zip" \
  ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

The browser gate uses the pinned upstream Playwright dev dependency and installed
Chromium/Chrome with a fresh isolated profile. It reproduces redirected script
execution without CSP, then checks that the actual server middleware's CSP blocks
the other origin while local scripts still run. This does not replace WebView
validation on Android. The genuine archive test is skipped without the explicit
archive environment variable; CI supplies it and must not accept a skipped gate.

Runtime identity includes launcher/server hashes so a same-upstream-version patch
updates APK-owned binaries. Android versionName remains the upstream version;
production versionCode is the increasing workflow run number. Manual recovery
builds must explicitly use a versionCode higher than every previously installed
build, with the same permanent signing key.

## Required device matrix

Maintainer must confirm and supply ARM64 devices for API 29, an intermediate
supported Android version, and Android 16/API 36. Record device model, API, WebView
version, previous/new versionCode, APK/runtime SHA-256, signer fingerprint, test
results and Logcat categories. Include real-device coverage.

Run `./gradlew --no-daemon :app:connectedDebugAndroidTest` on a dedicated lab
installation. The optional `workflow_dispatch` device lane targets a provisioned
`[self-hosted, android-arm64]` runner. It must have JDK/SDK, a connected ARM64 device
and installation consent. Restrict the runner group to the reviewed main-branch
workflow and isolated lab data; do not expose a device with user data to PR jobs. Never install a differently signed debug APK over a
user's production `com.termux` installation or uninstall it to make a test pass.

The smoke checks real service/runtime pairing, WebView URL/settings and Android
cleartext policy. Execute and attach these additional scenarios on every device:

- Offline clean install; package second stage completes; local pairing/WebUI works.
- Ordinary project read/edit/Git; shell and external-directory approval/rejection;
  sensitive paths ask; existing malformed/custom configuration bytes remain intact.
- Main-frame navigation, redirects and resources: file/external access is blocked;
  external links open outside the authenticated WebView; local-to-external script
  redirects fail under CSP. Confirm UI assets, workers and uploads still work.
- Document picker uploads without broad storage; automatic once-per-install
  storage grant/deny/retry on API 29 and API 30+; conflicting storage entries remain.
- Concurrent export pickers completed out of order; local/off-origin redirects;
  HTTP errors, stalled/canceled transfers and provider partial-document cleanup.
- Stop/start during bootstrap/pair/readiness/backoff; unexpected server crash;
  at most one server, and intentional stop stays stopped.
- Kill Android app during bootstrap. A durable group journal must preserve the
  prefix while old scripts may be live. Force-stop OpenCodie in Android settings
  and reopen; retry only after the old process group is confirmed absent. Pending
  launchers require a unique acknowledgement, published only after the group
  record is durable, so an unacknowledged old launcher cannot execute scripts.
- Interrupt initial installation and every upgrade/rollback boundary; retry keeps
  projects, user packages, custom config and shared storage intact. Unknown prefixes
  are preserved and rejected. Release WebView debugging and raw upstream logs stay off.

## Production signer, provenance and recovery

1. Build a release only with the existing permanent signing credentials. Verify
   the actual APK signer using `apksigner verify --print-certs` and compare its
   SHA-256 fingerprint to `.github/signing/release-public.pem`. CI compares both
   the unlocked keystore and the finished APK to that certificate. An unsigned
   local release build is compile evidence only.
2. Use `aapt2 dump badging` to verify `com.termux`, ARM64, minSdk 29, targetSdk 28,
   upstream versionName and expected increasing versionCode. Independently compare
   the new versionCode to the last approved installed APK; do not assume a local
   default versionCode of 1 is updateable.
3. Verify APK/runtime SHA-256, ZIP CRCs, native Android ELF checks, exact dependency
   lockfiles and signed Termux package metadata in `runtime-provenance`.
   GitHub's separate trusted-only provenance job attests the signed APK; PR jobs
   receive no signing secrets or OIDC permission. Verify the resulting attestation
   with `gh attestation verify OpenCodie.apk --repo anthracite-labs/OpenCodie`.
4. Bun SHA-256 and npm SHA-512 pins come from vendor checksum/committed lockfile
   sources. Google publishes a legacy SHA-1 for NDK r28b; document this limitation
   and obtain maintainer/security review of stronger toolchain provenance before
   production approval. Never replace an expected digest with one computed only
   from unverified download bytes. Review Action SHA and all lockfile changes.
5. Rehearse recovery on the lab device: rebuild known-good code using the **same
   signer** and a **higher versionCode**, install it as an update and verify data.
   Android downgrade is not the recovery procedure. The fixed `com.termux` identity
   collides with other differently signed Termux APKs; backup/export planning is
   required before any migration.
6. Attach evidence and record named maintainer and security reviewer approval.
   Only then may a maintainer publish the draft stable release. Missing evidence
   or approvals keeps the stable release blocked.
