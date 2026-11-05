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
| D4 | Developer machine pairing | QR or pasted public key from the developer machine; app confirms the fingerprint; user labels the machine; no pairing listener on the phone |
| D5 | System adbd lifecycle | `ADB_WIFI_ENABLED=0` after the patched adbd runs; Start writes `ADB_ENABLED=1` when needed, Stop writes `0`; the app never stops it while the patched adbd must run |
| D6 | Watchdog | Keeps `ADB_WIFI_ENABLED=0`; probes 5555 on every tick and reports the open listener as the §8 residual warning; restarts the patched adbd through the system adbd when reachable |
| D7 | Policy | Patched adbd enforces per-key and per-service rules; VPN ACL is the outer boundary |
| D8 | UI stack | Kotlin, Jetpack Compose, Material 3; package `cz.slush.adbanywhere` |
| D9 | Bootstrap pairing | Two `RemoteInput` notifications: the six-digit code first, then the port |
| D10 | Patched adbd transports | TCP only; USB and vsock disabled |
| D11 | Settings UI | Shows the current ADB state and any warning in the UI and in the persistent foreground notification; no system-adb switch |
| D12 | Failure policy | Explicit error and recovery steps; no silent fallback and no random-port redirect |

## 6. Architecture

Three ADB processes and one app process:

| Component | Runs as | Listener | Role |
|---|---|---|---|
| System `adbd` | `shell` (retail) | USB (left on); TLS before `ADB_WIFI_ENABLED=0`; TCP 5555 only on some devices | Bootstrap; key auth; system services |
| Patched `adbd` | `shell` | `127.0.0.1:<portA>` and `<vpn-ip>:<portB>` | Only ADB endpoint for the app and developer machines; key file and logs |
| App | app uid | none | Control plane: deploys, keys, watchdog, UI |
| Developer machine `adb` | host | none | Client |

The patched adbd is AOSP `adbd` vendored under `adbd/`, built as a standalone
CMake subproject. It is the "internal proxy" in the sense that it is the only
endpoint a developer machine can reach. There is no separate proxy process.

The upstream trees under `adbd/vendor/` are pristine. The patches in
`adbd/patches/` are applied to a build copy by `adbd/build.sh`, so a re-vendor is
a copy, never a merge.

### Patch set

| Patch | Purpose |
|---|---|
| `daemon/main.cpp`: accept `--listen`, `--keys`, `--log`, `--pid-file` | Bind the exact addresses and key file the app gives it |
| `daemon/main.cpp`: skip USB, vsock, mDNS, watchdog, jdwp, trade-in, privilege dropping | Never touch USB, vsock or the system adbd |
| `daemon/auth.cpp`: no TLS or pairing | RSA key-file authentication only |
| `daemon/services.cpp`: keep `shell:`, `exec:`, `abb_exec:`, `sync:`, `tcp:`, `reverse:`, `framebuffer:` | What developer machines, `adb install`, `adb reverse` and screenshots need |
| `daemon/services.cpp`: reject `abb:`, `jdwp:`, `root:`, `remount:`, `reboot:`, `usb:`, `tcpip:` and the test socket services | The patched adbd must not touch the system adbd or reboot |
| `transport.cpp`, `adb.cpp`, `adb_auth.h`: no TLS handshake | Plain ADB over the VPN |
| `compression_utils.h`: keep Zstd, stub Brotli/LZ4 | Compressed `adb push`/`adb pull` without vendoring all three codecs |
| `transport.cpp`: advertise only the implemented features | `apex`, `abb_exec`, `sendrecv_v2_zstd` and `devraw` are advertised; `abb`, Brotli, LZ4, mDNS, `track-app`, `remount_shell`, `app_info` and `devicetracker_proto_format` are not |
| `adb_trace.cpp`, `socket_spec.cpp`: null log tag, explicit-address bind | The standalone daemon's two small robustness fixes |

