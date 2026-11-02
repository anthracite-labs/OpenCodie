#!/usr/bin/env bash
set -uo pipefail

result=0
./gradlew --no-daemon :app:connectedDebugAndroidTest || result=$?

mkdir -p app/build/ui-captures
# Preserve the actual on-screen state before restarting the app.
adb exec-out screencap -p > app/build/ui-captures/after-tests.png || true

if [ "$result" -ne 0 ]; then
  exit "$result"
fi

adb shell am force-stop com.termux
adb shell am start -n com.termux/.MainActivity
sleep 2
adb exec-out screencap -p > app/build/ui-captures/welcome.png
