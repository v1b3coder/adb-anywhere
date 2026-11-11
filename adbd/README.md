# adbd

A standalone build of AOSP's `adbd`, vendored into this repository so the app
ships the real daemon instead of a reimplementation.

The sources under `vendor/` are pristine upstream copies. The patches in `patches/`
disable the subsystems this product does not use and add the CLI the app already
speaks. `build.sh` applies the patches to a build copy under `build/src`, so
re-vendoring is a straight copy and never a merge. Patches apply with
`--fuzz=0`, and a hunk that needs an offset fails the build: after a re-vendor
a security hunk must not land on a different line than the one it was written
for. Refresh the patch instead.

## Provenance

| Tree | Upstream | Commit |
|---|---|---|
| `vendor/adb` | `platform/packages/modules/adb` | `1cf2f017d312f73b3dc53bda85ef2610e35a80e9` (2025-03-25) |
| `vendor/libbase` | `platform/system/libbase` | `6d19b5c690fa4220c10e42c2326150bbd4d4b7bb` (2025-03-25) |
| `vendor/libcutils` | `platform/system/core` (`libcutils`) | `a3b721a32242006b59cb12bd62c9133632af3a2d` (2025-03-26) |
| `vendor/libcrypto_utils` | `platform/system/core` (`libcrypto_utils`) | `a3b721a32242006b59cb12bd62c9133632af3a2d` (2025-03-26) |
| `vendor/libasyncio` | `platform/system/core` (`libasyncio`) | `a3b721a32242006b59cb12bd62c9133632af3a2d` (2025-03-26) |
| `vendor/adbd_auth` | `platform/frameworks/native` (`libs/adbd_auth`) | `4f463a6b1de9198963dc6aff74154a504ba3f8f6` (2025-03-25) |
| `vendor/liblog` | `platform/system/logging` (`liblog/include`) | `4f463a6b1de9198963dc6aff74154a504ba3f8f6` (2025-03-25) |
| `vendor/boringssl` | `boringssl` | `fd0ed4d0a1273570612a08a31bace08e4dd73c81` (2026-11-03) |
| `vendor/zstd` | `facebook/zstd` (`lib/`) | `v1.5.6` (2024-03-30) |

The daemon, its protocol core and the support libraries are Apache-2.0 (BoringSSL
is ISC, zstd is BSD/GPLv2 dual); the full text is in `LICENSE`. See
`vendor/adb/NOTICE`, `vendor/boringssl/LICENSE` and `vendor/zstd/LICENSE`.

## Layout

```
adbd/
  CMakeLists.txt       build description for the daemon and its static deps
  build.sh             applies patches and builds adbd
  patches/             focused patches against vendor/adb
  src/adbd_auth.cpp    key-file implementation of libadbd_auth
  stubs/               the small liblog/SELinux/bionic surface the daemon needs
  vendor/              pristine upstream trees (see the table above)
```

## Build

```sh
./build.sh                     # arm64-v8a, armeabi-v7a, x86_64
ABIS="arm64-v8a" ./build.sh     # one ABI
ADBD_HOST=1 ./build.sh          # host compile check (see below)
```

Outputs: `build/out/<abi>/adbd` and the patched sources under `build/src`.

Requirements: Android NDK (r27 or newer), CMake 3.22+, Ninja, and a host Perl for
BoringSSL's perlasm.

The BoringSSL tree is built as a CMake subproject with `BUILD_TESTING=OFF`.
`libcrypto` backs the RSA key auth and `libssl` the `--tls-listen` tunnel;
adb's `tls/tls_connection.cpp`, `tls/adb_ca_list.cpp` and
`crypto/x509_generator.cpp` are compiled in. `crypto/key.cpp` and
`crypto/rsa_2048_key.cpp` are not: they need protobuf, and the server key comes
from `--tls-key` instead of being generated.

`ADBD_HOST=1` only checks that the sources compile on the host: the daemon
expects bionic and libc++, so the host link needs a matching libc++ and is not
part of the supported workflow. Behaviour is tested on a device (see Tests).

