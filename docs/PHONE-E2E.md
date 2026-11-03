# Phone end-to-end evidence

Device: Samsung SM-S911B (Galaxy S23), Android 16, stock, no root.
Transport: Tailscale address `100.64.0.10`.

## Pairing and deployment

The app was installed as a debug build and the host key was pre-added through
*Keys*. The Settings pairing dialog showed:

```
pairing code: 611305
pairing port: 43177
connect port: 100.64.0.10:34257
```

The app paired over loopback with `PairReplyReceiver`/`DebugReceiver` while the
dialog stayed open, then deployed the daemon with:

```
--listen tcp:127.0.0.1:15037,tcp:100.64.0.10:15038
--keys /data/local/tmp/adb-anywhere/adb_keys
--policy /data/local/tmp/adb-anywhere/policy.tsv
```

The daemon log on the phone showed:

```json
{"event":"auth_ok","message":"peer=127.0.0.1:33564 key=508b... label=app role=app"}
{"event":"auth_ok","message":"peer=100.64.0.1:48168 key=ec15... label=phone-laptop role=developer"}
{"event":"service_open","message":"... service=shell,v2,TERM=unknown,raw:id; echo phone-daemon-e2e-ok; getprop ro.product.model"}
```

The key file contained the app key and the `phone-laptop` key; `policy.tsv`
contained:

```
508b83a5... app
ec152a12... developer
```

## Host client

```
$ adb connect 100.64.0.10:15038
connected to 100.64.0.10:15038
$ adb -s 100.64.0.10:15038 shell id
uid=2000(shell) gid=2000(shell) ...
$ adb -s 100.64.0.10:15038 shell 'echo phone-daemon-e2e-ok; getprop ro.product.model'
phone-daemon-e2e-ok
SM-S911B
```

`adb push`/`adb pull` through the VPN listener returned the payload unchanged.

The app UI showed:

```
ADB is reachable
Ready for developer machines.
Running
adb connect
100.64.0.10:15038
```

## System adbd state after setup

```
adb_wifi_enabled=0
adb_enabled=1
```

The system adbd stayed on USB only; the patched daemon was the only TCP
endpoint. The app's daemon process ran as `shell` with the two listeners above.

## Watchdog limitation

Killing the patched daemon while the phone's Wi-Fi had no internet exposed a
Samsung/Android limitation: the framework refused to restart the TLS server after
the app re-asserted `adb_wifi_enabled=1`. The app showed the explicit error
("wireless debugging is not reachable ...") and did not fall back to a proxy or a
random port, matching DESIGN.md section 9. Recovery is the documented one-Wi-Fi
step: enable Wireless debugging once in Settings.
