# adb-anywhere — Design

Status: draft for review
Date: 2026-11-03
Scope: stock Android phones, no root

## 1. Problem

Android 14 turns wireless ADB off when Wi-Fi disconnects and randomises the ADB
port after reboot. That breaks remote development on a phone that moves between
networks. The stock ADB listener also binds every interface, which is unacceptable
on untrusted Wi-Fi.

`adb-anywhere` keeps ADB reachable from the developer machine on any network,
including mobile data over a VPN, while keeping the exposed surface under the app's
control.

## 2. Goals

- Keep ADB reachable from the developer machine across reboots, sleep and network
  changes, including mobile data over a VPN.
- Never expose plain ADB on an untrusted LAN.
- Make the app the only entity that controls the ADB listener and the only entity
  that decides which developer machines may connect.
- No root. No custom ROM. No permanent web server.

## 3. Non-goals

- Rooted devices, Magisk, KernelSU, custom ROMs.
- Replacing the system `adbd` on the system partition.
- A general-purpose VPN or firewall for other apps.
- Call-level ADB policy in the first release (see phase 3).
- TV and set-top boxes.

## 4. Verified platform constraints

These are facts from AOSP `main`, not assumptions.

1. **The framework gates TLS wireless debugging on Wi-Fi.**
   `AdbDebuggingManager` sets `ADB_WIFI_ENABLED=0` on Wi-Fi disable,
   Wi-Fi disconnect, empty BSSID, or BSSID change
   (`AdbDebuggingManager.java:611-658`). The TLS server is toggled by
   `persist.adb.tls_server.enable` (`system_adbd_prop`, system-only) and
   publishes `service.adb.tls.port` (`adbd_prop`, readable).
2. **Legacy `adb tcpip` is not Wi-Fi gated.**
   `tcpip:<port>` sets `service.adb.tcp.port`; adbd listens on `tcp:<port>` and
   `vsock:<port>` on all interfaces (`main.cpp:269-296`,
   `restart_service.cpp:57-66`).
3. **adbd cannot be bound to loopback on stock AOSP.**
   `service.adb.listen_addrs` is read at adbd startup and overrides
   `service.adb.tcp.port` (`main.cpp:273`), but it is not declared in
   `property_contexts`; it lands in `default_prop`, and shell has no
   `set_prop(shell, default_prop)`. `tcp:localhost` is separately broken
   (issuetracker 385868238, `EINVAL`).
4. **Pairing dies on `onPause`.**
   `WirelessDebuggingFragment.onPause()` removes the pairing dialog and
   `PairingCodeDialogListener.onDismiss()` calls `disablePairing()`
   (`WirelessDebuggingFragment.java:157-162, 236-242`). The pairing dialog is a
   plain `AlertDialog` with no `FLAG_SECURE`; Settings does not set
   `HIDE_OVERLAY_WINDOWS`.
5. **The pairing code and port are not readable by third-party apps.**
   They are delivered with `MANAGE_DEBUGGING`
   (`AdbDebuggingManager.java:204-209, 1365-1400`).
6. **`adb_keys` is `0640 system:system`.** Shell cannot read or write it
   (`AdbDebuggingManager.java:1588`).
7. **The system `adbd` cannot be stopped from shell.**
   `ctl.stop$adbd` is `ctl_adbd_prop`; shell has no `set_prop` for it. `kill`
   may be restarted by init and breaks USB debugging.
8. **`usb:` is not used as the off switch.**
   It sets `service.adb.tcp.port=0`, but adbd falls back to 5555 when the USB
   gadget endpoint is absent (`main.cpp:293-296`). `is_usb` is evaluated once at
   adbd startup by `access("/dev/usb-ffs/adb/ep0", F_OK)`, not from the live
   cable state. `ADB_WIFI_ENABLED=0` stops the TLS server; `ADB_ENABLED=0` stops
   adbd.
9. **The TLS port is readable by the app.**
   `service.adb.tls.port` is `adbd_prop`; `getprop` returns it to an
   unprivileged process. mDNS is a fallback, not the only source.
10. **`adb_enabled=0` stops adbd.**
    `AdbService.setAdbEnabled(false, USB)` calls `stopAdbd()`, and
    `UsbDeviceManager` removes ADB from the USB functions and calls
    `stopAdbdForTransport(USB)` (`AdbService.java:444-469`,
    `UsbDeviceManager.java:2494-2503`). This is a clean off switch, unlike
    `usb:`.

## 5. Product decisions

