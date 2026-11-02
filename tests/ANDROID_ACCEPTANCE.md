# Android acceptance: permission decisions and live startup

PR #46 adds unit tests, a connected Android instrumentation suite, and a typed startup timeline. CI compiles the Android instrumentation APK; connected tests are run manually on physical Android devices. **A real ARM64 device remains necessary for release acceptance and fidelity validation.**

## Environment

- Use a **dedicated test/debug installation**; instrumentation resets onboarding preferences and is not intended for a personal production installation.
- Test on actual Android 12 (API 31/32), Android 13 (API 33), Android 14 (API 34), Android 15 (API 35), and Android 16 (API 36) where available.
- Leave `targetSdk=28` unchanged: the app executes its bundled Termux binaries from private app storage.
- For Android 13+ first-notification-popup cases, install the APK fresh (no previously created OpenCode notification channel). Run each first-prompt test *separately on a clean install*; do not assume the system will present the same first-run dialog twice.
- Set the test device's system language to English for UI Automator's permission-button labels.

Build and run the Android suite from a checkout with the Android SDK and test device available:

```sh
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
# Run one specific first-prompt test after a fresh install:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.termux.OnboardingAndroidTest#notificationPendingIsNeutralBeforeUserAnswers
```

The main build workflow compiles the instrumentation suite. Run `connectedDebugAndroidTest` on a dedicated physical ARM64 test device and retain the test reports as acceptance evidence.

For each pull request, the build workflow now uploads a **debug-signed** APK artifact named `OpenCodie-PR<PR_NUMBER>-debug` (7-day retention) after APK validation. Download the ZIP from the successful GitHub Actions run. **Debug signing is not production signing**. If your device already has production OpenCodie installed with package `com.termux`, Android may reject installing the test build over it; **do not uninstall or erase the production app without backing up your workspace**. Use a dedicated test device or prepared backup.

For targetSdk 28, the first notification request is Android-controlled. The app never fabricates a decision from a delay; it observes the actual return/focus transition, with an explicit **Cancel request** escape when a device does not show the first channel-created dialog.

## Manual regression matrix

| ID | Scenario | Expected behavior |
|---|---|---|
| P1 | Fresh install → Welcome → Get started | No premature notification dialog; both status rows say Not configured; both have Grant access |
| P2 | Tap Grant notifications, do not answer | Notification status Requesting… and its action becomes **Cancel request**; the other permission action is disabled; **never Not enabled before response** |
| P3 | Choose Allow | Notifications Allowed, action Change access; no repeat prompt |
| P4 | Choose Don't allow | Notifications Not enabled, action Grant access; later tap opens app-specific Settings |
| P5 | Dismiss notification system dialog (back/swipe) | Not enabled (neutral wording), not a claim of explicit denial; retry remains available |
| P6 | Return from notification Settings after grant/revoke | UI matches actual Android state |
| P7 | Tap file Grant access → back out of all-files page | Not enabled, can tap again; no pretend Allowed |
| P8 | Grant all-files access in Settings → return | Allowed, action Change access; bootstrap links only when runtime ready |
| P9 | Rotate during initial onboarding | Welcome/permissions state and controls preserved |
| P10 | Rotate while authorization pending, then close OS dialog | Recreated Activity retains pending/focus-loss evidence, updates only after actual return, and never starts a duplicate request; **Cancel request** remains an escape if no dialog appears |
| P11 | Continue with both denied | Workspace installation/connection proceeds; private workspace still works |
| P12 | Revoke previously granted authorization while app backgrounded and return | Existing one-time permission review appears, not repeated nagging |
| L1 | First install with runtime extraction | Typed RUNTIME → PACKAGES → SERVER → WEBVIEW states progress only on actual signals |
| L2 | Second launch with installed runtime | Already installed stages are not animated as fresh extraction; readiness checked |
| L3 | Slow package configuration | Displays Configuring Termux packages… without made-up percent or ETA |
| L4 | Valid server but WebUI not authenticated yet | Server step may complete; workspace connection remains active until authenticated page |
| L5 | Rotation while installing | Same completed milestones, no restart of installation or false rewind |
| L6 | Force bounded second-stage timeout / server readiness failure in a disposable test environment | Failed screen names the last stage, offers Try again and diagnostics, stops showing infinite progress |
| L7 | Restart server after ready | Server and WebUI stages become active again, preserving verified runtime milestones and the most recent safe failure reason in diagnostics |
| L8 | Force a recoverable server failure then observe automatic retry | Shows **Retrying the local OpenCode server…** with last cause and diagnostics, never flashes a terminal error screen; clears retry status only after verified readiness |
| A1 | Font scale 1.3/2.0 and TalkBack | Control text wraps; status announced politely; stage updates do not repeatedly steal focus |
| A2 | Long setup logs, injected credentials in diagnostics | UI shows only controlled stage names; user secrets never appear in status or copied diagnostics |
| A3 | 320dp split-screen, landscape and 2.0x font | Permission controls stay inside cards; main column remains readable and scrollable |
| A4 | Renderer termination (memory reclaim and repeated crash) | Activity stays alive; valid session recovers with bounded retries; repeated crash offers Try again |
| A5 | HTTP 404/429/503, expired cookies and temporary probe timeout | Missing project routes stay in the Web UI; recoverable failures retry; terminal failure remains actionable |
| A6 | Download stalls, cancellation and HTTP redirect | Stream has timeout; no credential-bearing redirects; cancellation and partial files are explained |
| A7 | Rotate and simulate process death in a deep project route | Safe route/history restored after session validation; pairing code never replayed |
| A8 | Revoked permission review then Back | Existing user returns to workspace, not initial Welcome |
| A9 | Light/dark screenshot review, TalkBack and large font | Semantics, contrast, spacing and focal controls verified against approved baseline |

## Evidence boundaries

- **No fake percent**: `StartupSnapshot.percent` remains null because no stage currently reports measured work units.
- `RuntimeInstaller` events are emitted after/before actual checks and steps, not inferred from free-form log messages.
- `OpenCodeHost` publishes local server readiness only after its HTTP readiness + pairing check; WebUI is completed only after session cookie verification.
- OS-controlled notification permission prompts on `targetSdk=28` vary by Android version, grant history, and channel creation. Tests must describe the actual authorization UI seen and should not simulate an unsupported force re-prompt.
- Installer timeout behavior is already bounded by `SecondStageRunner`. To validate timeouts, use a disposable test fixture rather than altering users' Termux files.
