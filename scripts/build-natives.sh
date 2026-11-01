#!/usr/bin/env bash
# Builds the bionic librust_pty cdylib that bun-pty (OpenCode's inline PTY) loads.
#
# The npm package ships prebuilt glibc/musl libraries but not the Rust sources,
# so the library is built from the pinned bun-pty tag and dropped into the
# `../rust-pty/target/release/librust_pty_arm64.so` slot that Bun embeds at
# compile time (bun-pty resolves that filename for arm64 on every non-musl unix).
#
# RUNS ON: Linux x86_64 build host. Requires cargo, rustup and the Android NDK.
# Usage: scripts/build-natives.sh <opencode checkout>
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
# shellcheck source=../runtime.lock disable=SC1091
source "$REPO_ROOT/runtime.lock"
# shellcheck source=lib.sh disable=SC1091
source "$REPO_ROOT/scripts/lib.sh"

UPSTREAM="${1:?usage: build-natives.sh <opencode checkout>}"
WORK="${WORK:-$REPO_ROOT/work}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK r28b directory}"
TARGET="aarch64-linux-android"
SRC="$WORK/bun-pty-$BUN_PTY_VERSION"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CLANG="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
test -x "$CLANG" || {
    echo "no NDK clang at $CLANG" >&2
    exit 1
}
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CFLAGS_aarch64_linux_android="--sysroot=$TOOLCHAIN/sysroot"

echo "==> bun-pty v$BUN_PTY_VERSION sources"
if [ ! -d "$SRC/.git" ]; then
    if [ -e "$SRC" ]; then
        echo "stale $SRC without .git (interrupted clone?) — remove it and re-run" >&2
        exit 1
    fi
    mkdir -p "$WORK"
    git clone --depth 1 --branch "v$BUN_PTY_VERSION" https://github.com/sursaone/bun-pty.git "$SRC"
fi
test "$(git -C "$SRC" rev-parse HEAD)" = "$BUN_PTY_COMMIT" || {
    echo "bun-pty source commit differs from runtime.lock" >&2
    exit 1
}
# A required patch must either apply or already be applied.
apply_patch_if_needed "$SRC" "$REPO_ROOT/patches/bun-pty-portable09.patch"

echo "==> cargo build $TARGET"
(
    cd "$SRC/rust-pty"
    rustup target add "$TARGET"
    # No --locked: bun-pty-portable09.patch bumps portable-pty 0.8->0.9, so
    # Cargo.lock must update on first build; source is still commit-pinned.
    cargo build --release --target "$TARGET"
)
BUILT="$SRC/rust-pty/target/$TARGET/release/librust_pty.so"
test -f "$BUILT" || { echo "missing built library: $BUILT" >&2; exit 1; }
bash "$REPO_ROOT/scripts/check-elf.sh" --no-glibc --no-musl --no-errno-location --arch aarch64 "$BUILT"

echo "==> install into bun-pty embed slots"
found=0
while IFS= read -r -d '' slot; do
    install -m 755 "$BUILT" "$slot/librust_pty_arm64.so"
    echo "installed $slot/librust_pty_arm64.so"
    found=1
done < <(find "$UPSTREAM/node_modules" -type d -path "*/bun-pty/rust-pty/target/release" -print0 2>/dev/null || true)
test "$found" = 1 || {
    echo "no bun-pty install found under $UPSTREAM/node_modules" >&2
    exit 1
}
