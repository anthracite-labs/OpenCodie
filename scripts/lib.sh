#!/usr/bin/env bash
# Shared shell helpers for OpenCodie build scripts.
# Sourced by scripts/*.sh, not executed directly.
# RUNS ON: Linux x86_64 build host.
set -euo pipefail

# Checkout a pinned commit in an existing clone.
# Usage: checkout_pinned <dir> <sha>
checkout_pinned() {
    if [ $# -ne 2 ]; then
        echo "usage: checkout_pinned <dir> <sha>" >&2
        return 1
    fi
    local dir="$1" sha="$2"
    if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$sha" ]; then return 0; fi
    git -C "$dir" fetch --depth 1 origin "$sha"
    git -C "$dir" -c advice.detachedHead=false checkout --detach "$sha"
    test "$(git -C "$dir" rev-parse HEAD)" = "$sha" || {
        echo "wrong revision in $dir (expected $sha)" >&2
        exit 1
    }
}

# Apply a patch if needed, skip if already applied.
# Usage: apply_patch_if_needed <dir> <patch-file>
apply_patch_if_needed() {
    if [ $# -ne 2 ]; then
        echo "usage: apply_patch_if_needed <dir> <patch-file>" >&2
        return 1
    fi
    local dir="$1" patch="$2" name
    name="$(basename "$patch")"
    if git -C "$dir" apply --reverse --check "$patch" 2>/dev/null; then
        echo "$name already applied"
    else
        git -C "$dir" apply --check "$patch"
        git -C "$dir" apply "$patch"
        echo "applied $name"
    fi
}
