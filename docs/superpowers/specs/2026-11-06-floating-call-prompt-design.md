# Floating call prompt — design

Status: approved
Date: 2026-11-06
Scope: the call-policy approval prompt, shown as a non-modal overlay above the
current app instead of a notification that sometimes opens an activity

## 1. Goal

When the call policy holds an `A_OPEN`, show the existing approval UI as a
non-modal floating card above whatever app is in the foreground. The card is the
primary prompt; the notification becomes the fallback when the app cannot draw
overlays.

The current flow posts a high-priority notification and also calls
`startActivity(CallPromptActivity)` from `ControlService`. Android 10+ restricts
background activity starts, so the activity appears only when the system happens to
allow it. The notification tap is reliable, but it is the only reliable path
today.

## 2. Non-goals

- Showing the prompt over the keyguard or while the screen is off. The existing
  rule stays: no interactive screen or a locked keyguard denies without a prompt.
- A general-purpose overlay, a floating bubble, or a draggable window.
- A second prompt style or a setting to choose modal vs non-modal.
- Changing the relay, the rule evaluation, the prompt timeout, or the audit format.

## 3. Behavior

- With `Settings.canDrawOverlays(context)` true, `ControlService.promptForCall`
  shows the floating card and does not post the prompt notification.
- The card is non-modal: it takes no input focus, touches outside it go to the app
  underneath, and the app underneath stays usable.
- The card is top-center, with a 24 dp edge inset, and reads as a heads-up card
  rather than a full-screen dialog.
- The card holds the call for the configured prompt timeout. The existing actions
  (`Allow once`, `Always allow this exact call`, `Custom filter`, `Block`,
  dismiss) resolve the call exactly as they do today.
- `Custom filter` expands the rule editor inside the card. It does not open a
  second window, so the editor cannot fall behind the overlay.
- Without the overlay permission, `promptForCall` keeps today's behavior: post the
  prompt notification and try to open `CallPromptActivity`. The notification tap is
  user-initiated, so that launch is allowed.
- The overlay is hidden on a response, on timeout, when the prompt is cleared, when
  the firewall is stopped, and in `onDestroy`.
- The ongoing service notification still reports `Firewall: N sessions, M prompts`.

## 4. Components

| Component | Responsibility |
|---|---|
| `ui/CallPromptOverlay.kt` | Owns the `WindowManager` and the `ComposeView`. `show(request)` adds the overlay window; `hide()` removes it. It hosts `CallPrompt` in inline mode and sends the decision through `ControlService.startPromptResponse`. |
| `ui/CallPromptScreen.kt` | Gains an `inline` mode. Inline renders the card as a `Surface` and the rule editor as an expanded state inside the same window. Dialog mode is unchanged for `CallPromptActivity`. |
| `ui/RuleEditor.kt` | The editor body is extracted from `RuleEditorDialog` so the overlay can host it inline. `RuleEditorDialog` keeps wrapping it in a `Dialog` for the activity. |
| `service/ControlService.kt` | `promptForCall` chooses overlay or notification, hides the overlay on every exit path, and removes it in `stopEverything`/`onDestroy`. |
| `AppViewModel.kt` | Adds `requestOverlayAccess(activity)` using `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. |
| `ui/FirewallScreen.kt` | The Call policy section gains a "Floating prompt" row with the current grant state and a "Display over other apps" action. |
| `AndroidManifest.xml` | Adds `android.permission.SYSTEM_ALERT_WINDOW`. |

## 5. Data flow

```
AdbRelay A_OPEN
  -> RelayPolicy.prompt
  -> ControlService.promptForCall
     -> screen off / locked: deny, audit, return
     -> canDrawOverlays: CallPromptOverlay.show(request)
     -> otherwise: postPromptNotification + CallPromptActivity
  -> CallPrompt (inline or dialog)
  -> startPromptResponse
  -> ControlService resolves the CompletableDeferred
  -> AdbRelay forwards or denies the held stream
  -> overlay hidden, audit call_allowed / call_denied / call_timeout
```

The overlay does not own the prompt state. `ControlService` remains the single
owner of the pending prompt, the timeout, and the response, so the overlay and the
activity share one path and cannot diverge.

## 6. Failure modes

| Failure | Response |
|---|---|
| Overlay permission missing | Notification fallback, unchanged from today |
| Overlay add rejected by the OEM | Notification fallback; `AppLog.warn("firewall", ...)` |
| Overlay removed while the prompt is pending | The prompt still times out and denies; the service hides the overlay |
| App process killed | The overlay disappears; the prompt is lost and denied, as today |
| Screen off or keyguard locked | Deny before any prompt; unchanged |
| Custom filter editor | Inline expanded card; no second window, no Compose `Dialog` in the overlay |
| Notification fallback | `CallPromptActivity`, unchanged; the notification tap is user-initiated |

The overlay is a `TYPE_APPLICATION_OVERLAY` window. Android does not show it over
the keyguard, which matches the existing denial rule rather than contradicting it.

## 7. Testing

JVM unit tests:

- Presenter choice: overlay when the overlay host is available, notification
  when it is not, deny when the screen is off or locked.
- Overlay hide is called on allow, block, custom, timeout, and service stop.

Instrumented/e2e:

- Grant `SYSTEM_ALERT_WINDOW` with
  `adb shell appops set cz.slush.adbanywhere SYSTEM_ALERT_WINDOW allow`.
- Background the app, fire `adb shell id`, assert the card is visible with the app
  not in the foreground, answer `Allow once`, assert the call proceeds and no
  prompt notification was posted.
- Deny path: answer `Block`, assert the call fails and the audit records
  `call_denied` with rule `prompt`.
- Timeout path: do not answer, assert the card disappears and the audit records
  `call_timeout`.

Manual:

- Background the app, fire a call, see the floating card above the current app,
  answer it, verify the call and that no notification prompt was posted.

## 8. Documentation

`docs/FIREWALL.md` §7.5 (prompt flow) and §10 (UI) are updated with the
overlay path and the notification fallback.

## 9. Decisions

1. Prompt style: non-modal floating card. The call is already held for 30 s, so
   the prompt does not need to block the phone too.
2. Position: top-center, 24 dp edge inset. A centered card would cover the
   content it is asking about.
3. Fallback: the notification and `CallPromptActivity` stay for the missing
   permission and for the user who taps the notification.
4. Custom filter: inline in the same overlay window. A Compose `Dialog` inside an
   overlay would open a normal app window that can fall behind the overlay.
5. Keyguard: no change. Overlays cannot cover the keyguard, so the existing
   screen-off/locked denial remains the authority.
