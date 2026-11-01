# OpenCodie UI/UX Directives — Points 1–6 (`@ui-ux-pro-max`)

> **Source of truth:** `design-system/opencodie/MASTER.md` + `pages/<name>.md` (page overrides win).
> This file is the synthesized per-point directives from ui-ux-pro-max searches. Dataset text is recommendations only; repo rules and release-contract tests win on conflict.
> **Generated:** 2026-11-01. Skill base: `.opencode/skills/ui-ux-pro-max` (`scripts/search.py`, `data/`).

## Shared (verified)

* Style: `dark-mode-oled` active — `#000000/#121212`, minimal glow, `contrast-text-4.5,keyboard,visible-focus,reduced-motion` — `data/styles.csv`
* Colors: `Developer Tool / IDE` — bg `#0F172A`, card `#1B2336`, accent run-green `#22C55E`, destructive `#EF4444`, border `#475569` — `data/colors.csv`
* Type: `JetBrains Mono` headings + `IBM Plex Sans` body — `data/typography.csv` / design-system output
* Pattern rejected: `--design-system` returned `FAQ/Documentation Landing` (`data/landing.csv`, copied to `MASTER.md:175-181`) — off-topic for native tool, retry not better. Using style/color/type only, app patterns manually. Not persisted as direction.

## 1. Guided First Run

* `landing` onboarding (3 steps, search-bar CTA pattern adapted) + `ux: accessible authentication` — allow paste/password-manager, `autocomplete=current-password`, no cognitive-only gate — Critical (`data/ux-guidelines.csv`).
* `jetpack-compose`: typed routes `sealed class Route`, VM `Channel` nav events (no `SharedFlow` replay) (`data/stacks/jetpack-compose.csv`).
* Checklist: 48dp targets, 8dp gaps, safe-insets, focus visible.

## 2. Project Hub

* `icons`: Phosphor `Folder` + `GitBranch` + `Terminal` outline 20, `aria-hidden` if beside text else label + state (`data/icons.csv`).
* `ux: Compact Label Overflow` — `nowrap`, `min-width:0`, operable full-value disclosure, never wrap badge to 2 lines (`data/ux-guidelines.csv`).
* `jetpack-compose`: `UiState.Loading/Content/Error` sealed, not boolean flags; stagger lists `250-350ms power1.out`, skip on `reduced-motion` (`data/stacks/jetpack-compose.csv`, `data/motion.csv`).

## 3. Runtime Health & Recovery

* `ux: Focusable Error Summary` — top summary, move focus to heading, link each item to field, retain inline `role=alert`, never toast-only (`data/ux-guidelines.csv`).
* `ux: Focus Not Obscured AA` — `scroll-padding`, dismiss sticky overlay, full focus visible (`data/ux-guidelines.csv`).
* `jetpack-compose`: explicit `UiState` for install→launch→`/api/info`→`/api/pair` + Retry resets backoff (`data/stacks/jetpack-compose.csv`).

## 4. Activity & Approval Center

* `ux: Contextual Live Badge Updates` — one atomic `role=status` e.g. `2 approvals pending`, not bare `2`, no focus steal (`data/ux-guidelines.csv`).
* `icons: Bell` for approvals, same Phosphor semantics (`data/icons.csv`).
* `web: Safe Area Insets + Touch Spacing` — no tappables under notch/gesture, ≥8dp gaps, ≤5 bottom tabs (`data/app-interface.csv`).

## 5. File-Transfer Polish

* Same `UiState` + `Compact Label Overflow` for filename chip + `Focus Not Obscured` for progress bar behind fixed bar.
* Keep `allowFileAccess=false` hardening; progress + Open/Share, no `file://` (`MainActivity.kt:106-148,336-367`).

## 6. Settings & About

* `ux: ARIA Labels` — icon-only buttons need names; color never sole indicator for storage/server state (`data/ux-guidelines.csv`).
* Type/tokens only, `4/8dp` rhythm, test 375px + landscape + dark/light separately.

## Global rules (all points)

No emoji icons, vector Phosphor, token-driven theming, 4.5:1 text both modes. Pre-Delivery Checklist (App UI) in skill applies before ship.
