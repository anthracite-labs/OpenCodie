# Third-party notices

OpenCodie combines its own Android host/build code with independently licensed
upstream software. The root [MIT LICENSE](LICENSE) licenses only material for
which OpenCodie contributors hold the rights to grant that license. It **does
not relicense** upstream source, bundled executables, assets, agent skills or
their transitive dependencies.

The entries below are the identifiable direct inputs used by the build at the
versions/revisions in [`runtime.lock`](runtime.lock) and
[`skills-lock.json`](skills-lock.json). This is **not a complete SBOM** or a
substitute for the component-specific license files included in the Termux
bootstrap or upstream source distributions.

## Software included in the Android APK

| Component | Use in OpenCodie | License / upstream terms |
| --- | --- | --- |
| [OpenCode v2](https://github.com/anomalyco/opencode), v2.2.2 (commit `098aa7fd6e02b03d6fc410f0b052038c7d4c5e81`) | CLI/server and embedded WebUI; locally patched by `patches/opencode-*.patch` and `patches/watcher-android.patch` | [MIT](https://github.com/anomalyco/opencode/blob/098aa7fd6e02b03d6fc410f0b052038c7d4c5e81/LICENSE) |
| [Bun](https://github.com/oven-sh/bun), v1.4.4 | Executable/runtime used to compile and run OpenCode | [MIT **plus separately licensed linked components**](https://github.com/oven-sh/bun/blob/bun-v1.4.4/LICENSE.md). Bun documents statically linked JavaScriptCore/WebKit code under LGPL-2 and other libraries under their own licenses. |
| [Termux packages](https://github.com/termux/termux-packages) (bootstrap generator commit `4fd34c88f930defaed700fc5123536f5d9dce266`) | Bundled Termux userspace: APT/dpkg, Bash, Git, ripgrep, libc++, dependencies and second-stage scripts | **Per-package licenses**. Representative licenses at the referenced revision: `apt` GPL-2.0, `bash` GPL-3.0, `git` GPL-2.0, `ripgrep` MIT, `libc++` NCSA. See each package's `build.sh` and the installed `$PREFIX/share/doc/<package>/copyright*` files. |
| [OpenTUI](https://github.com/anomalyco/opentui), `@opentui/core` 0.7.0 | Compiled OpenCode native/UI dependencies (the TUI is not supported on Android here) | [MIT](https://github.com/anomalyco/opentui/blob/main/LICENSE) |
| [bun-pty](https://github.com/sursaone/bun-pty), v0.4.11 | Rust PTY native rebuilt for Android with `patches/bun-pty-portable09.patch` | [MIT](https://github.com/sursaone/bun-pty/blob/v0.4.11/LICENSE) |
| [Parcel watcher](https://github.com/parcel-bundler/watcher), `@parcel/watcher` 2.5.1 | Android ARM64 native file-watching support | [MIT](https://github.com/parcel-bundler/watcher/blob/master/LICENSE) |

The Termux generator commit pins the **bootstrap scripts**, but the scripts
download packages from the live Termux APT repository; package versions and the
full transitive set can therefore differ between builds. The actual installed
versions are recorded in `$PREFIX/var/lib/dpkg/status`. Termux supplies
package-specific license texts under `$PREFIX/share/doc/` (and common license
texts through `termux-licenses`); do not remove these from release archives.

## Source material in this repository (not bundled in the APK)

| Component | Location / attribution | License |
| --- | --- | --- |
| [Astreele/opencode-android](https://github.com/Astreele/opencode-android) | Adapted Android build scripts/patch approach identified in the README | [MIT](https://github.com/Astreele/opencode-android/blob/main/LICENSE) |
| [addyosmani/agent-skills](https://github.com/addyosmani/agent-skills) | Imported skills in `.agents/skills/`, as recorded in `skills-lock.json` | [MIT](https://github.com/addyosmani/agent-skills/blob/main/LICENSE) |
| [oil-oil/beautify-github-readme](https://github.com/oil-oil/beautify-github-readme) | `.agents/skills/beautify-github-readme/` | [MIT](https://github.com/oil-oil/beautify-github-readme/blob/main/LICENSE) |
| [s1dashu/ip-as-logo-skill](https://github.com/s1dashu/ip-as-logo-skill) | `.agents/skills/ip-as-logo/`; its own `LICENSE` is also retained there | [MIT](https://github.com/s1dashu/ip-as-logo-skill/blob/main/LICENSE) |

Build/test-time-only tools, not intentionally bundled in the release APK,
include Gradle/Android Gradle Plugin (Apache-2.0) and JUnit 4.13.2 (EPL-1.0).
Their own distributions retain their license terms.

## MIT copyright notices

The following copyright lines are retained from the upstream MIT license files
for the components identified above. The MIT permission and warranty terms
reproduced after this list apply to each such MIT-licensed component; copyright
remains with its respective author(s).

- OpenCode: Copyright (c) 2025 opencode
- OpenTUI: Copyright (c) 2025 opentui
- bun-pty: Copyright (c) 2025 Dilip Thapa
- Parcel watcher: Copyright (c) 2017-present Devon Govett
- Astreele/opencode-android: Copyright (c) 2026 astreele
- addyosmani/agent-skills: Copyright (c) 2025 Addy Osmani
- oil-oil/beautify-github-readme: Copyright (c) 2026 oil-oil
- s1dashu/ip-as-logo-skill: Copyright (c) 2026 s1dashu

Bun and the Termux-packaged ripgrep also identify MIT terms; consult their
linked upstream notices and the bundled Termux package copyright files for the
applicable attribution and any additional conditions.

**MIT License (permission and warranty text)**

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Source and redistribution obligations

- **Termux GPL software:** This APK redistributes GPL-licensed executables,
  including Bash, Git and APT. Preserve their complete installed notices and
  satisfy the applicable license's corresponding-source / source-offer
  requirements when distributing binary releases. The
  [Termux package recipes](https://github.com/termux/termux-packages) identify
  upstream source locations and patch sets. Links alone are not always enough
  to meet a redistributor's source obligations.
- **Bun LGPL components:** Bun's
  [license and relinking guidance](https://github.com/oven-sh/bun/blob/bun-v1.4.4/LICENSE.md)
  documents statically linked LGPL components. Assess and fulfill applicable
  LGPL source, notice and relinking requirements for the specific shipped
  executable. Do not treat Bun's top-level MIT designation as covering those
  components.
- **OpenCode transitive packages and native dependencies:** The frozen Bun
  dependency graph and compiled artifact contain additional third-party work
  not exhaustively itemized here. Before labeling an APK release
  license-complete, generate an artifact-specific dependency inventory,
  check original license texts/attributions, and make any required notices,
  source and replacement/relinking materials available to recipients.

For users of an installed APK, OpenCodie's own license and this file are copied
to `$PREFIX/share/doc/opencodie/` as part of the bundled Termux runtime.
Other package licenses remain in their normal Termux documentation directories.
