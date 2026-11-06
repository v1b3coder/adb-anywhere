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
| C4 | P0 | The app key is not in the system adbd; `prefs.paired=true` is stale | `dumpsys adb user_keys` only `dev@bro`; `connect 44607: ADB pairing is required` | Pair again; the real cause is the `AppKeyStore` identity race below |
| C5 | P1 | Two-step pairing can mix code and port from different dialogs | pairing code 00:34:44, port 00:35:00, pair failed | One notification with `code:port`, or one in-app form |
| C6 | P1 | mDNS returns the first resolved port, which can be a stale advertisement | mDNS 34857 stale, 44607 actual | Collect all ports and try them; keep mDNS as fallback |
| C7 | P1 | Restart starts the replacement without waiting for the old pid | `2c76489`, 40+ s `/proc` scan | `ps` + pid wait, `/proc` fallback; `awaitRestartScripts` |
| C8 | P2 | Watchdog turns off the system wireless debugging every tick | `ensureWifiDebuggingOff()` in `watchdog()` | Only harden once after setup; leave the recovery path |

## Fixes in this tree

- App identity: `AppKeyStore` has one process-wide `identities` map behind a
  lock and a synchronized `loadOrCreate()`; `keyPair`, `certificate`,
  `publicKeyB64` and `publicKey` all derive from it.  The service and the
  activity create separate stores, so the identity must be shared across
  instances, not only across the lazy properties of one instance.  This is the
  pairing root cause: the old three lazies could generate different pairs,
  which produced `CERTIFICATE_AND_PRIVATE_KEY_MISMATCH` during pairing and a
  stale `paired=true` after a later regeneration.  `AppKeyStoreTest` covers
  concurrent first use of three instances.
- Bootstrap: `setup()` writes `adb_wifi_enabled=1` unconditionally when
  Wi-Fi is up, writes `adb_allowed_connection_time=0`, waits 900 ms and calls
  `ensureConnected()`.  `awaitSystemWirelessDebugging()` retries mDNS every 5 s
  for 30 s.  The debug bootstrap address is one-shot.  The default never
  writes `ADB_ENABLED`.
- Stop: stops only the custom daemon by default.  It does not write
  `ADB_ENABLED` or `adb_wifi_enabled`; the system wireless debugging and
  its paired keys survive.  With the opt-in switch on, Stop also writes
  `ADB_ENABLED=0` and `ADB_WIFI_ENABLED=0`.
- Start/Stop race: Stop uses `stopSelf(startId)`, resets `foregroundStarted`
  and Start re-promotes the service and restarts the notification collector.
  Both sides share `lifecycleLock`, and the stop cleanup re-checks
  `userWantsRunning` under that lock, so a Stop cleanup that runs after a
  later Start skips the notification/foreground teardown.  The quick Stop->Start
  test leaves the process, the notification and the daemon in place.
- Stale pairing: `AdbManager.connectTo()` records
  `AdbPairingRequiredException`; setup drops `prefs.paired` and posts the
  pairing notification instead of reporting "wireless debugging is not
  reachable".
- Boot: a stopped app no longer writes `ADB_ENABLED=0`.  A running app
  bootstraps over mDNS after a reboot.
- Pairing UI: one notification with a single `code:port` reply.  The in-app
  code/port form was removed because opening the app closes Android's pairing
  dialog on AOSP.
- Connection: `SystemAdb` keeps the old candidate order and retries mDNS
  during the wait; `MdnsDiscovery` collects all ports and uses the DNS-SD
  trailing dot.
- Restart: `ps` + pid wait, `/proc` fallback, `awaitRestartScripts()` marker.
- Settings: the opt-in *Turn system adb off on Stop* switch.  It is off by
  default; on makes Start write `ADB_ENABLED=1` and Stop write
  `ADB_ENABLED=0`.  Android may drop the paired key when ADB is turned off,
  so the next Start can need a new pairing code.