Multi-listener is not a large patch. `setup_adb` already loops over the address
list and `init_transport_socket_server` starts one thread per address
(`vendor/adb/daemon/main.cpp`, `vendor/adb/daemon/transport_socket_server.cpp`).
The comma-separated list already exists in `service.adb.listen_addrs`. The patch
only changes where the list comes from and skips USB, vsock, and mDNS; the listener
count itself is free. The large work was the cross-compile, which is now a checked-in
build script and vendored dependencies, not a per-release patch.

Build targets: `arm64-v8a`, `armeabi-v7a`, `x86_64` (`adbd/build.sh`). Bundle in
the APK as assets. AOSP `adbd` is Apache 2.0; ship `NOTICE` and the source offer.

Not implemented: USB, vsock, TLS, pairing, mDNS, `jdwp:`, `track-jdwp`,
`track-app`, `abb:` (the shell-protocol variant), `root:`, `remount:`, `reboot:`,
`restore:`, `disable-verity:` and `enable-verity:`. The daemon also has no per-key policy or filtering layer
yet, so every key in the key file may open every supported service. The app no
longer writes a `policy.tsv`; filtering will get its own design.

### Key provisioning

- First run: the app generates an ephemeral RSA keypair. The private key stays in
  app-private storage; the public key is written to the patched adbd key file before
  it starts.
- The patched adbd key file contains the app key and the developer keys the user
  adds. Unknown keys are never prompted for or auto-accepted.
- Developer machine keys are added by QR or pasted public key. Each key has a label.
- The app rewrites the key file through the patched adbd's `shell:`. It never needs
  a second listener.
- On reinstall, the app generates a new keypair and rewrites the key file. Old keys
  are removed.

### Developer machine pairing

The developer machine shows a QR with the raw public key; the app scans it or
accepts a paste. No companion app is needed.

1. The developer machine runs `qrencode -t ansiutf8 < ~/.android/adbkey.pub`, or
   any QR generator, and shows the QR.
2. The app scans the QR, or the user pastes the contents of
   `~/.android/adbkey.pub`. If the file is binary, the user pastes its base64
   encoding.
3. The app decodes the base64 DER, validates it as an RSA public key, and shows
   the SHA-256 fingerprint.
4. The user confirms the fingerprint and labels the machine.
5. The app appends the key to the patched adbd key file with the label.
6. The developer machine runs `adb connect <vpn-name>:<portB>` with its private key.

The public key is public; no signature or nonce is needed to add it. The defense is
the fingerprint confirmation and the label. A malicious QR can only add the key it
contains, and the user sees the fingerprint before confirming. Replay is harmless
because the key is idempotent; removing the key revokes it.

### System adbd control

- Mandatory: `ADB_WIFI_ENABLED=0` after the patched adbd is running. The
  framework stops the system TLS server (`persist.adb.tls_server.enable=0`); no ADB
  call is needed. The system adbd stays on USB, which is not a network listener.
- The app never stops the system adbd while the patched adbd must run: on devices
  where init kills the adbd cgroup, stopping it would also kill the patched adbd.
  Stop is the only exception, after it has stopped the patched adbd.
- Bootstrap: Start enables `ADB_ENABLED=1` when needed; `ADB_WIFI_ENABLED=1` on
  Wi-Fi opens the TLS path, and the app reads `service.adb.tls.port` and connects.
- The patched adbd ignores both toggles.

### Start/Stop ownership

Start and Stop own the system adbd lifecycle. Start writes `ADB_ENABLED=1` only
when it is off, then leaves it on while the patched adbd runs. Stop stops the
patched adbd and writes `ADB_ENABLED=0`; boot after a stop re-asserts `0`, boot
after a start writes `1` and deploys the patched adbd. Each write happens only
when the value differs, so a Start or Stop does not re-enumerate USB when the
setting already has the target value. The app never stops the system adbd while it
needs the patched adbd: on devices where init kills the adbd cgroup, stopping it
would also kill the patched adbd. The security benefit is therefore at Stop, not
during a run.

