# adb-anywhere — agent notes

Project-specific guidance. The global agent rules still apply.

## The `adbd/` fork

`adbd/` is a standalone build of AOSP `adbd`, vendored into this repository.

- `adbd/vendor/` holds **pristine upstream trees**. Never edit these files.
  Their commits are recorded in `adbd/README.md`.
- `adbd/patches/` holds the focused patches that disable USB, vsock, TLS,
  pairing, mDNS, `jdwp:`, `abb:`, `root:`, `remount:`, `reboot:` and the test
  socket services, and add `--listen`, `--keys`, `--log`, `--pid-file`.
- `adbd/build.sh` copies `vendor/adb` to `build/src`, applies `patches/`, and
  builds with the NDK. Re-vendoring is a copy and a patch reapply, never a merge.
- `adbd/src/adbd_auth.cpp` and `adbd/stubs/` are our replacements for the
  Android-only pieces (`libadbd_auth`, liblog, SELinux, bionic property serials).
- The app's `syncAdbd` task builds `adbd/` and copies the per-ABI binaries into
  `app/src/main/assets/adbd/`.

Commands:

```sh
cd adbd && ./build.sh                          # all ABIs
cd adbd && ABIS="arm64-v8a" ./build.sh           # one ABI
cd adbd && ADBD_TESTS=1 ABIS=x86_64 ./build.sh   # + adbd_tests
```

Do not add a new daemon implementation or reimplement protocol code. Extend the
vendored AOSP daemon with a focused patch instead.

## Other

- The app is Kotlin/Compose; `vendor/adb-auto-enable` is a gitignored reference
  clone, not part of the build.
- `docs/E2E.md` and `docs/PHONE-E2E.md` are historical evidence from the
  original daemon.
