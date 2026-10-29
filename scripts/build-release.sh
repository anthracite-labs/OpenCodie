#!/usr/bin/env bash
# Full release: OpenCode executable, bundled Termux runtime, signed APK.
#
# Signing (optional, from the environment):
#   OPENCODIE_KEYSTORE_FILE / OPENCODIE_KEYSTORE_PASSWORD / OPENCODIE_KEY_ALIAS /
#   OPENCODIE_KEY_PASSWORD
# Without them the release APK is built unsigned; the debug APK is installable.
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

bash scripts/build-opencode.sh
bash scripts/build-runtime.sh

./gradlew --no-daemon :app:assembleRelease

APK="$REPO_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
for candidate in "$REPO_ROOT/app/build/outputs/apk/release/"*.apk; do
    [ -f "$candidate" ] && APK="$candidate"
done
echo "APK: $APK"