- Pairing failure logging: `AdbManager.pairTo()` logs the exception.
- Files: `Shell.writeTextFile()` removes the target before writing, so a file
  left by a different uid (an emulator `adb root` run) does not block the
  shell adbd.

## Residual

- A phone whose app key was generated by the old build must pair again once:
  the old key may be in the system list or may never have been paired.  The app
  now detects `ADB pairing is required` and posts the one-reply pairing
  notification instead of reporting "wireless debugging is not reachable".
- The notification `RemoteInput` wrapper is exercised by a debug-only
  `DEBUG_PAIR_REPLY` action that builds a real `RemoteInput` result and calls
  `PairReplyReceiver`.  The notification action itself is present in
  `dumpsys notification`, but the AOSP notification shade did not render its
  button in the group; the receiver and the RemoteInput extraction are covered.
- The AOSP emulator has `ro.adb.secure=0`, so the
  `AdbPairingRequiredException` branch is code-reviewed, not emulator-tested.
  The phone evidence for the stale `paired` flag is in the section above.
- Two simultaneous starts from different app processes are still
  last-writer-wins; there is no cross-process lock.

## Emulator end-to-end (2026-11-06)

A clean API 35 AOSP emulator (`adbanywhere-e2e`, x86_64, `-wipe-data`) was used
for the whole control plane.  `ro.adb.secure=0` on the emulator means the system
adbd accepts a key without pairing, so pairing was exercised separately with the
real pairing dialog; the bootstrap and Start/Stop paths are identical to the phone.

### Pairing failure reproduced and root-caused

The first pair attempt after a fresh install failed with:

```
[pair] pair 127.0.0.1:32885 failed: Error configuring certificate:
  SSL routines:OPENSSL_internal:CERTIFICATE_AND_PRIVATE_KEY_MISMATCH
```

The app's `AppKeyStore` had three independent `by lazy` properties, each of
which called `loadOrCreate()`.  On a fresh install the service reads
`keyPair`, `certificate`, `publicKeyB64` and `publicKey` from different
threads (the setup coroutine and the app-fingerprint coroutine).  Two of them can
both see missing files, generate a pair, delete and rewrite the files, and the
in-memory `keyPair` and `certificate` then belong to different generations.
The on-disk files match each other; the process's certificate does not match its
private key.  The same race explains the phone's stale `paired=true`: a pair can
succeed with generation A, a later lazy property regenerates B, and the system
adbd then rejects B with `ADB pairing is required`.

Fix: one `identity` lazy in `AppKeyStore`; `keyPair`, `certificate`,
`publicKeyB64` and `publicKey` all derive from it.  `loadOrCreate()` is also
`synchronized`.

After the fix, the same pairing dialog paired successfully:

```
[pair] started on 127.0.0.1:35357
[pair] paired; starting setup
[setup] running; endpoint=not available yet
```

`dumpsys adb user_keys` then contained the app key (`adb-anywhere`) next to
the host key.  The key survived the opt-in Stop (`ADB_ENABLED=0`), the next
Start (`ADB_ENABLED=1`), the mDNS bootstrap, a process kill and a daemon
restart.

### Bootstrap and connection

- Start with the patched daemon down writes `ADB_WIFI_ENABLED=1`, then retries
  mDNS during the wait.  The emulator log shows the first advertisement
  `10.0.2.16:45525`, the next one `10.0.2.16:35783`, and
  `system wireless debugging is up on 127.0.0.1:35783`.  This is the phone
  path: Android 13+ does not publish `service.adb.tls.port`.
- `SystemAdb` then connected on the TLS port, granted
  `WRITE_SECURE_SETTINGS`, deployed the patched adbd on
  `127.0.0.1:15037` and hardened `ADB_WIFI_ENABLED=0`.
- `adb forward tcp:15037 tcp:15037` + `adb connect 127.0.0.1:15037`
  authenticated with the host key and `shell id` returned `uid=0(root)` (the
  emulator was `adb root`; a phone returns `uid=2000(shell)`).

