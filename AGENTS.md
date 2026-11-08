# adb-anywhere — agent notes

Project-specific guidance. The global agent rules still apply.

Wireless ADB for stock Android 12+, no root: a Kotlin/Compose app deploys and
supervises a patched AOSP `adbd` and is the only entity that controls the
listeners and decides which machines may connect.

## Where the contract lives

| Path | What it owns |
|---|---|
| [`DESIGN.md`](DESIGN.md) | Architecture, decisions D1–D19, lifecycle invariants I1–I14, failure modes, security model |
| [`docs/FIREWALL.md`](docs/FIREWALL.md) | Relay, IP/app allowlists, call policy, audit log, and their limits |
| [`adbd/README.md`](adbd/README.md) | Daemon fork, patch set, supported services, tests |
| [`README.md`](README.md) | User-facing behavior and recovery steps |
| [`Makefile`](Makefile) | Build, install and e2e helpers |

`DESIGN.md` is the contract. A change that contradicts a decision, an invariant or
a documented limit updates the design doc in the same commit; design drift is a
defect.

## Invariants

Each exists because its violation shipped a real defect; the full table is
`DESIGN.md` §6. A change that breaks one needs a test proving the new behavior is
correct.

- One owner per port and per value. The UI shows only what the owning component
  publishes; `ControlState` is the single UI truth.
- Remove a fallback last: turn the system wireless debugging off only after the
  replacement listener is verified.
- Persisted is not actual: validate `paired`, `setupComplete` and
  `daemonFirewallMode` against `user_keys`, the open port and the listener.
- Re-assert the effect, not the setting (`adb_wifi_enabled=1` even when it reads 1).
- No silent fallback: an explicit error with recovery steps, never another port,
  listener or proxy.
- Ports stay below 32768 and are never 5555.
- The watchdog never writes `ADB_ENABLED`; only Start does, and only when it reads 0.
- Serialize listener and prompt lifecycle under the existing locks and epochs.
- A fix closes the class, not the instance: test the class, not only the reported
  case.

## Security

- No root, no custom ROM, no proxy fallback, no random-port redirect.
- `adbd` binds explicit addresses only; wildcard binds are refused.
- Only keys in the app's key file are accepted; never auto-accept or prompt for an
  unknown key.
- The relay is byte-transparent and does not authenticate; ADB key auth stays
  end-to-end. The firewall is policy and audit, not a sandbox.

## The `adbd/` fork

- `adbd/vendor/` is pristine upstream; never edit it. Upstream commits are in
  `adbd/README.md`.
- Change the daemon with a focused patch in `adbd/patches/`. Re-vendoring is a copy
  and a patch reapply, never a merge.
- Do not add a second daemon implementation or reimplement protocol code. The daemon
  stays policy-free; filtering lives in the app relay.
- `adbd/build.sh` applies the patches to `build/src` and builds with the NDK.
  `syncAdbd` copies the per-ABI binaries into `app/src/main/assets/adbd/`, which is
  never committed.
- Keep the licenses: the app is MIT, the daemon Apache-2.0; update `NOTICE` when the
  fork moves.

```sh
cd adbd && ./build.sh                          # all ABIs
cd adbd && ABIS="arm64-v8a" ./build.sh           # one ABI
cd adbd && ADBD_TESTS=1 ABIS=x86_64 ./build.sh   # + adbd_tests
```

## Gates

```sh
./gradlew :app:testDebugUnitTest   # JVM tests
./gradlew :app:lintDebug           # lint gate; errors fail the build
./gradlew :app:assembleDebug       # APK; needs the Android NDK
make e2e-firewall                  # firewall e2e on a rooted emulator
```

`.github/workflows/ci.yml` runs tests, lint and assemble on every push to `main`
and every PR; keep it green. `scripts/e2e-emulator.sh` is the control-plane e2e.

- Never commit `signing/`, keystores, `local.properties` or
  `app/src/main/assets/adbd/`.
- Commits: Conventional Commits with a scope (`fix(firewall):`, `docs(design):`).
  The body states the defect, the fix and the tests run.
- Release: push a `vMAJOR.MINOR[.PATCH]` tag to `github`; `origin` (Gitea) does
  not run the workflow. No version-bump commit.

## Not part of the build

- `vendor/adb-auto-enable` is a gitignored reference clone.
- `docs/E2E.md` and `docs/PHONE-E2E.md` are historical evidence from the original
  C daemon, not current instructions.
