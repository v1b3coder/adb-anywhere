# adb-anywhere — Design

Status: implemented; records the decisions the code follows
Date: 2026-11-10
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

- Keep ADB reachable from the developer machine across sleep and network
  changes, including mobile data over a VPN, and redeploy it after a reboot when the
  system adbd can be reached once.
- Never expose plain ADB on an untrusted LAN.
- Make the app the only entity that controls the ADB listener and the only entity
  that decides which developer machines may connect.
- No root. No custom ROM. No permanent web server.

## 3. Non-goals

- Rooted devices, Magisk, KernelSU, custom ROMs.
- Replacing the system `adbd` on the system partition.
- A general-purpose VPN or firewall for other apps.
- A sandbox for `shell`. Call policy is policy and audit, not isolation
  (see §8 and `FIREWALL.md`).
- Filtering the app's own loopback channel.
- TV and set-top boxes.

## 4. Verified platform constraints

These are facts from AOSP `main`, not assumptions.

1. **The framework gates TLS wireless debugging on Wi-Fi.**
   `AdbDebuggingManager` sets `ADB_WIFI_ENABLED=0` on Wi-Fi disable,
   Wi-Fi disconnect, empty BSSID, or BSSID change
   (`AdbDebuggingManager.java:611-658`). The TLS server is toggled by
   `persist.adb.tls_server.enable` (`system_adbd_prop`, system-only) and
   publishes `service.adb.tls.port` (`adbd_prop`, readable) through Android 16;
   AOSP `main` delivers the port to the framework over the auth socket instead
   (`adbd_send_tls_server_port`, `adbd_auth.h:194`, `__INTRODUCED_IN(37)`).
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
9. **The TLS port is not readable by the app.**
   `service.adb.tls.port` is `adbd_prop`, and `system_internal_prop` adds
   `neverallow { domain -coredomain } adbd_prop:file no_rw_file_perms`; only
   `system_server` and the rest of `coredomain` read it, so `getprop` from the
   app returns empty. That empty value is the device evidence, not proof that a
   release stopped publishing the port. AOSP `main` after Android 16 stops
   setting it and delivers the port to the framework over the auth socket; that
   socket is a private `system_server` channel (`adbd`, `0660 system system`).
   The app's discovery path is mDNS (`_adb-tls-connect._tcp`), the same
   advertisement the host `adb` uses, with the port from the last verified
   bootstrap as a hint. `/proc/net/tcp` is not app-readable on the releases this
   app targets, so there is no socket-table fallback. Any app can advertise an
   mDNS service or listen on a loopback port, so a discovered port is only a
   candidate: it must speak TLS and pass the shell proof (I16) before the app
   trusts it.
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
12. **Binding the VPN address does not confine traffic to the VPN.** Linux
    accepts a packet for any local address on any interface, so a LAN host can
    address the VPN IP through the phone's Wi-Fi. A TCP handshake still needs the
    reply to reach the peer, and the reply follows the phone's routes; the relay
    therefore accepts only peers whose replies are routed into the tunnel
    (`FIREWALL.md` §5). The patched daemon's direct VPN listener cannot make that
    check.
13. **The system adbd's TLS key cannot be pinned.** adbd generates a random RSA
    key for its TLS server per process, and pairing exchanges no server key the
    app could keep. A TLS client certificate still signs the handshake transcript,
    so a signature given to a fake server cannot be replayed to the real one; the
    fake server is excluded by the shell proof instead (I16).
14. **A plain ADB token signature can be replayed.** The client signs only the
    20-byte token the server chose, not the connection. Whoever owns the port the
    client connects to can forward a token from a real plain listener and replay
    the signature there (I15).
15. **The VPN address is the VPN transport's address, not an interface guess.**
    `ConnectivityManager` reports the VPN networks and their `LinkProperties`; the
    app takes the address from those networks only. A scan of the Wi-Fi or cellular
    interfaces returns the LAN or carrier address when no VPN is up, and binding it
    would expose plain ADB outside the VPN.

## 5. Product decisions

