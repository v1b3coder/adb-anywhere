# Changelog

All notable changes to ADB Anywhere are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
semantic versioning once the project leaves 0.x.

## 0.9.0 — 2026-11-08

First feature-complete preview. ADB Anywhere keeps wireless ADB reachable on a
stock Android 12+ phone without root across sleep and network changes, and redeploys
it after a reboot when Android's adb can be reached once.

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
