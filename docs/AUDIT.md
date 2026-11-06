<!-- Production-readiness audit of adb-anywhere against DESIGN.md. -->
# Production-readiness audit

Date: 2026-11-03
App commit: `0c54704`
Daemon at audit time: `94dcaba` (`../adb-anywhere-adbd`); replaced after the
audit by the vendored AOSP adbd in `adbd/` (see `adbd/README.md`).
Scope: app, bundled daemon, build/release setup, tests, docs, and
conformance with `DESIGN.md`.

**Verdict: not production-ready.** The core path is real and was demonstrated
end-to-end on a Samsung S23, and both the app and daemon test suites pass. But
there are P0 correctness, security, and recovery defects, and the implementation
has drifted substantially from the design. The app must not be described as
production-ready until the P0 items below are fixed and the design is reconciled.

**Later change:** the destructive `ADB_ENABLED` ownership is now opt-in.  The
Settings switch *Turn system adb off on Stop* is off by default, so Start and Stop
never write `ADB_ENABLED`; Android keeps the paired key and the USB recovery path.
With the switch on, Start enables the system adbd when it is off and Stop disables
it.  The app never stops the system adbd while it needs the patched adbd: on devices
that share its cgroup, that would also kill the patched adbd.


## 0. Scorecard (informal)

| Dimension | Rating | Main reason |
|---|---|---|
| Functional core | 7/10 | The real phone path works; process-death recovery is broken. |
| Security | 5/10 | Key auth and loopback are good; failure paths can leave the system listener on; revocation is not live; the boot receiver was exported (P0-7, fixed). |
| Reliability | 4/10 | No backoff, no log rotation, daemon DoS, no USB state, no exact alarm. |
| Test coverage | 5/10 | Good daemon tests; the wireless-debugging guard now has app unit tests; still missing watchdog/backoff, two-listener/two-key integration, and a device matrix. |
| Release engineering | 6/10 | Signed tag releases with versioning and CI; tests and lint run on push and PR; no device matrix or update channel. |
| Design conformance | 4/10 | The daemon is a rewrite, not the design's patched AOSP adbd; several flows are missing. |
| Documentation | 5/10 | Phone evidence is good; DESIGN.md and E2E.md are stale; the license is now aligned (P2-15, fixed). |

---

## 1. Method and evidence

Read: `DESIGN.md`, `README.md`, `docs/E2E.md`, `docs/PHONE-E2E.md`, all app
Kotlin sources, the manifest and Gradle files, and the daemon fork's C sources,
headers, integration test, and build scripts.

Executed:

```text
./gradlew :app:testDebugUnitTest --rerun-tasks
  BUILD SUCCESSFUL
  AndroidPubKeyTest: 7 tests, 0 failures
  ControlDataTest:   7 tests, 0 failures
  ScaffoldTest:      1 test,  0 failures

./gradlew :app:lintDebug :app:assembleRelease
  lintDebug: 0 errors, 44 warnings
  assembleRelease succeeded

./gradlew build
  BUILD SUCCESSFUL (check runs lintDebug)

./gradlew :app:assembleRelease
  BUILD SUCCESSFUL
  output: app/build/outputs/apk/release/app-release-unsigned.apk

cd ../adb-anywhere-adbd
make
  all host binaries built with -Wall -Wextra -Werror
./tests/test_util   -> 15 checks, 0 failures
./tests/test_auth   -> 30 checks, 0 failures
./tests/test_proto  -> 86 checks, 0 failures
./tests/integration.sh
  integration ok (real adb client: shell v1/v2, exec-out,
  stderr/exit code, push/pull, forward, key rejection)
```

Not executed in this environment: any Android device or emulator, Android 12,
WireGuard, USB attach/detach, Doze, or OEM devices. The phone evidence in
`docs/PHONE-E2E.md` is treated as reported evidence, not as a rerun.

---

## 2. What works

These are verified or strongly supported by the repository:

- The daemon binds only explicit `tcp:IP:PORT` listeners and refuses `0.0.0.0`
  (`proto.c:listen_parse`). It has no USB, vsock, mDNS, or TLS code.
- RSA-2048 Android `adbkey.pub` authentication, per-key roles, key-file reload
  on mtime change and `SIGHUP`, structured JSON-lines logs.
- `shell:`, `shell,v2`, `exec:`, `sync:`, and `tcp:` work with the real `adb`
  client in the daemon integration test.
- The app's setup flow is explicit and has no proxy fallback or random-port
  redirect.
- The app's key import supports Android `adbkey.pub`, `ssh-rsa`, base64 DER, and
  PEM, and shows the fingerprint before adding the key.
- The app uses device-protected storage for its key and preferences,
  `allowBackup="false"`, a `connectedDevice` foreground service, and a
  direct-boot-aware boot receiver.
- The phone evidence in `docs/PHONE-E2E.md` shows the intended product path:
  two listeners (`127.0.0.1:15037` and `100.64.0.10:15038`), app-key and
  developer-key auth, `uid=2000(shell)`, `adb push`/`pull`, and
  `adb_wifi_enabled=0`.

---

## 3. P0 blockers

### P0-1 — The app crashes on Android 12 (contradicts D1)

**Status: fixed in `a21654d`.** `encode()` now uses
`BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8 * 2)`; `lintDebug` no longer reports
`NewApi`, and `encodeReproducesAndroidFixtureBytes` pins the byte output. Not yet
re-verified on an API 31/32 device or emulator.

`AndroidPubKey.kt:40` uses `BigInteger.TWO`. The API database marks
`java/math/BigInteger.TWO` as `since="33"`; lint reports it as an error against
`minSdk = 31`. `AndroidPubKey.encode()` is called during first-run key
generation (`AppKeyStore.loadOrCreate`) and in `KeyFiles`, so on Android 12/12L
(API 31/32) the app throws `NoSuchFieldError` before it can pair or deploy.

Impact: the stated Android 12+ support is false; the app cannot complete first run
on Android 12. The JVM unit test passes because the host JDK has the field.

Fix: `BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8 * 2)` (or
`BigInteger.valueOf(2)`), or raise `minSdk` to 33 and update DESIGN.md D1.

### P0-2 — Setup failures can leave the system TLS listener on

**Status: fixed in `a99716e`.** `setup()` now runs `abortSetup()` from a
`finally` block whenever the attempt does not reach `Phase.Running`: it stops the
patched daemon (daemon loopback shell, else the system shell), writes and verifies
`adb_wifi_enabled=0`, and restores `ADB_ENABLED` to the value captured before
the bootstrap. If the listener cannot be closed, the explicit setup error is
extended with the reason and the manual recovery step. Not re-verified on a device.

`ControlService.setup()` deliberately re-enables the bootstrap before it has a
patched daemon: `setAdbEnabled(true)` when keep-off is on, and
`setAdbWifiEnabled(true)` when Wi-Fi is connected (`ControlService.kt:183-186`).
Every subsequent failure path calls `fail()` without a cleanup:

- `grantSelfPermission()` failure (`ControlService.kt:209-215`);
- `deployAndStart`/`restartDetached` failure (`ControlService.kt:230-247`);
- `verifyDaemon()` failure (`ControlService.kt:252`);
- `harden()` failure (`ControlService.kt:261`);
- any other exception (`ControlService.kt:281-283`).

`fail()` only records the error and changes UI state (`ControlService.kt:521-532`). It does not set
`adb_wifi_enabled=0`, does not stop the daemon, and does not restore
`ADB_ENABLED`.

Impact: on a failed setup the system `adbd` can remain reachable over TLS on all
interfaces, which violates the design's security model ("never expose plain ADB on an
untrusted LAN" and "system adbd ... not reachable over TCP after
`ADB_WIFI_ENABLED=0`"). The failure policy in DESIGN.md §9 says to show an
explicit error; it does not authorize leaving the system listener on.

Fix: put the bootstrap in a `try/finally` or explicit cleanup path that always sets
`adb_wifi_enabled=0`, stops a partially started daemon, and restores the original
`ADB_ENABLED` value when the patched daemon is not verified.

### P0-3 — Revocation does not terminate live sessions

