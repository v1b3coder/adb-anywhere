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

7. Remove the developer key in *Keys* and confirm that the next connection is
   rejected.
8. Reboot the phone with Wi-Fi on and confirm the app re-asserts the bootstrap,
   redeploys and turns wireless debugging off again.