| # | Decision | Choice |
|---|---|---|
| D1 | Device support | Stock Android 12+, no root |
| D2 | Data plane | One patched `adbd` instance with two listeners: `127.0.0.1:<portA>` for the app, `<vpn-ip>:<portB>` for developer machines |
| D3 | App key | Ephemeral RSA keypair generated on first run; private key in app-private storage; public key in the patched adbd key file |
| D4 | Developer machine pairing | QR shown by the developer machine, scanned by the app; no pairing listener on the phone |
| D5 | System adbd hardening | `ADB_WIFI_ENABLED=0` always after the patched adbd is running; `ADB_ENABLED=0` only with "Keep system adb off" (default off; restore the original value when unchecked) |
| D6 | Watchdog | Keeps the D5 state; probes 5555 only when "Keep system adb off" is on; restarts the patched adbd |
| D7 | Policy | Patched adbd enforces per-key and per-service rules; VPN ACL is the outer boundary |
| D8 | UI stack | Kotlin, Jetpack Compose, Material 3; package `cz.slush.adbanywhere` |
| D9 | Bootstrap pairing | Notification `RemoteInput` with `code:port` |
| D10 | Patched adbd transports | TCP only; USB and vsock disabled |
| D11 | Settings UI | "Keep system adb off" checkbox; shows the current ADB state and the residual |
| D12 | Failure policy | Explicit error and recovery steps; no silent fallback |

## 6. Architecture

Three ADB processes and one app process:

| Component | Runs as | Listener | Role |
|---|---|---|---|
| System `adbd` | `shell` (retail) | USB (left on); TLS before `ADB_WIFI_ENABLED=0`; TCP 5555 only on some devices | Bootstrap; key auth; system services |
| Patched `adbd` | `shell` | `127.0.0.1:<portA>` and `<vpn-ip>:<portB>` | Only ADB endpoint for the app and developer machines; policy and logs |
| App | app uid | none | Control plane: deploys, keys, policy, watchdog, UI |
| Developer machine `adb` | host | none | Client |

The patched adbd is a stock AOSP `adbd` with a minimal patch set. It is the
"internal proxy" in the sense that it is the only endpoint a developer machine can
reach. There is no separate proxy process.

### Patch set

All in `daemon/`:

| Patch | Purpose |
|---|---|
| `main.cpp`: accept `--listen` or read `service.adb.listen_addrs` | Bind the exact addresses given by the app |
| `main.cpp`: skip `usb_init()` and `vsock` | Never touch USB or vsock |
| `adb_wifi.cpp`: skip `start_mdnsd()` and `register_adb_secure_connect_service()` | No mDNS advertisement |
| `auth.cpp` / `adb_auth.cpp`: key path | Use `/data/local/tmp/adb-anywhere/adb_keys` |
| `services.cpp`: reject `tcpip:`, `usb:`, `root:`, `unroot:`, `remount:`, `reboot:` | The patched adbd must not touch the system adbd or reboot |
| `services.cpp`: keep `shell:`, `sync:`, `exec:` | What developer machines need |
| `services.cpp`: optional `tcp:`, `local:`, `reverse:` | Only if `adb forward`/`reverse` is required |
| `services.cpp`: policy hook on `OPEN`/`WRTE` | Per-key and per-service allow/deny, structured logs |
| `auth.cpp`: reload key file on change | Key provisioning without restart |

Multi-listener is not a large patch. `setup_adb` already loops over the address
list and `init_transport_socket_server` starts one thread per address
(`main.cpp:205-208`, `transport_socket_server.cpp:89-92`). The comma-separated
list already exists in `service.adb.listen_addrs` (`main.cpp:272-303`). The patch
only changes where the list comes from and skips USB, vsock, and mDNS; the listener
count itself is free. The large work is the cross-compile, deployment, key
provisioning, and policy, not the number of listeners.

Build targets: `arm64-v8a`, `armeabi-v7a`, `x86_64`. Bundle in the APK as assets.
AOSP `adbd` is Apache 2.0; ship `NOTICE` and the source offer.

### Key provisioning

- First run: the app generates an ephemeral RSA keypair. The private key stays in
  app-private storage; the public key is written to the patched adbd key file before
  it starts.
- The patched adbd key file contains only the app key at startup, so the app is the
  only entity accepted.
- Developer machine keys are added by QR pairing. Each key has a label and a role:
  `app` (full access) or `developer` (policy).
- The app rewrites the key file through the patched adbd's `shell:`. It never needs
  a second listener.
