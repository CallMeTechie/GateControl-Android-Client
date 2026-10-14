# Familie (family plugin tab)

Developer notes for the "Familie" tab: the app side (part C) of the
`gatecontrol-familie` plugin 1.0.0. The server side is the core client
plugin API (`GET /api/v1/client/plugins`, `ALL
/api/v1/client/plugins/:id/api/*`, GateControl ≥ 1.164.0) and the plugin's
own app API (contract B3). Everything goes to the user's own GateControl
server through the same client (token, machine binding, VPN-safe DNS) as
every other call of the app.

## When the tab shows

- `PluginDirectory` (`familie/PluginDirectory.kt`) calls `GET
  /api/v1/client/plugins?lang=…` on process start (`FamilieStartup`) and
  whenever `MainActivity` comes to the foreground (at most every 3 minutes).
- `PluginAvailability.from()`: the tab shows when `gatecontrol-familie` is
  listed **with** a non-null `tab`. Listed without a tab (plugin's
  `appVisible()` said no) = API usable, no tab. 404 / 401 / 403 (older
  server, token without the scope or owner) = no plugins, no tab. Other
  errors and offline keep the last answer.
- The answer is cached in SharedPreferences (`familie_plugins`) together
  with a hash of server URL + token (`ownerKey`), read synchronously at
  start: the tab is there from the first frame and never flickers. Another
  server or token = empty cache.
- Slot: after the built-in tabs (Start, Fernzugriff, Netzwerk), before
  Einstellungen. Settings stay last everywhere in the app; existing tabs
  keep their position. Label from the app's strings ("Familie" /
  "Family"), icon = the plugin's `tab.icon` SVG path (validated, falls back
  to a house).
- `gatecontrol-alltag` listed → the dates screen also loads Alltag
  appointments and waste dates (`GET …/gatecontrol-alltag/api/app/events`).

## Code

