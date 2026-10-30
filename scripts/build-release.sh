#!/usr/bin/env bash
# Build a single installable ARM64 OpenCodie.apk, signed with the release key.
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

: "${OPENCODIE_KEYSTORE_FILE:?Set OPENCODIE_KEYSTORE_FILE for a signed release APK}"
: "${OPENCODIE_KEYSTORE_PASSWORD:?Set OPENCODIE_KEYSTORE_PASSWORD}"
: "${OPENCODIE_KEY_ALIAS:?Set OPENCODIE_KEY_ALIAS}"
: "${OPENCODIE_KEY_PASSWORD:?Set OPENCODIE_KEY_PASSWORD}"

bash scripts/build-opencode.sh
bash scripts/build-runtime.sh
./gradlew --no-daemon :app:assembleRelease

APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
test -s "$APK"
mkdir -p "$REPO_ROOT/dist"
install -m 644 "$APK" "$REPO_ROOT/dist/OpenCodie.apk"
echo "Signed APK: $REPO_ROOT/dist/OpenCodie.apk"
