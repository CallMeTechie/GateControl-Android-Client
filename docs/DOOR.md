# Tür & Kamera (door plugin tab) and push pictures / quick actions

Developer notes for app 1.34: the native "Tür" / "Door" tab of the
`gatecontrol-door` plugin 1.0.0 (part C of the door contract) and the two
generic notification features of GateControl 1.169 it relies on — pictures
in pushes (`imageUrl`) and notification buttons that run a plugin's quick
action (`quick_action`). Everything goes to the user's own GateControl
server through the same client (token, machine binding, VPN-safe DNS) as
every other call of the app.

## Push pictures (all plugins)

- Core 1.169 adds `imageUrl` to the `notification` stream event and to the
  inbox items (`GET /api/v1/client/push/inbox`): a relative path
  `/notify-media/<id>.<ext>` on the own server, `null` without a picture
  or once it expired (the push's ttl; doorbell: 1 h).
- `PushEventParser.imagePath()` keeps only `/notify-media/<url-safe id>.(jpg|jpeg|png|webp)`;
  anything else is dropped. `PushMessage.imageUrl`
  is cached with the inbox (null for entries cached by older versions).
- `NotifyMediaUrls.resolve()` builds the absolute address from the
  configured server URL only (an absolute URL is accepted only on the same
  scheme, host and port, without query) — a message can never make the app
  fetch from another host.
- `NotifyMediaLoader` (sibling of `AvatarLoader`) downloads through the
  client API's OkHttp (`ApiClient.fetchNotifyMedia`), accepts only
  `image/*` answers up to 1 MB, decodes downscaled (notification 1024 px,
  inbox thumbnail 256 px) and keeps the bitmaps **in memory only** (LRU,
  8 MB) — doorbell pictures may show people and expire on the server, so
  nothing is written to disk.
- `PushNotifier.show()` waits up to 2.5 s for the picture; with it the
  notification uses `BigPictureStyle` (+ large icon), without it the
  notification is posted at once and updated quietly
  (`setOnlyAlertOnce`, `setSilent`) when the picture arrives — only if the
  same message is still shown (message id in the notification extras).
- The inbox row shows a thumbnail, the message detail the full picture
  (`rememberPushPicture`).

## Notification quick actions (all plugins)

- Shape the apps receive in `data.actions[]`:
  `{ id, label, type: "quick_action", plugin, confirm: bool, auth?: "device" }`
  where `id` is one of the sending plugin's quick action ids (≤ 64,
  `[A-Za-z0-9:._-]`). Malformed ones (no plugin, bad id, empty label) are
  dropped by the parser; `auth` is kept only when it is exactly `device`.
- Buttons (`NotificationQuickActions.plan`, pure): the first server action,
  "Gesehen", then the rest, at most three. A `quick_action` becomes a
  broadcast to the non-exported `PushActionReceiver` (`ACTION_QUICK`); with
  `auth: "device"` the button is built with
  `NotificationCompat.Action.Builder#setAuthenticationRequired(true)`, so
  Android asks for the device unlock before it sends it (minSdk 31). The
  notification never asks `confirm` — the unlock is the confirmation; an
  action without auth runs straight away.
- Before running, `NotificationQuickActions.decide()` checks: the message is
  still on the device, the action is one of **that message's own**
  quick_action buttons (same plugin and id), the message has not expired
  (`expires_at`), and an `auth: device` action is refused while the device
  is still locked (`KeyguardManager.isDeviceLocked`, defence in depth).
- Run: `QuickActionRepository.runFromNotification(plugin, id)` →
  `POST /api/v1/client/actions/:plugin/:id` with body
  `{ "source": "notification" }` (core passes it to the plugin as
  `via: 'notification'`; in-app runs send `source: "app"`). Success acks
  the message as read with the action id (`POST push/ack`, `action` ≤ 64).
- Result: the message's notification is replaced quietly with the plugin's
  `message` (or "Erledigt" / "Hat nicht geklappt"). Success: no buttons,
  gone after 10 minutes. Failure: the buttons stay so it can be tried
  again. A slow lock (> 8 s) shows "Läuft noch …" and the call keeps
  running, so a late success is never reported as a failure.
- In the app (message detail): a quick action with `confirm` or
  `auth: device` asks first; `auth: device` then needs the device unlock
  (`GcDeviceAuth`, see below). The result is a toast.

## When the door tab shows

- `PluginAvailability.from()` (`familie/PluginDirectory.kt`, same cache as
  the Familie tab): `doorTab` when `gatecontrol-door` is listed with a
  non-null `tab` (the plugin lists it for people with a right on at least
  one lock). Icon: the plugin's `tab.icon` SVG path (validated, falls back
  to `GcIcons.Door`), label from the app's strings ("Tür" / "Door").