## Runtime contract

```sh
adbd --tls-listen tcp:127.0.0.1:15037 \
     [--listen tcp:<vpn-ip>:15038] \
     --tls-key /data/local/tmp/adb-anywhere/tls_server.pem \
     --tls-clients /data/local/tmp/adb-anywhere/tls_clients \
     --keys /data/local/tmp/adb-anywhere/adb_keys \
     --log /data/local/tmp/adb-anywhere/adbd.log \
     --pid-file /data/local/tmp/adb-anywhere/adbd.pid
```

- `--listen` (plain ADB) and `--tls-listen` (TLS tunnel) are comma-separated
  lists. At least one listener is required. Each spec is exactly
  `tcp:<numeric IPv4 or [IPv6] literal>:<port>` with an explicit port; a
  hostname, a missing host or port, and every spelling of a wildcard address
  (`0.0.0.0`, `0`, `::`, `::ffff:0.0.0.0`) are refused after parsing. There is no
  fallback: no `service.adb.listen_addrs`, no `service.adb.tcp.port` or
  `persist.adb.tcp.port`, no `ADBD_PORT`, no default 5555.
- Every listener is bound before the event loop starts. An invalid spec, a busy
  port or any other bind failure logs the address and the error and exits with
  status 1; nothing is retried or bound later.
- `--tls-key` is the TLS server key: PEM RSA-2048, PKCS#8
  (`BEGIN PRIVATE KEY`) or traditional (`BEGIN RSA PRIVATE KEY`). The
  certificate is generated from it on every handshake, so a client pins the
  SHA-256 of the key's DER SubjectPublicKeyInfo, not the certificate.
- `--tls-clients` is an `adb_keys`-format file. A TLS handshake completes only
  for a client certificate whose RSA key is in it. It is re-read on every
  handshake; a missing or empty file admits nobody. `--tls-key` and
  `--tls-clients` are required with `--tls-listen`.
- `--keys` points at an `adb_keys`-format file: `<base64-adbkey-pub> <label>`.
  It is the only source for ADB key auth on both listener kinds; unknown keys are
  never prompted for or auto-accepted.
- `--log` redirects the daemon's stderr to a file and `--pid-file` writes the
  daemon's pid. Both are opened `0600` with `O_NOFOLLOW`. The daemon sets
  `umask 022`, so nothing it or its shells create is world-writable; a pushed
  file's mode also loses group and other write (upstream copies the owner bits
  to both and `fchmod` ignores the umask). The log rotates at 1 MiB while the
  daemon runs, keeping one predecessor (`.1`). It closes
  every inherited descriptor except stdio at startup. There is no `--policy`
  flag: per-key policy is not implemented.
- `--version`, `--device_banner`, `--logpostfsdata`, `--root_seclabel` and
  `--tim_seclabel` are kept from AOSP. The seclabel options are unused because
  the daemon does not change its own SELinux context.

### TLS tunnel protocol

A `--tls-listen` listener authenticates the channel with TLS and still runs the
normal ADB key auth inside it, so a relayed developer machine keeps its own key
auth end to end:

1. The client sends `A_CNXN`; the daemon answers `A_STLS` (version
   `0x01000000`).
