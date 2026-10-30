#!/usr/bin/env bash
# Shared shell helpers for OpenCodie build scripts.
# Sourced by scripts/*.sh, not executed directly.
# RUNS ON: Linux x86_64 build host.
set -euo pipefail

# Checkout a pinned commit in an existing clone.
# Usage: checkout_pinned <dir> <sha>
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
