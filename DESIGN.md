# adb-anywhere — Design

Status: implemented; records the decisions the code follows
Date: 2026-11-06
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
- A sandbox for `shell`. Call policy is policy and audit, not isolation
  (see §8 and `docs/FIREWALL.md`).
- Filtering the app's own loopback channel.
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
11. **A per-app VPN that excludes the app does not deliver inbound packets to
    its UID.** The relay listens in the app's UID, so an excluded app cannot
    accept on the VPN address even though the bind succeeds. The app detects the
    exclusion and fails with an instruction instead of reporting a listener that
    cannot be reached.

## 5. Product decisions

| # | Decision | Choice |
|---|---|---|
| D1 | Device support | Stock Android 12+, no root |
| D2 | Data plane | Two listener modes. Firewall off: one patched `adbd` with `127.0.0.1:<portA>` and `<vpn-ip>:<portB>`. Firewall on: the patched `adbd` binds only `127.0.0.1:<portA>` and the app relay owns `<vpn-ip>:<portB>` |
| D3 | App key | Ephemeral RSA keypair generated on first run; private key in app-private storage; public key in the patched adbd key file |
| D4 | Developer machine pairing | QR or pasted public key from the developer machine; app confirms the fingerprint; user labels the machine; no pairing listener on the phone |
| D5 | System adbd lifecycle | Start writes `ADB_ENABLED=1` when it reads 0, then tries the system adbd's existing TCP listeners; on Wi-Fi it falls back to `ADB_WIFI_ENABLED=1` even when it already reads 1. Stop stops only the patched adbd; the opt-in Settings switch "Turn system adb off on Stop" makes Stop write `ADB_ENABLED=0` |
| D6 | Watchdog | Keeps `ADB_WIFI_ENABLED=0`; probes 5555 on every tick and reports the open listener as the §8 residual warning; restarts the patched adbd through the system adbd when reachable; never writes `ADB_ENABLED` (only Start does, and only when it reads 0) |
| D7 | Policy | The app enforces policy, not the daemon. The optional relay parses `A_OPEN` and applies the IP allowlist, the foreground-app allowlist and call rules. The patched `adbd` stays policy-free; the VPN ACL is the outer boundary |
| D8 | UI stack | Kotlin, Jetpack Compose, Material 3; package `cz.slush.adbanywhere` |
| D9 | Bootstrap pairing | One `RemoteInput` notification: reply with `code:port` from the open dialog |
| D10 | Patched adbd transports | TCP only; USB and vsock disabled |
| D11 | Settings UI | Shows the current ADB state, any warning in the UI and in the persistent foreground notification, and the opt-in "Turn system adb off on Stop" switch |
| D12 | Failure policy | Explicit error and recovery steps; no silent fallback and no random-port redirect |
| D13 | Firewall placement | App-side Kotlin relay in `ControlService`; byte-transparent; ADB key auth stays end-to-end between the developer machine and `adbd` |
| D14 | Listener ownership | Exactly one component owns `<vpn-ip>:<portB>`: the patched `adbd` when the firewall is off, the app relay when it is on. The open port, not a pref, decides which |
| D15 | Listener mode persistence | `firewallEnabled` is the intent; `daemonFirewallMode` is the mode the running daemon was started with. A mismatch restarts the daemon and the relay |
| D16 | Port range | The loopback and VPN ports are below 32768, outside Android's ephemeral range, and never 5555, the system adbd's plain listener |
| D17 | State publication | `ControlState` is the only UI source of truth. Each listener publishes its own state, `setup()` never resets a live listener's state, and `firewallDown` is derived |
| D18 | Restart coordination | One daemon identity (`--listen` in `ps`, `/proc` fallback), one restart-script naming scheme, a PID marker, and a wait for the predecessor |

## 6. Architecture

Two ADB endpoints and one app process, in two listener modes:

