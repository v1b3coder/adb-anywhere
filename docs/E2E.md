# End-to-end test

## Emulator

`scripts/e2e-emulator.sh` runs the complete control plane on a rooted
`emulator-5556`: install, permission grant, app start, app-key authorization,
debug developer-key add, daemon deploy, host forward and a real `adb` shell.

It deliberately uses a host forward because the emulator's loopback is not reachable
from the host. On a phone the daemon's VPN listener is reached directly.

## Phone

The phone test is the real product path:

1. Install the debug APK and open it once.
2. Add the host public key with the app's *Keys → Add key* flow.
3. Open Settings → Developer options → Wireless debugging → *Pair device with
   pairing code*.
4. Read `code:port` from the dialog and pair from the app (notification reply or
   the manual field).
5. The app deploys the daemon with `127.0.0.1:15037` and
   `<vpn-ip>:15038`, verifies both and turns Android's wireless debugging off.
6. From the developer machine run:

   ```sh
   adb connect <vpn-ip>:15038
   adb -s <vpn-ip>:15038 shell id
   ```

   The identity must be `uid=2000(shell)`.

7. Remove the developer key in *Keys* and confirm that an open connection drops
   and the next connection is rejected.
8. Reboot the phone with Wi-Fi on and confirm the app re-asserts the bootstrap,
   redeploys and turns wireless debugging off again.

## Phone result (2026-11-03)

Verified on a Samsung SM-S911B, Android 16, stock, no root:

- The app paired with Android's wireless debugging over `127.0.0.1` using the
  six-digit code from the Settings dialog (the debug receiver keeps the dialog open).
- The app deployed `/data/local/tmp/adb-anywhere/adbd` and bound
  `127.0.0.1:15037` and `100.64.0.10:15038`.
- `adb connect 100.64.0.10:15038` from the host authenticated with the paired
  `phone-laptop` key; `shell id` returned `uid=2000(shell)`.
- `adb push` and `adb pull` through the VPN listener worked.
- The app turned Android's wireless debugging off (`adb_wifi_enabled=0`) while
  keeping `adb_enabled=1`; the system adbd stayed on USB only and the patched daemon
  remained the only TCP endpoint.
- The daemon log shows `auth_ok` with `label=app role=app` and
  `label=phone-laptop role=developer`; `policy.tsv` has the same split.

### Watchdog limitation found

Killing the daemon on the phone while the phone's Wi-Fi had no internet exposed an
OEM limitation: Android refused to re-enable the system TLS server after the app
re-asserted `adb_wifi_enabled=1`, so the watchdog could not reach the system adbd to
redeploy. The app showed the explicit error and did not fall back to a proxy or a
random port, as required by the design. Recovery is the documented one-Wi-Fi-connection
step: enable Wireless debugging in Settings once, then the app redeploys. On the emulator
the watchdog restart path is covered by the daemon integration test and the app's
foreground service; the phone's Wi-Fi gating is outside app control.