**Status: fixed in `822fc50`.** `removeDeveloperKey()` now sends
`ACTION_REVOKE_KEYS`, which the service handles like an explicit restart:
`setup(forceRestart = true)` writes the reduced key file and then runs
`restartDetached()`, which kills and starts the daemon process. Every connection
is a thread in that process, and the forked shell children close every descriptor
above 2 (`stream.c:spawn_process`), so the kill drops the sessions that the
removed key authenticated. Adding a key keeps the light path: `ACTION_APPLY_KEYS`
rewrites the file and the new key is loaded on the next authentication. The
removal dialog now says the daemon restarts and open connections drop.

Verified on the host with the real `adb` client and the host daemon: a live
`shell sleep 120` session ended when the key file was rewritten and the daemon
killed/restarted, the removed key was rejected (`auth_reject`), and the remaining
key still got a shell (`auth_ok`).

Verified on the SM-S911B (Android 16) with the real Keys screen and a throwaway
developer key: tapping Remove restarted the daemon once (`stop`/`start`, `keys=2` =
app + `phone-laptop`), the throwaway key's live `shell sleep 300` session ended,
its next connection was rejected (`auth_reject`, "failed to authenticate"), the app
returned to `Running` without a setup error, and the `phone-laptop` key kept its
shell. The key file no longer contained the throwaway key. The first device run
exposed a restart race: the app verified the daemon while the old process was still
dying, reported `setup failed: Stream closed`, stopped the replacement, and the
watchdog recovered about three minutes later. `restartDetached()` waited only for
an open loopback port; `waitForRestart()` now waits for the old listener to close
first (`14f8664`).

`AppViewModel.removeDeveloperKey()` rewrites the key list and sends
`ACTION_APPLY_KEYS`. `ControlService.setup()` sees a live daemon and
`restart == false`, so it only calls `daemon.writeKeysAndPolicy(...)`
(`ControlService.kt:227-229`). The daemon reloads the key file only on the next
authentication (`main.c:maybe_reload_keys`), and an already authenticated
connection keeps its role and can open new streams.

Impact: a developer key that has an open connection remains usable after it is
removed from the UI. This contradicts DESIGN.md §8: "Revocation: app removes a key
from the key file and restarts the patched adbd if needed" and "removing the key
revokes it". The app UI says the machine "will no longer be able to connect", which
is only true for new connections.

Fix: restart the daemon on key removal, or add a control operation that closes
connections whose fingerprint was revoked and then reloads the keyring. At minimum,
`ACTION_APPLY_KEYS` should force a daemon restart, not only a key-file rewrite.

### P0-4 — `START_STICKY` restart is a no-op

**Status: fixed in `284b75b`.** `onStartCommand` now matches a null intent (the
`START_STICKY` restart) and resumes the full `setup()` when a previous run
completed setup and this process has not started one yet (`resumeAfterRestart()`,
gated on `prefs.userWantsRunning`, `prefs.setupComplete` and `Phase.Idle`). `ACTION_WATCHDOG` takes the same
path, because the 15-minute alarm is the other way a dead process is woken; it used
to reach `watchdog()`, which returned on a non-`Running` phase and left no alarm
scheduled. An explicit stop is unaffected: it returns `START_NOT_STICKY` and leaves
`Phase.Stopped`. Compiles, the app unit tests pass, and `scripts/e2e-emulator.sh` kills
the process and requires the service back in `Phase.Running`; Doze/LMK on a phone is
still not covered.

`ControlService.onStartCommand` returns `START_STICKY` (`ControlService.kt:126`),
but when the system restarts the service with a null intent, the `when` has no
matching branch and the service does nothing. `onCreate` only publishes the saved
preferences; it does not call `setup()`. `ControlBus.state.phase` stays `Idle`,
and `watchdog()` returns immediately unless the phase is `Running`
(`ControlService.kt:430`).

Impact: after the process is killed by Doze/LMK (without a reboot), the app does not
redeploy, does not re-harden, and does not restart the watchdog. DESIGN.md §9
explicitly relies on `START_STICKY` for Doze recovery. The daemon may still be
running, but the app no longer supervises it.

Fix: on `intent == null` (or when `phase == Idle` and `setupComplete`), call
`setup()`.

### P0-5 — Mandatory `ADB_WIFI_ENABLED=0` is not re-asserted or verified

**Status: fixed in `17277a2`.** `harden()` now runs a `WirelessDebuggingGuard`
that writes `adb_wifi_enabled=0` on every call, even when it already reads off, and
verifies that `service.adb.tls.port` is empty or that the published port is closed
before reporting "off"; the watchdog runs the same guard on every tick and boot reaches
it through `setup()`. The guard is covered by JVM unit tests
(`WirelessDebuggingGuardTest`). Not re-verified on a device; `abortSetup()`
still checks only the setting (P1-9).

DESIGN.md D5/D6 say the app "always keeps the system wireless debugging off" and
the watchdog "keeps the D5 state". `harden()` sets `adb_wifi_enabled=0` once during
setup (`ControlService.kt:373`), but `watchdog()` never re-asserts it
(`ControlService.kt:428-469`). If the user or the OEM turns wireless debugging
back on, the system TLS listener stays on and the app never notices.

The app also does not verify that the write stopped the listener: it does not check
`service.adb.tls.port` or `persist.adb.tls_server.enable`. The only evidence is the
setting value itself. The phone evidence shows `adb_wifi_enabled=0`, not that the TLS
port closed.

Fix: re-assert `setAdbWifiEnabled(false)` on every watchdog tick and boot; verify
that `service.adb.tls.port` is empty (or that the TLS port is closed) before
declaring "system wireless debugging off".

### P0-6 — The 5555 warning is discarded and the UI infers state from the phase

`harden()` sets a warning in `ControlBus.message` when `127.0.0.1:5555` is open and
"Keep system adb off" is off (`ControlService.kt:395-402`). `setup()` then overwrites
`message` with "Ready for developer machines." or "VPN is down…"
(`ControlService.kt:269-277`). `HomeScreen` only shows a warning when
`state.message.contains("Warning")` (`HomeScreen.kt:89`), so the warning is never
visible.

`SecurityRow` derives "System wireless debugging is off" from
`state.phase == Phase.Running` (`HomeScreen.kt:271-295`). It never reads
`adb_wifi_enabled` or `adb_enabled`, so it can claim "off" while the system
listener is on. This contradicts D11 ("shows the current ADB state and any warning")
and DESIGN.md §8's residual-risk requirement.

Fix: add explicit `adbWifiEnabled`, `adbEnabled`, and `plain5555Open` fields to
`ControlState`; populate them from `SystemSettings`/`SystemAdb`; show a persistent
warning instead of inferring from the phase.

**Status: fixed in `2b73e78`.** `ControlState.adbStatus` now carries the platform
`AdbStatus` (`adb_enabled`, `adb_wifi_enabled` and the `127.0.0.1:5555` probe), and
`ControlService.refreshAdbStatus()` fills it on service create, at the end of every
setup attempt (after `harden()` and `abortSetup()`), on every watchdog tick and after
a stop, always on `Dispatchers.IO`. `harden()` no longer writes a message, so nothing
overwrites the warning. `HomeScreen`'s `SecurityRow` renders
`AdbStatus.warnings(keepSystemAdbOff)` in the error color, independent of the phase
and of later status messages, and says the state was not read yet while `adbStatus` is
null. The probe now runs in both "Keep system adb off" modes because D11/§8 require
the warning when the checkbox is off too; DESIGN.md D6 is updated to match.
`AdbStatusTest` asserts the 5555 warning for both modes. The commit also carries the
adbd CI workflow from a concurrent change. Not verified on a device.

### P0-7 — `BootReceiver` is exported and does not check the action

`AndroidManifest.xml:53-56` declared `BootReceiver` with `android:exported="true"`,
and `BootReceiver.onReceive` started `ControlService.ACTION_START` for any intent
(`BootReceiver.kt:12-14`). It did not check `intent.action`.

Impact: any app on the phone can send an explicit intent with an unprotected action to
`cz.slush.adbanywhere/.service.BootReceiver` and trigger the control service. If the
daemon is down, `setup()` can re-enable `ADB_ENABLED=1` and `adb_wifi_enabled=1`
before bootstrap (`ControlService.kt:183-186`), which is exactly the state the design
promises to keep off. The receiver does not need to be exported: system broadcasts are
delivered to non-exported receivers.