| Component | Runs as | Listener, firewall off | Listener, firewall on | Role |
|---|---|---|---|---|
| System `adbd` | `shell` (retail) | USB (left on); TLS before `ADB_WIFI_ENABLED=0`; TCP 5555 only on some devices | same | Bootstrap; key auth; system services |
| Patched `adbd` | `shell` | `127.0.0.1:<portA>`, `<vpn-ip>:<portB>` | `127.0.0.1:<portA>` | Only ADB endpoint for the app and developer machines; key file and logs |
| App relay | app uid | none | `<vpn-ip>:<portB>` | Optional firewall: accept, policy, relay to loopback |
| App | app uid | none | none | Control plane: deploys, keys, watchdog, UI, audit |
| Developer machine `adb` | host | none | none | Client |

The patched adbd is AOSP `adbd` vendored under `adbd/`, built as a standalone
CMake subproject. It is the only ADB endpoint for a developer machine while the
firewall is off; with the firewall on, the app relay is the only endpoint and
forwards to the daemon's loopback listener. The VPN port has exactly one owner in
either mode.

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
`restore:`, `disable-verity:` and `enable-verity:`. The daemon has no per-key
policy or filtering layer: every key in the key file may open every service the
relay allows. Filtering lives in the app relay (`docs/FIREWALL.md`), not in the
daemon.

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
- The app never stops the system adbd by default.  Writing `ADB_ENABLED=0`
  removes the USB recovery path and, on devices where the pairing store is
  tied to it, can lose the paired key.  The opt-in Settings switch makes this
  explicit: off by default, so a Stop leaves ADB and its paired key alone.
- Bootstrap: after pairing, the app key is in the system adbd's `user_keys`.
  Start writes `ADB_ENABLED=1` when it reads 0 (safe: it does not delete the key)
  and then tries the system adbd's existing TCP listeners: the TLS property, the
  last TLS port, the plain 5555 listener and mDNS.  On Wi-Fi, if none answers,
  it writes `ADB_WIFI_ENABLED=1` even when it already reads 1, waits 900 ms and
  retries.  On mobile data the system adbd has no TCP listener unless the plain
  listener was enabled over USB (`adb tcpip 5555`) or root; the app fails with that
  instruction instead of waiting for Wi-Fi wireless debugging.
- The patched adbd ignores both toggles.

### Start/Stop ownership

Start writes `ADB_ENABLED=1` only when it reads 0, because the write starts the
system adbd and does not delete the paired key.  It then tries the system adbd's
existing TCP listeners and, on Wi-Fi, falls back to `ADB_WIFI_ENABLED=1`, waits
900 ms and retries; the write is not skipped when the setting already reads 1,
because the framework can have the setting on while its TLS server is down.  Stop
stops only the patched adbd; the system wireless debugging and its paired keys
survive as the recovery path.  Stop never writes `ADB_ENABLED` unless the opt-in
switch is on.

The Settings switch **Turn system adb off on Stop** is the opt-in ownership:

- Off (default): Start writes `ADB_ENABLED=1` only when it reads 0, and Stop
  never writes it, so the system adb and its paired key stay.
- On: Start writes `ADB_ENABLED=1` when it reads 0, and Stop writes
  `ADB_ENABLED=0` and `ADB_WIFI_ENABLED=0`.  Android may drop the paired key
  when ADB is turned off, so the next Start can need a new pairing code.

| Action | `ADB_ENABLED` | `ADB_WIFI_ENABLED` | Patched adbd |
|---|---|---|---|
| Start, patched adbd down | `1` when off | `1` only in the Wi-Fi fallback | deploy |
| Start, patched adbd up | unchanged | unchanged | keep running |
| Running | unchanged | `0` after harden | keep running |
| Stop, switch off | unchanged | unchanged | stop |
| Stop, switch on | `0` | `0` | stop |
| Boot after Start | unchanged | `1` only in the Wi-Fi fallback | deploy |
| Boot after Stop | unchanged | unchanged | not running |

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

