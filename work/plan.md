# OpenCodie — Security & Reliability Implementation Plan

Status: **Code implemented and locally verified; Android device and production-release gates remain open.**
Target: `anthracite-labs/OpenCodie` on `main`; audit baseline `fd513e8b21dfecf2b19b6cb6143934c3e452fe0f`.  
Tracker: [todo.md](todo.md). Read the checked-out code again before changing it.

## Agent execution contract

This document is self-contained. **No Agent Skills installation or access is required.** The methods come from the original [addyosmani/agent-skills](https://github.com/addyosmani/agent-skills) repository (direct source links below).

1. Before each task, check `main`/working-tree state, read its named code files, and verify relevant APIs against the project's pinned versions.
2. Write a failing behavioral/reproduction test first (**RED**); make the smallest change to pass (**GREEN**); simplify with tests still passing (**REFACTOR**).
3. Run focused tests, then relevant full tests/build and Android verification. Source-string contract tests alone are insufficient.
4. Change one work package at a time. Preserve existing projects, Termux packages, custom configuration, storage links, signer and upgrade path. Do not introduce unrelated refactors.
5. Record outcomes, commands and evidence in [todo.md](todo.md). Mark work completed **only** after verification; record blockers honestly.
6. Do not commit secrets, bypass permission approval, disable integrity checks or silently migrate user configuration. Do not publish a stable release without explicit maintainer approval.

## Immutable constraints

- Preserve Android WebView → `OpenCodeService` → `OpenCodeHost` → local OpenCode → bundled Termux. Do not rewrite the architecture.
- Keep the server bound to `127.0.0.1:4096` with per-install password + pairing session.
- Maintain offline initial runtime extraction; no network dependency for first bootstrap.
- Preserve `com.termux` identity, target SDK 28, ARM64 build and signing continuity during this project. Modernization is a separate initiative.
- Never overwrite existing `~/.config/opencode/opencode.json` or user files without an explicit, reversible opt-in.
- Declining broad storage access must not block the WebUI or workspace.
- Startup/recovery must never leave multiple supervised OpenCode server processes.
- A failed or corrupt update must fail safely without destroying the last working installation or user data.
- Recovery from a bad release uses a higher `versionCode` with the same signing key; Android downgrades are not a reliable rollback strategy.

## Decisions for maintainer before user-visible changes

- **Config migration (T09):** Default recommendation is a previewed, opt-in migration with backup; never silently change existing configurations.
- **Storage UX (T08):** Confirm whether to replace automatic broad-access prompting with a user-initiated explanation/consent flow.
- **Device matrix (T11):** Confirm at least Android 10/API 29, an intermediate supported Android version and Android 16 on ARM64, including real-device coverage.
- **Stable release:** Require explicit maintainer/security approval. Main-branch pushes already trigger CI and may create prereleases.

If a decision is outstanding, implement unrelated, non-blocked tasks and mark the dependent task blocked.

## Findings mapped to work packages

| Audit ID | Severity | Verified concern / necessary validation | Task |
|---|---|---|---|
| S-01 | High | Initial OpenCode V2 policy asks for shell approval but not external-directory approval; test real tool behavior | T01, T09 |
| S-02 | High | Bun/NDK/npm binary downloads lack independent artifact-integrity checks | T03 |
| R-01 | High | Runtime/Android behavior lacks automated execution tests in indexed graph | T00, T05, T11 |
| R-02 | Medium | Possible stop/start race while prior supervisor is still alive; reproduce first | T04 |
| S-03 | Medium | WebView file access and global cleartext allowance need explicit trust boundaries | T02 |
| S-04 | Medium | Archive path checks exist; symlink-target validation and extraction budgets need tests | T06 |
| R-03 | Medium | Downloads lack read timeout, redirect policy, and request-scoped state | T07 |
| R-04 | Medium | Bootstrap errors exit outside the restart loop; second-stage has no deadline | T05 |
| S-05 | Medium | Optional all-files permission materially enlarges agent access to shared files | T08 |
| S-06 | Low | Upstream output may leak credentials into diagnostic logs; no leak established | T10 |
| R-05 | Low | Existing package ID/old target SDK complicate compatibility and migration | T11 |

These are code-derived findings/risks, not claims that exploitation has been demonstrated. Retain existing protections: random local password, loopback binding, non-exported service, checksum, staging/backup, and signing verification.

## Execution dependency order

`T00` first. Afterward `T01`, `T02`, `T03`, `T04` and `T10` can be developed separately. Then:
- `T04 → T05 → T06` (supervisor / installer / archive)
- `T02 → T07` (WebView / downloads)
- `T01 → T08, T09` (tool policy / storage / existing configs)
- `T01–T10 → T11` (release gate)

Coordinate tasks touching `MainActivity.kt` or `RuntimeInstaller.kt`; never concurrently land overlapping edits.

## Implementation tasks

### Phase A: establish tests and reduce high-impact exposure

#### T00 — Behavioral test baseline [P0]

Files: `app/build.gradle.kts`, `tests/`, new `app/src/test/java/com/termux/`, new `app/src/androidTest/java/com/termux/`, CI workflow.

- Keep existing Python contract tests. Add focused Kotlin/JVM tests for extracted policy/state logic and a minimal instrumented Android smoke test for real WebView/service behavior.
- Add injection seams for fake processes, time and filesystem operations **only where needed** for deterministic fault/race tests.
- Establish a known-good baseline APK + device smoke result; do not refactor runtime code as part of harness setup.

**Done when:** a genuine state-based Kotlin test and Android smoke test run in a documented CI/device lane; Python contracts and debug build are green; commands/results are recorded.

#### T01 — Harden OpenCode V2 agent permissions [P0; depends T00]

Files: `app/src/main/java/com/termux/RuntimeInstaller.kt`, config-generation tests.

- Verify actions, resource patterns, last-rule-wins ordering, and `external_directory` behavior against the **pinned** OpenCode V2; use [V2 permissions docs](https://opencode.ai/v2/docs/permissions).
- Retain `shell` approval; add `external_directory` approval for new installs and test `read` / `edit` restrictions for sensitive files without blocking ordinary project workflows.
- Preserve existing config files **byte-for-byte**; migration is a separate consent-based T09. Shell approval must not be misrepresented as a sandbox.

**Done when:** generated config is valid; project tasks function; external file access asks/denies correctly on-device; customized configs are untouched.

#### T02 — Restrict WebView and cleartext network scope [P0; depends T00]

Files: `app/src/main/java/com/termux/MainActivity.kt`, `app/src/main/AndroidManifest.xml`, optional `app/src/main/res/xml/network_security_config.xml`; instrumentation tests.

- Explicitly disable WebView file-URL access not required by OpenCode; determine if `content://` access is needed for the document picker before changing it.
- Narrow cleartext HTTP to the local endpoint where supported; verify actual Android Network Security Configuration behavior across target API levels.
- Test main-frame navigations, subresources, redirects, downloads and file uploads; `shouldOverrideUrlLoading` does **not** intercept every request.

**Done when:** pairing and WebUI work on supported devices, normal picker/upload works, and blocked file/external navigation cannot inherit the local authenticated session.

#### T03 — Independently verify build inputs [P0; depends T00]

Files: `runtime.lock`, `scripts/build-opencode.sh`, `scripts/build-runtime.sh`, `.github/workflows/build-and-release.yml`, optional source integrity manifest.

- Replace unverified remote execution/extraction (`curl | bash`, `curl | tar`) with downloaded artifacts checked against independently reviewed digests or equivalent verified package integrity.
- Pin/review GitHub Actions revisions and update procedures. Preserve Git commit pinning, least-privilege CI permissions, PR isolation from signing secrets and expected signer checks.
- Add controlled tampered-archive tests and dependency/SBOM evidence. A SHA calculated from newly downloaded bytes is not independent provenance.

**Done when:** altered artifacts fail **before** execution/extraction, fresh-runner build works and release signer verification remains intact.

**Checkpoint A:** Tests and debug build green; local session/picker usable; permission and integrity tests pass; security review recorded.

### Phase B: process and filesystem resilience

#### T04 — Serialize host lifecycle [P1; depends T00]

Files: `OpenCodeHost.kt`, `OpenCodeService.kt`, fake-process race tests.

- Reproduce rapid stop/start during bootstrap, pairing, readiness and backoff. Ensure a pending restart is not lost because a previous supervisor thread is still alive.
- Make state ownership and shutdown acknowledgement deterministic; avoid waiting on UI thread. Preserve bounded backoff and child termination behavior.

**Done when:** intentional stop does not restart, unexpected exit restarts, no permanently inert supervisor, and at most one server exists during stress tests.

#### T05 — Fault-test installation, rollback and bootstrap [P1; depends T00, T04]

Files: `RuntimeInstaller.kt`, `OpenCodeHost.kt`, installer/fault-injection tests.

- Build a state-transition test matrix around readiness markers, staging, backups, activation and cleanup. Inject termination/errors before and after each destructive rename/delete.
- Give bootstrap second-stage execution a bounded deadline; offer explicit recoverable-error retry, but fail closed on checksum, unsafe archive or unverified prefix.
- Never recursively remove an existing prefix without proof it belongs to an interrupted *app-owned* installation; keep user packages, project files and configs.

**Done when:** clean/install/failed-upgrade/restart scenarios recover or preserve the prior runtime; a stalled bootstrap exits safely; on-device upgrade keeps user data.

#### T06 — Harden archive extraction [P1; depends T05]

Files: `RuntimeInstaller.kt`, malicious/valid ZIP fixtures, optional build artifact checks.

- Validate normalized ZIP names, duplicate paths/type conflicts, symlink location and parents, and decompressed size/count limits before activating staging.
- Define explicit allowed Termux symlink target classes, including required absolute in-prefix links; verify that link-parent transitions cannot escape staging.
- Keep checksum checks, refuse unsafe input before replacing active runtime and test the real pinned bootstrap fixture.

**Done when:** traversal, symlink-parent, collision and oversized fixtures fail without out-of-tree writes, while the genuine Termux runtime installs.

**Checkpoint B:** Simulated interruption + device recovery pass; at most one server; no user-data loss; adversarial ZIP suite green.

### Phase C: user data and operational safety

#### T07 — Make downloads bounded and request-scoped [P1; depends T02]

Files: `MainActivity.kt`, optional small transfer helper, local HTTP test server and Android picker tests.

- Replace global `pendingDownload` with request-scoped state so concurrent pickers cannot mismatch a URL and destination.
- Enforce local URL/redirect policy without forwarding authenticated cookies off origin; set connect/read timeouts, check HTTP status and cleanly disconnect.
- Support cancellation and partial-write cleanup; provide accurate error/progress messages.

**Done when:** concurrent, stalled, redirected, errored and canceled transfers behave safely, with no cross-origin cookie leakage; normal exports retain exact bytes.

#### T08 — Least-privilege shared storage UX [P1; depends T01 and maintainer UX decision]

Files: `MainActivity.kt`, `AndroidManifest.xml`, strings and API 29/30+ tests.

- Retain document-picker and no-permission coding paths; if approved, move all-files request behind an explicit user action with an accurate access explanation.
- Preserve existing `~/storage` files and symlinks; reject conflicting entries without deleting them.

**Done when:** grant/deny/retry flows work across API versions, routine coding does not require broad access, and user storage remains unchanged on failed link setup.

#### T09 — Opt-in existing-config migration [P1; depends T01 and maintainer migration decision]

Files: `RuntimeInstaller.kt` or a narrowly scoped migrator, optional UI/help, config fixtures.

- Never silently modify pre-existing `opencode.json`. Offer preview + consent, backup and reversal; handle malformed/customized files without loss.
- If semantic merge cannot preserve unknown fields and ordering, give manual instructions instead of overwriting.

**Done when:** consent/decline/invalid/interrupted scenarios preserve the original where appropriate; migrated config is accepted by pinned OpenCode V2.

#### T10 — Redact diagnostics [P2; depends T00]

Files: `OpenCodeHost.kt`, redaction tests.

- Avoid logging credentials, cookies, pairing codes, URLs carrying secrets and unbounded upstream output. Keep concise, actionable failure categories.
- Retain bounded diagnostics; verify release WebView debugging is disabled.

**Done when:** synthetic secret-bearing logs never reach Logcat/UI errors, while failures remain diagnosable.

**Checkpoint C:** Downloads, storage and config migration tested; custom settings and user files preserved; logs redacted.

### Phase D: device validation and release gate

#### T11 — Compatibility, provenance and release verification [P1; depends T01–T10]

Files: `README.md`, release verification docs/checklist, CI workflow as necessary.

- Run device matrix for offline first install, local pairing, permission approval, Git project, declined/granted storage, process crash/restart, interrupted install, upgrade preservation and downloads.
- Verify signing certificate, APK package/runtime checksums, independent build provenance and non-regressing `versionCode`. Document `com.termux` collision and backup/migration constraints.
- Rehearse recovery from a bad APK by shipping known-good code with a **higher** `versionCode` and the **same** signer; avoid destructive downgrade assumptions.
- Require named maintainer/security sign-off before a **stable** release. Main may publish automatic prereleases under existing workflow.

**Done when:** all gates below pass with attached build/test/device evidence, or release is explicitly blocked.

## Verification commands

Run repository checks after relevant changes; additional test commands become available after T00:

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
for f in scripts/*.sh; do bash -n "$f"; done
bash -n runtime.lock
python3 -m py_compile scripts/*.py
./gradlew --no-daemon :app:assembleDebug
./gradlew :app:testDebugUnitTest                 # once configured
./gradlew :app:connectedDebugAndroidTest       # connected device/emulator required
./gradlew --no-daemon :app:assembleRelease      # signing credentials required
```

Do not claim a task passes if the corresponding test/build/device command was not run. Record environment, observed result and relevant artifact SHA in the tracker.

## Definition of done / ship block

- [ ] All high-severity issues fixed or given explicit accepted mitigation/owner.
- [ ] New security and lifecycle behaviors have executable regression tests, not only textual assertions.
- [ ] Existing tests, debug build, signed release checks and required real-device matrix pass.
- [ ] Malformed archive/source artifacts fail closed; secrets do not leak in logs.
- [ ] Upgrade/recovery preserves projects, custom config, Termux packages and shared storage.
- [ ] Maintainer approves config/storage UX and signer continuity; security reviewer signs off.
- [ ] Evidence + remaining supported-device limitations documented. No unapproved stable publication.

## Direct original sources (accessible without installed skills)

**Original Agent Skills GitHub source:** https://github.com/addyosmani/agent-skills

- [Test-driven development](https://github.com/addyosmani/agent-skills/blob/main/skills/test-driven-development/SKILL.md) — failing test, minimal fix, verification
- [Security and hardening](https://github.com/addyosmani/agent-skills/blob/main/skills/security-and-hardening/SKILL.md) — threat boundaries and security controls
- [Planning and task breakdown](https://github.com/addyosmani/agent-skills/blob/main/skills/planning-and-task-breakdown/SKILL.md) — small verifiable slices and checkpoints
- [Incremental implementation](https://github.com/addyosmani/agent-skills/blob/main/skills/incremental-implementation/SKILL.md) — one coherent change at a time
- [CI/CD and automation](https://github.com/addyosmani/agent-skills/blob/main/skills/ci-cd-and-automation/SKILL.md) — T03 release pipeline
- [Source-driven development](https://github.com/addyosmani/agent-skills/blob/main/skills/source-driven-development/SKILL.md) — confirm version-specific APIs
- [Shipping and launch](https://github.com/addyosmani/agent-skills/blob/main/skills/shipping-and-launch/SKILL.md) — T11 gates

These links are optional reading, not required tools. The execution steps above are sufficient on their own.

**OpenCodie source:** https://github.com/anthracite-labs/OpenCodie/tree/fd513e8b21dfecf2b19b6cb6143934c3e452fe0f

**Platform references:** [OpenCode V2 permissions](https://opencode.ai/v2/docs/permissions) · [Android WebView file security](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion) · [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config) · [GitHub Actions secure use](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions)
