# adb-anywhere

Wireless ADB that survives reboots, sleep and network changes on ordinary
Android phones, without root.

Android turns wireless debugging off when Wi-Fi disconnects, randomises its port
after reboot and exposes the stock listener on every interface. `adb-anywhere`
keeps a patched AOSP `adbd` running as the `shell` user with two explicit
listeners: loopback for this app and the VPN address for developer machines. The
app is the only entity that controls the listener, and the only entity that decides
which machines may connect.

The design is in [DESIGN.md](DESIGN.md).

## Requirements

- Android 12 or newer, stock ROM, no root.
- A VPN that gives the phone a stable address reachable by the developer
  machines (Tailscale or WireGuard).
- One Wi-Fi connection for the first pairing and for the first boot after a reboot.
- The developer machine has its `~/.android/adbkey.pub` at hand.

## Install

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app declares `WRITE_SECURE_SETTINGS`, which is a signature permission. It
self-grants it over ADB after the one-time pairing. If an OEM blocks `pm grant`,
the app shows the exact `adb shell pm grant cz.slush.adbanywhere
android.permission.WRITE_SECURE_SETTINGS` command instead of failing silently.

## First run

1. Open the app and allow notifications.
2. Tap ▶️ *Start* in the status card. Nothing starts the daemon before that.
3. Open Settings → Developer options → Wireless debugging → *Pair device with
   pairing code*.
4. Tap *Show pairing notification* in the app, pull down the shade and reply with
   `code:port`. The same value can be entered in the app's manual field.
5. The app pairs, grants `WRITE_SECURE_SETTINGS`, deploys the patched `adbd`,
   verifies loopback and the VPN listener, and turns Android's wireless debugging
   off.
6. Add each developer machine's public key under *Keys* (paste or QR). The app shows
   the SHA-256 fingerprint before the key is added.

After setup, developer machines run:

```sh
adb connect <vpn-ip>:15038
```

The app's own channel is `127.0.0.1:15037` and is not reachable from the
network.

## Start and stop

The status card has one toggle: ▶️ starts the daemon, 🛑 stops it. The app stores
the last choice in device-protected storage, so a stopped app stays stopped when it
is reopened, after a reboot and after the system restarts the service. Only ▶️ starts
it again. Stop waits for a deployment in progress to finish before it stops the daemon,
and it reports the loopback port if the daemon is still listening.

## Security

- The patched daemon binds only `127.0.0.1:<loopback>` and
  `<vpn-ip>:<vpn-port>`. Wildcard binds are refused.
- Only keys in `/data/local/tmp/adb-anywhere/adb_keys` are accepted. Keys offered
  by clients are never auto-accepted.
- Removing a key removes it from the key file and restarts the daemon, so the
  sessions that the key authenticated end. Per-key policy and filtering are not
  implemented yet: every key in the file may open every supported service.
- Android's wireless debugging is turned off after the daemon is verified. *Keep
  system adb off* also sets `ADB_ENABLED=0` and restores the original value when
  turned off.
- No root, no custom ROM, no proxy fallback and no random-port redirect.

## Build and tests

```sh
./gradlew :app:testDebugUnitTest   # JVM tests
./gradlew :app:lintDebug           # lint gate; errors fail the build
./gradlew :app:assembleDebug       # APK
scripts/e2e-emulator.sh            # end-to-end against a rooted emulator
```

`.github/workflows/ci.yml` runs the JVM tests, `lintDebug` and `assembleDebug` on
every push to `main` and every pull request.

The patched `adbd` is a standalone AOSP build vendored under
[`adbd/`](adbd/README.md). The Gradle `syncAdbd` task cross-compiles it and
copies the per-ABI binaries into `app/src/main/assets/adbd/`. If the NDK is
absent, the committed binaries are used.

## Release

Pushing a `vMAJOR.MINOR[.PATCH]` tag to the `github` remote runs the release
workflow: it runs the JVM tests, builds the release APK, signs it with the project
key, and publishes it on the GitHub release page. `versionName` and `versionCode`
come from the tag (`versionCode = major*10000 + minor*100 + patch`), so no version
bump commit is needed. A manual run builds and uploads the APK as a workflow
artifact without publishing.

```sh
git tag v0.1.0
git push github v0.1.0   # origin (Gitea) does not run this workflow
```

Local signed release builds read `signing/keystore.properties`; the keystore and the
password are not in the repository. Without that file, `assembleRelease` produces an
unsigned APK as before. Debug builds are signed by the standard Android debug key.

## Limitations

- The daemon runs as the `shell` user, so it gives a shell, not root.
- The VPN listener needs a VPN address. Without a VPN the app still controls the
  daemon over loopback but developer machines cannot connect.
- A reboot with no Wi-Fi needs one Wi-Fi connection (or USB `adb tcpip`) to
  bootstrap. The app shows the recovery steps.
- On some OEMs, re-asserting `adb_wifi_enabled=1` does not restart the system TLS
  server on a Wi-Fi network without internet. The app reports the failure explicitly;
  enable Wireless debugging once in Settings to recover.
- A VPN address change restarts the daemon; active ADB sessions drop and must
  reconnect to the new address.
- If an OEM blocks `pm grant WRITE_SECURE_SETTINGS`, the app shows the manual
  command and stops.
