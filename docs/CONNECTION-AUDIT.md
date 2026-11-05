# Connection and pairing audit

Scope: `b59bde9` (the last commit before the system-ADB ownership and
firewall work) through `HEAD`, on the SM-S911B (Android 15) that reported the
regression.  The firewall itself is disabled in this run.

## Device evidence (2026-11-06)

```
adb_enabled=1
adb_wifi_enabled=1
service.adb.tls.port=
persist.adb.tls_server.enable=
ss -ltn: *:44607, *:44609, *:15037, *:5556
mDNS: adb-RFCW81992XX-pU34kS _adb-tls-connect._tcp 192.168.39.199:34857
dumpsys adb user_keys: QAAAA... dev@bro   (host key only)
app prefs: paired=true, last_tls_port=44603
```

The app log shows the failure chain:

```
setup start: adb_enabled 1 -> 1, adb_wifi_enabled 1 -> 1
system: service.adb.tls.port is empty; resolving mDNS
system: waiting for system wireless debugging on [34857, 44607]
setup: system wireless debugging is up on 127.0.0.1:44607
system: bootstrap 127.0.0.1:44603 failed
system: connect 127.0.0.1:44607 failed: ADB pairing is required.
setup: Android's wireless debugging is not reachable and the custom adb is down.
```

The system TLS listener is up.  The app can reach it.  The system adbd
rejects the app key because the app key is not in its `user_keys`; the app's
persisted `paired=true` is stale.

## Old path (`b59bde9`)

### Bootstrap

1. `probeDaemon()` checks the custom daemon on loopback.
2. If it is down and `setupComplete` is true and the app has
   `WRITE_SECURE_SETTINGS`:
   - write `adb_wifi_enabled=1` **unconditionally** when Wi-Fi is up;
   - write `adb_allowed_connection_time=0`;
   - `delay(900)`;
   - `system.ensureConnected()`.
3. `SystemAdb.ensureConnected()` tries `bootstrapHost`, then
   `service.adb.tls.port`, `lastTlsPort`, listening shell ports and 5555,
   then mDNS.
4. It never writes `ADB_ENABLED`.

### Start and Stop

- Start writes `adb_wifi_enabled=1` when the custom daemon is down and
  Wi-Fi is up.
- Start leaves `ADB_ENABLED` alone.
- Stop stops only the custom daemon.  It does not write `ADB_ENABLED` or
  `adb_wifi_enabled`; the system wireless debugging and its paired keys
  survive.

### Pairing

- One notification with one `code:port` reply.
- The user keeps Android's pairing dialog open and replies from the
  notification; the app process does not come to the foreground.
- `PairingConnectionCtx` pairs the app key.
- `prefs.paired=true` is set only after a successful pair.

### Restart and watchdog

- `restartDetached()` kills the pid file PID, sleeps 0.4 s and starts the
  replacement.
- `waitForRestart()` waits for the loopback port to be open.
- `watchdog()` re-asserts `adb_wifi_enabled=0` every tick and probes the
  custom daemon.

## New path (`HEAD`)

### Bootstrap

1. `a4757c4` introduced `AdbLifecycle.start()` and
   `SystemSettings.applyAdbState()`.  `applyAdbState()` writes **only the
   settings whose value differs**.
2. On this device `adb_enabled=1` and `adb_wifi_enabled=1`, so the app
   logs `adb_enabled 1 -> 1, adb_wifi_enabled 1 -> 1` and writes nothing.
   The framework's TLS server stays down.
3. `SystemSettings.setAdbEnabled()` is now also written by Start and Stop.
4. `b12d1af` and later commits added the firewall relay, `stopFirewall()`,
   `restartStack()`, `stopStack()` and the listener-mode restart.
5. `2c76489` replaced the `/proc` cleanup with a pid-file kill plus a
   `ps`/`awk` cleanup and removed the restart-script wait.
6. `8cc603b` made mDNS the primary bootstrap path and fixed its service
   type.

### Start and Stop

- `a4757c4` made Stop write `AdbLifecycle.stop()`:
  `adb_enabled=0, adb_wifi_enabled=0`.  This stops the system adbd and
  drops its paired keys.  This is the pairing that disappeared.
- Start writes `adb_enabled=1` and `adb_wifi_enabled=1` back-to-back, with
  no wait for the system adbd to come back.
