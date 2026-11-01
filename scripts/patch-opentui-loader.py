#!/usr/bin/env python3
# RUNS ON: Linux x86_64 CI runner (NOT on Termux, NOT on device).
"""Map Bun's Android runtime onto the linux-arm64-musl opentui asset.

Bun's Android build reports process.platform === "android", which stock
@opentui/core rejects ("Unsupported OpenTUI Node asset target"). The musl
asset is the right one (only needs bare libc.so, resolved to bionic).

Usage: patch-opentui-loader.py <upstream-checkout-dir>
Finds node_modules/@opentui/core/chunk-bun-*.js (hash in name varies by
version) and applies two anchor edits. Idempotent.
"""
import glob
import os
import sys
import tempfile


def read_text(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


if len(sys.argv) != 2:
    sys.exit("usage: patch-opentui-loader.py <upstream-checkout-dir>")
root = sys.argv[1]

cands = [
    p for p in glob.glob(
        os.path.join(root, "node_modules", ".bun", "@opentui+core@*",
                     "node_modules", "@opentui", "core", "chunk-bun-*.js"))
    if "function getCurrentNodeAssetTarget()" in read_text(p)
]
if len(cands) != 1:
    sys.exit(f"expected 1 opentui loader chunk, found {len(cands)}: {cands}")
path = cands[0]
src = read_text(path)

# Note: this probe assumes upstream has no Android branch of its own.
# If upstream ever ships one, this early-exit must be revisited.
if 'process.platform === "android"' in src:
    print("already patched")
    sys.exit(0)

anchor1 = ('function getCurrentNodeAssetTarget() {\n'
           '  const libc = process.env.OPENTUI_LIBC;')
repl1 = ('function getCurrentNodeAssetTarget() {\n'
         '  if (process.platform === "android") '
         'return { platform: "linux", arch: process.arch, libc: "musl" };\n'
         '  const libc = process.env.OPENTUI_LIBC;')
if src.count(anchor1) != 1:
    sys.exit("anchor1 (getCurrentNodeAssetTarget) not found")
src = src.replace(anchor1, repl1)

anchor2 = ('  if (process.platform === "linux") {\n'
           '    if (process.arch === "x64") {')
repl2 = ('  if (process.platform === "linux" || process.platform === "android") {\n'
         '    if (process.arch === "x64") {')
if src.count(anchor2) != 1:
    sys.exit("anchor2 (resolveNativeLibraryPath) not found")
src = src.replace(anchor2, repl2)

fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path))
try:
    with open(fd, "w", encoding="utf-8") as f:
        f.write(src)
    os.replace(tmp, path)
except BaseException:
    try:
        os.unlink(tmp)
    except OSError:
        pass
    raise
print(f"patched {path}")