| # | Decision | Choice |
|---|---|---|
| D1 | Device support | Stock Android 12+, no root |
| D2 | Data plane | Two listener modes. Firewall off: one patched `adbd` with the TLS listener `127.0.0.1:<portA>` and the plain listener `<vpn-ip>:<portB>`. Firewall on: the patched `adbd` binds only `127.0.0.1:<portA>` and the app relay owns `<vpn-ip>:<portB>` |
| D3 | App keys | Two ephemeral RSA keypairs generated on first use, private keys in app-private device-protected storage, excluded from backup and device transfer: the system key (paired with Android's adbd, bootstrap only) and the daemon key (trusted only by the patched adbd). See D20 |
| D4 | Developer machine pairing | QR or pasted public key from the developer machine; app confirms the fingerprint; user labels the machine; no pairing listener on the phone |
| D5 | System adbd lifecycle | An explicit Start, Restart or pairing writes `ADB_ENABLED=1` when it reads 0, then tries the system adbd's existing TCP listeners; on Wi-Fi it falls back to `ADB_WIFI_ENABLED=1` even when it already reads 1. Opening the app, a boot, an update, a sticky restart, a network change and the watchdog never write `ADB_ENABLED`: they re-check, and with ADB off they fail and wait for Start. Stop stops only the patched adbd; the opt-in Settings switch "Turn system adb off on Stop" makes Stop write `ADB_ENABLED=0` |
| D6 | Watchdog | Keeps `ADB_WIFI_ENABLED=0`; probes 5555 on every tick and reports the open listener as the §8 residual warning; restarts the patched adbd through the system adbd when reachable; checks the daemon key files against the app's copy and restores them; re-arms its alarm on every tick and backs off after a failure; retries a transient failure indefinitely at the backoff cap, also in a process that never reached Running; a failure that needs the user (a missing VPN, a port change, a missing permission, ADB turned off) waits for Try again, across process restarts too; never writes `ADB_ENABLED` |
| D7 | Policy | The app enforces policy, not the daemon. The optional relay parses `A_OPEN` and applies the IP allowlist, the foreground-app allowlist and call rules. The patched `adbd` stays policy-free; the VPN ACL is the outer boundary |
| D8 | UI stack | Kotlin, Jetpack Compose, Material 3; package `cz.slush.adbanywhere` |
| D9 | Bootstrap pairing | One `RemoteInput` notification: reply with `code:port` from the open dialog |
| D10 | Patched adbd transports | TCP only; USB and vsock disabled |
| D11 | Settings UI | Shows the current ADB state, any warning in the UI and in the persistent foreground notification, and the opt-in "Turn system adb off on Stop" switch. The System adb card shows only the system adbd's own state; the app's loopback channel is shown in the Ports card. The card re-reads when the tab is entered and when the app returns to the foreground, and the read runs off the main thread |
| D12 | Failure policy | Explicit error and recovery steps; no silent fallback and no random-port redirect |
| D13 | Firewall placement | App-side Kotlin relay in `ControlService`; byte-transparent; ADB key auth stays end-to-end between the developer machine and `adbd` |
| D14 | Listener ownership | Exactly one component owns `<vpn-ip>:<portB>`: the patched `adbd` when the firewall is off, the app relay when it is on. The open port, not a pref, decides which |
| D15 | Listener mode persistence | `firewallEnabled` is the intent; `daemonFirewallMode` is the mode the running daemon was started with. A mismatch restarts the daemon and the relay |
| D16 | Port range | The loopback and VPN ports are below 32768, outside Android's ephemeral range, and never 5555, the system adbd's plain listener |
| D17 | State publication | `ControlState` is the only UI source of truth. Each listener publishes its own state, `setup()` never resets a live listener's state, and `firewallDown` is derived |
| D18 | Restart coordination | One daemon identity (`--listen` in `ps`, `/proc` fallback), one restart-script naming scheme, a PID marker, and a wait for the predecessor |
| D20 | App channel and key separation | The patched adbd's loopback listener is TLS-only: the server key is generated by the app, pushed over a verified channel and pinned (SHA-256 of its SubjectPublicKeyInfo); only the daemon key may finish the handshake (`--tls-clients`), and ADB key auth then runs inside the channel. The relay tunnels each developer session through the same listener, so developer key auth stays end to end. The system key is used only with the system adbd; the daemon never trusts it. libadb is used only for pairing; every connection goes through the app's own client, which decides when a token is signed (I15) |
| D21 | Test hooks | The end-to-end hooks live in a debug-only receiver protected by `android.permission.DUMP`, which only the shell user holds. The exported launcher activity reads no intent extras |
| D19 | Ownership | Every value has one owner. The UI shows only the state its component publishes: the System adb card reads the system adbd's state, the Ports card reads the app's ports, and a probe names the owner it found. A persisted config has one writer and one read-modify-write entry point. A platform read runs off the UI thread, and the UI shows an explicit loading state. The UsageStats appop is checked before the app allowlist is enabled, and a revoked appop clears the foreground so the module fails closed. The app's own tooling is not exempt from the call policy |

## 6. Architecture

Two ADB endpoints and one app process, in two listener modes:

| Component | Runs as | Listener, firewall off | Listener, firewall on | Role |
|---|---|---|---|---|
| System `adbd` | `shell` (retail) | USB (left on); TLS before `ADB_WIFI_ENABLED=0`; TCP 5555 only on some devices | same | Bootstrap with the system key; system services |
| Patched `adbd` | `shell` | TLS `127.0.0.1:<portA>`, plain `<vpn-ip>:<portB>` | TLS `127.0.0.1:<portA>` | Only ADB endpoint for the app and developer machines; key files and logs |
| App relay | app uid | none | `<vpn-ip>:<portB>` | Optional firewall: accept, policy, tunnel to the TLS loopback listener |
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
| `daemon/main.cpp`: accept `--listen`, `--tls-listen`, `--tls-key`, `--tls-clients`, `--keys`, `--log`, `--pid-file`; no listener fallback; every listener binds at startup or the daemon exits | Bind the exact addresses and key files the app gives it; never a wildcard, 5555 or a late bind |
| `daemon/main.cpp`: skip USB, vsock, mDNS, watchdog, jdwp, trade-in, privilege dropping | Never touch USB, vsock or the system adbd |
| `daemon/auth.cpp`, `transport.cpp`, `adb.cpp`: TLS tunnel on `--tls-listen` | TLS 1.3 with the app's server key; the client certificate must be in `--tls-clients`; ADB key auth against `--keys` then runs inside TLS. No pairing, no TLS on the plain listener |
| `adb.cpp`, `adbd_auth.cpp`: pre-auth limits | 16 failed signatures or a second offered key close the transport; the unlisted-key log line is bounded |
| `daemon/services.cpp`: `reverse:` binds loopback only | A developer's `adb reverse` never opens a port on Wi-Fi or cellular |
| `daemon/services.cpp`: keep `shell:`, `exec:`, `abb_exec:`, `sync:`, `tcp:`, `reverse:`, `framebuffer:` | What developer machines, `adb install`, `adb reverse` and screenshots need |
| `daemon/services.cpp`: reject `abb:`, `jdwp:`, `root:`, `remount:`, `reboot:`, `usb:`, `tcpip:` and the test socket services | The patched adbd must not touch the system adbd or reboot |
| `compression_utils.h`: keep Zstd, stub Brotli/LZ4 | Compressed `adb push`/`adb pull` without vendoring all three codecs |
| `transport.cpp`: advertise only the implemented features | `apex`, `abb_exec` and `sendrecv_v2_zstd` are advertised; `abb`, Brotli, LZ4, mDNS, `track-app`, `remount_shell`, `app_info`, `devraw` and `devicetracker_proto_format` are not |
| `adb_trace.cpp`, `socket_spec.cpp`: null log tag, explicit-address bind | Explicit numeric addresses only; a wildcard (`0.0.0.0`, `::`, v4-mapped any) is refused after parsing, not by string |
| `daemon/main.cpp`: `umask(022)`; log and pid files `0600`, `O_NOFOLLOW` | Nothing the daemon or its children create is world-writable |

Multi-listener is not a large patch. `setup_adb` already loops over the address
list and `init_transport_socket_server` starts one thread per address
(`vendor/adb/daemon/main.cpp`, `vendor/adb/daemon/transport_socket_server.cpp`).
The comma-separated list already exists in `service.adb.listen_addrs`. The patch
only changes where the list comes from and skips USB, vsock, and mDNS; the listener
count itself is free. The large work was the cross-compile, which is now a checked-in
build script and vendored dependencies, not a per-release patch.

Build targets: `arm64-v8a`, `armeabi-v7a`, `x86_64` (`adbd/build.sh`). Bundle in
the APK as assets. AOSP `adbd` is Apache 2.0; ship `NOTICE` and the source offer.

Rejecting a service is not a security control: the daemon runs every service
as `shell`, so `exec:reboot`, `exec:setprop` or `abb_exec:` reach what `reboot:`,
`tcpip:` or `abb:` did. The rejections stop accidental client commands only;
the call policy and the key file are the controls.

Not implemented: USB, vsock, TLS on the plain listener, pairing, mDNS, `jdwp:`, `track-jdwp`,
`track-app`, `abb:` (the shell-protocol variant), `root:`, `remount:`, `reboot:`,
`backup:`, `restore:`, `disable-verity:`, `enable-verity:` and `dev-raw:`. The daemon has no per-key
policy or filtering layer: every key in the key file may open every service the
relay allows. Filtering lives in the app relay (`FIREWALL.md`), not in the
daemon.

### Key provisioning

- First use: the app generates two ephemeral RSA keypairs (D20) and the daemon's
  TLS server key. The private keys stay in app-private device-protected storage,
  excluded from cloud backup and device-to-device transfer.
- The system key (`appkey.pk8`) is what pairing puts into the system adbd's
  `user_keys`. It is used only to bootstrap and never written to the patched
  adbd's files.
- The patched adbd has three files, each written atomically (private temporary
  file, then `mv`) with mode `600`:
  - `adb_keys` (`--keys`): the daemon key and the developer keys the user adds.
  - `tls_clients` (`--tls-clients`): the daemon key only.
  - `tls_key.pem` (`--tls-key`): the TLS server key; the app pins its public key.
- Unknown keys are never prompted for or auto-accepted.
- Labels are comments in a line-based file written through a shell command:
  every control character becomes a space and the length is capped at 64.
- Developer machine keys are added by QR or pasted public key. Each key has a label.
- The app rewrites the key files through the patched adbd's pinned channel. It
  never needs a second listener.
- Every shell-uid process can edit these files. The watchdog compares them with
  the app's copy on every tick, restores them and restarts the daemon on a
  mismatch, and tells the user; it cannot prevent the edit.
- On reinstall, the app generates new keys and rewrites the key files. Old keys
  are removed.
- A daemon deployed by a build before D20 trusts the old single app key, which is
  now the system key. It is migrated once through its own shell: only while
  `127.0.0.1:5555` is closed (no plain system listener to replay a signature to),
  and only after the shell proof (I16).

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
- Bootstrap: after pairing, the system key is in the system adbd's `user_keys`.
  An explicit Start writes `ADB_ENABLED=1` when it reads 0 (safe: it does not
  delete the key) and then tries the system adbd's existing TCP listeners: the
  TLS property, the last TLS port, the plain 5555 listener and mDNS. Every
  candidate except 5555 must speak TLS; a plain token there is refused unsigned
  (I15). The first endpoint that passes the shell proof is used (I16). On Wi-Fi, if none answers,
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
| Start, patched adbd down | `1` when off (explicit Start only) | `1` only in the Wi-Fi fallback | deploy |
| Start, patched adbd up | unchanged | unchanged | keep running |
| Running | unchanged | `0` after harden | keep running |
| Stop, switch off | unchanged | unchanged | stop |
| Stop, switch on | `0` | `0` | stop |
| Boot after Start | unchanged; fails and waits for Start when off | `1` only in the Wi-Fi fallback | deploy |
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
| off | TLS `127.0.0.1:<portA>`, plain `<vpn-ip>:<portB>` | nothing | `adbd` |
| on | TLS `127.0.0.1:<portA>` | `<vpn-ip>:<portB>` | the app relay, which tunnels to `adbd` loopback |

0. The loopback listener is TLS in both modes. The relay forwards the
   developer's own `A_CNXN`, answers the daemon's `A_STLS` with the pinned
   handshake and relays the rest inside TLS, so the developer key authenticates
   end to end and the relay still sees plain ADB framing.
1. The VPN port has one owner. Before the relay binds, the app checks whether the
   port is open and who owns it: the relay itself (reuse it), the patched `adbd`
   (restart it loopback-only), or Android's legacy plain listener, identified by
   the verified system shell reading `service.adb.tcp.port` (send `usb:`,
   reconnect, retry). Any other holder is foreign and the bind fails with the
   port. `usb:` keeps the system adbd on USB, so the recovery path stays. The
   port, not a pref, is the authority.
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
   not a stopped daemon. A setup that verified the daemon and then failed at the
   relay keeps the daemon running.

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
| I13 | One owner per value. The UI shows only the state its component publishes; a persisted config has one writer; a platform read runs off the UI thread; the app's own tooling is not exempt. | The System adb card showed the app's own loopback port as the system adbd's port; the relay and the UI overwrote each other's config write; the app-catalog query froze the Firewall tab; `make install-phone` reported a failed install as success | `0bc537c`, `5e446cf`, `10cda0a`, `63067d5` |
| I15 | Sign only where a signature cannot be replayed. A plain ADB token is signed only inside the pinned daemon channel, on the system adbd's 5555, or for the one-time legacy migration with 5555 closed; everywhere else the app requires TLS and refuses a plain token unsigned. Each adbd has its own key | libadb signed any token a loopback server sent, so a local app could relay a token from the patched adbd and get `shell` | this change |
| I16 | Trust an endpoint only after it proves who it is. The patched adbd proves it with the pinned TLS key; the system adbd and a legacy daemon prove they run commands as shell by delivering a nonce to a receiver only the shell user can reach. A port that is open, advertised or remembered proves nothing | The daemon identity check ran over the connection it checked, so a squatter answered it; a discovered port was trusted on connect | this change |
| I17 | Unknown is denied. The relay drops a frame for a stream it does not track or with a mismatched device id; it parses sync as a byte stream; the daemon refuses a listener it cannot bind instead of retrying | A close naming another stream, two requests in one frame, or a request inside a data phase bypassed the sync policy | this change |
| I14 | A fix closes the class, not the instance. Every fix names the class of defect it closes and adds the test for the class, not only for the reported instance. | A fix closed the reported instance and left a smaller instance of the same class: a preference comparison after a port-ownership fix, a whole-config write after a writer-race fix, a nested dialog after a warning-placement fix, and a swallowed exit code after a tooling decision | `0bc537c`, `10cda0a`, `303f9aa`, `5e446cf`, `63067d5` |

### Start and stop order

Start:

1. Validate the ports (below 32768) and the persisted intent.
2. Probe the loopback port: the pinned daemon, a legacy daemon to migrate, or a
   foreign holder that is never trusted.
3. Bootstrap the system adbd: an explicit Start enables ADB when it reads 0; try
   its existing listeners, then the Wi-Fi fallback; prove the endpoint (I16).
4. Deploy the patched `adbd` with the listener set for the current mode and verify
   loopback through the pinned channel (and that the VPN port is bound when the
   firewall is off).
5. Start the relay and verify the VPN listener (when the firewall is on).
6. Turn the system wireless debugging off. This is the last step: it removes the
   fallback.
7. Publish Running and start the watchdog.

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
- Poll `127.0.0.1:<portA>` through the pinned channel every 60 s while the
  process runs, and every 15 min via `AlarmManager.setAndAllowWhileIdle`
  otherwise (an exact alarm needs `SCHEDULE_EXACT_ALARM`, which the app does not
  ask for).
- Compare the daemon key files with the app's copy; restore them, restart the
  daemon and warn on a mismatch.
- "Listener gone" means nothing on the system adbd's last TLS port still answers
  `A_STLS`; the property is not app-readable, so the remembered port is the evidence.
- If `<portA>` is down, restart the patched adbd through the system adbd if it is
  reachable, otherwise show an explicit error with recovery steps.
- Register `UsbManager.ACTION_USB_STATE` (sticky) and
  `ConnectivityManager.NetworkCallback`.
- Backoff and hysteresis; never fight an explicit user "off". A guard failure backs off
  exponentially to 15 minutes and re-arms the alarm before it returns.
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
   reboot, a sticky restart and the watchdog all respect it. Only an explicit
   Start, Restart or pairing may turn the system adb on (D5).
4. Start finds every vendored adbd through `ps` by its `--listen` argument
   (`/proc` as the fallback), kills it, waits for it to disappear and only then
   starts its own. A detached restart from an earlier process is waited for first.
   This needs no lock and no adbd change; a survivor aborts the start instead of
   leaving two daemons.
5. Opening the app while it runs re-checks the daemon; it never overrides a stop,
   never writes `ADB_ENABLED` and never retries a failure that waits for the user.
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
6. App pairs with the system adbd over loopback (SPAKE2 with the code) using the
   system key.
7. App connects over TLS, proves the endpoint runs as shell (I16) and self-grants
   `WRITE_SECURE_SETTINGS` over ADB.
8. App extracts the patched adbd for the device ABI, pushes it to
   `/data/local/tmp/adb-anywhere/`, verifies SHA-256, and `chmod 755`.
9. App writes the daemon key files (`adb_keys`, `tls_clients`, `tls_key.pem`).
10. App starts the patched adbd with `--tls-listen tcp:127.0.0.1:<portA>` and, when
    the firewall is off and the VPN is up, `--listen tcp:<vpn-ip>:<portB>`.
11. App verifies loopback through the pinned channel (and the VPN port when the
    firewall is off).
12. With the firewall on, the app starts the relay on `<vpn-ip>:<portB>`, verifies
    it and publishes its state.
13. App sets `ADB_WIFI_ENABLED=0` to stop the system TLS server. This is the last
    step, so the fallback exists until the new path is verified.

### Port discovery

- Primary: `NsdManager` discovery of `_adb-tls-connect._tcp`; the host `adb`
  uses the same advertisement.
- Hint: the port from the last verified bootstrap. There is no manual port entry: Android's
  pairing dialog closes when the user leaves Settings, so the code:port reply
  in the notification is the only reliable user-supplied input.
- `getprop service.adb.tls.port` is not app-readable: `adbd_prop` is
  `system_internal_prop`, so only `coredomain` reads it. The property and the
  auth socket are internal to adbd and `system_server`.
- Every candidate must answer `A_STLS`; a plain token is refused unsigned. A
  candidate is used only after the shell proof, so an advertisement or a
  listener from another app is never trusted. The status card's port probe only
  looks for `A_STLS` and never signs.
- The only plain candidate is 5555, the system adbd's own `adb tcpip` port. The
  app never sets `tcpip:<port>`.

### Boot

1. `BootReceiver` starts the service on `BOOT_COMPLETED` and
   `LOCKED_BOOT_COMPLETED`, but only when the user last started it.
2. The service re-checks; it never writes `ADB_ENABLED` (D5). With ADB off it
   fails and waits for Start. It probes the system adbd's existing TCP
   listeners: the TLS property, the last known port, `5555` and mDNS.
3. If one answers, start the patched adbd, verify, then set `ADB_WIFI_ENABLED=0`.
4. If none answers and Wi-Fi is up, write `adb_wifi_enabled=1` (even when it
   already reads 1), wait 900 ms, resolve the TLS port over mDNS and retry. This is
   a manual recovery step, not a blocker.
5. On mobile data with no plain listener, fail with the explicit instruction:
   connect Wi-Fi once, or run `adb tcpip 5555` over USB.
6. `adb_allowed_connection_time=0` is applied with the bootstrap and every
   watchdog tick, so the system key does not expire between reboots (§8 residual).
7. A Legacy daemon left by an update is migrated in place (Key provisioning).

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
5. If no VPN is up, the app removes the VPN listener and keeps the loopback
   channel. It never switches the developer machine to the system TLS endpoint and
   never binds a Wi-Fi or cellular address.

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
2. The service re-reads the system ADB state. If the system adbd is on USB, it
   is not a network listener; the patched adbd is unaffected.
3. If the system adbd returns to TCP, the app shows an explicit warning that the
   plain listener is back. It never sets `ADB_ENABLED=0`.

## 8. Security model

- **Assets**: ADB shell access, the app key, developer machine keys.
- **Outer boundary**: the VPN ACL. With the firewall off, the patched adbd's VPN
  listener is only reachable by allowed VPN peers. With the firewall on, the app relay
  is the only inbound TCP endpoint and the IP allowlist is the first gate.
- **Inner boundary**: the patched adbd key file. Only the app and explicitly paired
  developer machines are accepted.
- **App channel**: loopback TLS with the pinned daemon key (D20). A local app can
  connect to the port, but cannot finish the handshake (only the daemon key is in
  `--tls-clients`), cannot pose as the daemon (pin) and gets no replayable
  signature (I15).
- **Local apps**: a third-party app can bind loopback ports, advertise mDNS
  services and draw overlays. It cannot hold a port the daemon holds, cannot pass
  the pin or the shell proof, cannot reach the DUMP-protected receivers, and
  cannot cover the approval activity (`FIREWALL.md` §7.5). It can deny service by
  holding the daemon's ports first; the daemon then refuses to start and the app
  reports the port.
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
- **Revocation**: app removes a key from the key file and restarts the patched adbd,
  which ends the sessions that key authenticated. "Remove all" rewrites the file
  with the daemon key only. Revocation stops a lost key; it cannot undo what a
  holder already did as `shell` (a detached process, an installed app, a changed
  setting). Recovery from a malicious holder is reboot, check, reinstall.
- **`adb reverse`**: the daemon binds the device side of a reverse forward to
  `127.0.0.1` only, so a developer's local service is never exposed on Wi-Fi or
  cellular.
- **Authorization expiry**: `adb_allowed_connection_time=0` disables expiry for
  every key the system adbd trusts, not only the app's. Accepted residual: the
  system key is used only to bootstrap and would otherwise expire between
  reboots.
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
| Relay bind fails | Setup waits for the port, closes Android's legacy plain listener with `usb:` when the system adbd says it owns the port, and retries; if the port stays busy, fail with the port and the recovery step. A verified daemon keeps running |
| Another process holds a daemon port | The daemon refuses to start; the pinned channel fails; setup reports the port and the recovery step (change the ports or reboot). Nothing is signed for or sent to the holder |
| A pre-TLS daemon after an update | Migrated once through its own shell, only with 5555 closed and after the shell proof; otherwise redeployed through the system adbd |
| Daemon key files changed on the device | Watchdog restores them, restarts the daemon and warns |
| Relay dies | Watchdog restarts it and reports Error if it cannot; active sessions drop |
| Setup fails after the Wi-Fi bootstrap | The bootstrap this attempt opened is closed and the TLS port is verified closed; a bootstrap that was already on before the attempt is left as it was, and a failure to close is reported with the manual step |
| VPN excludes the app | Setup fails with an instruction to include ADB Anywhere in the VPN; no fallback listener |
| App process is killed | Service restart starts the relay again; prompts are lost and denied |
| Prompt not answered | 30 s timeout, deny, audit; a cancelled prompt releases its slot without suspending |
| Prompt is covered by a malicious overlay | The floating card offers only Review and Block. Allowing happens in the approval activity, which hides every non-system overlay while visible and refuses obscured touches in every window |
| Malformed ADB frame | Close the connection, audit, never crash the service |
| `UsageStats` missing | App allowlist cannot be enabled; the UI shows the grant action |
| App key lost | Re-pair via notification; regenerate key; rewrite key file |
| Developer key leaked | Remove from key file; restart patched adbd; revoke on the developer machine |
| Doze kills the app | Battery exemption prompt; foreground service; `START_STICKY`; watchdog |
| OEM blocks `pm grant` | Show an explicit error: the app cannot self-grant; link the upstream issue and the manual `adb shell pm grant` steps |
| Reboot on cellular | Probe the TLS property, the last port, the shell-owned listening ports, 5555 and mDNS; otherwise show the Wi-Fi/USB recovery steps |

The app never silently falls back. If the patched adbd cannot start, bind its
listeners, or dies without a restart path, the app shows the exact failure, the
last log line, and the recovery steps (connect Wi-Fi once, connect USB once, or
reinstall the patched adbd). It does not enable a proxy or leave the system adbd in
a different mode.

## 10. Testing

- Unit: QR payload parser and public-key validation, key file writer, label
  sanitizing, address selection, the VPN routing check, watchdog state machine,
  `ControlState.firewallDown`, the port guard, `AppKeyStore` concurrent first use
  of several instances, the ADB client (plain signing, TLS-only refusal, pin,
  tunnel, `offersTls`), relay frame parsing, the sync stream parser, the relay's
  fail-closed stream rules and key attribution, prompt state and audit ring.
- Integration: the vendored adbd's shared-core suite (`ADBD_TESTS=1`), the host
  TLS smoke test (`adbd/tests`), two listeners, auth with two keys,
  `shell:`/`sync:`/`tcp:`/`reverse:`, key reload, strict `--listen`.
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
  the call-policy model and the audit log are in `FIREWALL.md`. Remaining:
  IPv6 allowlist entries, per-key defaults, audit export.

## 12. Open risks

- Reboot on cellular with no system adbd TCP listener: the app needs one
  Wi-Fi connection to bootstrap. This is a manual recovery step, not a blocker.
- OEM blocks `pm grant WRITE_SECURE_SETTINGS`.
- Plain listener remains when `is_usb=false` or `service.adb.tcp.port` is set;
  the app shows an explicit warning for 5555 only. An `adb tcpip <other port>`
  listener is not detected, and a signature the app gives a squatter on a free
  5555 could be replayed to it.
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
  upstream commits are recorded in `../adbd/README.md`; a re-vendor is a copy and a
  patch reapply, not a merge.
- A per-app VPN that excludes the app does not deliver inbound packets to the
  relay, which runs in the app's UID; the app fails with an instruction instead of
  falling back to the direct listener.
- The relay runs in the app process: a process kill drops external sessions, and a
  pending prompt is lost and denied. The loopback channel and the recovery steps are
  unaffected.
- QR pairing needs a QR generator on the developer machine; `qrencode` is enough.
- The system TLS listener is checked on its last verified port only; a listener
  that came up on a new random port is seen through mDNS on the status card, not
  by the guard.
- With the firewall off, the daemon's VPN listener accepts a LAN host that
  addresses the VPN IP; key auth is the only boundary there. The firewall's
  routing check closes it for the relay.
- A failed legacy migration (the new daemon does not verify) leaves no daemon;
  on cellular that needs the Wi-Fi or USB recovery step.

## 13. Provenance

Inspired by [mouldybread/adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)
(MIT). The patched adbd is vendored from AOSP `packages/modules/adb` (Apache 2.0)
and BoringSSL (ISC); the upstream commits, the patches and the `NOTICE` files are
in `../adbd/`. The app's own code is MIT (see `../LICENSE`); the vendored daemon
stays Apache 2.0 (see `../adbd/LICENSE`), and third-party attributions are in
`../NOTICE`.