### Listener modes

`firewallEnabled` selects the mode. The app never runs both listeners on the VPN
port.

| Firewall | Patched `adbd` listens on | App listens on | Developer machine connects to |
|---|---|---|---|
| off | `127.0.0.1:<portA>`, `<vpn-ip>:<portB>` | nothing | `adbd` |
| on | `127.0.0.1:<portA>` | `<vpn-ip>:<portB>` | the app relay, which connects to `adbd` loopback |

1. The VPN port has one owner. Before the relay binds, the app checks whether the
   port is open and who owns it: the relay itself (reuse it), the patched `adbd`
   (restart it loopback-only), or Android's legacy plain listener (send `usb:` to the
   system adbd, reconnect, retry). The port, not a pref, is the authority.
2. `daemonFirewallMode` records the mode the running daemon was started with.
   `firewallEnabled != daemonFirewallMode` forces a restart, because a daemon started
   in the other mode holds the wrong listener set even when the persisted intent
   already matches.
3. A mode change stops the relay first, then restarts the daemon, then starts the
   relay. The app process and the UI stay up; active external sessions drop.
4. The relay binds the exact VPN address, never `0.0.0.0`, and sets `SO_REUSEADDR`
   so a `TIME_WAIT` socket from the previous instance does not block the new bind. A
   live listener still blocks it.
5. The relay is the only component that can fail without killing ADB: the loopback
   channel is independent, so a relay failure is an error state with a recovery step,
   not a stopped daemon.

### Lifecycle invariants

Each invariant exists because its violation produced a real defect; the commit column
is the fix that established it. A change that breaks one needs a test showing the new
behavior is still correct.

| # | Invariant | Prevents | Fix |
|---|---|---|---|
| I1 | Remove a fallback last. Every step that removes a recovery path (turning the system wireless debugging off, closing the plain listener) runs after the replacement is verified. | A relay bind failure after `ADB_WIFI_ENABLED=0` left the phone with no reachable ADB endpoint | `0803d46` |
| I2 | One owner per port. Before binding, identify the current owner; never treat our own listener as foreign. | `vpnPortBusy` saw the relay on the VPN port, restarted the daemon and waited 10 s for a port the relay owned | `d22a63e` |
| I3 | State has one writer. A listener publishes its own state through one function; `setup()` preserves live fields and republishes them; derived flags live in `ControlState`. | Home showed "ADB is reachable" while the Firewall tab showed "not listening" | `7e8a4df`, `55d9bbf`, `74bb129` |
| I4 | Persisted is not actual. `paired`, `setupComplete` and `daemonFirewallMode` are hints; validate them against `user_keys`, the open port and the listener before use. | `paired=true` with only the host key in `user_keys` reported "wireless debugging is not reachable" | `77191da` |
| I5 | Re-assert the effect, not the setting. Write `adb_wifi_enabled=1` even when it reads 1; the framework's TLS server can be down while the setting is on. | Bootstrap wrote nothing and the TLS server stayed down | `4b5596d` |
| I6 | Intent wins over deferred work. Every cleanup and every queued setup re-checks `userWantsRunning` under `lifecycleLock`; a stop uses `stopSelf(startId)`. | A quick Stop→Start lost the foreground notification and the daemon | `83b5fb9` |
| I7 | One identity per process. The service and the activity share one `AppKeyStore` identity; pairing uses one atomic `code:port` reply. | `CERTIFICATE_AND_PRIVATE_KEY_MISMATCH`; a code from one dialog paired with a port from another | `77191da`, `83b5fb9` |
| I8 | One daemon identity and one restart path. The daemon is found by `--listen` in `ps` (`/proc` fallback); the restart script has a unique name and a PID marker; a new start waits for the predecessor. | A fixed script path truncated a running restart; a start verified the daemon that was about to die; the `/proc` scan took 40 s | `44a4ca9`, `68d48b7`, `d5a6706`, `2c76489` |
| I9 | No silent fallback. A failure is an explicit error with the recovery step; never another port, never another listener, never a proxy. | An occupied port silently moved the endpoint; the app connected to Android's TLS listener instead | `21e2280`, `0803d46` |
| I10 | Custom ports stay out of the ephemeral range and off the system adbd's 5555. Both ports are below 32768 and not 5555, and `setup()` rejects an older pref that violates either. | The phone used 43935 for both wireless debugging and the app endpoint | `21e2280` |
| I11 | Listener and prompt lifecycle are serialized. `startFirewall()`, `stopFirewall()` and the relay rebind share `relayLock`; a stop bumps an epoch that invalidates an in-flight start; the prompt map is concurrent and every prompt path releases its slot. | Two starts bound the VPN port at once; a retrying start bound after a stop; a cancelled prompt left `pending()` true and suspended the app-allowlist grace timer | `74bb129` |
| I12 | The watchdog never writes `ADB_ENABLED`. Hardening is bound to the running daemon, not to the app process; a stop leaves wireless debugging alone. | Stop wrote `ADB_ENABLED=0` and dropped the paired key | `4b5596d` |

