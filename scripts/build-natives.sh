#!/usr/bin/env bash
# Builds the bionic librust_pty cdylib that bun-pty (OpenCode's inline PTY) loads.
#
# The npm tarball ships glibc/musl builds only, so the arm64 slot Bun embeds at
# compile time is replaced with a real Android build of the same source.
#
# RUNS ON: Linux x86_64 build host. Requires cargo, rustup and the Android NDK.
# Usage: scripts/build-natives.sh <opencode checkout>
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
UPSTREAM="${1:?usage: build-natives.sh <opencode checkout>}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK r28b directory}"
ANDROID_API="${ANDROID_API:-29}"
TARGET="aarch64-linux-android"

BUN_PTY="$(ls -d "$UPSTREAM"/node_modules/.bun/bun-pty@*/node_modules/bun-pty 2>/dev/null | head -n1 || true)"
BUN_PTY="${BUN_PTY:-$UPSTREAM/node_modules/bun-pty}"
[ -d "$BUN_PTY/rust-pty" ] || {
    echo "bun-pty sources not installed under $BUN_PTY" >&2
    exit 1
}

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

# portable-pty 0.8 pulls termios/serial, which cannot build for Android;
# 0.9 uses serial2 and compiles unchanged (patch is idempotent).
if patch --dry-run -p1 -s -d "$BUN_PTY" -i "$REPO_ROOT/patches/bun-pty-portable09.patch" >/dev/null 2>&1; then
    patch -p1 -s -d "$BUN_PTY" -i "$REPO_ROOT/patches/bun-pty-portable09.patch"
fi

echo "==> cargo build $TARGET"
(
    cd "$BUN_PTY/rust-pty"
    rustup target add "$TARGET"
    cargo build --release --target "$TARGET"
)

SLOT="$BUN_PTY/rust-pty/target/release/librust_pty_arm64.so"
mkdir -p "$(dirname "$SLOT")"
cp -v "$BUN_PTY/rust-pty/target/$TARGET/release/librust_pty.so" "$SLOT"
bash "$REPO_ROOT/scripts/check-elf.sh" --no-glibc --no-musl --no-errno-location --arch aarch64 "$SLOT"
echo "==> bionic PTY binding installed at $SLOT"
