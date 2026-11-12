# Changelog

All notable changes to ADB Anywhere are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
semantic versioning once the project leaves 0.x.

## 0.10.0 — 2026-11-12

Security-hardening release. The daemon's app channel is now a pinned TLS channel
with its own key, the relay fails closed, and setup no longer signs a token that a
local app could replay. Paired computers keep their keys and reconnect without
re-pairing.

### Security

- The patched daemon's loopback listener speaks TLS only, with a server key that the
  app generates and pins on first use. Only the app's daemon key may finish the
  handshake, and ADB key auth then runs inside the channel.
- The app keeps two keys: the system key, used only to bootstrap Android's adbd, and
  the daemon key, the only key the patched daemon trusts. A signature made with one
  is useless against the other, and libadb is used only for pairing.
- An endpoint is trusted only after it proves who it is: the patched daemon with the
  pinned TLS key, Android's adbd and a pre-TLS daemon by delivering a nonce to a
  receiver only the shell user can reach.
- Bootstrap tries every TLS candidate before plain 5555 and never signs a token on 5555
  while a pre-TLS daemon runs, so a local app cannot relay the signature to it.
- The relay fails closed: a frame for an unknown or retired stream is dropped, sync is
  parsed as a byte stream, and every rule sees the same strictly decoded, normalized
  path that adbd opens. NUL, non-UTF-8 and relative paths are denied, so packed
  requests and the `SEND` data phase cannot bypass the path policy.
- `adb reverse` works through the relay and is policed at `A_OPEN`. The daemon binds
  the device side to loopback only.
- Call-policy command prefixes match on word boundaries, and an allow rule no longer
  matches a truncated call by its kept part.
- Approval prompts ignore touches that pass through another window, a locked screen
  denies a call that needs approval, and one peer's pending prompt no longer holds
  another session open.
- App keys are excluded from backup and device-to-device transfer.
- The daemon binds every listener at startup or exits, refuses wildcard listener
  specs, accepts at most 32 unauthenticated transports with a 10 s deadline from
  accept, rotates its log at runtime, and no longer creates world-writable files or
  leaks auth callback ids.
- Debug builds no longer expose shell, key and firewall actions to other apps.

### Setup and lifecycle

- A setup failure that needs the user (missing VPN, port conflict, missing
  permission) stops with the recovery steps instead of retrying every minute. Start,
  Restart and a successful Pair reset the retry backoff.
- With the VPN off, the app no longer mistakes the Wi-Fi or cellular address for
  the VPN endpoint. It no longer reports "excluded from the VPN" with no VPN, binds
  plain ADB on the LAN address, or rebinds the relay to the LAN address.
- Pairing is offered only in the NeedsPairing phase, and a pairing reply is accepted
  once, within 15 minutes of its notification.
- The watchdog checks all three key files, probes the daemon instead of a bare TCP
  connect, and stops the relay while the app is excluded from the VPN.
- The ongoing notification shows only the active session count, and it stays correct
  when several sessions close at once.
- The Security card's "Open settings" button opens Android's Wireless debugging
  page.

### Upgrading from 0.9.0

- The app migrates a running 0.9.0 daemon in place when Android's plain 5555
  listener is closed. Otherwise it needs one reachable Android adb to redeploy the
  daemon: Wi-Fi wireless debugging, plain 5555, or root.
- Paired computers keep their keys and reconnect without re-pairing.

### Known limitations

- The firewall IP allowlist is IPv4-only.
- Per-key policy, audit export and work-profile apps are not implemented yet.
- A reboot does not keep the daemon alive. On mobile data the app needs one Wi-Fi
  connection or USB `adb tcpip` to redeploy it.
- Some OEMs do not restart wireless debugging on a Wi-Fi network without internet;
  enable Wireless debugging once in Settings to recover.
- Another app can keep the daemon from starting by holding its ports. The app
  reports the port; change the ports in Settings or reboot.

**Full changelog**: https://github.com/v1b3coder/adb-anywhere/compare/v0.9.0...v0.10.0

## 0.9.0 — 2026-11-08

First feature-complete preview. Remote wireless ADB that follows your phone —
over VPN, on mobile data, across network changes. No root, stock Android 12+.

### Wireless ADB that stays up

- A patched AOSP `adbd` runs as the `shell` user with a stable loopback channel
  for the app and, by default, a VPN listener for your computers.
- Start, Stop, Re-check and Restart from the status card. The last explicit choice is
  remembered across reboots, app updates and service restarts.
- Boot receiver, foreground service and watchdog try to bring ADB back after a reboot,
  Doze or a process kill, and follow VPN address changes. After a reboot this needs one
  reachable Android adb: Wi-Fi wireless debugging, plain 5555, or root.
- Configurable loopback and VPN ports, kept out of Android's ephemeral range and
  away from the system adbd's plain 5555 listener.

### Pairing and keys

- One-time pairing through Android's wireless-debugging notification.
- Add a computer's public key by paste or QR; the SHA-256 fingerprint is shown
  before the key is added.
- Label and rename paired computers. Removing a key restarts the daemon and ends the
  sessions that key authenticated.

### Optional firewall

- The VPN listener moves into the app; the daemon stays loopback-only and ADB key
  authentication remains end-to-end.
- IPv4/CIDR allowlist for peers.
- Foreground-app allowlist with a configurable grace period and Usage access.
- Call policy for ADB service calls: allow once, always allow that call, block, or
  pin fields with a custom filter. Unmatched calls ask for approval and default to
  deny.
- Floating approval card above the current app, with a notification and dialog
  fallback.
- Bounded audit log with recent decisions in the UI and last-matched timestamps for
  entries and rules.

### Settings and recovery

- System ADB status, configurable ports, battery exemption and an optional
  diagnostic log.
- Explicit errors and recovery steps. No silent fallback, proxy or random-port
  redirect.
- Opt-in "Turn system adb off on Stop"; by default Stop leaves Android's ADB and its
  paired key alone.

### Security

- Only keys in the daemon key file are accepted; unknown keys are never
  auto-accepted.
- The firewall relay does not add authentication, and the approval card ignores taps
  that pass through another window.
- No root, no custom ROM, no fallback proxy and no random-port redirect.

### Known limitations

- The firewall IP allowlist is IPv4-only.
- Per-key policy, audit export and work-profile apps are not implemented yet.
- A reboot does not keep the daemon alive. On mobile data the app needs one Wi-Fi
  connection or USB `adb tcpip` to redeploy it.
- Some OEMs do not restart wireless debugging on a Wi-Fi network without internet;
  enable Wireless debugging once in Settings to recover.

**Full changelog**: https://github.com/v1b3coder/adb-anywhere/compare/v0.1.0...v0.9.0
