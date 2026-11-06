# adb-anywhere — Firewall design

Status: v1 implemented
Date: 2026-11-05
Scope: optional firewall mode, IP allowlist, foreground-app allowlist, call
policy with custom filters, audit log

## 1. Goal

Add an optional firewall that sits between developer machines and the custom
`adbd`. When it is enabled:

- `adbd` stops listening on the VPN address and keeps only
  `127.0.0.1:<loopbackPort>`.
- The app accepts TCP on `<vpn-ip>:<vpnPort>` and relays the ADB byte stream to
  `127.0.0.1:<loopbackPort>`.
- The relay parses the ADB framing far enough to apply three modules: IP allowlist,
  foreground-app allowlist and call policy. It records an audit log.

The firewall is a separate tab that appears only after the user enables it in
Settings. It is off by default.

Non-goals:

- A general-purpose firewall for other apps.
- A replacement for the VPN ACL. The VPN ACL remains the outer boundary.
- TLS ADB, USB ADB, vsock ADB.
- Root or a sandbox for `shell`. The daemon still runs as `shell`.
- Filtering the app's own loopback channel.

## 2. Data path

| Firewall | `adbd` listens on | App listens on | Developer machine connects to |
|---|---|---|---|
| off | `127.0.0.1:<loopback>`, `<vpn-ip>:<vpn-port>` | nothing | `adbd` |
| on | `127.0.0.1:<loopback>` only | `<vpn-ip>:<vpn-port>` | app relay, which connects to `adbd` loopback |

The relay is byte-transparent. It never authenticates, never decrypts and never
rewrites ADB payloads. ADB key authentication stays end-to-end between the
developer machine and `adbd`. The relay only parses the framing, evaluates policy
and forwards the original bytes.

The app's own channel is unchanged: it still connects directly to
`127.0.0.1:<loopbackPort>` with the app key and is never filtered by the
firewall. That channel is how the app deploys the daemon, rewrites keys and runs
its own shell commands.

## 3. Where the relay lives

The requested design puts the TCP listener and relay in the app. The alternative is a
focused `adbd` patch with a policy socket back to the app. This document proposes
the app-side relay for the first version and records the trade-off.

**Decision:** the first implementation is the app-side Kotlin relay. It is revisited
only if throughput or protocol correctness becomes a problem; the parser stays small
because it reads framing, not service semantics.

| Option | Pros | Cons |
|---|---|---|
| App-side Kotlin relay | No C++ change; UI, prompts and audit share one process; fastest iteration; no new failure mode in `adbd` | Reimplements the ADB frame parser in Kotlin; lower throughput than C++; protocol bugs are app bugs |
| `adbd` patch + policy socket | Reuses the AOSP parser; best throughput; follows `adbd/AGENTS.md`; policy decisions still in the app | C++ work; new IPC; harder to iterate; `adbd` becomes policy-aware |
| Hybrid: app accepts TCP, `adbd` parses services | App owns connection/IP/foreground gates; `adbd` owns service/path parsing | Two components, two failure domains, still C++ work |

The relay needs only the 24-byte ADB header, `A_OPEN` service strings, sync
frames and shell-v2 packets. It does not need the auth, TLS, USB, vsock or mDNS
code. That is a small parser, not a second ADB implementation. If the parser grows
into payload rewriting or service emulation, it belongs in `adbd` as a focused patch
instead.

## 4. Components

| Component | Process | Responsibility |
|---|---|---|
| `FirewallProxy` | app, `ControlService` | Bind, accept, relay, enforce connection-level policy, own sessions |
| `AdbFrameCodec` | app | Read/write the 24-byte header and bounded payloads |
| `CallParser` | app | Normalize an `A_OPEN` service string into an `AdbCall` |
| `SyncParser` | app | Track `sync:` frame state per stream and extract paths |
| `CallRules` | app | Evaluate normalized calls against stored rules |
| `IpAllowlist` | app | Parse and match IPv4 addresses and CIDR ranges |
| `ForegroundAppMonitor` | app | Track the resumed package with `UsageStatsManager` |
| `AuditLog` | app | Bounded JSONL log and in-memory ring |
| `FirewallState` | app | Publish listener, sessions, prompts and errors to the UI |
| `FirewallScreen` | app UI | Status, modules, rules, audit |
| `CallPromptActivity` | app UI | Approve or deny one pending call |
| `RuleEditor` | app UI | Custom filter editor |