2. The client sends `A_STLS`; the daemon runs the TLS 1.3 server handshake with
   the `--tls-key` key and requires a `--tls-clients` certificate. The
   CertificateRequest lists the allowed keys as upstream does (CN = SHA-256 of
   the key's DER), but a client may ignore the list.
3. Inside TLS the daemon sends `A_AUTH` TOKEN as the first record, verifies the
   `A_AUTH` SIGNATURE against `--keys` and answers `A_CNXN`. `RSAPUBLICKEY` is
   handled as on a plain listener.

Before the handshake completes, `A_AUTH` is ignored and nothing else is honoured
(the transport is offline). A second `A_STLS`, a failed handshake or an
`A_STLS` on a plain listener closes the transport. An `A_CNXN` inside an
established tunnel restarts the inner auth.

### Auth limits

A transport is closed after 16 failed signatures (upstream slept 1 s on the main
loop after 256, which stalled every transport). Only the first `RSAPUBLICKEY`
per transport is accepted, and its payload is never read past its length. The
log line for an unlisted key shows at most its first 64 bytes, with
non-printable bytes replaced by `?`.

Any local app can connect to the loopback listener, so unauthenticated peers are
bounded too: at most 32 transports that have not authenticated exist at once (a
connection over the cap is closed at accept, without a log line), and one that
has not authenticated 10 s after accept has its socket shut down. The shutdown
goes through a duplicate of the socket, so it also ends a TLS handshake that
holds the connection lock and never touches a reused descriptor number. An
offered key gets no callback id, because the vendored `adbd_auth` never calls
back.

## Supported subset

Transports: TCP only, plain or the TLS tunnel above. Services: `shell:` (v1 and
v2), `exec:`, `abb_exec:` (streaming install), `sync:` (v1 and v2, zstd), `tcp:`
(forward to loopback), `reverse:` and `framebuffer:`. `dev:`, `dev-raw:`,
`local*:`, `vsock:` and `acceptfd:` are not services, and `devraw` is not
advertised.

`reverse:` listens on loopback only. The device-side spec must be `tcp:<port>`,
`tcp:localhost:<port>` or `tcp:127.0.0.1:<port>`, and all three bind 127.0.0.1;
any other host (including `0.0.0.0`, `0`, `[::0]` and LAN addresses), any IPv6
address and every other socket type are refused with a `FAIL` reply. Upstream
binds `tcp:<port>` on every interface, which would expose the developer
machine's port to the phone's networks.

`framebuffer:` is included for screenshots. It shells out to `screencap`, so the
device needs the platform `screencap` binary. The app's own screenshot path uses
`exec-out screencap -p`, which is also covered by `exec:`.

## Deliberate differences from AOSP adbd

These are the patches, all in `patches/`:

| Patch | Purpose |
|---|---|
| `01-daemon-main.cpp` | TCP-only: no USB, vsock, mDNS, watchdog, jdwp, trade-in, privilege dropping. Adds `--listen`, `--tls-listen`, `--tls-key`, `--tls-clients`, `--keys`, `--log`, `--pid-file`. Strict listener specs, synchronous fail-hard binds, no listener fallbacks. `umask 022`, private log and pid files, inherited descriptors closed, log rotated at startup. |
| `02-daemon-auth.cpp` | RSA key-file verification; no pairing, Wi-Fi debugging or control socket. TLS only for the tunnel: server key from `--tls-key`, client certificates checked against `--tls-clients`, then the normal key auth inside TLS. |
| `03-daemon-services.cpp` | Keeps `shell:`, `exec:`, `abb_exec:`, `sync:`, `tcp:`, `reverse:` and `framebuffer:`; rejects `abb:`, `jdwp:`, `root:`, `remount:`, `reboot:`, `usb:`, `tcpip:`, `backup:` and the test socket services. |
| `04-transport.cpp` | The daemon's `DoTlsHandshake`: certificate from the fixed server key, `--tls-clients` verification, no protobuf key helpers. |
| `05-compression_utils.h` | Keeps Zstd; Brotli and LZ4 stay as stubs because the daemon advertises neither. |
| `06-adb_auth.h` | Declares `adbd_tls_configure` and includes the X509 types the TLS declarations need. |
| `07-adb.cpp` | `A_STLS` starts the handshake only on a `--tls-listen` transport and only once; AUTH inside an established tunnel; 16 failed signatures close a transport; one `RSAPUBLICKEY` per transport, read within its length. |
| `08-adb_trace.cpp` | Substitutes a tag for `LOG()` without `LOG_TAG`, instead of passing null to the logd splitter. |
| `09-socket_spec.cpp` | Binds any explicit address, loopback or VPN; AOSP only accepts `localhost`, `::1` or every interface. Wildcards are refused after parsing, and a spec without a host binds loopback, never every interface. |
| `10-transport.cpp` | Advertises only the features the daemon implements: `apex`, `abb_exec` and `sendrecv_v2_zstd` stay; `abb`, Brotli/LZ4, mDNS, `track-app`, `remount_shell`, `app_info`, `devicetracker_proto_format` and `devraw` go. Advertising the missing ones made `adb install` and large `adb push` fail with a closed service or `remote decompress failed`. |
| `11-daemon-services-dev.cpp` | Removes `dev:` and `dev-raw:`; a developer machine must not open arbitrary device nodes. |
| `12-services-socket-spec.cpp` | Only `tcp:` is a socket spec for connects; `local*:`, `vsock:` and `acceptfd:` are refused. |
| `13-transport-tls-tunnel` | The transport state of the tunnel: a `--tls-listen` origin, handshake started, handshake done, public key offered. |
| `14-daemon-listeners` | Binds each listener synchronously and reports a failure instead of retrying every second in the background; a listener can be a TLS tunnel. |
| `15-daemon-reverse-loopback` | `reverse:` device listeners are `tcp:` on loopback only. |
| `16-daemon-preauth-limits` | Caps unauthenticated transports at 32 and shuts one down 10 s after accept; rotates the log while the daemon runs; a pushed file is never group- or world-writable; no leaked auth callback ids. |

Not implemented: USB, vsock, TLS on plain listeners, Wi-Fi debugging TLS,
pairing, mDNS advertisement, `jdwp:`,
`track-jdwp`, `track-app`, `abb:` (the shell-protocol variant), `framebuffer:`
over USB, `root:`, `remount:`, `reboot:`, `restore:`, `disable-verity:`,
`enable-verity:`, `spin`, `reconnect`, `dev:`, `dev-raw:`, `local*:`, `vsock:`,
`acceptfd:` and the adb client.

## Tests

The AOSP shared-core tests are vendored too and built with `ADBD_TESTS=1`:

```sh
ADBD_TESTS=1 ABIS=x86_64 ./build.sh
adb push build/out/x86_64/adbd_tests /data/local/tmp/
adb shell /data/local/tmp/adbd_tests
```

`build/out/x86_64/adbd_tests` runs 104 tests from 17 suites: `adb_io`,
`adb_listeners`, `adb_utils`, `fdevent`, `shell_service_protocol`, `socket`,
`socket_spec`, `sysdeps`, `transport`, `types` and `test_utils`. They exercise the
shared protocol core with the same `ADB_HOST=0` configuration as the daemon.

Not ported: the client-side `adb_test` (needs libusb, libmdnssd, OpenScreen,
protobuf and the TLS stack) and the device `adbd_test` (needs the same plus
`libbinder`).

`tests/tls_smoke.py` drives the daemon on a device or emulator through
`adb forward` and checks the contract above: the strict listeners and their exit
codes, the TLS tunnel (STLS, client certificate gate, SPKI pin, key auth inside
TLS, a shell through it), the plain listener's STLS kick and auth limits, the
bounded log line, the reverse restriction, the `0600` files, the push mode
mask, runtime log rotation and the pre-auth deadline and cap. It needs `adb`,
`openssl` and the Python standard library:

```sh
ABIS=x86_64 ./build.sh
python3 tests/tls_smoke.py --serial emulator-5554
```

## Verification

The real `adb` client (platform-tools) is used against the daemon on an emulator:

- `adb connect 127.0.0.1:<port>` and `adb shell id`
- `adb push` and `adb pull`, including zstd-compressed pushes of any size
- `adb install` (streamed through `abb_exec:`) and `adb install --no-streaming`
- `adb exec-out screencap -p` produces a PNG
- the daemon rejects a client whose key is not in the key file and survives the
  disconnect