Fix: `android:exported="false"` and/or check
`intent.action in {BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED}`.

**Status: fixed in `c31f59c`.** `BootReceiver` is now declared with
`android:exported="false"`, and `onReceive` only starts `ControlService` for
`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. On an
Android 15 emulator, reinstalling the APK restarted the service through
`MY_PACKAGE_REPLACED`, an explicit `BOOT_COMPLETED` from the root uid still
started it, a bogus action from the root uid did not, and a send from the shell
uid 2000 was refused.

### P0-8 — Release artifact is unsigned

**Status: fixed in `ec83728`.** `app/build.gradle.kts` has a `release` signing config
that reads `signing/keystore.properties` locally and `RELEASE_*` environment variables
in CI; the key is not in the repository. `.github/workflows/release.yml` runs the JVM
tests, builds and signs the APK, verifies the certificate against the
`RELEASE_CERT_SHA256` repository variable and the version against the tag, and
publishes `adb-anywhere-<version>.apk` on the release page. Without signing inputs,
`assembleRelease` still produces an unsigned APK. Debug builds keep the standard
Android debug key.

### P0-9 — The lint quality gate fails

**Status: fixed in `5efcaec` and `ed5d22b`.** `./gradlew build` and `./gradlew check`
are green, and `.github/workflows/ci.yml` runs
`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` on every push to
`main` and every pull request.

`./gradlew :app:lintDebug` failed with 2 errors and 45 warnings:

- `ControlService.kt` `MissingPermission`: `NotificationManagerCompat.notify`
  without a `POST_NOTIFICATIONS` check. The call was inside `runCatching`, but lint
  does not accept that as a permission check, and the service notification update had
  no check at all.
- `AndroidPubKey.kt:40` `NewApi`: `BigInteger.TWO` requires API 33 (P0-1).

Impact: `./gradlew build`/`check` was red, so any CI gate using lint failed. The
`lintVitalRelease` task happened to pass because `NewApi` is not fatal there.

Fix: P0-1 landed in `a21654d`. `postNotification()` now keeps the
`POST_NOTIFICATIONS` check in the same method as the `notify()` call, so lint sees
the guard, and still wraps the call in `runCatching` for a revocation between the
check and the call. The foreground notification stays unguarded: it is exempt from
`POST_NOTIFICATIONS`, and skipping `startForeground()` would break the service. Lint
now reports 0 errors and 44 warnings; the warnings do not fail the build.

---

## 4. P1 findings

### P1-1 — The implementation is not the design's "patched AOSP adbd"

**Status: fixed in `c89676e` and `64abac8`.** The separate C daemon was removed;
`adbd/` now vendors AOSP `adbd` (pristine trees plus focused patches), and
DESIGN.md §6/§11/§12 describe the vendored daemon, its supported subset and its
re-vendor process. The original finding follows.

DESIGN.md §6 said: "The patched adbd is a stock AOSP adbd with a minimal patch set.
All in `daemon/`" and lists AOSP files (`main.cpp`, `adb_wifi.cpp`,
`auth.cpp`, `services.cpp`). The actual daemon is a ~2.3k-line independent C
implementation (`src/main.c`, `auth.c`, `stream.c`, `service.c`, `proto.c`,
`util.c`, `log.c`) in a separate repository. Its own README says it is "not a
drop-in replacement for AOSP adbd" and lists deliberate omissions (USB, vsock,
mDNS, TLS, `reverse:`, `jdwp:`, `abb:`).

Impact: DESIGN.md §6's patch-set table, §11 Phase 2, and §12's "AOSP adbd
cross-compile and patch maintenance" risk are stale. The implementation may be
better or worse than the design, but the design cannot be used to review it. The
provenance wording ("stock AOSP adbd with a minimal patch set") is inaccurate.

Fix: either replace the daemon with the described AOSP fork or rewrite DESIGN.md
§6/§11/§12 to describe the independent daemon, its supported protocol subset,
and its test/porting strategy.

### P1-2 — `adb_allowed_connection_time=0` is missing

DESIGN.md §7 Boot step 2 says the service re-asserts `adb_allowed_connection_time=0`.
The app never writes it (`grep` finds no occurrence). Without it, Android may disable
wireless debugging after the framework's timeout, so the bootstrap path can fail after
the app has gone to the background.

Fix: set `Settings.Global.ADB_ALLOWED_CONNECTION_TIME` to `0` when bootstrapping,
and re-assert it on boot/watchdog.

**Status: fixed in `d546e4b`.** `SystemSettings.ensureNoAdbConnectionTimeout()`
writes the key by name (it is `@hide`) and verifies the read-back. `setup()`
re-asserts it while it re-opens the bootstrap path, so a boot goes through it, and
`watchdog()` re-asserts it on every tick. A failed write is logged, not fatal: the
framework default still leaves a window, and an OEM that ignores the key must not
block setup. Not yet re-verified on a device.

### P1-3 — The `persist.adb.tcp.port` probe is missing

DESIGN.md §9 says a reboot on cellular probes 5555, the last port, TLS on Wi-Fi, and
`persist.adb.tcp.port`. `SystemAdb.ensureConnected()` probes
`service.adb.tls.port`, `prefs.lastTlsPort`, listening shell ports, 5555, and mDNS
(`SystemAdb.kt:30-65`); it never reads `persist.adb.tcp.port`.

Impact: the documented cellular recovery path is incomplete.

Fix: read `persist.adb.tcp.port` and add it to the candidate set.

### P1-4 — `ACTION_USB_STATE` is not registered

DESIGN.md §6/§7 say the watchdog registers the sticky
`UsbManager.ACTION_USB_STATE` and handles USB attach/detach. `ControlService`
registers only a VPN `NetworkCallback` and a default `NetworkCallback`
(`ControlService.kt:665-688`). There is no `UsbManager` use anywhere.

Impact: USB state changes are only noticed indirectly by the 60s watchdog. The design's
USB flow (system adbd returns to TCP, warning, `ADB_ENABLED=0`) is not implemented.

Fix: register the sticky USB receiver and route it to `watchdog()`/`networkChanged()`.

### P1-5 — The watchdog is not exact and is not screen-state aware

DESIGN.md §6 says: poll loopback every 60 s while the screen is on and every 15 min
via `AlarmManager.setExactAndAllowWhileIdle` otherwise. The app polls every 60 s
unconditionally (`ControlService.kt:414-425`) and schedules with
`setAndAllowWhileIdle` (`ControlService.kt:690-705`).

Impact: unnecessary wakeups/battery drain while the screen is off, and the Doze wakeup
is inexact and can be delayed.

Fix: use `PowerManager.isInteractive` to choose 60 s vs 15 min, and
`setExactAndAllowWhileIdle` (with the exact-alarm permission/`canScheduleExactAlarms`
handling) for the 15-minute case.

### P1-6 — No backoff or hysteresis on failure

DESIGN.md §6 says "Backoff and hysteresis; never fight an explicit user 'off'."
`startWatchdogLoop` calls `setup()` every 60 s whenever `phase == Error` and
`setupComplete` (`ControlService.kt:414-425`). There is no attempt counter, no
exponential backoff, and no stop after repeated failures. If `harden()` keeps failing
(e.g. OEM blocks `ADB_ENABLED=0`), the app re-enables the bootstrap every minute,
which is exactly "fighting" the failure.

Fix: exponential backoff with a cap, a failure counter, and a stop state that requires
user action.

### P1-7 — Daemon accepts unlimited unauthenticated connections

`accept_thread` creates one detached thread per connection with no cap
(`main.c:300-335`). Each `connection_thread` allocates `ADB_MAX_PAYLOAD` (1 MiB)
before authentication (`main.c:170-175`). The daemon listens on loopback, which any app
on the phone can reach. A local app can open thousands of connections and exhaust
threads/memory.

Impact: local DoS against the app's own channel and the developer endpoint. This
contradicts §2 ("Keep ADB reachable") and is a production-readiness gap.

Fix: cap concurrent connections (e.g. 8-16), cap per-IP connections, allocate a small
pre-auth buffer (e.g. 4 KiB) until CNXN, set read/write timeouts, and reject idle
connections.

### P1-8 — The daemon log and stderr grow without bound

`adb_log_open` appends to `adbd.log` with `O_APPEND` and no rotation or size cap
(`log.c:adb_log_open`). The app's `startCommand` also redirects stderr to
`adbd.stderr` (`DaemonManager.kt:125-133`). Both live in `/data/local/tmp` as the
shell user.

Impact: a long-running daemon or a noisy developer can fill the phone's storage. The app
shows the log tail but never trims it.

Fix: cap the log (e.g. 1 MiB) and rotate/truncate; add a bounded ring buffer for the
UI; document the log location and retention.

### P1-9 — The app does not verify that `adb_wifi_enabled=0` stopped TLS

`harden()` now verifies the TLS listener (P0-5, fixed in `17277a2`), but
`abortSetup()` writes the setting and checks only `adb_wifi_enabled`, not
`service.adb.tls.port` or the closed port. If an OEM ignores the write during cleanup,
the app does not notice.

Fix: verify and fail explicitly if the TLS port remains.

### P1-10 — The app's verification of the VPN listener can be a false negative

`verifyDaemon(vpnIp)` connects from the app to `<vpn-ip>:<vpn-port>`
(`ControlService.kt:355-369`). DESIGN.md §12 acknowledges that a VPN app can exclude
the app from the tunnel: loopback still works but the VPN listener is unreachable. In that
case the daemon is healthy and developer machines can connect, but the app calls `fail()`.

Fix: verify the VPN listener from a context that is not subject to the app's VPN exclusion,
or treat a failed VPN verification as a warning (loopback verified) rather than a fatal error.

### P1-11 — The policy roles are effectively identical

`policy_allows` gives `app` every service and `developer` every service
(`service.c:policy_allows`). There is no per-service or per-command distinction. The
policy file is therefore cosmetic: both roles can `shell`, `exec`, `sync`, and `tcp`.

Impact: DESIGN.md D7 says "Patched adbd enforces per-key and per-service rules." The
implementation enforces authentication and a role label, but not a meaningful policy.

Fix: define a real policy matrix (e.g. `developer` = shell/sync/exec; `app` = shell/sync/exec/tcp;
or remove `tcp` from developer), and test allow/deny per role.

### P1-12 — `tcpip:<port>` bootstrap/port discovery is not implemented

DESIGN.md §7 says the plain target port is chosen by the app and set with
`tcpip:<port>`, stored in prefs, and re-applied after boot. The app never calls
`tcpip:` on the system adbd and has no plain-target-port preference. It only probes
5555 and TLS ports.

Impact: the design's port discovery and USB bootstrap flows are not met. The README and
the setup screen tell the user to run `adb tcpip:<loopback>`, which is a different port
from the design's stable plain target port.

Fix: either implement the plain target port (call `tcpip:<port>`, store it, re-apply it) or
remove it from DESIGN.md §7 and update the README/setup text.

### P1-13 — The design's network-change TLS switch is not implemented (and contradicts D5)

DESIGN.md §7 says: "If the active transport is Wi-Fi or Ethernet and no VPN is up, the app
switches the developer machine to the system TLS endpoint and shows it." The app never does
this; `networkChanged()` only restarts the daemon when the VPN IP changes
(`ControlService.kt:471-479`). This flow also contradicts D5 ("`ADB_WIFI_ENABLED=0` always
after the patched adbd is running"). The implementation follows D5 and not §7.

Fix: reconcile the design. If D5 is the intended policy, remove the §7 TLS switch and say
the developer endpoint is unavailable without a VPN. If §7 is intended, implement the switch
and qualify D5.

### P1-14 — `SystemAdb` can be spoofed on loopback

`probeDaemon()` and `verifyDaemon()` trust any process that accepts the app key on
`127.0.0.1:<loopback>` and returns `uid=2000` (`ControlService.kt:331-369`). A malicious
app can bind `127.0.0.1:15037` before the real daemon, accept the app's key, and answer
`id` with `uid=2000`. The app would then use the fake daemon for key writes and log reads
and would report Running.

Impact: local impersonation of the daemon; the app's "only entity that controls the ADB
listener" claim is not enforceable against a malicious local app. The attacker does not learn
the app's private key, but it can capture the developer public keys and the app's shell commands.

Fix: have the daemon prove possession of a per-install secret (e.g. a random token written
to an app-private file and signed by the daemon), or verify the daemon binary's hash and
PID/owner; at minimum, bind the daemon to a socket in an app-private directory instead of
a predictable TCP port.

### P1-15 — The app key generation is racy

**Status: fixed in the connection-audit follow-up.** `AppKeyStore` now has a single
`identity` lazy; `keyPair`, `certificate`, `publicKeyB64` and `publicKey` all derive
from it, and `loadOrCreate()` is synchronized.  On the emulator the old code produced
`CERTIFICATE_AND_PRIVATE_KEY_MISMATCH` during pairing; after the fix the same dialog
paired and the app key appeared in `dumpsys adb user_keys`.

`AppKeyStore.keyPair` and `certificate` were separate `by lazy` properties, each calling
`loadOrCreate()` (`AppKeyStore.kt:31-33`). `loadOrCreate()` was not synchronized. The service
read `keys.publicKey` from one coroutine while `AppViewModel` read it from another; on first
run both could generate different keypairs and write different files. The private key and certificate
could then mismatch, and the daemon rejected the app key.

Impact: first-run flakiness, especially on slow devices.

Fix: use a single synchronized/lazy `loadOrCreate()` result, or a `Mutex`/`synchronized`
around generation.

### P1-16 — `QrScanner` leaks the camera and blocks the main thread

`QrScanner` calls `ProcessCameraProvider.getInstance(context).get()` inside a
`LaunchedEffect` on the main dispatcher (`QrScanner.kt:101-104`). It never calls
`provider.unbindAll()` on dispose; `DisposableEffect` only shuts down the executor
(`QrScanner.kt:93-95`). The camera stays bound to the activity after the dialog closes.

Impact: camera/battery drain and a potential ANR on slow devices.

Fix: call `provider.unbindAll()` in `onDispose`, and use `addListener`/`await` off the
main thread.

### P1-17 — No CI and no device matrix

**Status: partially fixed in `ec83728` and `ed5d22b`.** `.github/workflows/release.yml`
runs the JVM tests on every release tag and manual run; `.github/workflows/ci.yml`
runs the JVM tests, `lintDebug` and `assembleDebug` on every push to `main` and
every pull request. The device matrix is still absent.

DESIGN.md §10 says CI runs `./gradlew test assembleDebug` and no device tests; the device
matrix is Pixel/Samsung/OnePlus, Tailscale/WireGuard, reboot, Wi-Fi↔cellular, USB
attach/detach, and Doze. The CI gate runs no device tests, and the only documented device
is one Samsung S23 with Tailscale. The emulator script no longer exercises the host `adb`
client (see P2-1).

Impact: no automated regression gate; the most important paths (two listeners, two keys,
key revocation, reboot, Doze, USB, WireGuard) are not covered by tests.

Fix: add a CI workflow (`test`, `assembleDebug`, `lint`) and a device/integration matrix.
At minimum, add a daemon integration case with two listeners and two keys, key reload,
and a revocation test.

### P1-18 — The release build has no versioning/signing/CI path

**Status: partially fixed in `ec83728`.** Signing, CI and tag-derived versioning are
in place (P0-8, fixed in `ec83728`). Release notes come from
`generate_release_notes`. There is still no update mechanism and no rollback plan.

Fix: define versioning, signing, and a release process.

---

## 5. P2 findings

### P2-1 — `docs/E2E.md` is stale

`docs/E2E.md:5-9` says the emulator script "runs the complete control plane … host
forward and a real `adb` shell" and "deliberately uses a host forward because the
emulator's loopback is not reachable from the host." The current
`scripts/e2e-emulator.sh` does not use `adb forward` or a host `adb` shell; it runs a
debug shell through the app and checks the daemon log and UI. The host `adb` path is
now only in the daemon repository's integration test.

Fix: update `docs/E2E.md` and the script comment to describe what is actually
exercised, and add a real host-forward/`adb connect` case if that is still required.

### P2-2 — The app's policy/watchdog unit coverage is missing

DESIGN.md §10 lists a policy evaluator and a watchdog state machine as unit tests.
The app tests public-key parsing, key files, address selection, and pairing-code
parsing; there is no policy evaluator test and no watchdog state-machine test. The
daemon has policy tests, but not the app's state machine.

Fix: add tests for `policy_allows` (daemon) and a pure `WatchdogState`/decision
function in the app, and test it with fake time and fake probes.

### P2-3 — The daemon integration test does not cover two listeners or two keys

`tests/integration.sh` uses one listener, one key, shell v1/v2, exec-out, push/pull,
forward, and key rejection. DESIGN.md §10 asks for two listeners, auth with two keys,
policy allow/deny, and key reload. The phone evidence covers two keys manually, but the
automated test does not.

Fix: extend the integration test with a second listener and a second key, a policy
allow/deny case, and a key-reload case.

### P2-4 — `SystemAdb` uses private API reflection

`SystemAdb.kt:103` reflects `android.os.SystemProperties` as a fallback after
`getprop`. Lint warns `PrivateApi`; hidden APIs are blocked or changed across
releases. The `getprop` path is sufficient for `service.adb.tls.port`.

Fix: remove the reflection fallback, or use a supported API.

### P2-5 — Unused permissions

**Status: fixed in `8b14729` and corrected with the start/stop control.**
`app/src/main/AndroidManifest.xml` no longer declares `CHANGE_WIFI_STATE` or
`ACCESS_WIFI_STATE`. `CHANGE_NETWORK_STATE` stays: it is not used by app code, but
the `connectedDevice` foreground service type is refused without one of
`CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE` or the Bluetooth/NFC/USB permissions
(`0c54704`). Removing it made `startForeground()` throw a `SecurityException`; the
service never reached the foreground, the notification disappeared, and the system
killed the app with `ForegroundServiceDidNotStartInTimeException` about 15 s after
each start, including the one a Stop tap performs. The `io.github.muntashirakon.adb`
library still contributes `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, and
`READ_PHONE_STATE` as implied permissions (P2-14).