| Action | `ADB_ENABLED` | `ADB_WIFI_ENABLED` | Patched adbd |
|---|---|---|---|
| Start, patched adbd down | `1` | `1` on Wi-Fi | deploy |
| Start, patched adbd up | unchanged | `0` | keep running |
| Running | `1` | `0` | keep running |
| Stop | `0` | `0` | stop |
| Boot after Start | `1` | `1` | deploy |
| Boot after Stop | `0` | `0` | not running |

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
- Probe `127.0.0.1:5555` on every tick and show an explicit warning when it is
  open. Never set `ADB_ENABLED=0`: stopping the system adbd would also kill the
  patched adbd on devices that share its cgroup.
- Poll `127.0.0.1:<portA>` every 60 s while the screen is on and every 15 min
  via `AlarmManager.setExactAndAllowWhileIdle` otherwise.
- If `<portA>` is down, restart the patched adbd through the system adbd if it is
  reachable, otherwise show an explicit error with recovery steps.
- Register `UsbManager.ACTION_USB_STATE` (sticky) and
  `ConnectivityManager.NetworkCallback`.
- Backoff and hysteresis; never fight an explicit user "off".
- The app never writes `ADB_ENABLED`; if the system adbd is off, bootstrap
  fails with explicit recovery steps and no random-port redirect.

## 7. Flows

### Start and stop

1. The status card has a single toggle: ▶️ starts the daemon, 🛑 stops it.
2. The choice is stored in device-protected prefs as the user's last intent.
   A fresh install defaults to stopped; an install that already completed setup
   defaults to running, so an update keeps the daemon supervised.
3. Nothing brings the daemon up without that intent: opening the app, a
   reboot, a sticky restart and the watchdog all respect it.
4. Start kills every vendored adbd it finds through `/proc` by its
   `--listen` argument, waits for it to disappear and only then starts its own.
   This needs no lock and no adbd change; a survivor aborts the start instead of
   leaving two daemons.
5. Opening the app while it runs re-checks the daemon; it never overrides a stop.
6. Stop takes the setup mutex, so a stop during a deployment waits for the
   deployment and then stops the daemon; the loopback port confirms the stop.
7. Key and settings changes are stored immediately and applied on the next start
   while stopped.

### First run

1. User installs the app, opens it and taps ▶️ Start.
2. App requests notification permission.
3. App posts the pairing notification asking for the six-digit code.
4. User opens Settings → Developer options → Wireless debugging → Pair device with
   pairing code.
5. User pulls the shade over the dialog, enters the code, then the port.
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
   `LOCKED_BOOT_COMPLETED`, but only when the user last started it.
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

1. `NetworkCallback` fires. The app coalesces the callback pair and waits for
   the new tunnel to settle before it touches the daemon.
2. If the VPN address changed, restart the patched adbd with the new address and
   keep the loopback listener. A change that arrives during a setup is reconciled
   by the serialized setup, not dropped.
3. If the VPN is gone, remove the VPN listener; the app keeps the loopback
   listener.
4. The watchdog re-reads the VPN address, so a missed callback cannot leave the
   daemon on a stale address.
5. If the active transport is Wi-Fi or Ethernet and no VPN is up, the app
   switches the developer machine to the system TLS endpoint and shows it. If TLS
   is unavailable, the app shows an explicit error.

### USB

1. `ACTION_USB_STATE` fires.
2. If the system adbd is on USB, it is not a network listener; the app records
   this state and the patched adbd is unaffected.
3. If the system adbd returns to TCP, the app shows an explicit warning that the
   plain listener is back. It never sets `ADB_ENABLED=0`.

## 8. Security model

- **Assets**: ADB shell access, the app key, developer machine keys.
- **Outer boundary**: the VPN ACL. The patched adbd's VPN listener is only
  reachable by allowed VPN peers.
- **Inner boundary**: the patched adbd key file. Only the app and explicitly paired
  developer machines are accepted.