### Start and stop order

Start:

1. Validate the ports (below 32768) and the persisted intent.
2. Bootstrap the system adbd: enable ADB when it reads 0, try its existing
   listeners, then the Wi-Fi fallback.
3. Deploy the patched `adbd` with the listener set for the current mode and verify
   loopback (and the VPN listener when the firewall is off).
4. Start the relay and verify the VPN listener (when the firewall is on).
5. Turn the system wireless debugging off. This is the last step: it removes the
   fallback.
6. Publish Running and start the watchdog.

Stop:

1. Stop the relay.
2. Stop the patched `adbd`.
3. Only with the opt-in switch: write `ADB_ENABLED=0` and `ADB_WIFI_ENABLED=0`.
4. Publish Stopped and remove the foreground notification.

### Watchdog

The app keeps the system wireless debugging off while the patched adbd runs.
A stop leaves it alone: the system wireless debugging and its paired keys are the
recovery path. In the normal case (`is_usb=true`, `service.adb.tcp.port` empty) the
system adbd has no TCP listener, so there is nothing to monitor or turn off.

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
- The watchdog never writes `ADB_ENABLED`; only Start does, and only when it
  reads 0. If the system adbd is off and the app cannot write it, bootstrap fails
  with explicit recovery steps and no random-port redirect.

## 7. Flows

### Start and stop

1. The status card has a single toggle: ▶️ starts the daemon, 🛑 stops it.
2. The choice is stored in device-protected prefs as the user's last intent.
   A fresh install defaults to stopped; an install that already completed setup
   defaults to running, so an update keeps the daemon supervised.
3. Nothing brings the daemon up without that intent: opening the app, a
   reboot, a sticky restart and the watchdog all respect it.
4. Start finds every vendored adbd through `ps` by its `--listen` argument
   (`/proc` as the fallback), kills it, waits for it to disappear and only then
   starts its own. A detached restart from an earlier process is waited for first.
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
3. App posts the pairing notification asking for `code:port`.
4. User opens Settings → Developer options → Wireless debugging → Pair device with
   pairing code.
5. User pulls the shade over the dialog and replies once with `code:port`.
6. App pairs with the system adbd over loopback and stores its key.
7. App self-grants `WRITE_SECURE_SETTINGS` over ADB.
8. App extracts the patched adbd for the device ABI, pushes it to
   `/data/local/tmp/adb-anywhere/`, verifies SHA-256, and `chmod 755`.
9. App writes its public key to the patched adbd key file.
10. App starts the patched adbd with `--listen tcp:127.0.0.1:<portA>` and, when
    the firewall is off and the VPN is up, `--listen tcp:<vpn-ip>:<portB>`.
