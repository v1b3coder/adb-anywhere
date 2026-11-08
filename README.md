# adb-anywhere

Wireless ADB that survives reboots, sleep and network changes on ordinary
Android phones, without root.

Android turns wireless debugging off when Wi-Fi disconnects, randomises its port
after reboot and exposes the stock listener on every interface. `adb-anywhere`
keeps a patched AOSP `adbd` running as the `shell` user with a loopback listener
for the app. By default, when a VPN is up, the daemon also listens on the VPN
address for developer machines. With the optional firewall on, the app owns the VPN
address and the daemon stays loopback-only.

The app controls the daemon and decides which machines may connect.

The design is in [docs/DESIGN.md](docs/DESIGN.md); the firewall and its limits are
in [docs/FIREWALL.md](docs/FIREWALL.md).

## Requirements

- Android 12 or newer, stock ROM, no root.
- A VPN that gives the phone a stable IPv4 address reachable by the developer
  machines (Tailscale or WireGuard). With the firewall on, the VPN must also
  include ADB Anywhere in the tunnel.
- One Wi-Fi connection for the first pairing. After a reboot the app needs one TCP
  shell: Wi-Fi wireless debugging, the plain 5555 listener (`adb tcpip 5555` over
  USB), or root.
- The developer machine has its `~/.android/adbkey.pub` at hand.
- Optional firewall: Usage access for the app allowlist, and Display over other apps
  for the floating approval card. Without overlay permission the prompt falls back
  to a notification and dialog.

## Install

`make build` writes the debug APK. Install
`app/build/outputs/apk/debug/app-debug.apk` once with the platform `adb`.
Later updates can use `make install-phone`, which connects to `PHONE` and installs
through the app's ADB endpoint. The Makefile is the command index; `make help`
lists every target and variable.

With the firewall on, an update install is an install service call (`abb_exec`).
When call policy is on, it is held for approval on the phone. Approve the card,
or tap *Review* first if it is a notification. You can also save a custom filter
that keeps `argv[0]=package` and `argv[1]=install` and leaves the later arguments
unlocked, because `-S`, the size and the path vary per install. The overlay cannot
show over the keyguard, so keep the phone unlocked. An empty `failed to install`
message is expected when the package manager replaces the app and kills it mid-stream;
`make phone-version` verifies the installed version.

The app declares `WRITE_SECURE_SETTINGS`, which is a signature permission. After
the one-time pairing it self-grants the permission over the system adbd's loopback
shell. If an OEM blocks `pm grant`, the app shows the exact
`adb shell pm grant cz.slush.adbanywhere
android.permission.WRITE_SECURE_SETTINGS` command instead of failing silently.

## First run

1. Open the app and allow notifications.
2. On a fresh install tap *Set up* in the status card; after a stop tap *Start*.
   Nothing starts the daemon before that.
3. Open Settings → Developer options → Wireless debugging → *Pair device with
   pairing code*.
4. Tap *Show pairing notification* in the app, pull down the shade and reply
   once with `code:port` (for example `123456:37123`). Opening the app closes
   Android's pairing dialog on AOSP, so the notification reply is the reliable
   path; there are no in-app manual fields.
5. The app pairs, grants `WRITE_SECURE_SETTINGS`, deploys the patched `adbd`,
   verifies loopback and either the VPN listener or the firewall relay, and turns
   Android's wireless debugging off last.
6. Add each developer machine's public key under *Keys* (paste or QR). The app
   shows the SHA-256 fingerprint before the key is added.

After setup, developer machines run:

```sh
adb connect <vpn-ip>:15038
```

The VPN port defaults to `15038`; both ports are configurable in
Settings → Ports. The app's own channel defaults to `127.0.0.1:15037` and is not
reachable from the network.

## Start and stop

The status card shows *Set up* on a fresh install, *Start* when stopped, and
*Stop*, *Re-check* and *Restart* while running. The app stores the last explicit
choice in device-protected storage, so a stopped app stays stopped when it is
reopened, after a reboot and after the system restarts the service. Only an
explicit Start, Set up, Pair or Restart starts it again.

A fresh install waits for Set up or Start; an install that already completed setup
keeps its last choice across an update. Stop waits for a deployment in progress to
finish before it stops the daemon, and it reports the loopback port if the daemon is
still listening.

## Firewall

The *Firewall* switch in Settings adds a separate tab. It is off by default. When
it is on, the patched `adbd` listens only on `127.0.0.1` and the app accepts the
VPN port itself. It relays the ADB bytes to `adbd`, so ADB key authentication
stays end-to-end. While ADB is running, toggling the switch restarts the daemon
and the relay in the new mode; the app process and the UI stay up.

The tab has a Status section and four modules. The IP, app and call-policy modules
each have their own switch and are off by default.

- **IP allowlist**: when enabled, only listed IPv4 addresses and CIDR ranges may
  connect; an empty list denies every peer. IPv6 is not supported.
- **App allowlist**: when enabled, external ADB is available only while a selected
  app is in the foreground. It needs Usage access; screen off or locked counts as
  no foreground app. A configurable grace period covers a short switch. The app's
  own approval dialog never counts as the foreground app, so a call cannot grant
  itself access by raising the dialog.
- **Call policy**: when enabled, every ADB service call is parsed. Rules run first;
  an unmatched call asks through a floating card when Display over other apps is
  granted, otherwise through a notification and dialog. The default is deny after
  30 s. *Block* denies this call, *Allow once* allows it, *Always allow* saves an
  exact rule, and *Custom filter* lets the user pin the fields that must stay fixed.