## 5. Module: IP allowlist

- Stored as a list of IPv4 addresses and CIDR ranges. A single address is
  `a.b.c.d/32`. IPv6 is out of scope for the first version because the app's
  endpoint selection is IPv4-only (`Endpoint.isUsableIpv4`).
- Checked immediately after `accept()`, before any ADB byte is read. A peer
  outside the list is closed at once; there is no prompt and no ADB error text.
  The audit log records `ip_denied` with the peer address.
- The allowlist is only active when its own switch is on. When it is on and the
  list is empty, all external connections are denied. The UI prevents enabling it
  with an empty list, or shows a persistent warning if it is already enabled.
- The peer address is the VPN source address. A NAT or a VPN that rewrites source
  addresses must whitelist the rewritten address. This is documented in the UI.
- The relay rejects loopback, link-local and the phone's own VPN address as peers.
  A local app that connects to the VPN address has the VPN address as its source;
  the only legitimate local client is the app's own loopback channel, which the
  firewall does not touch.

## 6. Module: foreground-app allowlist

- The user selects one or more launcher packages. ADB is available only while the
  resumed package is in that set.
- The list is built from `PackageManager.queryIntentActivities` with
  `ACTION_MAIN` + `CATEGORY_LAUNCHER`, deduplicated by package, with label,
  icon and package name. The manifest adds a `<queries>` element for that intent so
  Android 11+ package visibility returns launcher apps. The UI is a searchable picker
  (modal sheet with a search field and a `LazyColumn`), not a plain dropdown,
  because the list is long.
- The current foreground package comes from `UsageStatsManager.queryEvents` with
  `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED`, reading the last resumed package, but
  skipping a resume of the app's own approval dialog.
  `PACKAGE_USAGE_STATS` is an appop, not a runtime permission; the manifest
  declares it and the app grants it with
  `appops set <package> GET_USAGE_STATS allow` through its own shell, or sends the
  user to Settings → Special app access → Usage access. If it is missing, the
  module cannot be enabled and the UI says why. Work-profile apps are out of
  scope for the first version.
- ADB Anywhere itself appears in the picker and can be allowlisted like any other
  app, so external ADB keeps working while its own UI is in the foreground. The
  picker warns that this is the weakest choice, because it covers every screen of
  the app, not only the audit log.
- The approval dialog is not a foreground app. A resumed `CallPromptActivity`
  leaves the previous package in place, so a call that raises the dialog cannot
  make the app allowlist pass by itself. A visible prompt is still exempt from the
  app check, so answering it does not close the call.
- The relay checks the package at `accept()` and again at `A_OPEN`. The first
  check closes a connection whose target app is not foreground before any ADB
  handshake; the second closes a connection whose target app lost the foreground
  while the handshake was in progress.
- Screen off or keyguard locked means no foreground app: external calls are denied.
- The package is sampled at `A_OPEN` time. A prompt captures the package that was
  resumed when the call arrived, not the package that is resumed when the user
  answers. The app check is exempt while a prompt is visible, otherwise the act of
  answering the prompt would close the call. The captured package remains the basis
  for the decision for the lifetime of that prompt.
- The monitor polls every 1 s while an external session or a prompt exists, and every
  5 s otherwise. The relay never polls on the main thread.
- On a foreground change away from the allowed set, the relay starts a grace timer
  (default 15 s, configurable, `0` means immediate). If the target app returns
  before it expires, nothing happens; otherwise the relay closes live sessions.
  New connections and new `A_OPEN`s are denied immediately while the target app
  is not foreground; the timer only delays closing live sessions. While a prompt
  is pending, the relay suspends the timer and keeps the captured package as the
  basis for the decision; otherwise answering the prompt would trip the rule.
- The module is advisory with respect to Android's own app lifecycle: a package can
  be "resumed" while a system dialog is on top. The relay treats the last resumed
  activity as the foreground app, which is the same signal app blockers use. The
  one exception is the app's own approval dialog, which is skipped.

