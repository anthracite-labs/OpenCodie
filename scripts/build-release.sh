#!/usr/bin/env bash
# Build a single installable ARM64 OpenCodie.apk, signed with the release key.
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

: "${OPENCODIE_KEYSTORE_FILE:?Set OPENCODIE_KEYSTORE_FILE for a signed release APK}"
: "${OPENCODIE_KEYSTORE_PASSWORD:?Set OPENCODIE_KEYSTORE_PASSWORD}"
: "${OPENCODIE_KEY_ALIAS:?Set OPENCODIE_KEY_ALIAS}"
: "${OPENCODIE_KEY_PASSWORD:?Set OPENCODIE_KEY_PASSWORD}"

# Fail fast before the long builds: the keystore must exist now, not after.
test -f "$OPENCODIE_KEYSTORE_FILE" || { echo "keystore not found: $OPENCODIE_KEYSTORE_FILE" >&2; exit 1; }

bash scripts/build-opencode.sh
bash scripts/build-runtime.sh
./gradlew --no-daemon :app:assembleRelease

APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
test -s "$APK" || { echo "missing release APK: $APK" >&2; exit 1; }
# Verify the signature right away so a mis-signed output never gets copied.
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/build-tools/37.0.0/apksigner" ]; then
    "$ANDROID_HOME/build-tools/37.0.0/apksigner" verify "$APK"
fi
mkdir -p "$REPO_ROOT/dist"
install -m 644 "$APK" "$REPO_ROOT/dist/OpenCodie.apk"
echo "Signed APK: $REPO_ROOT/dist/OpenCodie.apk"
