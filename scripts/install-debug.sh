#!/usr/bin/env bash
# Builds the debug APK, installs it on every connected adb device and grants its runtime
# permissions. A device that refuses the install (e.g. Xiaomi's "Install via USB" prompt) is
# reported and skipped; refused grants are reported and left for the app to request.
set -u
cd "$(dirname "$0")/.."

./gradlew :app:assembleDebug || exit 1

apk=app/build/outputs/apk/debug/app-debug.apk
package=com.example.myapplication.debug
permissions="CAMERA RECORD_AUDIO BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT BLUETOOTH_SCAN
    NEARBY_WIFI_DEVICES ACCESS_LOCAL_NETWORK ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION"
devices=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[ -z "$devices" ] && { echo "No devices connected"; exit 1; }

failed=0
for d in $devices; do
    echo "== $(adb -s "$d" shell getprop ro.product.model | tr -d '\r') ($d)"
    adb -s "$d" install -r "$apk" || { echo "!! install failed on $d"; failed=1; continue; }
    refused=""
    for p in $permissions; do
        # Permissions the device's Android version doesn't know (e.g. ACCESS_LOCAL_NETWORK) fail too.
        adb -s "$d" shell pm grant "$package" "android.permission.$p" >/dev/null 2>&1 || refused="$refused $p"
    done
    [ -n "$refused" ] && echo "   grants refused:$refused"
    case "$refused" in *CAMERA*) echo "   (adb grants blocked: enable 'USB debugging (Security settings)' on Xiaomi" \
        "or 'Disable permission monitoring' on OnePlus/Oppo in Developer options; the app asks instead)";; esac
done
exit $failed