| Where | What |
|---|---|
| `core/network` `ApiClient` | `getClientPlugins`, `pluginGet/Post/Patch/Delete` (raw JSON, path built by the app) |
| `core/notify` | `PushMessage.dataJson` (the message's `data` object as text), `PushPresenter.onNew()` hook (first arrival of a message, also when its topic is muted) |
| `familie/FamilieApi.kt` | one method per B3 endpoint, `FamilieResult` (Ok / Failed(code, http) / Offline / NotConfigured), never throws |
| `familie/FamilieJson.kt` | parsers (lenient booleans, strict ids, plain capped text) and request bodies |
| `familie/FamilieRepository.kt` | `GET me` cached in `files/familie/profile.json` (offline start, background work), push events for open screens, SOS state |
| `familie/shopping/` | offline shopping lists: `ShoppingLogic` (view = server snapshot + outbox, merge), `ShoppingSyncEngine`, `ShoppingRepository`, `ShoppingSyncWorker` |
| `familie/push/` | `FamiliePush` (parse `data.familie`, routing, buttons), `FamiliePushHandler`, `FamilieActionReceiver`, `FamilieLocateActivity`, `FamilieOpenActivity`, `FamilieLocalNotifier` |
| `familie/locate/` | `LocateAnswerer` (fix + answer/fail), `FamiliePermissions` (pure permission decisions) |
| `familie/places/` | `GeofenceRegistrar`, `GeofenceReceiver`, `PlaceTriggerWorker` |
| `ui/familie/` | screens, `FamilieTab.kt` (dest → screen, two-pane), `OsmMap.kt` |

Screens are addressed by `FamilieDest` (`home`, `shopping/<listId>`,
`tasks`, `dates`, `locate/<child>[/<request>]`, `sos/<id>/<user>/<lat>/<lon>/<acc>`,
`child`, `places`, `place/<id|new>`): route `familie?dest=` (tab; on wide
windows the screen beside the start screen, `GcTwoPane`) and
`familie/page?dest=` (phones).

## Screens

All screens follow `GET me`: only modules that are on, filtered by role.

- **Start** (mockup Main): members, "Heute" (my tasks, stars for
  children), shopping lists with open counts (from the device, works
  offline), "Kinder orten" per child (parents), "Mein Handy" with "Bin
  angekommen" / SOS (children), next dates, place reminders. Pull to refresh.
- **Einkaufsliste**: lists as chips, add (text + quantity), tick, edit,
  delete, "Erledigte entfernen", new / rename / delete list, "Ich gehe
  einkaufen" (`POST shopping/lists/<id>/going`). A line under the title
  says synced / sending / offline with N changes waiting.
- **Aufgaben**: Meine / Alle / Kinder (parents), done, "in 1 Stunde"
  (snooze 60), stars of the week; parents create / edit / delete (mode,
  assignees, repeat incl. weekdays, date, time, stars, reminder).
- **Termine**: next 60 days (`GET dates?from&to`) merged with Alltag
  (`Agenda.merge`, calendar colour bar, waste dates); create / edit /
  delete own dates and birthdays; member birthdays (`member:<id>`) read only.
- **Kind orten** (parents): explanation, "Standort anfordern" (`POST
  locate/<child>`), pending state with countdown to `expiresAt` (push +
  polling every 4 s), result on an OSM map with accuracy circle, address
  (Android Geocoder when present), time, battery, "In Karten öffnen"
  (`geo:` intent), history with the reason of declined/failed requests.
  The same screen shows an SOS (position, "Gesehen" → `POST sos/<id>/ack`).
- **Mein Handy** (children): who located me and when, the current mode,
  open requests (confirm mode: send / decline), "Bin angekommen" with label
  chips (Schule, Training, Freunde, Zuhause), SOS (hold 2 s; screen readers
  get a confirmation dialog instead), the parent's acknowledgement.
- **Orts-Erinnerungen** + edit: my reminders, family reminders with consent
  (accept / decline), Play services and permission state; edit with map
  tap, current position or address search, radius 50 m–2 km, arrive /
  leave, note / shopping list / tell members, for whom (others must
  consent), once / every time, active.

## Errors

`{ok:false, code, error}` → `FamilieResult.Failed(code, http, retryAfter, requestId)`;
`familieErrorText()` turns the codes of the contract (`not_member`,
`module_off`, `parents_only`, `invalid`, `forbidden`, `read_only`, `adult`,
`locate_off`, `not_pending`, `too_many_places`, `too_soon`, `daily_limit`,
`rate_limited`, `too_large`, `not_found`, 502–504) into text. A refused
`POST locate/<child>` that names the open `requestId` shows that request
instead of an error. Unknown fields are ignored everywhere.

## Offline shopping lists

`files/familie/shopping.json` holds the server snapshot (`lists`, `items`,
`serverTime`) and an outbox of contract ops with client UUIDs
(`list_add`, `item_add`, … ). Edits go into the outbox and show at once
(the view is the snapshot with the outbox applied). Sync (`ShoppingSyncEngine`):

1. send the outbox in order, ≤ 200 ops per `POST shopping/ops`; sent ops
   leave the outbox whether applied or rejected; a 400/413 batch is dropped
   (it never succeeds); offline / other errors keep it;
2. `GET shopping?since=<serverTime>` (or without `since` the first time and
   after 25 days, tombstones live 30) and merge — the server wins.

Triggers: screen open, pull to refresh, 1.5 s after an edit, VPN connected,
app start with waiting ops, a "going shopping" push, and — while ops wait
offline — a WorkManager job with a network constraint.

## Push (topic `plugin:gatecontrol-familie:*`, `data.familie`)

`PushRepository.onNotification` calls `PushPresenter.onNew()` once per new
message (not for replays, also for muted topics); `PushNotifier` hands it
to `FamiliePushHandler`:

| `type` | App does | Buttons | Tap opens |
|---|---|---|---|
| `locate_request` auto | shows the server's notification **and** answers: one fix (high accuracy, ≤ 30 s, else last known ≤ 2 min) → `POST …/answer`, or `…/fail` with `no_permission` / `location_off` / `timeout`. Only for requests younger than 10 min. | – | Mein Handy |
| `locate_request` confirm | – | Standort senden, Ablehnen | Mein Handy |
| `locate_result`, `locate_declined`, `locate_failed`, `locate_expired` | refreshes an open locate screen | – | Kind orten (request) |
| `task_due` | – | Erledigt (`POST done {occurrence}`), In 1 Std (`POST snooze {minutes:60, occurrence}`) | Aufgaben |
| `task_done` | removes my reminder of that occurrence (collapse key `task:<id>:<date>`) and refreshes the tasks screen | – | Aufgaben |
| `place_consent` | geofence re-sync | Annehmen, Ablehnen | Orts-Erinnerungen |
| `place_tell` / other place messages | geofence re-sync | – | Orts-Erinnerungen |
| `sos` (parents) | – | Gesehen (`POST sos/<id>/ack`) | SOS on the locate screen |
| `shopping_going` | shopping sync | – | that list |
| `date`, `arrival` | – | – | Termine / start |
| `sos_ack` (and anything else on topic `sos`, child) | shown as the parent's acknowledgement on Mein Handy | – | Mein Handy |

Buttons work without opening the app (`FamilieActionReceiver`, not
exported); on success the notification goes and the message is read
(`PushRepository.markRead`), on failure it is replaced by "Das hat nicht
geklappt". "Standort senden" runs in the receiver only with "Allow all the
time"; otherwise it opens `FamilieLocateActivity`, a small card that
(asks for the permission and) locates while visible. Any tap on a Familie
message (`PushOpenActivity` with the dest, or `open_app_route
plg-gatecontrol-familie`) opens the Familie tab at that screen; without the
tab the message detail opens as before.

## Geofencing (place reminders)

Play services `GeofencingClient`. Registered: places with `targetUserId ==
me`, `active`, `consent == accepted` (max 90, Android allows 100), circle
of the place's radius, enter **or** exit, no initial trigger (re-registering
must not remind again), 2 min responsiveness. Registered on process start,
after boot (`BootReceiver` → `FamilieStartup.onBoot`, from the stored list),
after `GET places`, after any place push and consent. On a transition
(`GeofenceReceiver`, not exported, mutable PendingIntent as Play services
require) the phone shows a local notification from what it stored (note:
title + note; list: the list's open items, tap opens the list; tell:
"Familie wurde benachrichtigt") and queues `POST places/<id>/triggered`
(WorkManager, network constraint, retries). `repeat: once` is unregistered
locally right away. Without Play services places are listed and marked
"auf diesem Gerät nicht verfügbar".