- The app's `prefs.paired=true` stays true even when the system adbd no
  longer has the app key.

### Pairing

- `f5cf244` split the notification into two replies: code first, port
  second.
- The code and port are from the same dialog only if the dialog stays open
  for both replies.  If the dialog closes or is reopened between them, the
  code and port belong to different pairing sessions and the pair fails.
- The SetupScreen also switches to the app, which can close the pairing
  dialog before the app connects.
- `a4757c4` introduced the `adb_enabled=0` Stop, which can clear the
  paired entry.

### Restart and watchdog

- `2c76489` starts the replacement after `sleep 0.4` without waiting for
  the old pid to disappear.  The old daemon can hold the port and the app
  verifies the old process.
- The old `/proc` scan takes 40+ s on this phone; `waitForRestart` times
  out at 10 s.
- `6fb76c6` stopped the watchdog's `adb_wifi_enabled=0` re-assertion;
  `2d58430` re-added it only while the daemon and relay run.
- The watchdog still turns off the system wireless debugging every tick, so a
  user's manual recovery path disappears within a minute.

## Findings

| ID | Severity | Finding | Evidence | Fix |
|---|---|---|---|---|
| C1 | P0 | Stop writes `ADB_ENABLED=0`, stops the system adbd and clears the paired app key | `a4757c4`, device paired list only `dev@bro` | Remove `AdbLifecycle.stop()` from Stop/Boot; never write `ADB_ENABLED` |
| C2 | P0 | `applyAdbState()` skips the write when the settings already match; a stale TLS server stays down | app log `adb_wifi_enabled 1 -> 1`, `service.adb.tls.port=` | Always write `adb_wifi_enabled=1` on bootstrap and wait 900 ms |
| C3 | P0 | Start writes `ADB_ENABLED=1` and `ADB_WIFI_ENABLED=1` without waiting for adbd | `a4757c4` | Do not write `ADB_ENABLED`; keep the old `setAdbWifiEnabled` + delay path |
| C4 | P0 | The app key is not in the system adbd; `prefs.paired=true` is stale | `dumpsys adb user_keys` only `dev@bro`; `connect 44607: ADB pairing is required` | Pair again; tie `paired` to the key fingerprint |
| C5 | P1 | Two-step pairing can mix code and port from different dialogs | pairing code 00:34:44, port 00:35:00, pair failed | One notification with `code:port`, or one in-app form |
| C6 | P1 | mDNS returns the first resolved port, which can be a stale advertisement | mDNS 34857 stale, 44607 actual | Collect all ports and try them; keep mDNS as fallback |
| C7 | P1 | Restart starts the replacement without waiting for the old pid | `2c76489`, 40+ s `/proc` scan | `ps` + pid wait, `/proc` fallback; `awaitRestartScripts` |
| C8 | P2 | Watchdog turns off the system wireless debugging every tick | `ensureWifiDebuggingOff()` in `watchdog()` | Only harden once after setup; leave the recovery path |

## Fixes in this tree

- Bootstrap: `setup()` writes `adb_wifi_enabled=1` unconditionally when
  Wi-Fi is up, writes `adb_allowed_connection_time=0`, waits 900 ms and
  calls `ensureConnected()`.  It never writes `ADB_ENABLED`.
- Stop: stops only the custom daemon.  It does not write `ADB_ENABLED` or
  `adb_wifi_enabled`; the system wireless debugging and its paired keys
  survive.
- Boot: a stopped app no longer writes `ADB_ENABLED=0`.
- Pairing: one notification with a single `code:port` reply.  The in-app form
  still accepts the code and port separately.
- Connection: `SystemAdb` keeps the old candidate order and uses mDNS only as
  the fallback; `MdnsDiscovery` collects all ports and uses the DNS-SD
  trailing dot.
- Restart: `ps` + pid wait, `/proc` fallback, `awaitRestartScripts()` marker.
- Pairing failure logging: `AdbManager.pairTo()` logs the exception.

## Residual

- The app key must be paired again once.  The system paired list is empty
  for the app key, so the app cannot bootstrap until the user replies to the
  pairing notification with `code:port` from the open dialog.
- Two simultaneous starts from different app processes are still
  last-writer-wins; there is no cross-process lock.
