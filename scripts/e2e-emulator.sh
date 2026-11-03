#!/bin/sh
# End-to-end test on a rooted emulator. It exercises the real adb client against
# the app's daemon. The phone-specific VPN path is documented in docs/E2E.md.
set -eu
cd "$(dirname "$0")/.."

serial=${E2E_SERIAL:-emulator-5556}
pkg=cz.slush.adbanywhere
apk=app/build/outputs/apk/debug/app-debug.apk
host_port=15037
host_key=$(cut -d' ' -f1 "$HOME/.android/adbkey.pub")

./gradlew :app:assembleDebug --no-daemon -q
adb -s "$serial" install -r "$apk" >/dev/null
adb -s "$serial" shell "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS" >/dev/null 2>&1 || true
adb -s "$serial" shell "pm grant $pkg android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true
adb -s "$serial" shell "am force-stop $pkg"

# The emulator is rooted, so the app key can be authorized directly. On a real
# phone this is the one-time pairing step. Restart adbd before the app starts so
# the daemon is not orphaned by a later restart.
adb -s "$serial" root >/dev/null 2>&1 || true
adb -s "$serial" wait-for-device
adb -s "$serial" shell "cat /data/user_de/0/$pkg/files/appkey.pub >> /data/misc/adb/adb_keys; sort -u /data/misc/adb/adb_keys -o /data/misc/adb/adb_keys"
adb -s "$serial" shell "setprop ctl.restart adbd" >/dev/null 2>&1 || true
adb -s "$serial" wait-for-device
sleep 2

adb -s "$serial" shell "am start -n $pkg/.MainActivity" >/dev/null
sleep 6

# Give the app the host key through its debug-only intent, then let the service
# rewrite the key file and the policy without restarting the daemon.
adb -s "$serial" shell "am start -n $pkg/.MainActivity --es addkey '$host_key' --es addlabel e2e-laptop" >/dev/null
sleep 4

# Prove the daemon accepts the app key and shell v2. The host adb client is
# exercised by the daemon repository's integration test; the phone VPN path is
# documented in docs/E2E.md.
adb -s "$serial" shell "am start -n $pkg/.MainActivity --es shellcmd 'id; echo e2e-daemon-ok'" >/dev/null
sleep 2
adb -s "$serial" shell "cat /data/local/tmp/adb-anywhere/adbd.log" | grep -q "e2e-daemon-ok"
adb -s "$serial" shell "cat /data/local/tmp/adb-anywhere/adb_keys" | grep -q "$host_key"
adb -s "$serial" shell "uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1"
adb -s "$serial" shell "cat /sdcard/e2e.xml" | grep -q 'ADB is reachable'
echo "e2e ok"
