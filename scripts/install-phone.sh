#!/bin/sh
# Installs the debug APK on the phone through the app's own relay and proves the
# build landed. The relay lives in the app the install replaces, so the stream
# always dies with the old process: `adb install` ends with an empty
# "failed to install" and a non-zero exit even when the install succeeded. That
# message and that exit code are not a verdict; this script decides by hashing
# the installed APK against the build.
set -u
cd "$(dirname "$0")/.."

apk=${APK:-app/build/outputs/apk/debug/app-debug.apk}
pkg=${PKG:-cz.slush.adbanywhere}
flags=${ADB_INSTALL_FLAGS:--r}
phone=${PHONE:-}

if [ -z "$phone" ]; then
	echo "install-phone: PHONE is not set." >&2
	echo "  Run it through make install-phone, or pass PHONE=host:port." >&2
	adb devices -l >&2
	exit 1
fi

want=$(sha256sum "$apk" | cut -d' ' -f1)
if [ -z "$want" ]; then
	echo "install-phone: cannot hash $apk" >&2
	exit 1
fi

adb connect "$phone" >/dev/null 2>&1 || true

# Waits up to $1 seconds until adb has a live transport to the phone. The probe
# stays on the host side on purpose: a repeated `adb shell` would ask the call
# policy for approval on every attempt.
wait_phone() {
	i=0
	while ! adb -s "$phone" get-state >/dev/null 2>&1; do
		i=$((i + 1))
		[ "$i" -ge "$1" ] && return 1
		sleep 1
	done
}

echo "Installing $apk on $phone through the relay."
echo "  A call-policy card may appear on the phone; see the Makefile header."

# The relay is the transport, so a stopped app and an absent phone look the same:
# no transport. Say which one it is before spending minutes on an install.
if ! wait_phone 5; then
	echo "install-phone: $phone does not answer." >&2
	echo "  The relay runs inside the app: start it on the phone, then retry." >&2
	exit 1
fi

out=$(adb -s "$phone" install $flags "$apk" 2>&1) || true
printf '%s\n' "$out" | sed 's/^/  adb: /'

# Replacing the app tears down the relay and the transport goes offline. It
# reconnects by itself once the app has restarted and rebound the port.
if ! wait_phone 60; then
	echo "install-phone: $phone did not come back within 60 s." >&2
	echo "  Retry the connection with: adb connect $phone" >&2
	exit 1
fi

# Hash the installed base.apk, not the build: this is the only proof that the
# bytes on the phone are the ones this run produced.
path=$(adb -s "$phone" shell "pm path $pkg" 2>/dev/null | sed -n 's/^package://p' | head -1)
got=
if [ -n "$path" ]; then
	got=$(adb -s "$phone" shell "sha256sum '$path'" 2>/dev/null | cut -d' ' -f1)
fi

if [ "$got" != "$want" ]; then
	echo "install-phone: the phone does not run this build." >&2
	echo "  built:     $want" >&2
	echo "  installed: ${got:-unknown}" >&2
	echo "  This install really failed: check the audit log, or retry the card." >&2
	exit 1
fi

echo "Installed and verified: $pkg on $phone"
adb -s "$phone" shell "dumpsys package $pkg | grep -E 'versionName=|lastUpdateTime='" 2>/dev/null | sed 's/^ */  /'