- On reinstall, the app generates a new keypair and rewrites the key file. Old keys
  are removed.

### QR pairing

The developer machine displays a QR; the app scans it. No listener is opened on the
phone.

1. Companion CLI on the developer machine reads `~/.android/adbkey.pub`.
2. It generates a nonce and signs it with `~/.android/adbkey`.
3. It prints a QR containing `pubkey`, `nonce`, `signature`, `label`, `issued_at`.
4. The app scans the QR, verifies the signature with the public key, shows the key
   fingerprint and label, and asks the user to confirm.
5. On confirm, the app appends the key to the patched adbd key file with role
   `developer`.
6. The developer machine runs `adb connect <vpn-name>:<portB>` with its private key.

This is out-of-band, one-time, and has no listening socket. A malicious QR can only
add the key it proves possession of, and the user sees the fingerprint before
confirming. Replay is limited by `issued_at` and by removing the key.

### System adbd control

- Mandatory: `ADB_WIFI_ENABLED=0` after the patched adbd is running. The
  framework stops the system TLS server (`persist.adb.tls_server.enable=0`); no ADB
  call is needed. The system adbd stays on USB, which is not a network listener.
- Optional, "Keep system adb off" checked: `ADB_ENABLED=0`. `AdbService` stops
  adbd and `UsbDeviceManager` removes ADB from the USB functions. The app stores
  the original value and restores it when the checkbox is turned off.
- Bootstrap: `ADB_ENABLED=1` and `ADB_WIFI_ENABLED=1` on Wi-Fi. The TLS server
  starts; the app reads `service.adb.tls.port` and connects.
- The patched adbd ignores both toggles.

### Port behavior

Wireless debugging toggles only the TLS listener; it does not touch the plain
TCP listener. The two are separate features with separate controls:

| Feature | Listener | Control | mDNS | Since |
|---|---|---|---|---|
| Legacy `adb tcpip` | plain TCP `0.0.0.0:<port>` | `service.adb.tcp.port` via `adb tcpip` / `adb usb` | `_adb._tcp` | before Android 11 |
| Wireless debugging | TLS `0.0.0.0:<random>` | `persist.adb.tls_server.enable` via the Settings toggle | `_adb-tls-connect._tcp` | Android 11 |
| USB debugging | USB only | `ADB_ENABLED` | none | always |

The Settings toggle was added for the TLS feature; it was never wired to the
legacy listener. That is why turning it off leaves `adb tcpip` running. The app
never calls `adb tcpip` on the system adbd, so the legacy listener should be
absent unless the device or a previous tool set it.

Enabling (`ADB_WIFI_ENABLED=1`):

1. `AdbService` sets `persist.adb.tls_server.enable=1`.
2. adbd starts `TlsServer(0)`.
3. `TlsServer` binds `0.0.0.0:<random>` via `network_inaddr_any_server`.
4. adbd registers `_adb-tls-connect._tcp` and publishes
   `service.adb.tls.port`.
5. The TLS listener is on all interfaces at a random port. No plain TCP
   listener is created.

Disabling (`ADB_WIFI_ENABLED=0`):

1. `AdbService` sets `persist.adb.tls_server.enable=0`.
2. adbd deletes `TlsServer`, closing the TLS listener.
3. adbd unregisters `_adb-tls-connect._tcp` and kicks TLS transports.
4. The TLS listener is gone. A plain TCP listener, if present, is unchanged.

Plain TCP listener:

- Created by `adb tcpip` (`service.adb.tcp.port=<port>`), bound to
  `0.0.0.0:<port>`.
- Fallback when `is_usb=false` and `service.adb.tcp.port` is empty:
  `0.0.0.0:5555`.
- Not created when `is_usb=true` and `service.adb.tcp.port` is empty.
- Independent of `ADB_WIFI_ENABLED`.

`ADB_ENABLED=0` stops adbd entirely: no TLS, no plain, no USB.

### Watchdog

The app's foreground service always keeps the system wireless debugging off.
In the normal case (`is_usb=true`, `service.adb.tcp.port` empty) the system adbd
has no TCP listener, so there is nothing to monitor or turn off.

- Mandatory: set `ADB_WIFI_ENABLED=0`. The framework stops the system TLS
  server.
- If "Keep system adb off" is checked: keep `ADB_ENABLED=0`. Probe
  `127.0.0.1:5555` once after boot, after a network change, and after a USB
  state change; if it is open, set `ADB_ENABLED=0`.