### Start/Stop with the switch off (default)

- Stop stopped only the patched adbd (`stopping pids=[6036]`), left
  `ADB_ENABLED=1`, `ADB_WIFI_ENABLED=0` and the app key in `user_keys`.
- Start reconnected through the system TLS listener and redeployed the patched
  adbd without a new pairing code.
- A process kill (`kill -9`) restored the service through `START_STICKY`:
  `[service] resuming after a process restart`, `[setup] started; restart=true`,
  `[adb] restarting detached`, listeners verified and the host connection
  recovered.
- The Home Restart button produced the same detached restart and the host
  connection recovered.
- A Stop followed immediately by Start was also tested.  The service stayed
  alive, re-promoted its foreground notification, restarted the collector and
  reached Running with a fresh daemon.  The stop cleanup and the start share
  `lifecycleLock`; the stop re-checks `userWantsRunning` under it, so a stop
  cleanup that runs after the later start skips the notification/foreground
  teardown.  `stopSelf(startId)` keeps the service alive for the later start.
  The log shows `[service] stop superseded by a newer start`; the process, the
  notification and the daemon were still present after 75 s, so Android did not
  kill the service as idle.
- The E2E script now runs the real host path as well: `adb forward
  tcp:15037 tcp:15037`, `adb connect 127.0.0.1:15037`,
  `adb -s 127.0.0.1:15037 shell id` and `adb disconnect`, and it logs
  `host adb path ok`.
- `DEBUG_PAIR_REPLY` built a real `RemoteInput` result and called
  `PairReplyReceiver`; the log shows `pairing reply received` and `paired;
  starting setup`.  The pairing notification has its own group, so its
  "Enter code:port" action renders in the shade; the RemoteInput editor opens
  from it.  The synthetic Send tap did not submit the typed value in the
  headless emulator, so the final tap is covered by the debug injection.
- A boot after an emulator `adb root` left `/data/local/tmp/adb-anywhere` owned by
  root, so the shell adbd could not overwrite `adb_keys`.  `Shell.writeTextFile`
  now removes the target before writing, and the boot flow recovered.  This is an
  emulator-only ownership case; a phone's system adbd is always shell.
- The E2E script now performs the real host path as well:
  `adb forward tcp:15037 tcp:15037`, `adb connect 127.0.0.1:15037`,
  `adb -s 127.0.0.1:15037 shell id` and `adb disconnect`; it logs
  `host adb path ok` before the sticky-restart and stop/start checks.

### Start/Stop with the switch on (opt-in)

- Stop wrote `ADB_ENABLED=0` and `ADB_WIFI_ENABLED=0`; the host adb went
  offline, which is the intended full-ownership behavior.
- Start from the stopped app wrote `ADB_ENABLED=1`, waited for the system adbd,
  opened wireless debugging (`ADB_WIFI_ENABLED=1`), resolved the new TLS port
  over mDNS, deployed the patched adbd and hardened wireless debugging again.
  The app key was still in `user_keys` on this emulator, so no new pairing was
  needed.
- The switch is off by default and is the only path that writes
  `ADB_ENABLED=0`; the default keeps the old pre-firewall behavior.

### Pairing UI

The in-app code/port form was removed.  Opening the app closes Android's
pairing dialog, so the form could never win the race on AOSP.  The Setup screen
now shows the notification path only: reply once with `code:port`; the notification
reply keeps the app in the background and the dialog open.  `PairReplyReceiver`
forwards the single reply to the same `pair()` call.

### Still not covered on the emulator

- `ro.adb.secure=1` is not set on the AOSP emulator, so the
  `ADB pairing is required` detection (`SystemAdb.pairingRequired`) is covered by
  code review, not by an emulator failure.  The phone evidence for the stale
  `paired` flag is in the section above.
- A real phone's VPN interface and the firewall relay are not part of this run;
  the firewall was off.
