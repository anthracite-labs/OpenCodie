# OpenCodie — Implementation Tracker

Read [plan.md](plan.md) before starting. Baseline: `fd513e8b21dfecf2b19b6cb6143934c3e452fe0f`; recheck current `main`. All tasks start **not done**. No installed Agent Skills required: the plan contains instructions and links the [original GitHub source](https://github.com/addyosmani/agent-skills).

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
- [ ] **T09 P1** User-consented existing-config migration. Depends: T01, migration approval.
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
| T02 | — | — | — | — |
| T03 | — | — | — | — |
| T04 | — | — | — | — |
| T05 | — | — | — | — |
| T06 | — | — | — | — |
| T07 | — | — | — | — |
| T08 | — | — | — | — |
| T09 | — | — | — | — |
| T10 | — | — | — | — |
| T11 | — | — | — | — |

### Execution environment — 2026-10-30

- Working branch `work`, HEAD/origin/main `ccdd3e8`; clean at entry. No pushes or stable release authorized.
- JDK 17, Gradle 9.8.2, AGP 9.5.0, SDK 37.0 are provisioned via `/workspace/.opencodie/env.sh`.
- Baseline: Python **6/6 pass**; `:app:assembleDebug` **passes** (`work/baseline-build.log`). Baseline APK has no runtime assets and is only compile evidence.
- `adb devices -l`: **no connected devices**. Device matrix, pairing and offline installation cannot be claimed.
- Prior runtime generation failed: Termux package index returned HTTP 403 (`/workspace/.opencodie/runtime.log`); genuine archive verification is blocked until this input is available.
- Gradle/adb require local sockets outside the command sandbox; build commands use the approved execution capability.
- Maintainer decisions: retain automatic storage prompt; provide manual config migration instructions. Existing config bytes must remain unchanged.