The manifest declared `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, and
`ACCESS_WIFI_STATE` but the app uses none of them (`grep` finds no use).
`ACCESS_NETWORK_STATE` is used by `ConnectivityManager`; the others are not.

Fix: remove the unused permissions, except the foreground-service prerequisite
above.

### P2-6 — `SettingsScreen` does not show the current ADB state

**Status: fixed in `068201e`.** The Settings security card reads `adb_enabled`,
`adb_wifi_enabled` and a `127.0.0.1:5555` connect probe from the platform
(`SystemSettings.status()`), shows them with a refresh action, and renders the
warnings from `AdbStatus.warnings()` in the error color (`AdbStatusTest`).
`HomeScreen`'s `SecurityRow` now shows the same platform state through
`ControlState.adbStatus` (P0-6, fixed in `2b73e78`).

D11 says the settings UI "shows the current ADB state and any warning". The Settings
screen shows the keep-off switch and a generic note; it does not show
`adb_enabled`/`adb_wifi_enabled` or whether 5555 is open. `HomeScreen`'s
`SecurityRow` inferred the state from the phase (P0-6, fixed in `2b73e78`).

Fix: read and display the actual settings and the 5555 probe result.

### P2-7 — `SystemAdb.listeningShellPorts()` is unreliable on Android 10+

It reads `/proc/net/tcp*` for uid 2000. Android 10+ restricts `/proc/net/tcp` to the
app's own sockets, so this fallback likely sees nothing on modern devices. It is only a
fallback, but the design presents it as a candidate.

Fix: rely on `getprop`/mDNS, or document the limitation.

### P2-8 — `DaemonManager.restartDetached` has a restart race

**Status: fixed.** `restartDetached()` writes a detached script that kills
every vendored adbd found through `/proc/<pid>/cmdline` by its `--listen`
argument, waits for all of them to disappear, and only then starts the new one.
The script writes a PID marker next to itself, so the next start waits for an
in-flight restart instead of racing its kill/start pair. `waitForRestart()` waits
for the old loopback listener to close and fails instead of verifying a process
that never died.

It killed the old daemon and started the new one in a detached script, then `waitForPort`
returned as soon as the old daemon's port was still open. The subsequent `probe(vpnIp, vpnPort)`
can connect to the old daemon, so `verifyDaemon` can verify the old daemon and the new
daemon may not be up yet.

Fix: wait for the old PID to disappear or the new PID file to change before probing.

### P2-9 — `DaemonManager.stop` relies on `ps`/`awk`/`xargs` availability

**Status: fixed.** `stop()` no longer uses `ps`/`awk`/`xargs`; it scans
`/proc/<pid>/cmdline` for `--listen`, kills every match and waits for it to
disappear. `deployAndStart()` runs it before starting.

The stale-process cleanup used `ps -A -o PID,NAME,ARGS`, `awk`, and `xargs -r`
(`DaemonManager.kt:143-151`). Toybox variants differ; if any is missing, the cleanup
silently fails. The pid file is the primary path, but a daemon that loses its pid file is
not cleaned.

Fix: verify the tools or use `/proc` directly.

### P2-10 — `Shell.exec` timeout does not cover a hung read

`execV2` checks the deadline only after a frame is read (`Shell.kt:55-81`). `readFully`
blocks on the socket, so a hung command (e.g. `cat` on a FIFO, a daemon that stops
responding) can block the IO coroutine indefinitely and hold `setupMutex`.

Fix: set `SO_TIMEOUT` on the socket or run the read with a coroutine timeout and close
the stream on timeout.

### P2-11 — `SystemAdb.ensureConnected` can treat a daemon as the system adbd

The candidate list includes `prefs.loopbackPort`? It skips `prefs.loopbackPort` and
`prefs.vpnPort` (`SystemAdb.kt:56`), but if the prefs were reset or the daemon is on a
different port, `listeningShellPorts()` can include the daemon. The app could then run
`pm grant` through its own daemon and use it as the bootstrap. Not a security boundary
(shell is shell), but a state confusion.

Fix: verify the system adbd's identity (e.g. the system adbd's TLS certificate or the
`ro.adb.secure` property) before using it.

### P2-12 — `libadb-android` is a JitPack dependency with a trust-all TLS manager

`app/build.gradle.kts:119` uses `com.github.MuntashirAkon:libadb-android:3.1.1` from
JitPack. Lint reports `TrustAllX509TrustManager` in libadb and BouncyCastle. For ADB
pairing, the pairing code is the authentication and the certificate is self-signed; for the
system TLS connect, ADB key auth is the authentication. The trust-all is expected in ADB, but
it is a supply-chain and review risk: the dependency is not pinned by hash/lockfile and the
JitPack artifact is built on demand.

Fix: vendor or mirror the dependency, pin a checksum, and document why trust-all is
acceptable for ADB's TLS model.

**Status: won't fix for the trust-all — accepted by design (owner decision).** Every
release connect and pair target is loopback or app-controlled: `SystemAdb` dials `127.0.0.1`
on a port from `service.adb.tls.port`, the last known port, mDNS, or 5555;
`ControlService` and `AppViewModel` dial `127.0.0.1:loopbackPort`; the VPN check dials
the app's own daemon. The only arbitrary-host path, `prefs.bootstrapHost`, is set from
the debug-only intent and is `BuildConfig.DEBUG`-gated. `SslUtils` therefore never sees
an untrusted peer, and ADB key auth (or the pairing code) is the authentication on every
path. The supply-chain half is not accepted: it is tracked as the dependency-verification
P3 below.

### P2-13 — Debug-only surfaces are broad

`DebugReceiver` is exported in debug builds and accepts `DEBUG_PAIR` with an arbitrary
`code:port`; `MainActivity` accepts `addkey`, `shellcmd`, `bootstrapHost`,
`remoteDir`, `loopbackPort`, `vpnPort`, `logkey`, `start` and `stop` extras in debug
builds. These are
debug-only (`BuildConfig.DEBUG` and `debugReceiverEnabled`), but they are powerful and must
never be enabled in a release. The release manifest sets `debugReceiverEnabled=false`, and
`MainActivity.handleIntent` returns early when `!BuildConfig.DEBUG`. This is acceptable if
debug builds are never distributed.

Fix: document the debug-only surface; consider gating `DebugReceiver` behind a signature
permission as well.

### P2-14 — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a Play policy issue

**Status: won't fix — distribution is sideload-only (owner decision).** The app is not
distributed through Play, so the content-policy rule does not apply. The permission and
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` stay: the daemon must survive Doze to keep
the listener reachable, and the direct request is the shortest path to that on a device the
user controls. Revisit only if the app is ever submitted to Play.