11. App verifies loopback (and the VPN listener when the firewall is off).
12. With the firewall on, the app starts the relay on `<vpn-ip>:<portB>`, verifies
    it and publishes its state.
13. App sets `ADB_WIFI_ENABLED=0` to stop the system TLS server. This is the last
    step, so the fallback exists until the new path is verified.

### Port discovery

- Primary: `getprop service.adb.tls.port` (`adbd_prop`, readable).
- Fallback: `NsdManager` discovery of `_adb-tls-connect._tcp`.
- Last resort: the user enters the port shown in Settings.
- The plain target port is chosen by the app and set with `tcpip:<port>`;
  it is stored in prefs and re-applied after boot.

### Boot

1. `BootReceiver` starts the service on `BOOT_COMPLETED` and
   `LOCKED_BOOT_COMPLETED`, but only when the user last started it.
2. Service enables ADB when it reads 0, then probes the system adbd's existing
   TCP listeners: `5555`, the last known port, the TLS property and mDNS.
3. If one answers, start the patched adbd, verify, then set `ADB_WIFI_ENABLED=0`.
4. If none answers and Wi-Fi is up, write `adb_wifi_enabled=1` (even when it
   already reads 1), wait 900 ms, resolve the TLS port over mDNS and retry. This is
   a manual recovery step, not a blocker.
5. On mobile data with no plain listener, fail with the explicit instruction:
   connect Wi-Fi once, or run `adb tcpip 5555` over USB.
6. `adb_allowed_connection_time=0` is applied with the bootstrap and every
   watchdog tick.

### Network change

1. `NetworkCallback` fires. The app coalesces the callback pair and waits for
   the new tunnel to settle before it touches the daemon.
2. If the VPN address changed: with the firewall off, restart the patched adbd with
   the new address and keep the loopback listener; with the firewall on, rebind the
   relay and do not restart the daemon. A change that arrives during a setup is
   reconciled by the serialized setup, not dropped.
3. If the VPN is gone, remove the VPN listener; the app keeps the loopback
   listener.
4. The watchdog re-reads the VPN address, so a missed callback cannot leave the
   daemon on a stale address.
5. If the active transport is Wi-Fi or Ethernet and no VPN is up, the app
   switches the developer machine to the system TLS endpoint and shows it. If TLS
   is unavailable, the app shows an explicit error.

### Firewall toggle

1. The Settings switch stores `firewallEnabled` and sends `ACTION_APPLY_FIREWALL`.
2. `setup()` compares it with `daemonFirewallMode`. A change stops the relay,
   restarts the daemon with the new listener set and starts the relay.
3. Active external sessions drop; the app process, the UI and the loopback channel
   stay up.
4. If the VPN is down, the relay does not listen and the UI says why; the toggle
   never blocks the daemon.

### USB

1. `ACTION_USB_STATE` fires.
2. If the system adbd is on USB, it is not a network listener; the app records
   this state and the patched adbd is unaffected.
3. If the system adbd returns to TCP, the app shows an explicit warning that the
   plain listener is back. It never sets `ADB_ENABLED=0`.

## 8. Security model

- **Assets**: ADB shell access, the app key, developer machine keys.
- **Outer boundary**: the VPN ACL. With the firewall off, the patched adbd's VPN
  listener is only reachable by allowed VPN peers. With the firewall on, the app relay
  is the only inbound TCP endpoint and the IP allowlist is the first gate.
- **Inner boundary**: the patched adbd key file. Only the app and explicitly paired
  developer machines are accepted.
- **App channel**: loopback, independent of the VPN.
- **Relay**: byte-transparent, no second authentication, no sandbox. ADB key auth
  stays between the developer machine and `adbd`. The relay is a policy enforcement
  point, not a boundary against a determined shell user.
- **App process**: with the firewall on it is a listener. A per-app VPN that excludes
  the app, or a process kill, drops external sessions; the loopback channel and the
  recovery steps are unaffected.