- Slot: after Familie, before Einstellungen.

## Code

| Where | What |
|---|---|
| `door/DoorModels.kt` | `DoorOverview` (`GET me`), `DoorLock` (LockView), `DoorCamera`, history items, event detail, pictures |
| `door/DoorJson.kt` | Parser of every answer (Gson tree, no reflection): positive ids, known enums, plain capped texts, base64 pictures ≤ 1 MB, error `code` / `retryAfter` / `error` text |
| `door/DoorApi.kt` | One method per endpoint of `/api/v1/client/plugins/gatecontrol-door/api/…` with `?lang=` |
| `door/DoorRepository.kt` | Last `me` (shown and stored as raw JSON in `files/door/me.json` per login, never pictures or history); door push signal |
| `door/DoorLogic.kt` | Pure decisions: buttons, `steps()` (confirm → device auth → run), state kinds, history filters |
| `door/DoorPush.kt` | `data.door` of door pushes, `DoorDest` (home / history / event:<id>) |
| `ui/door/…` | `DoorHomeScreen` (locks, cameras, history link), `DoorHistoryScreen`, `DoorEventScreen`, `DoorTwoPane`, `rememberDoorActionStarter` |
| `ui/components/GcDeviceAuth.kt` | androidx.biometric `BiometricPrompt`, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` |
| `push/NotifyMediaLoader.kt`, `push/NotificationQuickActions.kt` | Push pictures and quick action buttons (above) |

## Tab content

- **Locks** (`GET me`, polled every 10 s while the tab is resumed, plus on
  every door push): name, state text and icon (smart lock: locked …
  uncalibrated; opener: online, rto_active, open, …; `reachable: false` =
  "Nicht erreichbar"), door sensor chip (only with a sensor), battery
  (critical / percent / charging), "Nur ansehen" for the `view` right.
  One button per entry of `actions` the right allows (opener `open` =
  "Summer"); while `blocked` (`read_only`, `outside_window`, or any unknown
  value) or `me.canAct` is false they are disabled with the reason.
- **Before an action** (`DoorLogic.steps`): the confirm dialog for every
  action in the lock's `confirm`, then for `unlock` and `open` always the
  device unlock — `BiometricPrompt` with strong biometrics or the screen
  lock credential, confirmation required. **Without a screen lock the
  action is refused** with a hint to set one up (security first). Then
  `POST locks/<id>/<action>`; the answer's `lock` replaces the card, its
  `message` (or an error text) shows in a snackbar.
- **Cameras**: latest picture (`GET cameras/<id>/snapshot`, decoded in
  memory, dropped with the screen), "Neues Bild" (`?refresh=1`; on
  `429 too_soon` the button counts down `retryAfter`), "Klingel-Push" (only
  doorbells) and "Bewegungs-Push" switches (`PUT cameras/<id>/prefs`,
  optimistic, reverted on failure). Status line under the title:
  "Ring: inoffizielle Verbindung" / "Ring: gestört" / "Ring: nicht verbunden".
- **Verlauf**: `GET history` in pages of 30 (`nextCursor`), filter Alle /
  Aktionen (`kind=action`) / Klingeln (`kind=ding`). Rings and motion open
  the event detail; refused / failed entries show the plugin's
  `reasonText`.
- **Event detail** (`GET events/<id>`; from the history, a tap on a
  ding/motion push or its "Ansehen"): the picture (or "Kein Bild"), camera,
  lock, time, and for the `open` right "Tür öffnen" / "Summer" (confirm +
  device unlock).
- Wide windows: locks/cameras as the list, history or event beside it.

## Push routing

- Door messages: topic `plugin:gatecontrol-door:*` or route
  `plg-gatecontrol-door`. `DoorPush.destination()`: `ding`/`motion` with an
  `eventId` → `event:<id>`, everything else → the tab. A tap on the message
  and its `open_app_route` "Ansehen" both go there
  (`PushNavTarget.Door`); without the tab the message detail opens.
- Channels: `plugin:gatecontrol-door:doorbell` gets a **high importance**
  channel (`PushChannels.HIGH_TOPICS`), so a ring pops up; the other door
  topics use the default importance. Android keeps the importance of a
  channel that already exists.

## Privacy

Door history and doorbell pictures are stored on the own server only. The
app keeps the last `me` (rights, locks, states, cameras — no pictures) for
offline display and holds pictures in memory only. Push pictures are shown
in notifications (also on the lock screen, depending on the Android
setting) and the inbox. See `docs/PRIVACY_POLICY.md`.