Lint warns that `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` violates the Play Store
content policy for acceptable use cases. For sideload-only distribution this is fine; for Play
it is a blocker.

Fix: decide the distribution channel; if Play, use the standard battery-optimization
settings intent instead.

### P2-15 — Licensing is inconsistent

**Status: fixed in `d7cfaed`.** The app's own code is MIT and `LICENSE` holds the text.
The vendored daemon stays Apache 2.0 with its own `adbd/LICENSE`; `NOTICE` is now a
third-party attribution file without the app-wide Apache trailer, and DESIGN.md §13,
README.md and adbd/README.md describe the same split.

DESIGN.md §13 said "The app's own code is MIT." `NOTICE` said the app was licensed under
the Apache License 2.0 and there was no `LICENSE` file in the app repository. The daemon
fork has Apache 2.0. The app's README did not state a license.

Fix: choose one license for the app, add `LICENSE`, and align DESIGN.md §13 and `NOTICE`.

### P2-16 — The app's `themes.xml` is a legacy platform theme

The app uses `android:Theme.Material.NoActionBar` with a transparent window background
and Compose. It works, but it is not a Material 3 XML theme and can flash on launch. Lint
also reports unused `notification_channel_*` strings.

Fix: use a Compose-compatible splash/theme and clean up unused resources.

