# Changelog

All notable changes to ADB Anywhere are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use
semantic versioning once the project leaves 0.x.

## Unreleased

### Adding a computer

- Each key in *Keys* has a QR button that shows its public key in the pairing
  format, with the fingerprint underneath, so a second phone scans the first
  phone instead of running `qrencode` on the computer.

### Fixes

- The scan tab reads `qrencode -t ansiutf8` output from a terminal. That code is
  inverted (light modules on black), which ZXing only tries when asked, so it
  never decoded before.
- Analysis frames whose Y plane has a row stride wider than the image are
  repacked per row before decoding. A padded stride used to shear the picture,
  which on such cameras left the scan tab unable to read any code.
- The scan tab analyzes at 1080p instead of 720p, because a key's QR has about
  97 modules and 720p left too few pixels per module when the code is on a
  screen. The preview also takes tap-to-focus and pinch-to-zoom now.
- The relay allows 8 sessions per machine, 16 in total, and a new connection
  replaces the oldest idle authenticated session with no open stream instead of
  refusing until the app restarts. The Firewall status shows the per-machine
  cap.

## 0.13.0 — 2026-11-23

Adding a computer is a copy and a paste, pairing tells you what happened, and
Home shows a machine at work instead of only a port that is open. The download
also stops being 66 MB: the release APK is 6.4 MB.

### Adding a computer

- The add-key dialog gives you the command instead of naming a file:
  `cat ~/.android/adbkey.pub` for the paste path, with a copy button, and the
  `qrencode` line for both ways to make a code — `-t ansiutf8` in a terminal,
  `-o adbkey.png` for an image. Both paths end in a review card with the key's
  fingerprint, so you see what you are about to save before you save it.
- The scan tab now says how to produce the code at all. A successful scan stops
  the camera and keeps the parsed key on screen; an invalid code says what is
  wrong and keeps scanning; a denied camera permission offers the paste path
  instead of a dead end.
- The scan preview stays inside its rounded frame instead of drawing over the
  tabs and the text below it, and reads a full 700-character key from a screen,
  so a whole `~/.android/adbkey.pub` scans.
- The pasted key field is capped at five lines. A whole key still scrolls inside
  it and the review card confirms the value, but the dialog no longer grows past
  the screen.

### Pairing

- A pair reports itself: a *Paired. Setting up the custom adb…* notice replaces
  the reply notification and goes away once the daemon runs or setup fails,
  instead of the screen falling quiet for the length of the setup.
- The Setup screen keeps one action per phase — pairing, setting up, running,
  failed. After a successful pair the pairing steps no longer sit there beside a
  second *Set up now*.
- A pair that fails stays on the screen with the reason and a retry that asks
  for a new code, because Android's dialog owns the only valid one.

### Home

- The status badge pulses while the firewall relay forwards an adb frame, so a
  live session no longer looks like an idle listener. One command is one pulse,
  a session that pauses between frames does not strobe, and a client that merely
  connected does not move it. Direct mode has no relay to watch, so it stays
  still.

### Ports

- The VPN port developer machines connect to is 5556 by default, right beside
  the standard adb port 5555. The loopback channel keeps 15037, and both ports
  stay configurable in Settings.
- An upgrade moves a running daemon onto the new default once, because a daemon
  carrying the old ports is no longer adopted.

### Install

- The release APK drops from 66 MB to 6.4 MB and ships `arm64-v8a` only, which
  is what every Android 12 device Google certifies runs. Unused BouncyCastle
  PICNIC tables are gone and the native libraries are compressed in the APK.
  Decision D26 in [docs/DESIGN.md](docs/DESIGN.md) records it, and the release
  workflow fails when the APK carries another ABI; `x86` stays the e2e build and
  `universal` a local one that installs anywhere.