## 7. Module: call policy

### 7.1 What an ADB call is

ADB is a multiplexed byte stream over one TCP connection. The unit that maps to
"a call" is `A_OPEN` plus its service string. The relay parses the framing, not
the service's own payload semantics.

| Message | Direction | Meaning | Relay use |
|---|---|---|---|
| `A_CNXN` | both | version, maxdata, banner/features | Audit only |
| `A_AUTH` | both | token, signature, public key | Optional key fingerprint |
| `A_OPEN` | client → device | local id, send buffer, service string | **Policy decision point** |
| `A_OKAY` | both | stream accepted / delayed-ack credit | Forward only |
| `A_WRTE` | both | stream data | Forward, parse for `sync:` and shell-v2 |
| `A_CLSE` | both | stream closed | Close both sides, audit |
| `A_STLS` | both | TLS upgrade | Reject: plain ADB only |

A denied `A_OPEN` is answered with `A_CLSE(0, client-local-id)`, which is the
same close a device sends for an unknown service. The relay does not forward the
`A_OPEN` upstream.

### 7.2 Parsing depth per service

| Service string | Fields the relay extracts | Limit |
|---|---|---|
| `shell:<cmd>` | `command`, shell-lexed `argv[]`, `interactive`, `metacharacters` | Shell syntax is not interpreted |
| `shell,v2,raw:<cmd>` | same, plus shell-v2 stdin packets | Stdin after `OPEN` is opaque |
| `exec:<cmd>` | `command`, `argv[]` | Same as shell |
| `abb_exec:<argv\0...>` | NUL-separated `argv[]` | Used by `adb install` / `uninstall` |
| `sync:` | v1 and v2 `direction`, `path`, `mode`, `size` from sync frames | File contents are opaque |
| `tcp:<port>` | `port` | Device port for `adb forward` |
| `reverse:forward:<remote>;<local>` | `remote`, `local` specs | Both are `tcp:` / `local*:` specs |
| `framebuffer:` | none | Screen stream is opaque |
| `jdwp:<pid>` | `pid` | Daemon rejects it |
| `root:`, `remount:`, `reboot:`, `usb:`, `tcpip:` | none | Daemon rejects them |
| `abb:`, `restore:`, `disable-verity:`, `enable-verity:` | none | Daemon rejects them |
| unknown | raw service string | Deny by default |

`adb install` uses `abb_exec:package install ...` with a NUL-separated argv on
modern `adb`. `adb push` and `adb pull` use `sync:` with `SEND` and `RECV` frames.
`adb shell <cmd>` uses `shell:` or `shell,v2,raw:<cmd>`. `adb shell` with no
command uses `shell:` and is an interactive session.

Examples:

| `adb` command | `A_OPEN` service string | Normalized |
|---|---|---|
| `adb shell ls /sdcard` | `shell:ls /sdcard` | shell, `argv=["ls","/sdcard"]` |
| `adb shell` | `shell:` | shell, interactive |
| `adb exec-out screencap -p` | `exec:screencap -p` | exec, `argv=["screencap","-p"]` |
| `adb install app.apk` | `abb_exec:package␀install␀...` | abb_exec, `argv=["package","install",...]` |
| `adb push app.apk /data/local/tmp/` | `sync:` then `SEND` | sync, SEND, path |
| `adb pull /sdcard/x` | `sync:` then `RECV` | sync, RECV, path |
| `adb forward tcp:1234 tcp:5678` | `tcp:5678` | tcp, port 5678 |
| `adb reverse tcp:8080 tcp:8080` | `reverse:forward:tcp:8080;tcp:8080` | reverse, specs |
| `adb logcat` | `shell:logcat` | shell, `argv=["logcat"]` |
| `adb shell "ls; rm -rf /sdcard"` | `shell:ls; rm -rf /sdcard` | shell, metacharacters |

### 7.3 Normalized call

