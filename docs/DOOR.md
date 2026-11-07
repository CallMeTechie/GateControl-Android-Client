# Tür & Kamera (door plugin screen) and push pictures / quick actions

Developer notes for app 1.34: the native door screen "Tür & Kamera" /
"Door & camera" of the `gatecontrol-door` plugin 1.0.0 (part C of the door
contract) and the two
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

## No tab: how the door screen is reached

The door plugin has **no tab and no card** (since 1.34.1; 1.34.0 had a
"Tür" tab — the bottom bar got too full). Everyday use goes through the
plugin's quick actions (start-tab tiles, widget, app shortcuts, Quick
Settings — unchanged) and the door pushes. The door screen "Tür & Kamera"
is a normal navigation destination (`Screen.Door`, `door?dest=…`, no
bottom bar; back returns to where the person was) reached only by:

- **a door push**: a tap on the message or its "Ansehen" (see
  "Push routing" below);
- **a long press on a door quick action** — a pinned tile on the start tab
  or a row of the quick-action picker ("Schnellaktionen"). Generic rule
  (`QuickActionLongPress.target`, pure): an action of plugin
  `gatecontrol-door` opens the door screen; other plugins' actions have no
  long press. Accessibility label of the long press: "Tür & Kamera öffnen".
- **the row "Tür & Kamera · Kameras ansehen" on Start** for people without
  any door quick action — someone who may only see cameras (home cameras of
  plugin 1.6.0, or Ring cameras without a lock) has nothing to long-press.
  `DoorEntry.show` (pure): the door plugin is listed, it is Start (not a page
  beside it), the quick actions came from the server at least once (no
  flicker for people with locks) and none of them is the door plugin's. A
  card in the app's list-row style (camera icon, chevron) below the tiles; a
  tap opens the door screen.

- `PluginAvailability.from()` (`familie/PluginDirectory.kt`, same cache as
  the Familie tab): `door` when `gatecontrol-door` is listed at all. The
  plugin's `ui.app` / `tab` entry is ignored — it never creates a tab
  (`MainTabs.routes`: Start, Netzwerk, Familie).
- Without `door` (plugin gone, older server) a stored door pin has no long
  press and door pushes open the message itself.

## Code

