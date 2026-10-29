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
    mkdir -p "$WORK"
    git clone --depth 1 --branch "v$BUN_PTY_VERSION" https://github.com/sursaone/bun-pty.git "$SRC"
fi
# portable-pty 0.8 pulls termios/serial, which cannot build for Android;
# 0.9 uses serial2 and compiles unchanged (the patch is idempotent).
if patch --dry-run -p1 -s -d "$SRC" -i "$REPO_ROOT/patches/bun-pty-portable09.patch" >/dev/null 2>&1; then
    patch -p1 -s -d "$SRC" -i "$REPO_ROOT/patches/bun-pty-portable09.patch"
    echo "applied bun-pty-portable09.patch"
fi

echo "==> cargo build $TARGET"
(
    cd "$SRC/rust-pty"
    rustup target add "$TARGET"
    cargo build --release --target "$TARGET"
)
BUILT="$SRC/rust-pty/target/$TARGET/release/librust_pty.so"
test -f "$BUILT"
bash "$REPO_ROOT/scripts/check-elf.sh" --no-glibc --no-musl --no-errno-location --arch aarch64 "$BUILT"

echo "==> install into bun-pty embed slots"
SLOTS=$(find "$UPSTREAM/node_modules" -type d -path "*/bun-pty/rust-pty/target/release" 2>/dev/null || true)
test -n "$SLOTS" || {
    echo "no bun-pty install found under $UPSTREAM/node_modules" >&2
    exit 1
}
for slot in $SLOTS; do
    install -m 755 "$BUILT" "$slot/librust_pty_arm64.so"
    echo "installed $slot/librust_pty_arm64.so"
done
