# adb-anywhere

Wireless ADB that stays reachable across sleep and network changes on ordinary
Android phones, without root. After a reboot it comes back once Android's adb can be
reached again.

Android turns wireless debugging off when Wi-Fi disconnects, randomises its port
after reboot and exposes the stock listener on every interface. `adb-anywhere`
keeps a patched AOSP `adbd` running as the `shell` user with a loopback listener
for the app. By default, when a VPN is up, the daemon also listens on the VPN
address for your computers. With the optional firewall on, the app owns the VPN
address and the daemon stays loopback-only.

The app controls the daemon and decides which machines may connect. The daemon
process itself does not survive a reboot: the app redeploys it after boot once it can
reach Android's adb.

The design is in [docs/DESIGN.md](docs/DESIGN.md); the firewall and its limits are
in [docs/FIREWALL.md](docs/FIREWALL.md). Release notes are in
[CHANGELOG.md](CHANGELOG.md).

## Requirements

- Android 12 or newer, stock ROM, no root.
- A VPN that gives the phone a stable IPv4 address reachable by your computers
  (Tailscale or WireGuard). With the firewall on, the VPN must also include ADB
  Anywhere in the tunnel.
- One Wi-Fi connection for the first pairing.
- After a reboot the app needs one TCP shell to redeploy the daemon: Wi-Fi wireless
  debugging, the plain 5555 listener (`adb tcpip 5555` over USB), or root. The app does
  not keep ADB alive through the reboot by itself.
- The computer you connect from has its ADB public key at hand.
- Optional firewall: Usage access for the app allowlist, and Display over other apps
  for the floating approval card. Without overlay permission the prompt falls back
  to a notification and dialog.

## Install

Download the latest signed APK from the
[GitHub releases](https://github.com/v1b3coder/adb-anywhere/releases/latest) and
install it on the phone. Install a newer release over the current one; no uninstall
is needed. 0.x releases are previews.

The app needs `WRITE_SECURE_SETTINGS` for the ADB bootstrap. After the one-time
pairing it grants the permission over ADB. If an OEM blocks the grant, the app shows
the manual command instead of failing silently.

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
6. Add each computer's public key under *Keys* (paste or QR). The app shows the
   SHA-256 fingerprint before the key is added.

After setup, connect from the computer:

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

The optional firewall moves the VPN listener into the app. It is off by default.
When it is on, the daemon listens only on loopback and the app accepts the VPN
port, applies the IP and app allowlists and the call policy, then relays to the
daemon. ADB key authentication stays end-to-end.

The Firewall tab has:

- **IP allowlist**: only listed IPv4 addresses and CIDR ranges may connect. IPv6
  is not supported.
- **App allowlist**: external ADB is available only while a selected app is in the
  foreground. A configurable grace period covers a short switch. Usage access is
  required.
- **Call policy**: rules for ADB service calls. An unmatched call asks for approval
  on the phone; the default is deny. You can allow once, always allow that call,
  block it, or pin fields with a custom filter.
- **Audit log**: recent firewall decisions on the device and in the UI.

Each entry and rule shows when it last matched. The app's own loopback channel is
not filtered.

The firewall is policy and audit, not a sandbox: shell access and sync paths are
not isolated. The full design and its limits are in
[docs/FIREWALL.md](docs/FIREWALL.md).

## Security

- The daemon binds loopback and, by default, the VPN address. With the firewall
  on it binds only loopback and the app owns the VPN port.
- Only keys in the daemon key file are accepted. Unknown keys are never
  auto-accepted.
- Removing a key restarts the daemon and ends the sessions that key
  authenticated. Per-key policy is not implemented yet.
- The firewall does not add authentication. ADB key authentication stays
  end-to-end. The approval card ignores taps that pass through another window.
- Start can re-enable Android's ADB and wireless debugging after the one-time
  permission grant. Stop leaves them alone unless you opt in.
- The app does not stop Android's ADB while it needs the daemon.
- If Android's ADB is reachable over the network anyway, the app warns you in the
  UI and in the notification.
- No root, no custom ROM, no fallback proxy and no random-port redirect.

## Limitations

- The daemon runs as the `shell` user, so it gives a shell, not root.
- The VPN listener needs a VPN address. Without a VPN the app still controls the
  daemon over loopback, but your computers cannot connect. With the firewall on,
  the VPN must include ADB Anywhere.
- The firewall IP allowlist is IPv4-only.
- The app allowlist needs Usage access. The floating approval card needs Display
  over other apps; without it the app uses a notification and dialog. A locked
  screen denies a call that needs approval.
- A reboot does not keep the daemon running. The app redeploys it after boot when it
  can reach Android's adb; on mobile data without the plain 5555 listener it waits for
  Wi-Fi or USB. The app shows the recovery steps.
- On some OEMs Android does not restart wireless debugging on a Wi-Fi network
  without internet. Enable Wireless debugging once in Settings to recover.
- A VPN address change restarts the daemon when the firewall is off and rebinds the
  relay when it is on. Active sessions drop and must reconnect.
- The firewall relay runs in the app process. If Android kills it, external
  sessions drop until the service restarts.
- If an OEM blocks the permission grant, the app shows the manual command and stops.

## License

The app's own code is MIT; see [LICENSE](LICENSE). The daemon bundled in `adbd/`
is derived from AOSP `adbd` and stays Apache 2.0; see [adbd/LICENSE](adbd/LICENSE).
Third-party attributions are in [NOTICE](NOTICE).
