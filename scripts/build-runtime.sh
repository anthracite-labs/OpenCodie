#!/usr/bin/env bash
# Builds runtime-aarch64.zip: the ARM64 Termux userspace (APT bootstrap) plus the
# compiled OpenCode installation, and embeds it in the APK assets.
#
# RUNS ON: Linux x86_64 build host (network needed for the package repository).
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
# shellcheck source=../runtime.lock disable=SC1091
source "$REPO_ROOT/runtime.lock"

WORK="${WORK:-$REPO_ROOT/work}"
DIST="${DIST:-$REPO_ROOT/dist}"
ASSETS="$REPO_ROOT/app/src/main/assets"
STAGE="$WORK/runtime/rootfs"
BOOTSTRAP="$WORK/bootstrap-aarch64.zip"
ARCHIVE="$REPO_ROOT/runtime-aarch64.zip"

test -x "$DIST/opencode/opencode" && test -x "$DIST/opencode/opencode.bin" || {
    echo "run scripts/build-opencode.sh first" >&2
    exit 1
}

echo "==> termux bootstrap $TERMUX_BOOTSTRAP_TAG @ $TERMUX_BOOTSTRAP_COMMIT"
if [ ! -f "$BOOTSTRAP" ]; then
    TERMUX="$WORK/termux-packages"
    if [ ! -d "$TERMUX/.git" ]; then
        mkdir -p "$WORK"
        git clone --filter=blob:none --no-checkout "$TERMUX_PACKAGES_REPO" "$TERMUX"
    fi
    if [ "$(git -C "$TERMUX" rev-parse HEAD 2>/dev/null || true)" != "$TERMUX_BOOTSTRAP_COMMIT" ]; then
        git -C "$TERMUX" fetch --depth 1 origin "$TERMUX_BOOTSTRAP_COMMIT"
        git -C "$TERMUX" -c advice.detachedHead=false checkout --detach "$TERMUX_BOOTSTRAP_COMMIT"
    fi
    test "$(git -C "$TERMUX" rev-parse HEAD)" = "$TERMUX_BOOTSTRAP_COMMIT" || {
        echo "wrong termux-packages revision" >&2
        exit 1
    }
    (
        cd "$TERMUX"
        ./scripts/generate-bootstraps.sh \
            --architectures aarch64 \
            --pm apt \
            --add "$TERMUX_BOOTSTRAP_PACKAGES"
    )
    mv "$TERMUX/bootstrap-aarch64.zip" "$BOOTSTRAP"
fi

echo "==> stage runtime"
rm -rf "$STAGE"
mkdir -p "$STAGE"
unzip -q "$BOOTSTRAP" -d "$STAGE"
mkdir -p "$STAGE/libexec/opencode" "$STAGE/etc/opencode-apk"
install -m 755 "$DIST/opencode/opencode" "$STAGE/bin/opencode"
install -m 755 "$DIST/opencode/opencode.bin" "$STAGE/libexec/opencode/opencode.bin"
printf '%s\n' "$OPENCODE_VERSION" >"$STAGE/etc/opencode-apk/version"

echo "==> archive"
rm -f "$ARCHIVE"
(cd "$STAGE" && zip -r -9 -q "$ARCHIVE" ./*)

for expected in bin/pkg bin/apt bin/dpkg bin/bash bin/rg lib/libc++_shared.so etc/opencode-apk/version libexec/opencode/opencode.bin; do
    unzip -l "$ARCHIVE" | grep -q " $expected\$" || {
        echo "runtime is missing $expected" >&2
        exit 1
    }
done

echo "==> embed in APK assets"
mkdir -p "$ASSETS"
cp "$ARCHIVE" "$ASSETS/runtime-aarch64.zip"
sha256sum "$ARCHIVE" | cut -d' ' -f1 >"$ASSETS/runtime.sha256"
printf '%s\n' "$OPENCODE_VERSION" >"$ASSETS/runtime.version"
ls -lh "$ARCHIVE" "$ASSETS/runtime-aarch64.zip"
cat "$ASSETS/runtime.sha256"
