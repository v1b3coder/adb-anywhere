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

# The daemon only starts after an explicit start and the app remembers the
# last one. Clear the persisted control state so the run does not depend on how
# the previous run was left. The app key is in files/, not in this file.
adb -s "$serial" shell "rm -f /data/user_de/0/$pkg/shared_prefs/adb-anywhere.xml"

adb -s "$serial" shell "am start -n $pkg/.MainActivity --ez start true" >/dev/null
sleep 6

# Give the app the host key through its debug-only intent, then let the service
# rewrite the key file without restarting the daemon.
adb -s "$serial" shell "am start -n $pkg/.MainActivity --es addkey '$host_key' --es addlabel e2e-laptop" >/dev/null
sleep 4

# The real host path: forward the app's loopback port through the system
# adbd, then connect and run a shell with the developer key that was just
# added.  This is the same path a developer machine uses on a phone.
adb -s "$serial" forward --remove tcp:"$host_port" >/dev/null 2>&1 || true
adb -s "$serial" forward tcp:"$host_port" tcp:"$host_port" >/dev/null
adb connect 127.0.0.1:"$host_port" >/dev/null
adb -s 127.0.0.1:"$host_port" shell id | grep -q 'uid='
adb disconnect 127.0.0.1:"$host_port" >/dev/null
echo "host adb path ok"

# Prove the daemon started and accepts the app key.  The host adb client is
# exercised above; the app-side shell is exercised below.  The phone VPN path is
# in docs/E2E.md.
adb -s "$serial" shell "cat /data/local/tmp/adb-anywhere/adbd.log" | grep -q "adbd started"
adb -s "$serial" shell "am start -n $pkg/.MainActivity --es shellcmd 'id; echo e2e-daemon-ok'" >/dev/null
sleep 2
adb -s "$serial" logcat -d -s AdbAnywhere | grep -q "e2e-daemon-ok"
adb -s "$serial" shell "cat /data/local/tmp/adb-anywhere/adb_keys" | grep -q "$host_key"
adb -s "$serial" shell 'cmd statusbar collapse' >/dev/null 2>&1 || true
adb -s "$serial" shell "uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1"
adb -s "$serial" shell "cat /sdcard/e2e.xml" | grep -q 'ADB is reachable'

# A process kill must not end supervision. `force-stop` clears the sticky flag, so
# kill the process: START_STICKY recreates the service and setup() resumes, which
# brings the notification back to the Running phase. The activity must stay closed:
# launching it sends ACTION_START and would mask a missing resume.
pid_before=$(adb -s "$serial" shell pidof "$pkg" | tr -d '\r' | cut -d' ' -f1)
[ -n "$pid_before" ]
adb -s "$serial" shell "kill -9 $pid_before"
pid_after=""
i=0
while [ "$i" -lt 30 ]; do
    pid_after=$(adb -s "$serial" shell pidof "$pkg" | tr -d '\r' | cut -d' ' -f1)
    [ -n "$pid_after" ] && [ "$pid_after" != "$pid_before" ] && break
    sleep 1
    i=$((i + 1))
done
[ -n "$pid_after" ] && [ "$pid_after" != "$pid_before" ]
i=0
while [ "$i" -lt 60 ]; do
    adb -s "$serial" shell 'dumpsys notification --noredact' |
        grep -q 'android.title=String (ADB Anywhere' && break
    sleep 1
    i=$((i + 1))
done
adb -s "$serial" shell 'dumpsys notification --noredact' |
    grep -q 'android.title=String (ADB Anywhere'
echo "sticky restart ok"

# A stop is remembered: after the process is killed and the app is reopened the
# daemon must stay down until an explicit start, and then start again.
adb -s "$serial" shell "am start -n $pkg/.MainActivity --ez stop true" >/dev/null
i=0
while [ "$i" -lt 30 ]; do
    adb -s "$serial" shell 'dumpsys notification --noredact' |
        grep -q 'android.title=String (ADB Anywhere' || break
    sleep 1
    i=$((i + 1))
done
! adb -s "$serial" shell 'dumpsys notification --noredact' | grep -q 'android.title=String (ADB Anywhere'
pid_stopped=$(adb -s "$serial" shell pidof "$pkg" | tr -d '\r' | cut -d' ' -f1)
[ -n "$pid_stopped" ]
adb -s "$serial" shell "kill -9 $pid_stopped"
sleep 5
adb -s "$serial" shell "am start -n $pkg/.MainActivity" >/dev/null
sleep 6
! adb -s "$serial" shell 'dumpsys notification --noredact' | grep -q 'android.title=String (ADB Anywhere'
adb -s "$serial" shell "am start -n $pkg/.MainActivity --ez start true" >/dev/null
i=0
while [ "$i" -lt 60 ]; do
    adb -s "$serial" shell 'dumpsys notification --noredact' |
        grep -q 'android.title=String (ADB Anywhere' && break
    sleep 1
    i=$((i + 1))
done
adb -s "$serial" shell 'dumpsys notification --noredact' |
    grep -q 'android.title=String (ADB Anywhere'
echo "stop and start ok"

echo "e2e ok"