- Every release publishes itself to
  [Zapstore](https://zapstore.dev/apps/cz.slush.adbanywhere) from the tag, so
  the store no longer lags a release behind GitHub.

### Known limitations

- The firewall IP allowlist is IPv4-only.
- Per-key policy, audit export and work-profile apps are not implemented yet.
- The screen switch covers the app's own screen only. With it off and the phone
  locked, calls that need approval are denied without a prompt.
- A reboot does not keep the daemon alive. On mobile data the app needs one
  Wi-Fi connection or USB `adb tcpip` to redeploy it.
- Some OEMs do not restart wireless debugging on a Wi-Fi network without
  internet; enable Wireless debugging once in Settings to recover.
- Another app can keep the daemon from starting by holding its ports. The app
  reports the port; change the ports in Settings or reboot.

**Full changelog**: https://github.com/v1b3coder/adb-anywhere/compare/v0.12.0...v0.13.0

## 0.12.0 — 2026-11-20

A locked screen no longer breaks the firewall. The phone locks on its display
timeout, the keyguard takes the foreground, and a call that needs approval is then
denied without a prompt. Settings → Display → *Keep the screen on* holds the display
awake while the app's screen is open, so the timeout cannot get in the way.

### Display

- **Keep the screen on** sets `FLAG_KEEP_SCREEN_ON` on the app's own window: no wake
  lock, no permission, it ends when you leave the app, and it never overrides the
  power button. Off by default, stored in device-protected storage like the other
  settings that must survive a reboot.
- The screen-off rule stays: with the switch off, a locked screen still denies a
  call that needs approval. [docs/FIREWALL.md](docs/FIREWALL.md) names the switch as
  the mitigation next to that rule, and decision D25 in
  [docs/DESIGN.md](docs/DESIGN.md) records why it is a window flag and not a wake
  lock.

### Install

- The README now sends people to
  [Zapstore](https://zapstore.dev/apps/cz.slush.adbanywhere) first, with the GitHub
  releases as the second path.

### Known limitations

- The firewall IP allowlist is IPv4-only.
- Per-key policy, audit export and work-profile apps are not implemented yet.
- The screen switch covers the app's own screen only. With it off and the phone
  locked, calls that need approval are denied without a prompt.
- A reboot does not keep the daemon alive. On mobile data the app needs one
  Wi-Fi connection or USB `adb tcpip` to redeploy it.
- Some OEMs do not restart wireless debugging on a Wi-Fi network without
  internet; enable Wireless debugging once in Settings to recover.
- Another app can keep the daemon from starting by holding its ports. The app
  reports the port; change the ports in Settings or reboot.

**Full changelog**: https://github.com/v1b3coder/adb-anywhere/compare/v0.11.0...v0.12.0

## 0.11.0 — 2026-11-18

Pause without a redeploy. The firewall port can now be closed and reopened on
its own, so a phone stops exposing ADB while the daemon keeps running — and an
idle timeout can take that same pause for you. Updating the app no longer
restarts a daemon that is already the one it would deploy.

### Pause, resume and auto pause

- **Pause** closes the firewall relay and keeps the custom adb running on its
  loopback listener, so nothing on the network answers while the app keeps its
  own channel to the daemon. **Resume** rebinds the relay against the same
  daemon: it needs neither Android's adb nor Wi-Fi, so it also works on mobile
  data, where a Stop followed by a Start cannot always be undone. In firewall
  mode Pause and Resume are the main action on Home and in the ongoing
  notification; Stop stays as the secondary CTA.
- **Auto pause** in the Firewall tab takes the same Pause after a chosen time
  without an adb command (5 min to 12 h, or a custom duration). The clock
  restarts only on authenticated relay traffic, so the app's own loopback
  channel, the watchdog and an unauthenticated peer cannot keep the VPN port
  open; a session that stays idle for the whole timeout is closed with the
  relay.
- The paused state says *ADB is paused*, not *Firewall is paused*, which read as
  "unprotected"; Pause and Resume are icon buttons on Home so the action row
  still fits a narrow screen.

### Daemon and lifecycle

- A daemon that already matches is adopted instead of killed and restarted. A
  process restart, a boot, an app update that ships the same binary, a re-check
  and a repeated Start keep the running daemon when it answered the pinned
  probe, its binary on the device is byte-identical to the one this build ships,
  the mode and ports match what it listens on, nothing else holds the VPN port
  and no legacy daemon needs migrating. That closes the window in which the old
  daemon was gone and only Android's adb could bring it back — the window that
  left a phone on mobile data with no reachable adb. A stale binary, a port or
  mode change, a legacy migration, the Restart button and a key revocation still
  restart it.
- One persisted lifecycle value with three states — started, paused, stopped —
  replaces the start/stop boolean, and only an explicit Start, Restart, Pair,
  Pause, Resume or Stop writes it. A Start or Restart keeps a paused firewall
  closed, the watchdog and a network change never reopen it, and a paused app
  redeploys the daemon loopback-only after a reboot.
- A firewall mode change clears a pause, and the Settings switch only stores the
  mode while the app is stopped.

### Audit log

- The firewall audit log reads like a sentence instead of an event dump: it
  names the peer, the foreground app that failed the app allowlist (or that the
  screen is off, the phone is locked or Usage access is missing), the command or
  transfer, the reason and the matched rule, with a timestamp. One call is one
  line — the redundant `call_open` row is gone — and the log appends at the
  bottom, following new decisions only while it is scrolled to the bottom.
- The app and adb log tabs re-arm their tail-follow when you return to the
  bottom, and a connect failure logs the exception class instead of
  `failed: null`.

### Fixed

- Changing a setting no longer starts the daemon: the firewall mode switch used
  to set the "user wants running" intent, so a stopped app came back on.
- An action that does not own the lifecycle (a log refresh, a prompt answer) no
  longer leaves a stopped app running as a foreground service with an ongoing
  notification.

### Upgrading from 0.10.0

- Install over 0.10.0; paired computers keep their keys and reconnect without
  re-pairing.
- An app update that ships the same daemon binary now adopts the running daemon
  instead of restarting it, so an update no longer needs Android's adb. An
  update from an older build whose daemon binary differs still needs one
  reachable Android adb to redeploy it: Wi-Fi wireless debugging, plain 5555, or
  root.
- The firewall stays off unless you turned it on; auto pause is off by default
  and lives in the Firewall tab.

### Known limitations

- The firewall IP allowlist is IPv4-only.
- Per-key policy, audit export and work-profile apps are not implemented yet.
- A reboot does not keep the daemon alive. On mobile data the app needs one
  Wi-Fi connection or USB `adb tcpip` to redeploy it.
- Some OEMs do not restart wireless debugging on a Wi-Fi network without
  internet; enable Wireless debugging once in Settings to recover.
- Another app can keep the daemon from starting by holding its ports. The app
  reports the port; change the ports in Settings or reboot.

**Full changelog**: https://github.com/v1b3coder/adb-anywhere/compare/v0.10.0...v0.11.0

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