```kotlin
data class AdbCall(
    val service: String,          // shell, exec, sync, tcp, reverse, abb_exec, ...
    val raw: String,              // exact OPEN payload
    val fields: Map<String, String>,
    val argv: List<String>?,      // shell, exec, abb_exec
    val path: String?,            // sync
    val direction: String?,       // SEND or RECV
    val port: Int?,               // tcp
    val interactive: Boolean,      // shell with no command
    val metacharacters: Boolean,   // ; | & < > ( ) $ ` \n * ? [ ] { } ~
)
```

Shell commands are tokenized with a small POSIX-like lexer that understands
single quotes, double quotes and backslash escapes. It does not expand variables,
globs or command substitutions. `metacharacters` is set when the raw command
contains shell operators outside quotes; those calls are treated as raw shell, not as
a normal argv match.

`sync:` is parsed as a per-stream state machine:

- First client frame is a sync v1 or v2 `STAT`, `LSTAT`, `LIST`, `SEND` or
  `RECV` (`STA2`, `LST2`, `LIS2`, `SND2`, `RCV2`).
- `SEND` and `RECV` carry the device path; `SEND` v2 adds mode and flags.
- `DATA` frames carry file bytes and are forwarded without inspection.
- A path rule matches the normalized lexical path. Symlinks and `..` are not
  resolved; the UI labels path rules as advisory.

### 7.4 Rules

A rule is an ordered predicate over a normalized call:

```kotlin
data class CallRule(
    val id: String,
    val action: Action,          // ALLOW or DENY
    val service: String,         // exact, or "*"
    val predicates: List<Predicate>,
    val note: String,
    val createdAt: Long,
)

sealed interface Predicate {
    data class Exact(val field: String, val value: String) : Predicate
    data class Prefix(val field: String, val value: String) : Predicate
    data class Glob(val field: String, val pattern: String) : Predicate
    data class AnyValue(val field: String) : Predicate
}
```

Evaluation is first match wins. A rule with fewer predicates is broader; a rule
with no predicates matches every call of its service. The editor warns before
saving a rule that matches every call of a service. The rule list is ordered and
the UI supports move up, move down and delete.

Built-in rules, before user rules:

1. Deny every service the patched `adbd` rejects (`jdwp:`, `root:`, `remount:`,
   `reboot:`, `usb:`, `tcpip:`, `abb:`, `restore:`, `disable-verity:`,
   `enable-verity:` and the test services).
2. An interactive `shell:` / `shell,v2:` with no command is a capability. It
   prompts unless the user has created an explicit interactive-shell rule; when
   prompts are disabled it is denied. That rule allows the session as a whole, not
   individual commands; it is labelled "full shell, not filtered" in the UI.
3. Deny any `A_STLS`.

When no rule matches and call policy is on, the default is a prompt. When prompts
are disabled, the default is deny. The default is never allow. A matching `ALLOW` or
`DENY` rule never prompts; the prompt is only for an unmatched call. When call
policy is off, every parsed call is allowed; the IP and app allowlists still apply.

### 7.5 Prompt flow

1. The relay parses `A_OPEN` and evaluates the rules.
2. No rule matches: the stream is held. The relay does not forward `A_OPEN`
   upstream yet.
3. If the app is foreground and the screen is interactive, a dialog appears. If the
   app is background, a high-priority notification with a `Review` action appears
   instead; the call waits. If the screen is off or locked, the call is denied
   without a prompt. The screen-off rule is separate from the app allowlist: an
   `A_OPEN` that arrived while the target app was foreground is still decided by
   the prompt, even if the screen turns off while the prompt is open.
4. The prompt shows the source IP, the key label if the fingerprint was matched,
   the service, the parsed fields, and the raw service string. Values are monospace
   and truncated to 512 characters per field.
5. The user chooses:
   - **Block**: deny this call; no rule.
   - **Allow once**: allow this call; no rule.
   - **Always allow**: create an `ALLOW` rule with every field fixed, then allow.
   - **Custom filter**: open the editor; apply its rule, then allow or deny.
6. Timeout is 30 s and defaults to deny. A closed prompt, a backgrounded app
   without a notification tap, or a lost connection also denies. The ADB client has
   no application-level timeout for an `A_OPEN`, so the prompt timeout is the
   bound; the prompt shows a countdown.
7. Pending prompts are capped at 8; excess calls are denied and audited.
8. A `once` grant is bound to the connection and stream id, not to "the next
   call". It cannot be replayed by another connection.

`Block` means "deny this call"; "always deny" is a custom filter with the `DENY`
action. This keeps the four options from the request and avoids a permanent deny
rule created by a mis-tap.

### 7.6 Custom filter editor

The editor is prefilled with the parsed call. Each field is a row with the value
and a lock toggle:

- **Locked**: exact match on this value.
- **Unlocked**: `AnyValue`; the field is omitted from the rule.

For paths the editor offers exact or glob. For shell commands it shows the tokenized
`argv[]` and hides the raw command, because the raw command and the argv are two
views of the same data. If the call has shell metacharacters, the editor does not
allow unlocking the command or any argv token; it offers only an exact raw-shell rule
with an explicit "raw shell" warning. For an interactive `shell:` call, the editor
offers a single "full shell, not filtered" capability with an explicit confirmation;
there are no fields to pin.

The action selector is `Allow` or `Deny`. The preview shows the normalized rule in
the same notation as the prompt, for example:

```
ALLOW shell where
  argv[0] = "ls"
  argv[1] matches "/sdcard/*"
  all other fields fixed
