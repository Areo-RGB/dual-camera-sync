#!/usr/bin/env bash
# Builds the debug APK and installs it on every connected adb device. A device that refuses
# the install (e.g. Xiaomi's "Install via USB" prompt) is reported and skipped.
set -u
cd "$(dirname "$0")/.."

./gradlew :app:assembleDebug || exit 1

apk=app/build/outputs/apk/debug/app-debug.apk
devices=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[ -z "$devices" ] && { echo "No devices connected"; exit 1; }

failed=0
for d in $devices; do
    echo "== $(adb -s "$d" shell getprop ro.product.model | tr -d '\r') ($d)"
    adb -s "$d" install -r "$apk" || { echo "!! install failed on $d"; failed=1; }
done
exit $failed
