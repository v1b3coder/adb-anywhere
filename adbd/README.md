# adbd

A standalone build of AOSP's `adbd`, vendored into this repository so the app
ships the real daemon instead of a reimplementation.

The sources under `vendor/` are pristine upstream copies. The patches in `patches/`
disable the subsystems this product does not use and add the CLI the app already
speaks. `build.sh` applies the patches to a build copy under `build/src`, so
re-vendoring is a straight copy and never a merge.

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

The daemon, its protocol core and the support libraries are Apache-2.0 (BoringSSL
is ISC). See `vendor/adb/NOTICE` and `vendor/boringssl/LICENSE`.

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
ADBD_HOST=1 ./build.sh          # host build, for a quick compile check
```

Outputs: `build/out/<abi>/adbd` and the patched sources under `build/src`.

Requirements: Android NDK (r27 or newer), CMake 3.22+, Ninja, and a host Perl for
BoringSSL's perlasm.

The BoringSSL tree is built as a CMake subproject with `BUILD_TESTING=OFF`; only
`libcrypto` is linked. `libssl` is not built because TLS is disabled.

## Runtime contract

```sh
adbd --listen tcp:127.0.0.1:15037[,tcp:<vpn-ip>:15038] \
     --keys /data/local/tmp/adb-anywhere/adb_keys \
     --log /data/local/tmp/adb-anywhere/adbd.log \
     --pid-file /data/local/tmp/adb-anywhere/adbd.pid
```

- `--listen` is a comma-separated list of `tcp:IP:PORT`. `127.0.0.1` and
  `localhost` are accepted; `::1` is accepted; wildcard binds are refused.
- `--keys` points at an `adb_keys`-format file: `<base64-adbkey-pub> <label>`.
  The file is the only authorization source; unknown keys are never prompted for
  or auto-accepted.
- `--log` redirects the daemon's stderr to a file. `--pid-file` writes the
  daemon's pid. `--policy` is accepted for compatibility with the app and
  ignored: this daemon has no per-key policy layer yet.
- `--version`, `--device_banner`, `--logpostfsdata`, `--root_seclabel` and
  `--tim_seclabel` are kept from AOSP. The seclabel options are unused because
  the daemon does not change its own SELinux context.

## Supported subset

Transports: TCP only. Services: `shell:` (v1 and v2), `exec:`, `sync:` (v1),
`tcp:` (forward) and `framebuffer:`.

`framebuffer:` is included for screenshots. It shells out to `screencap`, so the
device needs the platform `screencap` binary. The app's own screenshot path uses
`exec-out screencap -p`, which is also covered by `exec:`.

## Deliberate differences from AOSP adbd

These are the patches, all in `patches/`:

| Patch | Purpose |
|---|---|
| `01-daemon-main.cpp` | TCP-only: no USB, vsock, mDNS, watchdog, jdwp, trade-in, privilege dropping. Adds `--listen`, `--keys`, `--log`, `--pid-file`; accepts `--policy` as a warning. |
| `02-daemon-auth.cpp` | No TLS or pairing; keeps the RSA key-file verification. Skips the init control socket. |
| `03-daemon-services.cpp` | Keeps `shell:`, `exec:`, `sync:`, `tcp:` and `framebuffer:`; rejects `abb:`, `jdwp:`, `root:`, `remount:`, `reboot:`, `usb:`, `tcpip:`, `reverse:` and the test socket services. |
| `04-transport.cpp` | `DoTlsHandshake` returns false; TLS is not built. |
| `05-compression_utils.h` | Compiles out Brotli/LZ4/Zstd; the daemon never advertises `sendrecv_v2`. |
| `06-adb_auth.h` | Drops the TLS declarations from the shared header. |
| `07-adb.cpp` | Rejects `A_STLS` on the daemon side instead of starting a TLS handshake. |
| `08-adb_trace.cpp` | Substitutes a tag for `LOG()` without `LOG_TAG`, instead of passing null to the logd splitter. |
| `09-socket_spec.cpp` | Accepts `127.0.0.1` as a loopback literal; AOSP only accepts `localhost` and `::1`. |

Not implemented: USB, vsock, TLS, pairing, mDNS advertisement, `reverse:`,
`jdwp:`, `track-jdwp`, `track-app`, `abb:`, `framebuffer:` over USB, `root:`,
`remount:`, `reboot:`, `restore:`, `disable-verity:`, `enable-verity:`, `spin`,
`reconnect`, and the adb client.

## Verification

The real `adb` client (platform-tools) is used against the daemon on an emulator:

- `adb connect 127.0.0.1:<port>` and `adb shell id`
- `adb push` and `adb pull`
- `adb exec-out screencap -p` produces a PNG
- the daemon rejects a client whose key is not in the key file
