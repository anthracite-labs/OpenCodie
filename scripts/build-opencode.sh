#!/usr/bin/env bash
# Builds the Android/Bionic OpenCode v2 executable, WebUI included.
#
# Staged output (Termux layout):
#   dist/opencode/opencode       launcher wrapper
#   dist/opencode/opencode.bin   compiled aarch64 Android executable with the
#                                server, the WebUI assets and the PTY natives
#
# RUNS ON: Linux x86_64 build host with bun 1.4.4, cargo and the Android NDK.
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
# shellcheck source=../runtime.lock disable=SC1091
source "$REPO_ROOT/runtime.lock"

WORK="${WORK:-$REPO_ROOT/work}"
DIST="${DIST:-$REPO_ROOT/dist}"
UPSTREAM="$WORK/opencode-$OPENCODE_VERSION"
export PATH="$HOME/.bun/bin:$PATH"

command -v bun >/dev/null || { echo "bun is not installed (need $BUN_VERSION)" >&2; exit 1; }
test "$(bun --version)" = "$BUN_VERSION" || {
    echo "bun $BUN_VERSION required, found $(bun --version)" >&2
    exit 1
}

# Pinned source revision: tags are never trusted on their own.
checkout_pinned() {
    local dir="$1" sha="$2"
    if git -C "$dir" rev-parse HEAD 2>/dev/null | grep -q "^$sha"; then return 0; fi
    git -C "$dir" fetch --depth 1 origin "$sha"
    git -C "$dir" -c advice.detachedHead=false checkout --detach "$sha"
    test "$(git -C "$dir" rev-parse HEAD)" = "$sha" || {
        echo "wrong revision in $dir (expected $sha)" >&2
        exit 1
    }
}

echo "==> upstream $OPENCODE_VERSION @ $OPENCODE_COMMIT"
if [ ! -d "$UPSTREAM/.git" ]; then
    mkdir -p "$WORK"
    git clone --filter=blob:none --no-checkout "$OPENCODE_REPO" "$UPSTREAM"
fi
checkout_pinned "$UPSTREAM" "$OPENCODE_COMMIT"

apply_patch() {
    if git -C "$UPSTREAM" apply --reverse --check "$REPO_ROOT/patches/$1" 2>/dev/null; then
        echo "$1 already applied"
    else
        git -C "$UPSTREAM" apply "$REPO_ROOT/patches/$1"
        echo "applied $1"
    fi
}
apply_patch opencode-android.patch
apply_patch watcher-android.patch
apply_patch opencode-mobile-ui.patch

echo "==> javascript dependencies"
cd "$UPSTREAM"
bun install --frozen-lockfile --ignore-scripts
python3 "$REPO_ROOT/scripts/patch-opentui-loader.py" "$UPSTREAM"
# The compile embeds natives for the build target, so every platform variant
# (including the Android file watcher and the linux-arm64-musl PTY slot) must be
# on disk before the bundler runs.
bun install --frozen-lockfile --os="*" --cpu="*" --libc="*"

# Bun skips some os:android optionals even with --os="*"; fetch them verbatim.
ensure_npm_package() {
    local name="$1" version="$2" flat="node_modules/$1"
    if [ -d "$flat" ] || ls -d "node_modules/.bun/$(echo "$name" | sed 's@/@+@')@$version" >/dev/null 2>&1; then
        return 0
    fi
    echo "fetching $name@$version from npm"
    mkdir -p "$flat"
    curl -fsSL "https://registry.npmjs.org/$name/-/$(basename "$name")-$version.tgz" |
        tar xz -C "$flat" --strip-components=1
}
ensure_npm_package "@parcel/watcher-android-arm64" "$PARCEL_WATCHER_VERSION"
ensure_npm_package "@opencode/pty-linux-arm64-musl" "$PTY_VERSION"

echo "==> native PTY binding"
bash "$REPO_ROOT/scripts/build-natives.sh" "$UPSTREAM"

echo "==> compile (WebUI embedded)"
export BUN_COMPILE_RELEASE OPENCODE_CHANNEL=latest
export OPENCODE_VERSION="${OPENCODE_VERSION#v}"
export GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
cd "$UPSTREAM/packages/cli"
bun run script/build.ts \
    --target=opencode-linux-arm64-android \
    --skip-install \
    --outdir="$DIST/cli"

BIN="$DIST/cli/cli-linux-arm64-android/bin/opencode"
test -x "$BIN" || { echo "missing compiled binary: $BIN" >&2; exit 1; }
bash "$REPO_ROOT/scripts/check-elf.sh" --no-glibc --no-musl --arch aarch64 "$BIN"

echo "==> stage launcher"
mkdir -p "$DIST/opencode"
install -m 755 "$REPO_ROOT/scripts/opencode-wrapper.sh" "$DIST/opencode/opencode"
install -m 755 "$BIN" "$DIST/opencode/opencode.bin"
ls -lh "$DIST/opencode"