## Permissions

No new permissions: `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` were declared
for trusted Wi-Fi already. Each request is preceded by a prominent
disclosure (`rememberLocationPermissionFlow`): what is collected, why, and
that it also happens while the app is closed; then the system dialog,
then — for background — Android's settings page ("Allow all the time").

- Children in mode `auto` (`me.locateMode`): banner on Start and Mein Handy
  until background location is allowed.
- Places: banner + "Standort erlauben" on the places screen.
- Decisions are pure functions in `FamiliePermissions` (unit-tested).

The Play Console background-location declaration is not part of this
change.

## Maps

osmdroid 6.1.20 (`OsmMap`), standard OSM tiles. User-Agent = the
application id (`com.gatecontrol.client`) as the OSM tile policy asks,
tile cache in the app's cache dir, "© OpenStreetMap-Mitwirkende" always
shown (links to the copyright page). Only the parent's phone loads tiles,
for the area around a child's answered position or a chosen place.
osmdroid was chosen over Google Maps because it needs no API key and no
Google account, fits the self-hosted idea and the server's own Leaflet/OSM
maps; it is in maintenance mode but stable and small.

## Privacy

- Location only on a parent's request (one fix, child always notified and
  sees the list), on "Bin angekommen" / SOS by the child, and geofence
  transitions (only the trigger leaves the phone).
- Everything goes to the user's own GateControl server; coordinates are
  deleted there after 7 days, the request log after 90; locating ends at 18.
- See `privacy-policy.html` / `docs/PRIVACY_POLICY.md` ("Familie").

## Tests

`app/src/test/.../familie/`: `FamilieJsonTest` (parsers, bodies),
`ShoppingSyncTest` (view, merge, outbox replay, batching, offline),
`FamiliePushTest` (parse, routing, buttons, freshness), `FamilieLogicTest`
(tab visibility, owner key, permission decisions, geofence selection,
destinations, agenda, API paths), `FamilieApiTest` (bridge paths, error
codes, offline). Core: `PushEventParserTest` (`dataJson`),
`PushRepositoryTest` (hook once, also when muted). `ManifestSecurityTest`
checks the new components are not exported.

## Not on the device

Needs a real device / server: background answer with the screen off
(Doze, push service running), geofence transitions, the Play services
missing case, the OSM map and Geocoder, notification buttons from the
lock screen.