- If the checkbox is off: leave `ADB_ENABLED` alone. Show the plain listener as
  a residual if `127.0.0.1:5555` is open.
- Poll `127.0.0.1:<portA>` every 60 s while the screen is on and every 15 min
  via `AlarmManager.setExactAndAllowWhileIdle` otherwise.
- If `<portA>` is down, restart the patched adbd through the system adbd if it is
  reachable, otherwise notify the user.
- Register `UsbManager.ACTION_USB_STATE` (sticky) and
  `ConnectivityManager.NetworkCallback`.
- Backoff and hysteresis; never fight an explicit user "off".
- If `ADB_ENABLED=0` does not stop adbd, move it to a random port with
  `tcpip:<random>` and record the residual.

## 7. Flows

### First run

1. User installs the app and opens it.
2. App requests notification permission.
3. App posts the pairing notification with a `code:port` `RemoteInput`.
4. User opens Settings → Developer options → Wireless debugging → Pair device with
   pairing code.
5. User pulls the shade over the dialog and enters `code:port`.
6. App pairs with the system adbd over loopback and stores its key.
7. App self-grants `WRITE_SECURE_SETTINGS` over ADB.
8. App extracts the patched adbd for the device ABI, pushes it to
   `/data/local/tmp/adb-anywhere/`, verifies SHA-256, and `chmod 755`.
9. App writes its public key to the patched adbd key file.
10. App starts the patched adbd with `--listen tcp:127.0.0.1:<portA>` and, when
    the VPN is up, `--listen tcp:<vpn-ip>:<portB>`.
11. App verifies both listeners and `shell:id`.
12. App sets `ADB_WIFI_ENABLED=0` to stop the system TLS server.
13. App starts the watchdog and shows the endpoint.

### Port discovery

- Primary: `getprop service.adb.tls.port` (`adbd_prop`, readable).
- Fallback: `NsdManager` discovery of `_adb-tls-connect._tcp`.
- Last resort: the user enters the port shown in Settings.
- The plain target port is chosen by the app and set with `tcpip:<port>`;
  it is stored in prefs and re-applied after boot.

### Boot

1. `BootReceiver` starts the service on `BOOT_COMPLETED` and
   `LOCKED_BOOT_COMPLETED`.
2. Service re-asserts `adb_wifi_enabled` only when Wi-Fi is up, and
   `adb_allowed_connection_time=0`.
3. Service probes for a system adbd TCP listener: `5555`, the last known port,
   or the TLS port on Wi-Fi.
4. If reachable, start the patched adbd, verify, then set `ADB_WIFI_ENABLED=0`.
5. If not reachable, ask the user to connect to any Wi-Fi once. The app then
   sets `adb_wifi_enabled=1`, reads `service.adb.tls.port`, connects with its
   paired key, and switches to the stable port. This is a manual recovery step,
   not a blocker.
6. If no Wi-Fi is available, the developer machine can connect USB once and
   run `adb tcpip:<port>` to bootstrap.

### Network change

1. `NetworkCallback` fires.
2. If the VPN address changed, restart the patched adbd with the new address and
   keep the loopback listener.
3. If the VPN is gone, remove the VPN listener; the app keeps the loopback
   listener.
4. If the active transport is Wi-Fi or Ethernet and no VPN is up, the developer
   machine uses the system TLS port, not the patched adbd.

### USB

1. `ACTION_USB_STATE` fires.
2. The system adbd stays on USB; the patched adbd is unaffected.
3. If the system adbd returns to TCP, the watchdog sets `ADB_ENABLED=0`.
4. If that does not stop it, move the system adbd to a random port with
   `tcpip:<random>` and log the residual.

## 8. Security model

- **Assets**: ADB shell access, the app key, developer machine keys, the policy
  file.
- **Outer boundary**: the VPN ACL. The patched adbd's VPN listener is only
  reachable by allowed VPN peers.
- **Inner boundary**: the patched adbd key file and per-key policy. Only the app and
  explicitly paired developer machines are accepted.
- **App channel**: loopback, independent of the VPN.
- **System adbd**: USB (left on); not reachable over TCP after
  `ADB_WIFI_ENABLED=0`.
- **Residual**: on devices where the system adbd exposes 5555, set
  `ADB_ENABLED=0` only when "Keep system adb off" is on; otherwise show the
  residual. If `ADB_ENABLED=0` fails, move it to a random port and document it.
