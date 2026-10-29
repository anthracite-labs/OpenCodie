#!/data/data/com.termux/files/usr/bin/sh
# OpenCodie launcher: runs the compiled OpenCode binary inside the Termux prefix.
set -eu
self=$(readlink -f "$0" 2>/dev/null || echo "$0")
dir=$(CDPATH= cd -- "$(dirname "$self")" && pwd)
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX
# The embedded file watcher (@parcel/watcher-android-arm64) links against the
# Termux libc++ package; Bun's own runtime has no RUNPATH into $PREFIX/lib.
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# /tmp is not writable for an Android app; keep PTY sockets in the prefix.
export OPENCODE_PTY_SOCKET_DIR="${OPENCODE_PTY_SOCKET_DIR:-$PREFIX/tmp}"
# OpenCodie ships no bionic build of the native file finder, so use OpenCode's
# ripgrep backend instead of a native binary that cannot load.
export OPENCODE_DISABLE_FFF=1
for candidate in "$dir/../libexec/opencode/opencode.bin" "$PREFIX/libexec/opencode/opencode.bin" "$dir/opencode.bin"; do
    if [ -x "$candidate" ]; then
        exec "$candidate" "$@"
    fi
done
echo "opencode: could not find opencode.bin" >&2
exit 127
