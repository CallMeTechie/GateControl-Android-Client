# Familie (family plugin tab)

Developer notes for the "Familie" tab: the app side (part C) of the
`gatecontrol-familie` plugin 1.0.0, plus the 1.1 modules "Abholen &
Fahrten" (`rides`) and "Notfall-Karte" (`emergency`) — see
[Familie 1.1](#familie-11-rides-and-emergency-card). The server side is the core client
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
| `familie/rides/` | 1.1: `RideModels`, `RideLogic` (who may take / decline / give back, "Offen" vs. week, reminder time, place reminder draft) |
| `familie/emergency/` | 1.1: `EmergencyModels`, `EmergencyCache` (AES-GCM file, `CacheCipher`), `EmergencyCacheStore` (Keystore key, no-backup dir), `EmergencyRepository` |
| `familie/FamilieShortcuts.kt` | 1.1: dynamic app shortcut "Notfall-Karte" |
| `ui/familie/` | screens, `FamilieTab.kt` (dest → screen, two-pane), `OsmMap.kt` |

Screens are addressed by `FamilieDest` (`home`, `shopping/<listId>`,
`tasks`, `dates`, `locate/<child>[/<request>]`, `sos/<id>/<user>/<lat>/<lon>/<acc>`,
`child`, `places`, `place/<id|new>`, 1.1: `rides[/<rideId>]`,
`emergency[/<userId>]`): route `familie?dest=` (tab; on wide
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
| `ride_ask` (1.1) | refreshes an open rides screen | Ich mache es (`POST rides/<id>/take`), Kann nicht (`…/decline`) | Abholen & Fahrten (the ride highlighted) |
| `ride_reminder`, `ride_open`, `ride_taken`, `ride_released`, `ride_unassigned` (1.1) | refreshes an open rides screen and the start screen | – | Abholen & Fahrten |
| `locate_age_hint` (1.1, parents) | – | – | Kind orten of that child |

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
`RideLogicTest` (1.1 ride actions, sections, reminder, place reminder),
`EmergencyCacheTest` (1.1 encrypted round trip, other login / key,
changed file, no plain text on disk),
`FamiliePushTest` (parse, routing, buttons, freshness), `FamilieLogicTest`
(tab visibility, owner key, permission decisions, geofence selection,
destinations, agenda, API paths), `FamilieApiTest` (bridge paths, error
codes, offline). Core: `ApiClientTest` (PUT, PATCH / DELETE with query), `PushEventParserTest` (`dataJson`),
`PushRepositoryTest` (hook once, also when muted). `ManifestSecurityTest`
checks the new components are not exported.

## Not on the device

Needs a real device / server: background answer with the screen off
(Doze, push service running), geofence transitions, the Play services
missing case, the OSM map and Geocoder, notification buttons from the
lock screen.

## Familie 1.1: rides and emergency card

Contract: plugin 1.1.0 (`GET me` → `modules.rides`, `modules.emergency`,
`modules.wall`; the wall module has no app UI). Both screens follow the
module switches like every other card.

### Abholen & Fahrten (`rides`, mockup AppPickup)

- `FamilieApi`: `rides(from, to)` (the screen asks today … +7),
  `createRide`, `updateRide(id, draft, series)` (`PATCH rides/<id>`,
  `?series=1` for the whole series), `deleteRide(id, series)`,
  `takeRide`, `releaseRide`, `declineRide`, `setRideDriver`
  (`{userId}|{label}|{clear:true}`), `askRide`. Ride ids are text (`12`
  or `<seriesId>:<date>`), checked against `FamilieJson.RIDE_ID_RE`
  before they become a path segment.
- Screen: "Offen" = open rides somebody asked for or that start within
  24 h, plus my own taken rides within 24 h ("Du holst Tim ab" +
  "Rückgängig", reminder time); then "Diese Woche" (weekday, title, time,
  who drives / "noch niemand eingetragen" + "Übernehmen", cancelled ones
  struck through). "Ich mache es": parents and adult members (`age ≥ 18`);
  "Kann nicht": parents, once; "Wer kann …? fragen" again after 5 minutes.
- Parents create / edit: title, for whom, date, time, place as text and
  optionally a point from the 1.0 place picker (`FamiliePlacePicker`, also
  used by the place editor), driver (member, label such as "Familie
  Weber", or open), driver reminder 0–120 min, one-off or weekly
  (weekdays, optional end), "Gleich fragen". A series occurrence is
  changed alone or with the whole series. The date of a series occurrence
  cannot move (the server refuses it); a series change sends no date but
  the rule (weekdays, end, from the ride's `repeat`) and the driver
  (`null` clears it). A changed driver of one ride goes through `POST
  rides/<id>/driver` (the family gets "ride_taken"). A cancelled
  occurrence can be reopened (`PATCH {status:"open"}`).
- Errors of the final plugin: `already_taken` (409, with the `driver`:
  "Schon übernommen – Marc fährt"), `cancelled` (409), `too_soon` (429,
  ask), `parents_only`, `forbidden` (give back by someone else); the
  screen reloads after every refused action.
- Children: "Bitte abholen" creates a ride for themselves (`childIds:
  [me]`, `ask: true`, no driver, never a series) and can cancel their own
  one-off request (`DELETE rides/<id>`).
- After "Ich mache es" on a ride with coordinates (and module `places`
  on) the app offers "Orts-Erinnerung anlegen": a 1.0 place reminder for
  me (arrive, 150 m, note "Tim einsammeln", once), then the geofences
  re-sync.
- Start screen: card with the next rides (`overview.rides.next`), open
  count, "Bitte abholen" for children.

### Notfall-Karte (`emergency`, mockup AppEmergency)

- `GET emergency`, `PUT emergency/members/<userId>` (own card; parents any
  card), `PUT emergency/contacts` (parents). New core call
  `ApiClient.pluginPut`. The PUT body carries blood type (`A+ … 0-`, null
  = unknown), weight, allergies, medication, conditions, notes, insurance
  and the member's numbers; name and birthdate come from the member list
  and are not sent. Weight 0.5–400 kg (0.1 steps), a number needs a
  digit; an `invalid` answer's `field` (`allergies`, `contacts.0.phone`,
  …) is shown at that field of the open form.
- Screen: member chips, the card (allergies highlighted, blood type,
  medication, conditions, notes), the member's and the family's numbers
  with a dial button (`ACTION_DIAL`, no `CALL_PHONE` permission),
  insurance number masked (last 4 digits) until tapped, fixed "Notruf
  112" (local, not from the server), "offline gespeichert" chip. The
  editor suggests labels (Kinderarzt, Giftnotruf, …), never numbers.
- Offline: every successful `GET emergency` is written to
  `no_backup/familie/emergency.bin` (never in a backup): `GCE1` header +
  AES-256-GCM (`iv(12) || ciphertext+tag`) of `{owner, savedAt, data}`.
  The key is a non-exportable Android Keystore key
  (`gatecontrol_familie_emergency`, no user authentication so the card
  opens in an emergency); no `androidx.security-crypto`. A copy of another
  login (`owner` = hash of server + token), a changed file or a lost key
  count as no copy and the file is deleted. Without a Keystore the card
  still shows, only nothing is stored. The screen shows the copy at once,
  refreshes on every open / resume and says "Stand: <time>" while it
  cannot reach the server. The copy is also refreshed on app start when
  the module is on, and removed when the module goes off, the member
  leaves the family or the plugin disappears.
- App shortcut: dynamic shortcut `familie:emergency` ("Notfall-Karte",
  long press on the app icon) while the module is on, opening
  `FamilieOpenActivity` → `emergency`. `QuickActionSurfaces` keeps the
  `familie:` shortcuts (first) when it sets the quick-action shortcuts.
- Nothing of the card is logged or shown in a notification.

### Device checks for 1.1

Notification buttons of `ride_ask` from the lock screen; the place
reminder after taking a ride firing at the hall; the emergency shortcut
(launcher long press, also while offline / in flight mode); the card
after a reboot and after clearing the app's data (copy gone, new key);
the dialer intent on a tablet without telephony.