- **System adbd**: USB (left on); not reachable over TCP after
  `ADB_WIFI_ENABLED=0`.
- **Residual**: on devices where the system adbd exposes 5555, show an explicit
  warning.  Unless the opt-in switch is on, the app never stops the system adbd; the
  user must do that outside the app, and the patched adbd may stop with it.
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
| Stale patched adbd after a crash | Start finds every adbd with `--listen` through `ps` (`/proc` fallback), waits for it to exit and aborts if it survives |
| System adbd returns to TCP | Watchdog shows an explicit warning; no `ADB_ENABLED` write |
| System adbd is off | Start writes `ADB_ENABLED=1` when it reads 0; if the write is unavailable or the system adbd still has no TCP listener, fail with explicit recovery steps |
| VPN address changes | Firewall off: restart the patched adbd with the new address. Firewall on: rebind the relay. Loopback stays |
| VPN down | Remove VPN listener; app still controls via loopback |
| Relay bind fails | Setup waits for the port, closes Android's legacy plain listener with `usb:` when it owns the port, and retries; if the port stays busy, fail with the port and the recovery step |
| Relay dies | Watchdog restarts it and reports Error if it cannot; active sessions drop |
| VPN excludes the app | Setup fails with an instruction to include ADB Anywhere in the VPN; no fallback listener |
| App process is killed | Service restart starts the relay again; prompts are lost and denied |
| Prompt not answered | 30 s timeout, deny, audit |
| Malformed ADB frame | Close the connection, audit, never crash the service |
| `UsageStats` missing | App allowlist cannot be enabled; the UI shows the grant action |
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
  selection, watchdog state machine, `ControlState.firewallDown`, the port guard,
  `AppKeyStore` concurrent first use of several instances, relay frame parsing,
  prompt state and audit ring.
- Integration: the vendored adbd's shared-core suite (`ADBD_TESTS=1`), two
  listeners, auth with two keys, `shell:`/`sync:`/`tcp:`/`reverse:`, key reload.
- Device matrix: Pixel, Samsung, OnePlus; Tailscale and WireGuard; reboot,
  Wi-Fi↔cellular, USB attach/detach, Doze.
- Device, firewall: toggle with a live session, `TIME_WAIT` rebind, mode change with
  the daemon already up, Stop→Start, process kill, VPN address change.
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
- **Phase 3 — depth**: implemented. The optional firewall, its IP/app allowlists,
  the call-policy model and the audit log are in `docs/FIREWALL.md`. Remaining:
  IPv6 allowlist entries, per-key defaults, audit export.

## 12. Open risks

- Reboot on cellular with no system adbd TCP listener: the app needs one
  Wi-Fi connection to bootstrap. This is a manual recovery step, not a blocker.
- OEM blocks `pm grant WRITE_SECURE_SETTINGS`.
- Plain listener remains when `is_usb=false` or `service.adb.tcp.port` is set;
  the app shows an explicit warning.
- The system adbd is off and the app cannot write `ADB_ENABLED` (no
  `WRITE_SECURE_SETTINGS`): bootstrap fails until the user turns USB debugging on.
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
- A per-app VPN that excludes the app does not deliver inbound packets to the
  relay, which runs in the app's UID; the app fails with an instruction instead of
  falling back to the direct listener.
- The relay runs in the app process: a process kill drops external sessions, and a
  pending prompt is lost and denied. The loopback channel and the recovery steps are
  unaffected.
- QR pairing needs a QR generator on the developer machine; `qrencode` is enough.

## 13. Provenance

Inspired by [mouldybread/adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)
(MIT). The patched adbd is vendored from AOSP `packages/modules/adb` (Apache 2.0)
and BoringSSL (ISC); the upstream commits, the patches and the `NOTICE` files are
in `adbd/`. The app's own code is MIT (see `LICENSE`); the vendored daemon stays
Apache 2.0 (see `adbd/LICENSE`), and third-party attributions are in `NOTICE`.