**Status: fixed in `9c10d0c`.** The theme is now a launch-window theme: the splash
screen and the window behind it share the opaque brand color, and the splash icon is the
adaptive launcher icon, so a cold start no longer shows a transparent window. On
`minSdk 31` the platform splash API is always available, so the unused
`core-splashscreen` dependency is gone. The two `notification_channel_*` strings are
removed (the channel names in `AdbAnywhereApp` were already hardcoded) and lint's
`UnusedResources` is clean. The parent stays `android:Theme.Material.NoActionBar` on
purpose: the app has no View-based UI, so a Views Material 3 XML theme would pull in the
Material Components library for attributes no Compose screen reads; the UI theme is Compose
Material 3 in `Theme.kt`.

---

## 6. P3 findings

- `ScaffoldTest` is a placeholder (`2 + 2 == 4`), not a real test. Replace it or remove it.
- `syncAdbd` builds the vendored daemon on every `preBuild`. **Status: fixed.** The
  binaries are no longer committed: CI compiles them in a reusable `adbd` workflow,
  caches them keyed on the build inputs and the pinned NDK, and both CI and the
  release ship the artifact built for that commit.
- `Prefs` uses `SharedPreferences.apply()` per field; acceptable, but a single transaction
  would be cleaner. **Status: won't fix.** `apply()` updates the in-memory map synchronously
  before it returns, and every reader (service and UI) is in the app's single process — the
  service declares no `android:process` — so a partial read is not observable. The two
  multi-field writers, `setPorts` and `setDebugBootstrap`, complete both writes before they
  send `ACTION_APPLY_SETTINGS`, which is the only point where the service re-reads them. A
  `savePorts()`/`saveKeys()` API would add surface and change no behaviour; the second
  `apply()` costs one small extra XML write on a rare settings change.
- `Theme.kt` has `SDK_INT >= 31` checks that are always true for `minSdk 31`.
  **Status: fixed in `4a5b8f5`.** `minSdk` is still 31, so the checks were dead and the
  hardcoded fallback palettes were unreachable; the theme now uses the dynamic light/dark
  schemes directly and lint's `ObsoleteSdkInt` is clean.
- `mipmap-anydpi-v26` is unnecessary for `minSdk 31`. **Status: fixed in `8310f3a`.**
  `ic_launcher.xml` now lives in `mipmap-anydpi`; lint no longer reports
  `ObsoleteSdkInt` for the folder.
- The notification small icon is `android.R.drawable.ic_dialog_info`, not a proper
  monochrome app icon. **Status: fixed in `0591109`.** The service and pairing
  notifications now use `R.drawable.ic_notification`, a white-on-transparent 24 dp
  vector; the pairing action icon still uses the framework glyph.
- `Docs/E2E.md` and `README.md` use future-dated evidence (2026-11-03); confirm the date
  is intentional.
- `libadb-android:3.1.1` and its `spake2-android:2.2.1` transitive are JitPack artifacts
  built on demand from a tag, and nothing verifies their bytes. Add Gradle dependency
  verification (`gradle/verification-metadata.xml`) with checksums for both. Vendoring is not
  needed: the library only provides connect, pair and stream multiplexing, and a fork would
  cost more than it buys. The trust-all half of P2-12 is a won't fix.

---

## 7. Design conformance matrix