- **App channel**: loopback, independent of the VPN.
- **System adbd**: USB (left on); not reachable over TCP after
  `ADB_WIFI_ENABLED=0`.
- **Residual**: on devices where the system adbd exposes 5555, show an explicit
  warning. The app never stops the system adbd; the user must do that outside the
  app, and the patched adbd may stop with it.
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
| Stale patched adbd after a crash | Start kills every adbd with `--listen` found through `/proc`, waits for it to exit and aborts if it survives |
| System adbd returns to TCP | Watchdog shows an explicit warning; no `ADB_ENABLED` write |
| System adbd is off | Bootstrap fails with explicit recovery steps; the app does not enable it |
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

- Unit: QR payload parser and public-key validation, key file writer, address
  selection, watchdog state machine.
- Integration: the vendored adbd's shared-core suite (`ADBD_TESTS=1`), two
  listeners, auth with two keys, `shell:`/`sync:`/`tcp:`/`reverse:`, key reload.
- Device matrix: Pixel, Samsung, OnePlus; Tailscale and WireGuard; reboot,
  Wi-Fi↔cellular, USB attach/detach, Doze.
- CI: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`;
  no device tests in CI.

## 11. Phasing

- **Phase 0 — probes**: `pm grant`, `ADB_WIFI_ENABLED=0`, `ADB_ENABLED=0`,
  5555 at boot, `persist.adb.tcp.port`, TLS port on Wi-Fi, patched adbd as
  shell, QR and paste pairing, VPN bind/rebind. Output: a report, no production code.
- **Phase 1 — control plane**: stock system adbd for bootstrap, notification
  pairing, network gating, UI. Development milestone only; the app fails
  explicitly if the patched adbd is unavailable.
- **Phase 2 — vendored adbd**: build from `adbd/`, bundle, deploy, two listeners,
  key provisioning, QR and paste pairing, logs, watchdog.
- **Phase 3 — depth**: per-service and per-command policy, connection log
  export, recovery hardening. The optional firewall, its IP/app allowlists, the
  call-policy model and the audit log are designed and implemented in
  `docs/FIREWALL.md`.

## 12. Open risks

- Reboot on cellular with no system adbd TCP listener: the app needs one
  Wi-Fi connection to bootstrap. This is a manual recovery step, not a blocker.
- OEM blocks `pm grant WRITE_SECURE_SETTINGS`.
- Plain listener remains when `is_usb=false` or `service.adb.tcp.port` is set;
  the app shows an explicit warning.
- System adbd is off: the app cannot bootstrap until the user turns USB debugging
  or wireless debugging on; this is a manual recovery step.
- Patched adbd is killed by LMK/Doze; no restart path if the system adbd is
  USB-only.
- No silent fallback: if the patched adbd cannot run, the app shows an explicit
  error and stops instead of falling back to a proxy or redirecting to a random
port.
- Android 15/16 foreground service limits.
- Vendored AOSP adbd re-vendor across releases: the patch set is small, but the
  vendored trees (`adb`, `libbase`, `libcutils`, `libcrypto_utils`, `libasyncio`,
  `adbd_auth`, `liblog`, BoringSSL) and the BoringSSL build move with it. The
  upstream commits are recorded in `adbd/README.md`; a re-vendor is a copy and a
  patch reapply, not a merge.
- VPN app excludes the app from the tunnel, so loopback still works but the VPN
  listener is unreachable.
- QR pairing needs a QR generator on the developer machine; `qrencode` is enough.

## 13. Provenance

Inspired by [mouldybread/adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)
(MIT). The patched adbd is vendored from AOSP `packages/modules/adb` (Apache 2.0)
and BoringSSL (ISC); the upstream commits, the patches and the `NOTICE` files are
in `adbd/`. The app's own code is MIT (see `LICENSE`); the vendored daemon stays
Apache 2.0 (see `adbd/LICENSE`), and third-party attributions are in `NOTICE`.
