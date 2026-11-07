#!/bin/sh
# Extensive firewall e2e on a rooted emulator. The emulator has no VPN, so the
# app's debug-only vpnIp override points the relay at loopback; the host reaches it
# through adb forward. It exercises the IP allowlist, the app allowlist, the call
# policy prompts, the sync path and the audit log.
set -u
cd "$(dirname "$0")/.."

serial=${E2E_SERIAL:-emulator-5560}
pkg=cz.slush.adbanywhere
apk=app/build/outputs/apk/debug/app-debug.apk
relay_port=15038
relay=127.0.0.1:$relay_port
host_key=$(cut -d' ' -f1 "$HOME/.android/adbkey.pub")
tmp=$(mktemp -d)
failures=0

cleanup() {
    adb -s "$serial" shell "am start -n $pkg/.MainActivity --ez stop true" >/dev/null 2>&1 || true
    adb disconnect "$relay" >/dev/null 2>&1 || true
    adb -s "$serial" forward --remove tcp:$relay_port >/dev/null 2>&1 || true
    rm -rf "$tmp"
}
trap cleanup EXIT

say() { printf '\n== %s ==\n' "$*"; }
ok() { printf 'ok   %s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }

intent() {
    adb -s "$serial" shell "am start -n $pkg/.MainActivity $*" >/dev/null 2>&1
}

prefs() {
    adb -s "$serial" shell "cat /data/user_de/0/$pkg/shared_prefs/adb-anywhere.xml"
}

audit() {
    adb -s "$serial" shell "run-as $pkg tail -n 2000 /data/user_de/0/$pkg/files/firewall-audit.jsonl"
}

audit_has() { audit | grep -q "\"event\":\"$1\""; }

ui_dump() {
    adb -s "$serial" shell "uiautomator dump /sdcard/e2e-ui.xml >/dev/null 2>&1"
    adb -s "$serial" shell "cat /sdcard/e2e-ui.xml"
}

ui_center() {
    ui_dump | python3 -c '
import re, sys
xml = sys.stdin.read()
want = sys.argv[1]
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    if m.group(1) == want:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

wait_for_text() {
    i=0
    while [ "$i" -lt 20 ]; do
        [ -n "$(ui_center "$1")" ] && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

tap_text() {
    local xy
    xy=$(ui_center "$1")
    if [ -z "$xy" ]; then
        adb -s "$serial" shell input swipe 540 1800 540 1000 200
        sleep 1
        xy=$(ui_center "$1")
    fi
    [ -n "$xy" ] || return 1
    adb -s "$serial" shell input tap "$xy"
}

ui_prefix_checkbox() {
    ui_dump | python3 -c '
import re, sys
xml = sys.stdin.read()
text = None
for m in re.finditer(r"text=\"Prefix capability\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    text = tuple(map(int, m.groups()))
    break
if text is None:
    sys.exit(1)
tx1, ty1, tx2, ty2 = text
for m in re.finditer(r"checkable=\"true\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    x1, y1, x2, y2 = map(int, m.groups())
    if abs((y1 + y2) // 2 - (ty1 + ty2) // 2) <= 100 and x2 <= tx1 + 10:
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
'
}

tap_checkable() {
    local xy
    xy=$(ui_prefix_checkbox)
    [ -n "$xy" ] || return 1
    adb -s "$serial" shell input tap "$xy"
}

relay_connect() {
    # The app restarts the system adbd when it takes the VPN port, which drops
    # the host forward; recreate it before every relay connection.
    adb -s "$serial" forward tcp:$relay_port tcp:$relay_port >/dev/null 2>&1 || true
    adb disconnect "$relay" >/dev/null 2>&1 || true
    adb connect "$relay" >/dev/null 2>&1 || true
    sleep 2
}

run_ok() {
    local label=$1
    shift
    (adb -s "$relay" "$@" >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
    local pid=$!
    wait "$pid" 2>/dev/null || true
    [ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && ok "$label" || bad "$label"
}

run_fails() {
    local label=$1
    shift
    (adb -s "$relay" "$@" >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
    local pid=$!
    wait "$pid" 2>/dev/null || true
    [ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && bad "$label" || ok "$label"
}

prompt_call() {
    local label=$1
    shift
    (adb -s "$relay" "$@" >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
    CALL_PID=$!
    if wait_for_text "ADB call needs approval"; then
        ok "prompt for $label"
        return 0
    fi
    bad "no prompt for $label"
    kill "$CALL_PID" 2>/dev/null || true
    return 1
}

prompt_tap() {
    tap_text "$1" || bad "could not tap $1"
    sleep 1
}

finish_call() {
    wait "$CALL_PID" 2>/dev/null || true
    [ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && ok "call finished" || bad "call failed"
}

say "build and install"
./gradlew :app:assembleDebug --no-daemon -q || exit 1
adb -s "$serial" install -r "$apk" >/dev/null || exit 1

say "prepare the emulator"
adb -s "$serial" shell "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS" >/dev/null 2>&1 || true
adb -s "$serial" shell "pm grant $pkg android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true
adb -s "$serial" shell "appops set $pkg GET_USAGE_STATS allow" >/dev/null 2>&1 || true
adb -s "$serial" shell "appops set $pkg SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1 || true
adb -s "$serial" shell "am force-stop $pkg"
adb -s "$serial" root >/dev/null 2>&1 || true
adb -s "$serial" wait-for-device
adb -s "$serial" shell "cat /data/user_de/0/$pkg/files/appkey.pub >> /data/misc/adb/adb_keys; sort -u /data/misc/adb/adb_keys -o /data/misc/adb/adb_keys"
adb -s "$serial" shell "setprop ctl.restart adbd" >/dev/null 2>&1 || true
adb -s "$serial" wait-for-device
sleep 2
adb -s "$serial" shell "rm -f /data/user_de/0/$pkg/shared_prefs/adb-anywhere.xml"
adb -s "$serial" shell "ip tuntap add dev tun0 mode tun 2>/dev/null || true"
adb -s "$serial" shell "ip addr add 10.9.9.1/32 dev tun0 2>/dev/null || true"
adb -s "$serial" shell "ip link set tun0 up 2>/dev/null || true"
ok "emulator prepared"

say "start the app with the debug VPN"
intent "--ez start true"
sleep 6
intent "--es addkey '$host_key' --es addlabel e2e-laptop"
sleep 4
intent "--es vpnIp 127.0.0.1"
sleep 3
intent "--ez firewall true"
sleep 8
adb -s "$serial" shell "netstat -ltn 2>/dev/null" | grep -q "127.0.0.1:$relay_port" && ok "relay listening" || bad "relay not listening"
adb -s "$serial" forward tcp:$relay_port tcp:$relay_port >/dev/null
relay_connect
i=0
while [ "$i" -lt 20 ]; do
    adb -s "$relay" shell id 2>/dev/null | grep -q "uid=" && break
    sleep 1
    i=$((i + 1))
done
[ "$i" -lt 20 ] && ok "relay shell" || bad "relay shell"

say "baseline calls"
run_ok "shell" shell id
run_ok "exec-out" exec-out getprop ro.product.model
adb -s "$relay" exec-out getprop ro.product.model 2>/dev/null | grep -q "sdk_gphone" && ok "exec-out output" || bad "exec-out output"
run_ok "logcat" logcat -d -t 1
run_ok "forward" forward tcp:18080 tcp:8080
run_ok "reverse" reverse tcp:18081 tcp:8080
printf 'e2e-push\n' >"$tmp/push.txt"
run_ok "push" push "$tmp/push.txt" /data/local/tmp/e2e-push.txt
run_ok "pull" pull /data/local/tmp/e2e-push.txt "$tmp/pull.txt"
grep -q e2e-push "$tmp/pull.txt" 2>/dev/null && ok "pull content" || bad "pull content"
printf 'id\nexit\n' | adb -s "$relay" shell 2>/dev/null | grep -q "uid=" && ok "interactive shell" || bad "interactive shell"

say "IP allowlist"
intent "--es firewallIp 10.9.9.2/32"
intent "--ez firewallIpAllowlist true"
sleep 2
relay_connect
adb -s "$relay" shell id >/dev/null 2>&1 && bad "IP allowlist did not deny" || ok "IP allowlist denied"
audit_has ip_denied && ok "ip_denied audit" || bad "ip_denied audit"
intent "--ez firewallIpAllowlist false"
sleep 2
relay_connect
adb -s "$relay" shell id 2>/dev/null | grep -q "uid=" && ok "IP allowlist disabled" || bad "IP allowlist disabled"

say "app allowlist"
intent "--es firewallApp $pkg"
intent "--ez firewallAppAllowlist true"
sleep 6
relay_connect
adb -s "$relay" shell id 2>/dev/null | grep -q "uid=" && ok "app allowlist foreground" || bad "app allowlist foreground"
adb -s "$serial" shell input keyevent 3
sleep 4
relay_connect
adb -s "$relay" shell id >/dev/null 2>&1 && bad "app allowlist did not deny" || ok "app allowlist denied"
audit_has app_denied && ok "app_denied audit" || bad "app_denied audit"
intent ""
sleep 6
relay_connect
adb -s "$relay" shell id 2>/dev/null | grep -q "uid=" && ok "app allowlist re-entered" || bad "app allowlist re-entered"
intent "--ez firewallAppAllowlist false"
sleep 2

say "call policy"
intent "--ez firewallCallPolicy true"
sleep 2
if prompt_call "allow once" shell id; then
    prompt_tap "Allow once"
    finish_call
    audit_has call_allowed && ok "allow once audit" || bad "allow once audit"
fi

if prompt_call "always allow" shell getprop ro.product.model; then
    prompt_tap "Always allow this exact call"
    finish_call
    prefs | grep -q 'firewall_call_rules">\[{' && ok "always allow rule" || bad "always allow rule"
fi

if prompt_call "block" shell getprop ro.product.brand; then
    prompt_tap "Block"
    wait "$CALL_PID" 2>/dev/null || true
    audit_has call_denied && ok "block audit" || bad "block audit"
fi

if prompt_call "custom filter" shell 'ls /sdcard'; then
    prompt_tap "Custom filter"
    prompt_tap "Save rule"
    finish_call
    audit_has call_allowed && ok "custom filter audit" || bad "custom filter audit"
fi

say "prefix capability"
if prompt_call "prefix" shell 'run-as cz.slush.adbanywhere id 2>&1'; then
    prompt_tap "Custom filter"
    wait_for_text "Prefix capability" && ok "prefix editor" || bad "prefix editor"
    tap_checkable && ok "prefix toggle" || bad "prefix toggle"
    sleep 1
    prompt_tap "Save rule"
    finish_call
    prefs | grep -q 'rawShell&quot;:true' && ok "raw shell rule" || bad "raw shell rule"
fi
run_ok "raw prefix rule" shell 'run-as cz.slush.adbanywhere sh -c id 2>&1'

say "floating prompt"
adb -s "$serial" shell input keyevent 3
sleep 2
(adb -s "$relay" shell getprop ro.product.board >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
CALL_PID=$!
if wait_for_text "ADB call needs approval"; then
    ok "overlay prompt while backgrounded"
    prompt_tap "Allow once"
    finish_call
    audit_has call_allowed && ok "overlay allow audit" || bad "overlay allow audit"
else
    bad "no overlay prompt while backgrounded"
    kill "$CALL_PID" 2>/dev/null || true
fi
adb -s "$serial" shell "am start -n $pkg/.MainActivity" >/dev/null 2>&1
sleep 3

say "sync path"
(adb -s "$relay" push "$tmp/push.txt" /data/local/tmp/e2e-sync.txt >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
CALL_PID=$!
if wait_for_text "ADB call needs approval"; then
    prompt_tap "Allow once"
    finish_call
    audit_has call_allowed && ok "sync audit" || bad "sync audit"
else
    wait "$CALL_PID" 2>/dev/null || true
    [ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && bad "sync push bypassed the prompt" || bad "sync push"
fi

say "timeout"
(adb -s "$relay" shell getprop ro.product.device >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
CALL_PID=$!
if wait_for_text "ADB call needs approval"; then
    sleep 35
    wait "$CALL_PID" 2>/dev/null || true
    [ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && bad "timeout allowed" || ok "timeout denied"
    audit_has call_timeout && ok "call_timeout audit" || bad "call_timeout audit"
else
    bad "no prompt for timeout"
fi

say "screen off"
adb -s "$serial" shell input keyevent 26
sleep 6
(adb -s "$relay" shell getprop ro.product.cpu.abi >"$tmp/call.out" 2>&1; echo $? >"$tmp/call.rc") &
CALL_PID=$!
wait "$CALL_PID" 2>/dev/null || true
[ "$(cat "$tmp/call.rc" 2>/dev/null)" = 0 ] && bad "screen off allowed" || ok "screen off denied"
audit_has call_denied && ok "screen off audit" || bad "screen off audit"
adb -s "$serial" shell input keyevent 26
sleep 2
adb -s "$serial" shell input keyevent 82
sleep 2

intent "--ez firewallCallPolicy false"
sleep 2

say "firewall off"
intent "--ez firewall false"
sleep 6
prefs | grep -q 'firewall_enabled" value="false"' && ok "firewall off stored" || bad "firewall off stored"
prefs | grep -q 'daemon_firewall_mode" value="false"' && ok "daemon returns to legacy" || bad "daemon returns to legacy"

say "firewall on"
intent "--ez firewall true"
sleep 8
prefs | grep -q 'firewall_enabled" value="true"' && ok "firewall on stored" || bad "firewall on stored"
relay_connect
i=0
while [ "$i" -lt 20 ]; do
    adb -s "$relay" shell id 2>/dev/null | grep -q "uid=" && break
    sleep 1
    i=$((i + 1))
done
[ "$i" -lt 20 ] && ok "firewall on restores the relay" || bad "firewall on restores the relay"

say "audit"
for event in connection_open call_open call_prompted call_allowed call_denied call_timeout ip_denied app_denied; do
    audit_has "$event" && ok "$event" || bad "$event"
done

say "summary"
if [ "$failures" -ne 0 ]; then
    printf '\n%d firewall e2e checks failed\n' "$failures" >&2
    exit 1
fi
printf '\nfirewall e2e ok\n'