| DESIGN.md | Status | Evidence |
|---|---|---|
| §2 Goals: reachable across reboots/sleep/network, including mobile data over VPN | **Partial** | Daemon and VPN restart work; a sticky restart or watchdog alarm now resumes setup (P0-4, fixed in `284b75b`), not verified under Doze/LMK; OEM bootstrap limitation is documented. |
| §2 Never expose plain ADB on an untrusted LAN | **Partial** | Daemon binds loopback + VPN only; setup and the watchdog re-assert and verify the system listener (P0-5, fixed in `17277a2`); the UI shows the platform state and the residual warning (P0-6, fixed in `2b73e78`); failed setup now closes it (P0-2, fixed in `a99716e`). |
| §2 App is the only entity that controls the listener / decides who connects | **Partial** | App controls deployment, but shell can rewrite the key file; revocation restarts the daemon and closes live sessions (P0-3, fixed in `822fc50`); loopback can be spoofed (P1-14). |
| §2 No root, no custom ROM, no permanent web server | **Met** | Daemon runs as shell; no web server. |
| §4 Verified platform constraints | **Mostly met** | Implementation relies on `ADB_ENABLED`, `adb_wifi_enabled`, `service.adb.tls.port`, `pm grant`; §4.6 key file path is replaced by the daemon's key file. |
| §5 D1 Android 12+ | **Met in code** | `encode()` no longer uses the API-33 `BigInteger.TWO` (P0-1, fixed in `a21654d`); no API 31/32 runtime run yet. |
| §5 D2 One patched adbd with two listeners | **Functional, not as designed** | Two listeners work, but the daemon is an independent implementation, not a patched AOSP adbd (P1-1). |
| §5 D3 App key ephemeral, private in app storage, public in daemon key file | **Met** | `AppKeyStore`, `KeyFiles`, `DaemonManager`. |
| §5 D4 QR/paste pairing, fingerprint confirmation, label, no listener | **Met** | `KeysScreen`, `AndroidPubKey`, `DeveloperKey`; no pairing listener. |
| §5 D5 `ADB_WIFI_ENABLED=0` always; `ADB_ENABLED=0` only with the opt-in switch | **Met** | `harden()` and the watchdog re-assert and verify `ADB_WIFI_ENABLED=0` (P0-5, fixed in `17277a2`); `ADB_ENABLED` is written only by the opt-in *Turn system adb off on Stop* switch (default off); failed setup stops only the patched adbd. |
| §5 D6 Watchdog keeps D5, probes 5555, restarts daemon | **Met** | Restarts daemon; never writes `ADB_ENABLED`; probes 5555 for the §8 residual warning (P0-6, fixed in `2b73e78`); re-asserts and verifies `ADB_WIFI_ENABLED=0` every tick (P0-5, fixed in `17277a2`); no backoff; a sticky restart or watchdog alarm resumes setup (P0-4, fixed in `284b75b`). |
| §5 D7 Patched adbd per-key/per-service policy | **Partial** | Roles exist, but both allow all services (P1-11). |
| §5 D8 Kotlin/Compose/Material 3/package | **Met** | App sources and manifest. |
| §5 D9 Bootstrap pairing notification with one `code:port` reply | **Met** | `ControlService.postPairingNotification`, `PairReplyReceiver`; the in-app form was removed because opening the app closes Android's pairing dialog. |
| §5 D10 TCP only; USB/vsock disabled | **Met** | Daemon has no USB/vsock code. |
| §5 D11 Settings UI shows current ADB state, warning and the opt-in switch | **Met** | Settings reads `adb_enabled`, `adb_wifi_enabled` and the 5555 probe, shows the warnings (P2-6, fixed in `068201e`) and the *Turn system adb off on Stop* switch; `HomeScreen` shows the same platform state and warnings (P0-6, fixed in `2b73e78`). |
| §5 D12 Explicit errors, no silent fallback/random-port redirect | **Partial** | Many errors are explicit; some failures are swallowed (`startForeground`, `postPairingNotification`, `scheduleWatchdog`). |
| §6 Patch set in `daemon/` | **Not met** | No `daemon/` directory; independent daemon (P1-1). |
| §6 Key provisioning: app key only at startup, developer keys appended | **Partial** | App writes app + developer keys before start; app key is not the only startup key (minor). |
| §6 Developer machine pairing: QR/paste, fingerprint, label, no listener | **Met** | App UI and key parsing. |
| §6 System adbd control: mandatory `ADB_WIFI_ENABLED=0`, optional `ADB_ENABLED=0` | **Met in code** | `harden()` and the watchdog re-assert `adb_wifi_enabled=0` and verify the TLS port (`WirelessDebuggingGuardTest`); not re-verified on a device (P0-5, fixed in `17277a2`). |
| §6 Port behavior: app never calls `adb tcpip` on system adbd | **Met** | No `tcpip` call in app. |
| §6 Watchdog: 60 s screen-on / 15 min otherwise, exact alarm, USB + network callbacks, backoff/hysteresis | **Not met** | 60 s always, `setAndAllowWhileIdle`, no USB callback, no backoff (P1-4, P1-5, P1-6). |
| §7 First run | **Met** | Implemented end-to-end (modulo P0-2 cleanup not re-verified on a device). |
| §7 Port discovery: TLS primary, mDNS fallback, manual port, `tcpip:<port>` | **Partial** | TLS/mDNS/manual implemented; `tcpip:<port>` not (P1-12). |
| §7 Boot: boot receiver, re-assert `adb_wifi_enabled` and `adb_allowed_connection_time=0`, probe, deploy, harden, manual recovery | **Partial** | Boot receiver and probe implemented; `adb_allowed_connection_time=0` is re-asserted on bootstrap and every watchdog tick (P1-2, fixed in `d546e4b`); `persist.adb.tcp.port` missing (P1-3). |
| §7 Network change: VPN restart, VPN down, TLS switch | **Partial / contradicts D5** | VPN restart and VPN down implemented, coalesced, and re-checked by the watchdog; TLS switch not implemented (P1-13). |
| §7 USB: `ACTION_USB_STATE`, warning, `ADB_ENABLED=0` | **Not met** | No USB receiver (P1-4). |
| §8 Security: VPN ACL outer, key file/policy inner, loopback app channel, system adbd off, residual warning, pairing, keys, revocation, no root | **Partial** | Key auth and loopback are good; the residual warning now comes from the platform state (P0-6, fixed in `2b73e78`); revocation now restarts the daemon and closes live sessions (P0-3, fixed in `822fc50`); system adbd is re-asserted and verified by the watchdog (P0-5, fixed in `17277a2`); `abortSetup()` does not verify the port (P1-9). |
| §9 Failure modes | **Partial** | Many are explicit; a sticky restart or watchdog alarm resumes setup (P0-4, fixed in `284b75b`). |
| §10 Testing: unit (QR/key/policy/address/watchdog), integration (two listeners/two keys/policy/key reload), device matrix, CI | **Partial** | App tests 29, including `AdbStatusTest` and `WirelessDebuggingGuardTest`; daemon tests 131 checks + integration; CI runs tests and lint on push/PR; no watchdog/backoff tests, no two-listener/two-key integration, no device matrix. |
| §11 Phasing: Phase 0 report, Phase 1 control plane, Phase 2 patched adbd, Phase 3 depth | **Partial** | Phase 0 report absent; Phase 1/2 functional; Phase 3 not implemented. |
| §12 Open risks | **Mostly acknowledged** | The design acknowledges the main risks; some are unhandled (process death, failure cleanup). |
| §13 Provenance | **Partial** | `LICENSE` (MIT) and `adbd/LICENSE` (Apache-2.0) are aligned with `NOTICE` and DESIGN.md (P2-15, fixed in `d7cfaed`); "stock AOSP adbd" wording is inaccurate (P1-1). |

---

## 8. Security review

### Strengths

- No wildcard bind: `listen_parse` rejects `0.0.0.0`.
- No USB, vsock, mDNS, or TLS listener in the daemon.
- RSA-2048 key auth with PKCS#1 v1.5/SHA-1 verification, random 20-byte
  tokens, three attempts, and key-file-only authorization. Client-offered keys are
  logged and ignored.
- Key file and policy are `0600` and written through the daemon's shell; the app key
  is the first line.
- The app uses `allowBackup=false`, device-protected storage, and a
  `connectedDevice` foreground service.
- The app's key import validates RSA-2048, rejects duplicates, and shows the
  fingerprint before adding.
- The app never calls `adb tcpip` on the system adbd and has no proxy fallback.

### Weaknesses

- **Bootstrap exposure on failure** (P0-2, fixed in `a99716e`): `abortSetup()`
  now closes the system listener and restores `ADB_ENABLED`; not re-verified on a
  device.
- **Revocation** (P0-3, fixed in `822fc50`): removing a key restarts the
  daemon, so the sessions that the key authenticated end; verified on the SM-S911B.
- **System listener on a failed setup** (P1-9): `abortSetup()` checks the setting,
  not the closed port; `harden()` and the watchdog now verify it (P0-5, fixed in
  `17277a2`).
- **Exported boot receiver** (P0-7, fixed in `c31f59c`): the receiver is not
  exported and only acts on the three boot actions.
- **Local daemon spoofing** (P1-14): loopback identity is not proven.
- **Daemon DoS** (P1-7): unlimited unauthenticated connections/threads.
- **Shell = policy authority** (P1-11): any developer key can rewrite the key
  file and policy, so the app is not the only entity that decides who connects.
  This is inherent to running the daemon as `shell`; the design should state it
  explicitly or move the key file to a location the shell cannot write (not possible
  without root/system privileges).
- **`tcp:` service** (P1-11): developer keys can use the phone as a TCP proxy
  into its LAN/VPN. Since they already have shell, this adds no new privilege, but
  it widens the blast radius and should be a deliberate policy decision.
- **Unbounded log** (P1-8): a developer can fill storage.

### Threat-model note

The design's outer boundary is the VPN ACL. The app does not enforce that ACL;
it trusts the VPN app. If the VPN app routes the phone's VPN address to the LAN or
to the internet, the daemon is reachable there. The README should state that the
VPN's ACL is the only network boundary and that the app cannot verify it.

---

## 9. Reliability and operations

- A sticky restart or a watchdog alarm resumes setup when a previous run
  completed it (P0-4, fixed in `284b75b`); not verified under Doze/LMK.
- No backoff (P1-6), so persistent failures loop every 60 s.
- No log rotation (P1-8), so storage can fill.
- No connection cap (P1-7), so a local app can DoS the daemon.
- No health/readiness signal beyond the UI phase and log tail; no metrics,
  no alerting, no crash reporting.
- No upgrade/migration path for the key file, policy file, or prefs.
- No uninstall cleanup: the daemon, `/data/local/tmp/adb-anywhere`, and the
  system settings (`ADB_ENABLED`, `adb_wifi_enabled`) are not restored on
  uninstall. The app cannot run on uninstall without a receiver; document the
  manual cleanup.