| Where | What |
|---|---|
| `door/DoorModels.kt` | `DoorOverview` (`GET me`), `DoorLock` (LockView), `DoorCamera`, history items, event detail, pictures |
| `door/DoorJson.kt` | Parser of every answer (Gson tree, no reflection): positive ids, known enums, plain capped texts, base64 pictures ≤ 1 MB, error `code` / `retryAfter` / `error` text |
| `door/DoorApi.kt` | One method per endpoint of `/api/v1/client/plugins/gatecontrol-door/api/…` with `?lang=` |
| `door/DoorRepository.kt` | Last `me` (shown and stored as raw JSON in `files/door/me.json` per login, never pictures or history); door push signal |
| `door/DoorLogic.kt` | Pure decisions: buttons, `steps()` (confirm → device auth → run), state kinds, history filters |
| `door/DoorPush.kt` | `data.door` of door pushes, `DoorDest` (home = door screen / history / event:<id>) |
| `ui/door/…` | `DoorHomeScreen` (door screen: locks, cameras, Ring status, history link), `DoorHistoryScreen`, `DoorEventScreen`, `DoorPages.kt` (`DoorPage`, `DoorTwoPane`), `rememberDoorActionStarter` |
| `navigation/DoorNavigation.kt` | Pure: which door screens a push opens (`DoorStep.Main` / `DoorStep.Page`) |
| `navigation/MainTabs.kt` | Pure: the tab list (no door tab) |
| `quickactions/QuickActionLongPress.kt` | Pure: long press on a door quick action → door screen |
| `ui/components/GcDeviceAuth.kt` | androidx.biometric `BiometricPrompt`, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` |
| `push/NotifyMediaLoader.kt`, `push/NotificationQuickActions.kt` | Push pictures and quick action buttons (above) |
| `door/HomeCameraModels.kt`, `door/HomeCameraJson.kt` | Home cameras (plugin 1.6.0): list, live, still, clips — parsed like `DoorJson` |
| `door/HomeCameraLogic.kt` | Pure: `CameraMediaUrls` (own-server `/camera-media/` paths, token swap for the player), token renewal, retries, which camera, clip length, still file name |
| `door/HomeCameraMedia.kt` | Stills and thumbnails over the client API's OkHttp (memory only), Media3 data sources, "Standbild" to the gallery / share file |
| `ui/door/HomeCameraViewModel.kt`, `ui/door/HomeCameraSection.kt` | "Kameras" of the door screen: chips, live picture / stills, Standbild, Vollbild, Ton, "Clips heute", clip player |

## Door screen content

- Title bar "Tür & Kamera" with back, the Ring status line below it.
- **Locks** (`GET me`, polled every 10 s only while the door screen is
  resumed, plus on every door push meanwhile; nothing polls while it is
  closed): name, state text and icon (smart lock: locked …
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
- Snapshots stay in memory only as long as the screen.

## Kameras im Heimnetz (plugin 1.6.0)

Cameras with ONVIF/RTSP behind a GateControl gateway (GateControl ≥ 1.240.0,
gateway ≥ 1.28.0 with "Kameras"; the gateway's `docs/cameras.md` is the
contract). The door screen shows them as the section **"Kameras"** between the
locks and the Ring cameras (those are then labelled "Ring-Kameras"); Ring
itself is unchanged.

- **Which cameras:** `GET cameras/home` → only the cameras the person "sieht"
  (column on the plugin's rights page; the server filters, the app shows what
  it gets). `supported: false` (older GateControl), 404 (older plugin) or 403:
  no section. Polled every 60 s while the door screen is in front.
- **Title** "Kameras" with "n online", a chip per camera (offline ones
  "(offline)"); the first online camera is shown, a push picks its camera.
- **Picture:** `GET cameras/home/<id>/live`. In the VPN (or the server's LAN —
  the server decides from where the request comes) `mode: live` with the HLS
  list (fMP4, `hls`), played by Media3 ExoPlayer over the client API's OkHttp
  (VPN-safe DNS); pills "● LIVE" and "HD · über VPN" (HD from the video height,
  before that from `settings.stream`), name and the clock. Until the first
  frame (and while the video fails) the still of the answer shows. Otherwise
  `mode: snapshot` (`reason: vpn_required`): a new `snapshot.jpg?t=<n>` every
  `every` s (10), with its `X-Snapshot-At` time. A change of the VPN asks
  again after 1.5 s. Nothing runs while the screen is not in front.
- **Tokens:** media addresses are `/camera-media/<token>/<file>` of the own
  server, valid 10 minutes (thumbnails and clips 1 h). The app asks for a new
  live answer one minute before `expiresAt` (within 2…9 minutes, the device
  clock may be off). The player keeps running: every load goes through a
  `ResolvingDataSource` that maps the requested live file (`index.m3u8`,
  `init.mp4`, `s<n>.m4s`) to the **newest** token (`CameraMediaUrls.retoken`)
  and refuses anything that is not a live file of the own server. A player
  error (ring not ready yet: 409) shows stills and tries again after 3, 6, …
  ≤ 60 s.
- **Own push (plugin 1.7.0):** a card with the switch **"Push bei Personen"**
  ("Mitteilung mit Standbild, wenn diese Kamera eine Person sieht – nur für
  dich") under the picture — `PUT cameras/home/<id>/prefs { push }`,
  optimistic, reverted with the error on refusal (e.g. `read_only`). Only when
  the list has `prefs.push` (older plugins: no row) and the camera pushes at
  all (`push`). The list also carries `width`/`height` (HD before the first
  frame), `audio`, `hasAudio`; state `tls_untrusted` and error `cam_tls` have
  their own texts.
- **Buttons:** **Standbild** → `GET …/still` (JPEG ≤ 700 KB) → "In Galerie
  speichern" (MediaStore, Pictures/GateControl, no permission) or "Teilen"
  (one file in `cacheDir/camera-still`, FileProvider, removed with the
  screen); **Vollbild** (the same player in a full-screen dialog); **Ton**
  (only when the live answer has `audio`; off by default).
- **Clips heute:** `GET …/clips` (the server's day) every 2 minutes and on a
  camera push: thumbnail (`thumb.jpg`, memory LRU by clip id), "Person · 0:24",
  time, "Niemand zu Hause", pill Person / Auto / Tier / Bewegung. A tap plays
  `clip.mp4` in a full-screen player with controls (only that address); a list
  older than 50 minutes is fetched again first.
- Errors `cam_*` have their own texts (`doorErrorText`).

## Push routing

- Door messages: topic `plugin:gatecontrol-door:*` or route
  `plg-gatecontrol-door`. `DoorPush.destination()`: `ding`/`motion` with an
  `eventId` → `event:<id>`, everything else → the door screen. A tap on the
  message and its `open_app_route` "Ansehen" both go there
  (`PushNavTarget.Door`, `DoorNavigation.forPush`): phones open the door
  screen and the ring's detail on top of it (back → door screen → where
  the person was); wide windows open the door screen with the detail
  beside its list. An already open door screen is replaced. Without the
  plugin listed the message detail opens.
- **"Personen an der Kamera"** (topic `plugin:gatecontrol-door:kamera`,
  `data.door = { type: "home_camera_event", cameraId: "c…", eventId: "e…" }`,
  with the still as push picture — shown like every push picture, see above):
  a tap opens the door screen showing that camera (`DoorDest.Camera`,
  `camera:<id>`; phones and wide windows alike) and scrolls "Kameras" into
  view; an open door screen reloads the clips. Plugin 1.7.0 adds the button
  **"Ansehen"** (`open_app_route` → `plg-gatecontrol-door`): the notification
  shows it first, its intent carries the same `camera:<id>`
  (`PushNavTarget.forRoute`), so it opens that camera too.
- Channels: `plugin:gatecontrol-door:doorbell` and
  `plugin:gatecontrol-door:kamera` get a **high importance** channel
  (`PushChannels.HIGH_TOPICS`), so a ring or a person pops up; the other door
  topics use the default importance. Android keeps the importance of a
  channel that already exists.

## Privacy

Door history and doorbell pictures are stored on the own server only. The
app keeps the last `me` (rights, locks, states, cameras — no pictures) for
offline display and holds pictures in memory only. Home-camera video, stills
and clips are played from the own server and never stored, except a still the
person saves ("In Galerie speichern") or shares (one cache file, removed with
the screen). Push pictures are shown
in notifications (also on the lock screen, depending on the Android
setting) and the inbox. See `docs/PRIVACY_POLICY.md`.