- **Pairing**: QR is out-of-band, one-time, fingerprint-confirmed. No listener.
- **Keys**: app key is ephemeral; developer keys are public only; private keys
  never leave their owners.
- **Revocation**: app removes a key from the key file and restarts the patched adbd
  if needed. "Remove all" rewrites the file with the app key only.
- **No root**: the patched adbd runs as `shell`, same as the system adbd. It gives
  a shell, not root.

## 9. Failure modes

| Failure | Response |
|---|---|
| Patched adbd dies | Restart via system adbd if reachable; otherwise show an explicit error with recovery steps |
| System adbd returns to TCP | Watchdog sets `ADB_ENABLED=0` if "Keep system adb off" is on; otherwise shows the residual |
| `ADB_ENABLED=0` does not stop adbd | Move to a random port, log residual, keep patched adbd as the stable endpoint |
| VPN address changes | Restart patched adbd with the new address; loopback stays |
| VPN down | Remove VPN listener; app still controls via loopback |
| App key lost | Re-pair via notification; regenerate key; rewrite key file |
| Developer key leaked | Remove from key file; restart patched adbd; revoke on the developer machine |
| Doze kills the app | Battery exemption prompt; foreground service; `START_STICKY`; watchdog |
| OEM blocks `pm grant` | Show an explicit error: the app cannot self-grant; link the upstream issue and the manual `adb shell pm grant` steps |
| Reboot on cellular | Probe 5555, last port, TLS on Wi-Fi, `persist.adb.tcp.port`; otherwise show the Wi-Fi/USB recovery steps |

The app never silently falls back. If the patched adbd cannot start, bind its
listeners, or dies without a restart path, the app shows the exact failure, the
last log line, and the recovery steps (connect Wi-Fi once, connect USB once, or
reinstall the patched adbd). It does not enable a proxy or leave the system adbd in
a different mode.

## 10. Testing

- Unit: QR payload parser and signature verification, key file writer, policy
  evaluator, address selection, watchdog state machine.
- Integration: patched adbd on an emulator, two listeners, auth with two keys,
  `shell:`/`sync:`, policy allow/deny, key reload.
- Device matrix: Pixel, Samsung, OnePlus; Tailscale and WireGuard; reboot,
  Wi-Fi↔cellular, USB attach/detach, Doze.
- CI: `./gradlew test assembleDebug`; no device tests in CI.

## 11. Phasing

- **Phase 0 — probes**: `pm grant`, `ADB_WIFI_ENABLED=0`, `ADB_ENABLED=0`,
  5555 at boot, `persist.adb.tcp.port`, TLS port on Wi-Fi, patched adbd as
  shell, QR pairing, VPN bind/rebind. Output: a report, no production code.
- **Phase 1 — control plane**: stock system adbd for bootstrap, notification
  pairing, network gating, UI. Development milestone only; the app fails
  explicitly if the patched adbd is unavailable.
- **Phase 2 — patched adbd**: build, bundle, deploy, two listeners, key
  provisioning, QR pairing, policy and logs, watchdog.
- **Phase 3 — depth**: per-service and per-command policy, connection log
  export, recovery hardening.

## 12. Open risks

- Reboot on cellular with no system adbd TCP listener: the app needs one
  Wi-Fi connection to bootstrap. This is a manual recovery step, not a blocker.
- OEM blocks `pm grant WRITE_SECURE_SETTINGS`.
- Plain listener remains when "Keep system adb off" is off and `is_usb=false`
  or `service.adb.tcp.port` is set; the app shows it as a residual.
- `ADB_ENABLED=0` may not stop adbd on some OEMs; move the system adbd to a
  random port and record the residual.
- After reboot with `ADB_ENABLED=0`, the app must set `ADB_ENABLED=1` to start
  the system adbd for bootstrap; the TCP listener still depends on `is_usb`.
- Patched adbd is killed by LMK/Doze; no restart path if the system adbd is
  USB-only.
- No silent fallback: if the patched adbd cannot run, the app shows an explicit
  error and stops instead of falling back to a proxy.
- Android 15/16 foreground service limits.
- AOSP adbd cross-compile and patch maintenance across releases.
- VPN app excludes the app from the tunnel, so loopback still works but the VPN
  listener is unreachable.
- QR pairing needs a companion CLI on the developer machine.

## 13. Provenance

Inspired by [mouldybread/adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)
(MIT). The patched adbd is derived from AOSP `packages/modules/adb` (Apache 2.0);
its `NOTICE` and source offer ship with the app. The app's own code is MIT.