- `adb_allowed_connection_time=0` is re-asserted on bootstrap and every
  watchdog tick (P1-2, fixed in `d546e4b`), so the framework does not disable
  wireless debugging on its timeout.
- No `persist.adb.tcp.port` probe (P1-3), so the documented cellular recovery
  is incomplete.

---

## 10. Testing and CI

Current automated coverage:

| Area | App | Daemon |
|---|---|---|
| Public-key encode/decode/parse/fingerprint | Yes (8 tests) | Yes (auth tests) |
| Key file/policy writer | Yes | Yes |
| Pairing code parser | Yes | N/A |
| Address selection | Yes | N/A |
| Policy evaluator | No | Yes (C unit test) |
| Watchdog state machine | Partial (D5 guard only; `WirelessDebuggingGuardTest`) | N/A |
| QR payload parser | Indirect (QR text → `AndroidPubKey.parse`) | N/A |
| Shell v1/v2, exec, sync, forward | No (app uses libadb) | Yes (integration) |
| Two listeners | No | No |
| Two keys | No (phone evidence only) | No |
| Key reload | No | No |
| Revocation/live session | No | No |
| Reboot/Doze/USB/network changes | No | No |
| Android 12 runtime | No | N/A |

Gaps:

- `ScaffoldTest` is a placeholder.
- The push/PR gate runs the JVM tests, `lintDebug` and `assembleDebug`
  (`.github/workflows/ci.yml`); it is not a device matrix.
- No device matrix; only one phone is documented.
- The emulator script no longer exercises the host `adb` client (P2-1).
- The daemon integration test is good but narrow.

---

## 11. Release engineering

- `assembleRelease` is signed when `signing/keystore.properties` or the CI secrets
  are present (P0-8, fixed in `ec83728`).
- `versionName`/`versionCode` come from the release tag; a plain checkout keeps
  `0.1.0`/`1` (P1-18).
- `isMinifyEnabled = false`; no shrinking/obfuscation. For a small app this is
  acceptable, but it means the debug code paths remain in the APK (they are
  guarded by `BuildConfig.DEBUG` and the manifest placeholder).
- Tag workflows run the tests, build, sign and publish a release (P1-17 partially
  fixed in `ec83728`); push/PR runs the JVM tests, `lintDebug` and
  `assembleDebug` (P0-9, fixed in `ed5d22b`). No update channel and no rollback
  plan.
- `syncAdbd` builds the vendored daemon from source; the binaries are not
  committed. CI builds them when their inputs change, caches them on the pinned NDK
  and the release ships the artifact built for that commit, so the shipped daemon is
  reproducible and a local build needs the NDK.
- No dependency lockfile/verification; `libadb-android` and `spake2-android` come from
  JitPack and are unverified (P3).
- No privacy policy/data-safety declaration. The app collects no analytics, but it
  does handle ADB keys and logs; document that.

---

## 12. Documentation and licensing

- DESIGN.md is stale: it describes an AOSP patch set that does not exist, a
  `daemon/` directory that does not exist, and flows (`tcpip` port, TLS switch)
  that are not implemented.
- Licensing is aligned: the app is MIT (`LICENSE`), the vendored daemon is
  Apache 2.0 (`adbd/LICENSE`), and `NOTICE` lists third-party attributions only
  (P2-15, fixed in `d7cfaed`).
- `docs/E2E.md` claims a host forward and a real `adb` shell that the script no
  longer performs.
- `README.md` is accurate on the core model but does not state the security
  limitations (shell can rewrite the key file; failure paths can leave the system
  listener on).
- The phone evidence is good and specific; keep it, but mark it as manual evidence
  and add the date/commit.

---

## 13. Remediation plan

### Phase A — P0 before any production claim

1. Fix `BigInteger.TWO` (P0-1) and add an Android 12 runtime test.
2. Add a `finally` cleanup to `setup()`: on any failure after bootstrap,
   set `adb_wifi_enabled=0`, stop the daemon, restore `ADB_ENABLED` (P0-2).
3. Force a daemon restart (or a live-session close) on key removal (P0-3).
4. Handle `intent == null`/`Idle` in `onStartCommand` by calling `setup()`
   (P0-4).
5. Re-assert and verify `adb_wifi_enabled=0` in the watchdog (P0-5, fixed in
   `17277a2`; P1-9 remains for `abortSetup()`).
6. Add explicit ADB state/warning fields to `ControlState` and stop inferring
   "off" from the phase in `HomeScreen`; keep the 5555 warning (P0-6, fixed in
   `2b73e78`). Settings reads the state directly (P2-6, fixed in `068201e`).
7. Set `BootReceiver` `exported=false` and check the action (P0-7, fixed in
   `c31f59c`).
8. Add release signing or document external signing (P0-8, fixed in `ec83728`).
9. Fix the lint errors and add lint to CI (P0-9, fixed in `5efcaec` and
   `ed5d22b`).

### Phase B — P1 before release

1. Reconcile DESIGN.md with the independent daemon, or replace the daemon with
   the described AOSP fork (P1-1).
2. Add the `persist.adb.tcp.port` probe and `ACTION_USB_STATE` (P1-3, P1-4).
3. Make the watchdog exact/screen-aware/backoff (P1-5, P1-6).
4. Cap daemon connections, add pre-auth limits/timeouts, rotate logs
   (P1-7, P1-8).
5. Add daemon identity proof or a private socket for the app channel (P1-14).
6. Fix the `AppKeyStore` race (P1-15) and the `QrScanner` lifecycle (P1-16).
7. Add CI and a two-listener/two-key/key-reload/revocation test (P1-17).
8. Define a real policy matrix or document that roles are equivalent (P1-11).
9. Resolve the `tcpip` port and network-change contradictions in DESIGN.md
   (P1-12, P1-13).

### Phase C — P2/P3 polish

1. Update `docs/E2E.md` and `README.md`.
2. Remove unused permissions and private API reflection.
3. Add `LICENSE` and align DESIGN.md §13 with `NOTICE` (fixed in `d7cfaed`).
4. Clean up `ScaffoldTest`, unused strings, and the legacy theme (strings and theme
   fixed in `9c10d0c`).
5. Document the debug-only surface and the uninstall cleanup.
6. Pin the JitPack artifacts with Gradle dependency verification (P3).

---

## 14. Open questions for the owner

1. **Distribution**: resolved — sideload-only, not Play. P2-14 is therefore a won't fix,
   and the signing/CI findings carry their own weight (P0-8 and P0-9 are fixed).
2. **Daemon**: is the independent daemon the intended implementation, or is the
   design's AOSP patch set still the target? If the former, DESIGN.md must be
   rewritten; if the latter, the daemon is a rewrite, not a patch.
3. **Android 12**: still supported? If yes, P0-1 is a blocker. If no, raise
   `minSdk` to 33 and update D1.
4. **Policy**: should `developer` be less privileged than `app`? If yes, define the
   matrix. If no, remove the policy file or document that it is only a label.
5. **Live revocation**: resolved — yes. Revocation restarts the patched daemon,
   which closes live sessions (P0-3, fixed in `822fc50`); the UI now says so.
6. **Plain TCP bootstrap**: is `adb tcpip:<port>` still required? If yes, implement
   it; if no, remove it from DESIGN.md §7.
7. **Network-change TLS switch**: is DESIGN.md §7's TLS fallback still desired?
   It contradicts D5. Choose one and update the other.
8. **`adb_allowed_connection_time=0`**: resolved — the design mandates it
   (P1-2, fixed in `d546e4b`). The app writes it best-effort and logs a
   failure, because the write needs `WRITE_SECURE_SETTINGS` and an OEM could
   ignore it; setup does not fail over it. Not yet re-verified across OEMs.

---

## 15. Bottom line

The core product idea works and the phone evidence is credible. The daemon is
small and auditable, the auth path is careful, and the app's UX is clear. But the
implementation is a prototype, not a production system: Android 12 crashes, failed
setups can leave the system listener on, revocation is not live, process-death
recovery is broken, and the UI can claim "off" when it is not. The design is also stale:
it describes a patched AOSP `adbd` and flows (`tcpip`, TLS switch) that do not
exist. Fix the P0 items, reconcile the design, add CI/signing, and add the missing
security and recovery tests before calling this production-ready.