```

The editor shows a warning when the rule is broad (all fields unlocked, or `service`
is `*`). A broad rule is allowed but labelled "allows/denies every call of this
service".

### 7.7 What the call policy cannot enforce

Shell is an execution primitive. Command-string filtering is policy and audit, not a
sandbox:

- `shell:sh -c '...'` and `shell:toybox ...` reach the same commands as a direct
  call. The parser can pin `sh` and its arguments, but cannot prove what the script
  does.
- `;`, `|`, `&&`, `$(...)`, backticks and redirection change the command. They are
  a `metacharacters` field; a normal argv rule never matches them.
- Interactive `shell:` is arbitrary after the first approval. It prompts by default
  and an `Always allow` answer creates an explicit capability rule. When that rule
  exists, the relay logs the open and close and does not inspect the typed
  commands; the UI labels the rule "full shell, not filtered".
- `sync:` paths can be bypassed with symlinks, `..`, bind mounts and aliases. Path
  matching is lexical and advisory.
- `tcp:` and `reverse:` expose ports and specs; the rule matches the spec, not what
  the peer does with it.
- File contents, screen frames and terminal output are not inspected.
- ADB key authentication is still enforced by `adbd`, not by the relay.
- The VPN ACL is still the outer boundary.

The UI says this in the module description and in the rule editor, so the user does
not mistake the firewall for a sandbox.

## 8. Audit log

Every event is a JSON line:

```json
{"t":"2026-11-05T10:00:00.000Z","event":"call_allowed","peer":"100.64.0.2","key":"phone-laptop","fingerprint":"SHA256:...","service":"shell","fields":{"argv[0]":"ls","argv[1]":"/sdcard/"},"rule":"r7","bytes":1234,"ms":42}
```

| Event | When |
|---|---|
| `connection_open` | TCP accepted |
| `ip_denied` | Peer outside the allowlist |
| `app_denied` | Target app not foreground, or screen off |
| `auth_ok` / `auth_unknown` | Signature matched a developer key, or did not |
| `call_open` | `A_OPEN` parsed |
| `call_allowed` / `call_denied` / `call_prompted` / `call_timeout` | Decision |
| `stream_closed` | Both sides closed; bytes and duration |
| `proxy_error` | Bind, accept, upstream or relay failure |

Storage is an append-only `audit.jsonl` in device-protected storage, rotated at
256 KiB with four files. The UI keeps the last 500 events in memory and exposes a
`StateFlow`. The audit log is always on while the firewall is on; it is separate
from the diagnostic log, which stays off by default.

Arguments can contain secrets (`adb shell` passwords, tokens, paths). The log stores
the parsed fields up to 512 characters each and offers a "Redact values" toggle that
replaces the field values with their SHA-256 prefix. The UI offers clear and copy.
The audit log never contains file contents, terminal output or screen frames.

## 9. State and lifecycle

`Prefs` gains:

```kotlin
var firewallEnabled: Boolean
var firewallIpAllowlistEnabled: Boolean
var firewallIpAllowlistJson: String
var firewallAppAllowlistEnabled: Boolean
var firewallAppAllowlistJson: String
var firewallAppGraceMs: Long
var firewallCallPolicyEnabled: Boolean
var firewallCallRulesJson: String
var firewallPromptTimeoutMs: Long
var firewallRedactAudit: Boolean
```

Debug-only: `debugVpnIp` overrides the detected VPN address for the emulator
e2e (`--es vpnIp`); a release build never sets it.

`ControlState` gains a `FirewallState` with the listener address, active
sessions, pending prompts, last denial and last error. `ControlService` owns the
relay exactly as it owns the daemon.

Lifecycle changes:

- `setup()` reads `firewallEnabled` and passes `exposeVpn = !firewallEnabled` to
  `DaemonManager`. With the firewall on, the daemon restarts only for a port change,
  not for a VPN address change; the relay rebinds instead.
- `verifyDaemon()` verifies loopback and, with the firewall on, verifies that the
  relay is bound; it does not dial the VPN address.
- `watchdog()` checks the relay with the daemon and restarts it if it is down.
- `networkChanged()` rebinds the relay when the VPN address changes and stops it
  when the VPN is gone.
- `stopEverything()` stops the relay before the daemon.
- `onDestroy()` stops the relay.
- Toggling the firewall in Settings stops the daemon, the relay and the watchdog,
  then runs a clean setup with the new listener set. Active external connections
  drop. The app process and the UI stay up.
- `Prefs.daemonFirewallMode` records the mode the daemon was started with, so a
  process restart or a stale `ControlBus` state cannot leave the daemon on the old
  listener set.

The relay binds only the exact VPN address, never `0.0.0.0`. If the VPN is down it
does not listen; the UI says "VPN is down" and external ADB is unavailable. If the
VPN port is Android's legacy plain listener (`adb tcpip`), the app sends `usb:` to
the system adbd, reconnects, waits for the port and retries the bind. It never
calls `adb tcpip` and never moves to another port silently.

## 10. UI

- Settings gains a `Firewall` card with a switch. The switch is the only way the
  tab appears. Turning it on or off restarts the daemon and the relay.
- The bottom bar shows `Firewall` only while the switch is on. If the switch is
  turned off while the tab is open, the UI returns to Home.
- `FirewallScreen` sections:
  - **Status**: listener address, active sessions, pending prompts, last error.
  - **IP allowlist**: switch, list, add dialog (`a.b.c.d` or `a.b.c.d/n`), delete.
  - **App allowlist**: switch, selected apps, searchable picker with a warning
    when ADB Anywhere itself is listed, delete, grace period (default 15 s, `0`
    means immediate).
  - **Call policy**: switch, rule list, default action, prompt timeout,
    interactive-shell capability, audit log link.
- `CallPromptActivity` is a modal screen with the call summary and the four
  actions. It uses `setShowWhenLocked(false)` and refuses to show over the
  keyguard; a locked phone gets a denial instead.
- `RuleEditor` is a modal screen with field rows, lock toggles, action selector,
  preview and save.
- The ongoing notification adds one line: `Firewall: 2 sessions, 1 prompt` or
  `Firewall: VPN is down`.

## 11. Failure modes

| Failure | Response |
|---|---|
| Relay bind fails | Setup waits for the port, closes Android's legacy plain listener with `usb:` if it owns the port, and retries. If the port stays busy, the setup fails with the port and the recovery step. |
| Relay dies | Watchdog restarts it; active sessions drop and must reconnect |
| VPN excludes the app | Relay cannot receive inbound packets; UI reports the bind/accept failure and asks to include ADB Anywhere in the VPN |
| VPN address changes | Relay rebinds; active sessions drop |
| VPN goes down | Relay stops; external ADB unavailable; UI says why |
| App process is killed | Service restart starts the relay again; prompts are lost and denied |
| `UsageStats` permission missing | App allowlist cannot be enabled; UI shows the grant action |
| Prompt not answered | 30 s timeout, deny, audit |
| Notifications denied | Prompt cannot be shown; deny after timeout, audit |
| Prompt queue full | Deny excess, audit |
| Too many connections | Cap at 8, close excess, audit |
| Malformed ADB frame | Close the connection, audit, never crash the service |
| `adbd` dies | Watchdog restarts it; relay sessions drop |
| System adbd reappears | Existing warning remains; the firewall does not hide it |
| Shell metacharacters | Denied unless an exact raw-shell rule exists |
| Path traversal / symlink | Path matching is advisory; the UI says so |

## 12. Security analysis

The firewall adds these layers:

1. The VPN address no longer reaches `adbd`; only the relay does.
2. The IP allowlist rejects peers before any ADB byte.
3. The app allowlist ties external ADB to a foreground app.
4. The call policy gates parsed services and arguments.
5. The audit log records every decision.

It does not add:

- A second authentication factor. ADB key auth remains the only
  authentication, enforced by `adbd`.
- A sandbox for `shell`. A shell is a shell.
- Protection from a compromised VPN peer that is on the IP allowlist and has a
  paired key. That peer has the same access as before, minus the call policy.
- Protection from a local app that can reach `127.0.0.1:<loopback>` and has the
  app key. The app key stays in app-private storage; the loopback channel is
  unchanged.
- Protection from a malicious local app that binds the relay address before the
  relay starts. The relay sets `SO_REUSEADDR` so a `TIME_WAIT` socket from its own
  previous instance does not block the bind, but a live listener still does; it reports
  a bind failure and does not fall back to another port.
- A VPN that excludes ADB Anywhere from the tunnel: this is an accepted corner
  case, not a supported configuration. The relay's listener is in the app's UID, so
  a per-app VPN that excludes the app does not deliver inbound packets to it. The
  bind or accept fails, the setup shows the error, and the app does not fall back
  to another port or to the direct VPN listener.

The relay caps connections at 8 total and 2 per peer, caps a pre-`A_CNXN` payload
at 4 KiB and any payload at 1 MiB, and closes a connection on a malformed frame.
That is DoS resistance for the app's own listener, not a general network firewall.

The relay is a policy enforcement point, not a security boundary against a
determined shell user. The UI and this document say so.

## 13. Tests

JVM unit tests:

- CIDR parse and match, including `/0`, `/32`, malformed and IPv6 rejection.
- ADB frame parser: valid header, bad magic, oversized payload, truncated
  payload, `A_OPEN`, `A_CLSE`, `A_WRTE`, `A_AUTH`.
- Call parser: `shell:` v1/v2, quotes, escapes, metacharacters, interactive,
  `exec:`, `abb_exec:`, `sync:` SEND/RECV/STAT v1 and v2, `tcp:`, `reverse:`,
  unknown.
- Rule matcher: exact, prefix, glob, any, first match, built-in denies.
- Sync parser: v1 and v2 ids, split frames, path extraction, truncation,
  malformed.
- Foreground app state machine with a fake clock and fake usage events,
  including the grace timer and a return before it expires.
- Audit ring, rotation and redaction.
- Relay with in-memory sockets: allow, deny, hold, timeout, close, oversized frame.

Device/integration:

- Emulator e2e: `scripts/e2e-firewall-emulator.sh` creates a debug-only
  fake VPN address, then exercises the relay, IP and app allowlists, call policy
  prompts, sync push/pull and the audit log against the real `adb` client.
- Real `adb` client through the relay: `shell`, `exec-out`, `push`, `pull`,
  `install`, `forward`, `reverse`, `logcat`.
- VPN bind probe: bind and accept on the VPN address from the app process,
  with and without per-app VPN exclusion.
- IP deny: `adb connect` fails, no prompt, audit event.
- App allowlist: switch apps and verify the decision, including the grace
  timer and a return before it expires.
- Self allowlist: allowlisting ADB Anywhere keeps ADB open in its UI, and a call
  that raises the approval dialog does not count as the foreground app.
- Call policy: allow once, always, block, custom filter, timeout.
- Prompt while backgrounded: notification path.
- Screen off: deny.
- VPN address change: rebind, sessions drop.
- Daemon restart: relay recovers.

## 14. Phasing

- **Phase 1 — relay and IP allowlist**: probe the app's bind on the VPN
  address, mode switch, loopback-only daemon, TCP relay, IP allowlist,
  connection audit, tab and Settings switch.
- **Phase 2 — call policy**: `A_OPEN` parser, shell/exec/abb_exec/tcp/reverse
  calls, rules, prompt, custom filter, interactive-shell capability, audit per
  call.
- **Phase 3 — app allowlist**: `UsageStats` monitor, launcher picker, grace
  timer, live session enforcement, screen-off deny.
- **Phase 4 — depth**: `sync:` path rules, shell-v2 stdin rules, IPv6, audit
  export and redaction presets, per-key defaults.

## 15. Decisions and residual questions

Decisions:

1. Relay placement: app-side Kotlin relay.
2. Foreground change: close live sessions after a configurable grace period
   (default 15 s, `0` means immediate); a return before it expires cancels it.
3. Prompt scope: apply rules first; prompt only an unmatched `A_OPEN`.
4. Interactive `shell:`: allow as an explicit capability rule created by
   `Always allow`; log open and close, do not inspect input; label it
   "full shell, not filtered".

Residual questions for implementation:

1. IPv6 allowlist entries: add after the IPv4 relay works, or keep IPv4 only.
2. Audit redaction: default on or off; which field names are treated as
   secrets.
3. Per-key defaults: should a developer key carry its own default action and
   rule set, or share one global rule set.
4. Audit export: share a file, copy JSONL, or both.

## 16. Implementation status

Implemented in the app:

- `firewall/FirewallModels.kt`: `IpNetwork`, `IpAllowlist`, `AdbCall`, `CallRule`,
  `CallPredicate`, `FirewallConfig` and their JSON form.
- `firewall/AdbFrameCodec.kt`: the 24-byte ADB header, bounded payloads and
  injected `A_CLSE`.
- `firewall/AdbCallParser.kt`: `shell`, `exec`, `abb_exec`, `sync`, `tcp`,
  `reverse`, `framebuffer`; unknown and unsupported services are denied.
- `firewall/SyncParser.kt`: the first client sync frame, path and direction,
  v1 and v2 ids.
- `firewall/CallRules.kt`: built-in denies, first-match user rules, prompt and
  deny defaults.
- `firewall/AdbRelay.kt`: the byte relay, `A_OPEN` policy, sync path policy,
  stream tracking and denied-stream drops.
- `firewall/FirewallProxy.kt`: VPN listener, IP allowlist, app allowlist, grace
  timer, connection caps and the relay.
- `firewall/ForegroundAppMonitor.kt` and `ForegroundTracker.kt`: `UsageStatsManager`,
  screen and keyguard state; the first sample queries the last hour, so a
  long-running foreground app is still seen.
- `firewall/AuditLog.kt`: the bounded JSONL log and ring.
- `firewall/FirewallConfigStore.kt`: the persisted configuration.
- `adb/SystemAdb.closePlainListener()`: sends `usb:` to the system adbd so the
  firewall can take over its port.
- `service/ControlService.kt`: loopback-only daemon when the firewall is on,
  full-stack restart on a firewall toggle, relay lifecycle, port takeover from the
  system plain listener, prompt notification and response.
- `ui/FirewallScreen.kt`, `ui/CallPromptScreen.kt`, `ui/CallPromptActivity.kt`,
  `ui/RuleEditor.kt`: the tab, the prompt and the custom filter.
- `ui/SettingsScreen.kt` and `ui/App.kt`: the switch and the conditional tab.

Tests:

- `FirewallDataTest`: IPv4/CIDR, lexer, service parser, rules, sync parser,
  frame codec, foreground tracker, config JSON.
- `AdbRelayTest`: allow, deny, prompt, sync path deny, malformed frame.
- `FirewallProxyTest`: accept and relay, IP deny, app deny.
- `AuditLogTest`: ring, file, redaction, clear.
- `ControlDataTest`: `Endpoint.listenSpec` with the VPN listener hidden.

Deferred:

- IPv6 allowlist entries.
- Audit export and redaction presets.
- Per-key default actions and rule sets.
- Sync `DATA` contents and shell output inspection.
- Work-profile apps in the picker.
- Key fingerprint in the prompt and `auth_ok`/`auth_unknown` audit events.
- A dedicated "block once" action; Block denies this call only.
