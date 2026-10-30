# OpenCodie — Implementation Tracker

Read [plan.md](plan.md) before starting. Baseline: `fd513e8b21dfecf2b19b6cb6143934c3e452fe0f`; recheck current `main`. Code implementation and local checks are complete; boxes remain open where device/release acceptance evidence is missing. No installed Agent Skills required: the plan contains instructions and links the [original GitHub source](https://github.com/addyosmani/agent-skills).

**Task protocol:** RED failing behavior test → minimal fix → GREEN → refactor → focused + regression tests → build/device evidence → review. Only check a box after verification; note blockers and actual commands below.

## A — Tests and highest-priority security
- [ ] **T00 P0** Test harness + baseline. Depends: none.
- [ ] **T01 P0** First-install V2 permissions; preserve customized config. Depends: T00.
- [ ] **T02 P0** WebView and loopback cleartext hardening. Depends: T00.
- [ ] **T03 P0** Independent dependency integrity + CI pinning. Depends: T00.
- [ ] **Gate A:** tests + debug APK + policy/WebView/integrity verification; security review.

## B — Reliable process and filesystem
- [ ] **T04 P1** Supervisor start/stop race tests and fix. Depends: T00.
- [ ] **T05 P1** Fault-injected installer/upgrade/rollback and bootstrap retries. Depends: T00, T04.
- [ ] **T06 P1** ZIP/symlink/input budget validation. Depends: T05.
- [ ] **Gate B:** no duplicate server; interrupted install/upgrade preserves data; malicious archive fails closed.

## C — User data protection
- [ ] **T07 P1** Safe concurrent downloads, redirects, timeouts, cookies. Depends: T02.
- [ ] **T08 P1** Approved opt-in shared-storage experience. Depends: T01, UX approval.
- [x] **T09 P1** Approved manual existing-config instructions; no automatic migration. Depends: T01, migration decision.
- [ ] **T10 P2** Redact diagnostics. Depends: T00.
- [ ] **Gate C:** device download/storage/config tests and secret-redaction tests pass.

## D — Ship-readiness
- [ ] **T11 P1** Android matrix, signer/provenance checks, recovery drill. Depends: T01–T10.
- [ ] **Gate D:** no unmitigated high issues; tests and supported-device evidence attached; maintainer + security approval for stable release.

## Required maintainer decisions
- [x] Existing config: maintainer chose manual migration instructions; preserve every existing config.
- [x] Storage: maintainer chose to retain the current automatic first-launch prompt.
- [ ] Android matrix: confirm API 29, middle version, Android 16/ARM64 devices.
- [ ] Stable release: approve signing/recovery evidence and release authorization.

## Evidence log (fill per task)

| Task | Commit/PR | Failing test (RED) | Tests/build/device result | Review/decision |
|---|---|---|---|---|
| T00 | local harness | Legacy Android test API failed to compile; selected supported AndroidX runner | JVM state test passes; debug + instrumentation APK compile (`work/t00-build.log`) | PARTIAL: device lane defined; no device or genuine runtime assets |
| T01 | policy + atomic creation | Old generated config: external_directory was allow, expected ask (`work/t01-red.log`) | Pinned V2 schema/matcher PASS; config byte/symlink preservation tests + debug build PASS | Device approval/project workflow still pending |
| T02 | exact origin + NSC | LocalOrigin trust-boundary regression fails before helper exists (`work/t02-red.log`) | Exact-origin JVM cases, debug APK and Android test APK PASS (`work/t02-build.log`) | Device pairing/upload/redirect/subresource checks pending; content access retained for picker |
| T03 | verified inputs + signed APT patch | Downloader missing; altered archive/index tests fail before verification helper | Python 10/10 PASS; shell syntax PASS; bootstrap patch applies to pinned source; genuine Bun archive verified | Fresh runtime build blocked by Termux repository access; NDK vendor publishes legacy SHA-1, stronger provenance review outstanding |
| T04 | serial owner | Deterministic old-owner/stop/start test fails: requested restart lost (`work/t04-red.log`) | Shutdown acknowledgment/idempotence JVM tests + integrated debug build PASS (`work/t04-build.log`) | Child cleanup fails closed; real device process crash/stress remains pending |
| T05 | owned initial install + transactional upgrade | Missing behavioral recovery helpers fail (`work/t05-red.log`); prior rollback restored binary before launcher, losing recovery journal | 8 upgrade fault points, rollback retry, unknown-prefix preservation, no-follow deletion and real stalled process deadline PASS; debug build PASS (`work/t05-build.log`) | Tap-to-retry for recoverable bootstrap I/O only; on-device install/upgrade still pending |
| T06 | bounded no-follow staging extraction | Malicious ZIP helper regressions fail before extractor exists (`work/t06-red.log`) | Traversal, duplicate/type collision, link cycle/parent/target, actual size/count fixtures PASS; debug build PASS (`work/t06-build.log`) | Genuine fixture gate added to CI; local genuine archive absent, so its test is explicitly skipped |
| T07 | request-scoped picker + bounded HTTP | Request registry/transfer behavioral tests fail before implementation (`work/t07-red.log`) | Real HTTP byte export/local redirect/off-origin rejection/HTTP500 + cancellation, picker identity tests and debug build PASS (`work/t07-build.log`) | Android document-provider cancel/cleanup and concurrent picker UI still require device lane |
| T08 | retain approved prompt; storage link preflight | Missing StorageLinks behavior helper fails (`work/t08-red.log`) | Conflict preflight, injected partial-create rollback/idempotence, Python contracts + debug build PASS (`work/t08-build.log`) | User explicitly retained automatic prompt; API29/30+ grant/deny/retry checks require device |
| T09 | manual migration docs | Existing malformed/custom configs covered by T01 byte-preservation tests | Manual JSON recommendation exactly matches schema-tested shipped V2 policy; backup/restore + override caveats documented | Maintainer chose manual instructions; no automatic merge/UI or config changes |
| T10 | fixed diagnostic categories | Secret-bearing log tests fail before Diagnostics exists (`work/t10-red.log`) | Synthetic password/cookie/pair URL/API key and huge-output tests PASS; all 23 JVM tests including genuine fixture PASS; debug + Android test APKs PASS (`work/t10-genuine-build.log`) | Release WebView debugging now explicitly follows BuildConfig.DEBUG; runtime device Logcat check pending |
| T11 | release checklist + CI gates | Prior stable tag path lacked approval; changed to draft-only stable publication | Unsigned release compiles; package/min/target/version metadata verified; unsigned artifact correctly fails signature verification; trusted-only attestation workflow YAML validated | BLOCKED: physical device matrix, permanent signer, increasing production versionCode/recovery drill, live CI attestation, named maintainer/security approval |

### Execution environment — 2026-10-30

- Working branch `work`, HEAD/origin/main `ccdd3e8`; clean at entry. No pushes or stable release authorized.
- JDK 17, Gradle 9.8.2, AGP 9.5.0, SDK 37.0 are provisioned via `/workspace/.opencodie/env.sh`.
- Baseline: Python **6/6 pass**; `:app:assembleDebug` **passes** (`work/baseline-build.log`). Baseline APK has no runtime assets and is only compile evidence.
- `adb devices -l`: **no connected devices**. Device matrix, pairing and offline installation cannot be claimed.
- Prior runtime generation failed: Termux package index returned HTTP 403 (`/workspace/.opencodie/runtime.log`); genuine archive verification is blocked until this input is available.
- Gradle/adb require local sockets outside the command sandbox; build commands use the approved execution capability.
- Maintainer decisions: retain automatic storage prompt; provide manual config migration instructions. Existing config bytes must remain unchanged.

### T03 provenance sources

- Bun 1.4.4 pins copied from the vendor release `SHASUMS256.txt`, fetched independently of binary bytes on 2026-10-30. Verified genuine Linux x64 archive SHA-256: `bf8ccbfb2b0b80159e0f9a6d15c55317add8077534dbc6130a3edb52fccb395c`.
- npm SHA-512 integrity comes from `bun.lock` at the pinned OpenCode commit, not live registry metadata.
- NDK r28b digest is Google's published SHA-1 `f574d3165405bd59ffc5edaadac02689075a729f` in `repository2-3.xml`. This detects altered downloads but is a legacy algorithm; do not represent it as modern SHA-256 provenance. Stronger independent attestation remains a release gate.
- Action v4 refs resolved through GitHub's public git refs API; immutable commits pinned. Update digests only after independently reviewing vendor metadata/attestations and rerunning tamper tests.
- Bootstrap now requires `gpgv` (CI installs gnupg), authenticates Packages with pinned Termux source keys, then checks each .deb SHA-256 before ar/tar. Signature execution and the real bootstrap fixture are not verified locally (no runtime archive; repository previously denied access).
- CI saves exact Bun/Cargo lockfiles, signed Termux metadata/package inventory and runtime SHA in a separate provenance artifact, including cache hits.

## Final local verification — 2026-10-30

Implementation is committed on `work`; nothing was pushed or published. All executable local checks below passed. Original task/gate boxes intentionally remain open where the plan requires Android or production evidence.

- **29 JVM tests, 12 suites, 0 failures/errors/skips**, with `OPENCODIE_RUNTIME_ARCHIVE` set to the genuine bundled archive. Includes config byte preservation/atomic publication, the reproduced supervisor race, refusal after uncertain shutdown, persistent process-group authorization/recovery, actual child-process timeout cleanup, upgrade and rollback faults, ZIP attacks/genuine fixture, HTTP transfer/cookie isolation, storage conflicts and diagnostics.
- **10/10 Python contracts/integrity tests**, shell syntax, `runtime.lock` syntax, Python compile and final source whitespace checks pass (unified patch context syntax excluded).
- Pinned OpenCode V2 config schema and real matcher: PASS (`bun scripts/test-permissions.ts`). Actual server CSP middleware: PASS (`bun scripts/test-web-policy.ts`).
- Isolated Chromium browser regression: baseline redirected script executes; actual CSP blocks its off-origin fetch/execution while local scripts work: PASS (`work/csp-browser.log`). Uses upstream's existing pinned Playwright dev dependency.
- Fresh local OpenCode Android compile: PASS (`work/verified-opencode-build.log`), including independent Bun Android SHA-256 verification, native Rust build and Android ELF checks. Genuine Termux runtime generation and packaging: PASS (`work/final-runtime-build.log`); package signature/index verification retained under `dist/provenance` and reverified in `work/termux-provenance-verification.log`.
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug`: PASS (`work/final-verification.log`). Lint has 0 errors and 13 warnings (legacy storage/receiver/ABI/icon/path and UI strings); no lint rules were weakened.
- Local release artifact is **unsigned**. `apksigner verify` correctly rejects it (`work/release-signature-check.log`), so it is compile evidence, not a production APK. Local versionCode is 1; no update/recovery claim is made.
- Public permanent certificate SHA-256: `F7FB520B8FC0A76D639D4DC91CFD35E659D9396C032AAAB8601DCE877AA3BE7E`.
- Runtime SHA-256: `25c3045b9cd54bb8ffff9968da4cffe571e37bd6b3f9ff16e1b1747d4603716e`. Runtime identity additionally hashes launcher/server bytes, so patches sharing an upstream version still update APK-owned files.

### Review corrections and closure

An independent code review identified direct-shell-only bootstrap termination, missing app-process-death recovery, non-atomic initial-config publication, redirected WebView resource loading, and unapproved stable tag publication. Each was addressed:

- `00ec809`: durable pending/active bootstrap journal. A unique launcher acknowledgement is published only after the active group record is synced. On a new app instance, live or reused IDs are only probed, never killed; prefix mutation is refused until the old group is confirmed absent. Pending launchers without acknowledgement cannot execute package scripts. Timeouts stop the whole group and uncertain cleanup permanently blocks that in-memory supervisor.
- `2ed637f`: fully written/synced temporary config published by atomic no-replace hard link. Existing/dangling/concurrent user configs are preserved.
- `318a6f3`: actual server-side CSP plus pinned middleware and browser redirect tests. Android WebView compatibility remains a device gate.
- `d22cb7c`: complete signed-runtime packaging (including correction of an existing pipefail/grep false missing-file error) and executable-based runtime identity.
- Stable tags now create drafts. A separate trusted-only job attests the signed APK; the actual APK certificate is checked against the permanent certificate. PR jobs receive neither production signing secrets nor OIDC permission. These GitHub jobs are configured, not remotely executed in this session.

The reviewer found no remaining critical journal/authorization ordering issue in the targeted re-review. This is an agent review, not the named maintainer/security sign-off required to publish stable.

### Remaining release blockers

1. No connected ARM64 device: instrumented smoke, API29/intermediate/API36 real-device matrix, actual WebView/picker/storage behavior, app force-stop/process-death and upgrade/recovery drill remain unrun.
2. No permanent signing credentials: signed release certificate comparison, production update with higher versionCode and same signer remain unrun.
3. Fresh GitHub runner/attestation jobs are not executed; NDK r28b uses Google's independently published legacy SHA-1, which needs explicit provenance/security review.
4. Maintainer confirmation of the device matrix and named maintainer/security approval remain outstanding. Stable release stays blocked.

The earlier Termux repository access blocker is resolved: curl can access signed metadata; the repository rejected urllib with error 1010, so the verifier uses the generator's supported curl client without weakening signature/digest checks. GnuPG verification uses only the pinned public keyrings (gpgv in CI; gpg with an isolated no-default-keyring home locally).

## PR #12 follow-up security review — 2026-10-30

- Reviewed code at `f3e9b959c5508ba4f8499bf29f34b73063350fbc`, then fixed on the `work` branch via `ac562695289244e0205193636d147d865a1f16ef`, `b9a163b7080d029fd7ec0dabff587bcdec09c91f`, and `29fb60e1e2175b838f70a48a6bcddfbe9ad213b9`.
- **RED (locally reproduced):** compiled original `ChildProcess.kt` and a fake interrupted process using `kotlinc`; an interrupt thrown by `waitFor()` exited cleanup with `terminated=false, forced=false, restored=false`. **GREEN:** the same harness compiled and produced `terminated=true, forced=true, restored=true`. The committed `ChildProcessTest` now covers this condition in the JVM suite.
- Added an archive regression for a symlink parent appearing during entry selection; the extractor now resolves containment after selection and opens selected files with `CREATE_NEW` and `NOFOLLOW_LINKS`. Added a missing bootstrap group-record test and guarded startup, plus an exception-reporting/fail-closed supervisor regression.
- SonarCloud identified `kotlin:S1143` at `OpenCodeHost.kt:100` (a `return` from `finally`); moved the return outside `finally`. The SonarCloud analysis for `29fb60e` reports **quality gate PASS, zero new issues** (GitHub PR #12 check).
- GitHub Actions PR run [41010591213](https://github.com/anthracite-labs/OpenCodie/actions/runs/41010591213) on `29fb60e`: runtime preparation **PASS**, JVM behavioral tests and instrumentation compilation **PASS**, APK assembly and package/runtime verification **PASS**. These are CI/build checks, **not** a real-device run.
- CodeQL for the same head reports **neutral / configuration not found**, not a passing security reanalysis; the original ZIP-path alert must be properly rescanned and triaged before security acceptance. A deterministic malicious-entry regression passes through the configured JVM test lane, but that does not substitute for CodeQL triage.
- ARM64 device smoke, storage/WebView interaction matrix, upgrade recovery on physical Android, permanent signer/attestation and stable-release sign-off remain **NOT RUN / BLOCKED**. All corresponding task/gate checkboxes remain open. No merge or release has been authorized by these fixes.