- **Audit log**: a bounded on-device log and the last 500 events in the UI. It
  is always on while the firewall is on.

Each IP entry, app entry and call rule shows when it last matched, as a relative
age (`2 hours ago`, or `never`). The timestamps are stored with the firewall
configuration and are independent of the audit log.

The app's own loopback channel is unchanged and is not filtered. The relay is
byte-transparent and does not authenticate. Shell filtering is policy and audit,
not a sandbox: an interactive shell and shell metacharacters are explicit
capabilities, and sync path rules are lexical and advisory. The full design and its
limits are in [docs/FIREWALL.md](docs/FIREWALL.md).

## Security

- With the firewall off, the patched daemon binds `127.0.0.1:<loopback>` and, when
  a VPN is up, `<vpn-ip>:<vpn-port>`. With the firewall on, it binds only loopback
  and the app relay owns the VPN port. Wildcard binds are refused; device-node,
  local-socket and vsock services are not available.
- Only keys in `/data/local/tmp/adb-anywhere/adb_keys` are accepted. Keys offered
  by clients are never auto-accepted.
- Removing a key removes it from the key file and restarts the daemon, so the
  sessions that the key authenticated end. Per-key policy and filtering are not
  implemented yet: every key in the file may open every supported service.
- The firewall relay refuses a service call before the daemon has authenticated the
  connection, so a peer without a key cannot prompt or reach the call policy. The
  approval card ignores taps that pass through another window, so a malicious overlay
  cannot forward a tap to *Allow once*.
- After the one-time permission grant, Start turns the system adbd on when ADB is off
  (safe: it does not delete the paired key), then tries its existing TCP listeners.
  On Wi-Fi, once setup has completed, it can reopen wireless debugging; on mobile data
  it needs the plain 5555 listener or root. Stop stops the patched adbd; the opt-in
  *Turn system adb off on Stop* switch makes Stop turn ADB and wireless debugging
  off. Start turns ADB back on when it is off, regardless of the switch.
- The app never stops the system adbd while it needs the patched adbd: on devices
  where init kills the adbd cgroup, that would also stop the patched adbd. The
  security benefit is at Stop, not during a run.
- If the system adbd is reachable over the network anyway, the app shows the warning
  in the UI and in its persistent foreground notification. A failed setup closes the
  wireless-debugging listener it opened and verifies that it is gone; a bootstrap that
  was already on before the attempt is left as it was.
- No root, no custom ROM, no fallback proxy and no random-port redirect.

## Build and tests

The Makefile is the command index; `make help` lists every target and variable.
`make check` is the local CI gate: JVM tests, lint and the debug APK.
`make e2e` and `make e2e-firewall` run the device tests on a rooted emulator;
`make adbd`, `make adbd-abi` and `make adbd-tests` build the daemon.
`.github/workflows/ci.yml` runs tests, lint and assemble on every push to `main`
and every pull request.

The patched `adbd` is a standalone AOSP build vendored under
[`adbd/`](adbd/README.md). A local build needs the Android NDK;
`ADBD_SKIP_BUILD=1` uses the existing assets as-is. CI builds and caches the
daemon for the commit, and the release ships those binaries.

## Release

Push a `vMAJOR.MINOR[.PATCH]` tag to the `github` remote to run the release
workflow. It runs the JVM tests, builds the release APK, signs it with the project
key, verifies the signature and version, and publishes the APK on the GitHub release
page. `versionName` and `versionCode` come from the tag
(`versionCode = major*10000 + minor*100 + patch`), so no version bump commit is
needed. The tag must be on a commit that is an ancestor of the `main` branch on
GitHub; the workflow refuses a higher tag. A manual run builds and uploads the APK
as a workflow artifact without publishing.

Local signed release builds read `signing/keystore.properties`; the keystore and the
password are not in the repository. Without that file, `assembleRelease` produces an
unsigned APK. Debug builds are signed by the standard Android debug key.

## License

The app's own code is MIT; see [LICENSE](LICENSE). The daemon bundled in `adbd/`
is derived from AOSP `adbd` and stays Apache 2.0; see [adbd/LICENSE](adbd/LICENSE).
Third-party attributions are in [NOTICE](NOTICE).

## Limitations

- The daemon runs as the `shell` user, so it gives a shell, not root.
- The VPN listener needs a VPN address. Without a VPN the app still controls the
  daemon over loopback, but developer machines cannot connect. With the firewall
  on, the VPN must include ADB Anywhere; an excluded app cannot receive inbound
  packets.
- The firewall IP allowlist is IPv4-only. IPv6 peers are not supported.
- The app allowlist needs Usage access. The floating approval card needs Display
  over other apps; without it the app uses a notification and dialog. A locked
  screen denies a call that needs approval.
- A reboot with no Wi-Fi needs one Wi-Fi connection (or USB `adb tcpip`) to
  bootstrap. The app shows the recovery steps.
- On some OEMs, re-asserting `adb_wifi_enabled=1` does not restart the system TLS
  server on a Wi-Fi network without internet. The app reports the failure explicitly;
  enable Wireless debugging once in Settings to recover.
- A VPN address change restarts the daemon when the firewall is off and rebinds the
  relay when it is on. Active ADB sessions drop and must reconnect to the new
  address.
- The firewall relay runs in the app process. If Android kills the process, external
  sessions drop until the service restarts; the loopback channel and the recovery
  steps are unaffected.
- If an OEM blocks `pm grant WRITE_SECURE_SETTINGS`, the app shows the manual
  command and stops.
