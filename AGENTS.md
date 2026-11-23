# adb-anywhere — agent notes

Project-specific guidance. The global agent rules still apply.

Wireless ADB for stock Android 12+, no root: a Kotlin/Compose app deploys and
supervises a patched AOSP `adbd` and is the only entity that controls the
listeners and decides which machines may connect.

## Where the contract lives

| Path | What it owns |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | Architecture, decisions D1–D19, lifecycle invariants I1–I14, failure modes, security model |
| [`docs/FIREWALL.md`](docs/FIREWALL.md) | Relay, IP/app allowlists, call policy, audit log, and their limits |
| [`adbd/README.md`](adbd/README.md) | Daemon fork, patch set, supported services, tests |
| [`README.md`](README.md) | User-facing behavior and recovery steps |
| [`Makefile`](Makefile) | Every build, test, e2e and install target |

`docs/DESIGN.md` is the contract. A change that contradicts a decision, an
invariant or a documented limit updates the design doc in the same commit; design
drift is a defect.

## Folder map

| Path | Contents |
|---|---|
| `app/src/main/java/cz/slush/adbanywhere/adb/` | System adbd access, deployment, ports, mDNS, shell |
| `app/src/main/java/cz/slush/adbanywhere/data/` | App key, developer keys, prefs, endpoints, pairing |
| `app/src/main/java/cz/slush/adbanywhere/firewall/` | Relay, frame codec, call parser, rules, audit |
| `app/src/main/java/cz/slush/adbanywhere/service/` | Control service, state, notifications, watchdog, boot |
| `app/src/main/java/cz/slush/adbanywhere/ui/` | Compose screens, prompt, rule editor |
| `app/src/test/` | JVM unit tests |
| `adbd/` | Daemon fork: `vendor/` pristine, `patches/` ours |
| `scripts/` | Rooted-emulator e2e scripts |
| `docs/` | Design, firewall, historical e2e evidence |
| `signing/` | Release keystore inputs; never committed |

## The `adbd/` fork

- `adbd/vendor/` is pristine upstream; never edit it. Upstream commits are in
  `adbd/README.md`.
- Change the daemon with a focused patch in `adbd/patches/`. Re-vendoring is a copy
  and a patch reapply, never a merge.
- Do not add a second daemon implementation or reimplement protocol code. The daemon
  stays policy-free; filtering lives in the app relay.
- `adbd/build.sh` applies the patches to `build/src` and builds with the NDK.
  `syncAdbd` drops the per-ABI binaries into `app/adbd-bin/` and fans each flavor
  out to `app/src/<flavor>/assets/adbd/`, so an APK carries only its own ABI.
  Both are generated and never committed.
- Keep the licenses: the app is MIT, the daemon Apache-2.0; update `NOTICE` when the
  fork moves.

## Build, test, e2e

The Makefile owns every command, its variables and the `install-phone` approval
flow; `make help` lists the targets. Do not copy commands into docs or commits.

Installing replaces the app that carries the relay, so `adb install` always ends
in a killed stream: an empty "failed to install" and a non-zero exit, even when
the install succeeded. That output is not a verdict. `make install-phone` waits
for the phone to come back and proves the build by hashing the installed APK; it
fails only when the bytes differ. Never debug that message by hand.

- `make check` is the CI gate locally: JVM tests, lint and the debug APK.
- `make adbd`, `make adbd-abi ABI=...` and `make adbd-tests` build the daemon;
  a local build needs the Android NDK.
- `.github/workflows/ci.yml` runs tests, lint and assemble on every push to `main`
  and every PR; keep it green.

- Never commit `signing/`, keystores, `local.properties`, `app/adbd-bin/` or
  `app/src/*/assets/adbd/`.
- Commits: Conventional Commits with a scope (`fix(firewall):`, `docs(design):`).
  The body states the defect, the fix and the tests run.
- Release: push a `vMAJOR.MINOR[.PATCH]` tag to `github`; `origin` (Gitea) does
  not run the workflow. The workflow also publishes the release to Zapstore with
  `zsp`, under the delegate key in the `ZAPSTORE_SIGN_WITH` secret; that key is
  named in the app's C1 proof and may publish releases only. No version-bump
  commit.
- Zapstore's catalog does not poll this repository, so a release that skips the
  publish step stays invisible there: `zsp check` reports the version Zapstore
  still serves.

## Not part of the build

- `vendor/adb-auto-enable` is a gitignored reference clone.
- `docs/E2E.md` and `docs/PHONE-E2E.md` are historical evidence from the original
  C daemon, not current instructions.
