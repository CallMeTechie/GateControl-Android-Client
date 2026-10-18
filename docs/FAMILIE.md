# Familie (family plugin tab)

Developer notes for the "Familie" tab: the app side (part C) of the
`gatecontrol-familie` plugin 1.0.0, plus the 1.1 modules "Abholen &
Fahrten" (`rides`) and "Notfall-Karte" (`emergency`) — see
[Familie 1.1](#familie-11-rides-and-emergency-card) — and the 1.2 modules
Taschengeld, Wunschlisten, Haushaltsbuch, Pinnwand, Essensplan and
Anwesenheit — see [Familie 1.2](#familie-12-money-board-meals-presence). The server side is the core client
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
| `familie/FamilieJson12.kt` | 1.2: parsers and bodies of allowance, wishes, expenses, board, meals, presence (same hand-written Gson-tree style) |
| `familie/money/` | 1.2: `MoneyModels` (allowance, wishes, expenses), `Money` (cents ↔ text, category bar, star steps) |
| `familie/board/`, `familie/meals/` | 1.2: pinboard / checklists, meal plan (`MealWeek`: ISO weeks) |
| `familie/presence/` | 1.2: `PresenceLogic` (reading the home check + report decision), `PresenceReporter` (own home check, + `PresenceCheckWorker`, `PresenceReportWorker`), `PresenceStore` (consent, last report) |
| `ui/familie/` | screens, `FamilieTab.kt` (dest → screen, two-pane), `OsmMap.kt` |
| `ui/familie/FamiliePageKit.kt` | 1.2: `FamilieLoadViewModel` (load / act / one-shot messages), page frame, floating button, money helpers |

Screens are addressed by `FamilieDest` (`home`, `shopping/<listId>`,
`tasks`, `dates`, `locate/<child>[/<request>]`, `sos/<id>/<user>/<lat>/<lon>/<acc>`,
`child`, `places`, `place/<id|new>`, 1.1: `rides[/<rideId>]`,
`emergency[/<userId>]`, 1.2: `allowance[/<childId>]`, `wishes[/<userId>]`,
`expenses[/list/<listId>]`, `board`, `meals[/<YYYY-Www>]`, `presence`): route `familie?dest=` (tab; on wide
windows the screen beside the start screen, `GcTwoPane`) and
`familie/page?dest=` (phones).

## Screens

All screens follow `GET me`: only modules that are on, filtered by role.

- **Start** (mockup Main): members, "Heute" (my tasks, stars for
  children), shopping lists with open counts (from the device, works
  offline), "Kinder orten" per child (parents), "Mein Handy" with "Bin
  angekommen" / SOS (children), next dates, place reminders. Pull to refresh.
  The cards follow the person's order (1.47, mockup FamilyArrange): a long
  press on a card or the menu at the top right opens "Familie anordnen" —
  drag to sort, the eye hides a card (listed under "Ausgeblendet"). Stored
  with the account like the start tab's order (`GET/PUT
  /api/v1/client/layout`, lists `familie`/`familieHidden`; on the device for
  scope `device` or older servers). Ids: `members`, `child`, `emergency`,
  `tasks`, `rides`, `shopping`, `locate`, `dates`, `meals`, `board`,
  `allowance`, `wishes`, `expenses`, `places`.
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
| `allowance_convert` (1.2, parents) | refreshes an open allowance screen | Annehmen (`POST allowance/requests/<id>/approve`), Ablehnen (`…/decline`) | Taschengeld of that child |
| `allowance_paid`, `allowance_decided` (to the child) and any other `allowance_*` (1.2) | refreshes an open allowance screen | – | Taschengeld |
| `presence_rule` (1.2, a rule's notify action) | refreshes the presence screen and the start screen | – | Wer ist zuhause |
| anything else on `wishes` / `expenses` / `board` / `meals` / `presence` (1.2) | refreshes that screen | – | that screen |

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
`FamilieJson12Test` (1.2 parsers and bodies, reservations never on own
wishes, settlement / transfers, seven-day weeks, presence states),
`MoneyTest`, `MealWeekTest`, `PresenceLogicTest` (1.2),
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

## Familie 1.2: money, board, meals, presence

Contract: plugin 1.2.0 (GateControl ≥ 1.166.0). New switches in `GET me`
→ `modules`: `allowance`, `wishes`, `expenses`, `board`, `meals`,
`presence`; `GET overview` gains `allowance`, `meals`, `board`, `presence`
(null when off). Every screen follows the switches and the role; on wide
windows they open beside the start screen like the others. Money is
bookkeeping only — the app never pays, charges or transfers anything;
amounts are integer cents (EUR), formatted in the app language
(`Money.format`: "23,50 €", "+ 5,00 €", "− 8,50 €").

Parsing: `FamilieJson12`, hand-written over the Gson tree like
`FamilieJson` (no reflection, so R8 cannot strip anything), matching the
final plugin 1.2.0 answers (it also tolerates small shape variants, e.g.
convertible stars as `stars.convertible` or a flat number). Nothing new is
persisted with reflective Gson: presence settings live in plain
SharedPreferences (`familie_presence`), the stored profile only gained
booleans (`FamilieModules`, inside the kept `familie` package).
New error codes: `not_enough_stars`, `no_star_rate`, `no_consent`,
`too_many` (and `already_taken` for a reserved wish).

### Taschengeld (`allowance`, mockup AppAllowance)

- `GET allowance`, `POST allowance/<childId>/bookings {cents, note, kind}`
  (parents: "Gutschreiben" = `manual` +, "Ausgabe" = `spend` −), `PUT
  allowance/<childId>/rule` (amount, weekly weekday / monthly day 1–28,
  active, and the star rate `{stars, cents}` or null), `POST
  allowance/<childId>/convert {stars}` (the child), `POST
  allowance/requests/<id>/approve|decline` (parents), `DELETE
  allowance/<childId>/bookings/<id>` (parents, manual / spend bookings).
  The account carries `rule` (always an object; amount 0 and off = no
  rule), `starRate | null`, `nextPayout`, `stars {earned, converted,
  pending, convertible}`, bookings (≤ 50) and requests (pending + the last
  10 decided). Converting is the child's own call; the amount is a
  multiple of the rate.
- Screen: green balance card (red when negative), "nächstes Taschengeld:
  Samstag · 5,00 €", stars row "12 Sterne · 10 Sterne = 1 €" with
  "Umwandeln" (whole steps of the rate, at most what is convertible; one
  open request at a time), recent bookings; parents: child chips, open
  requests with Annehmen / Ablehnen, "Buchen", "Regel". Children see a
  link to their wish list.

### Wunschlisten (`wishes`)

- `GET wishes` → `lists: [{ownerId, name, color, items}]` (items marked
  `gotIt` are left out by the server), `POST/PATCH/DELETE wishes[/<id>]` (owner; parents for
  their children — `ownerId` on create), `PATCH {gotIt:true}`
  ("Bekommen", then hidden), `POST wishes/<id>/reserve|unreserve`.
- **Never** a reservation on the caller's own items: the server does not
  send it and `FamilieJson12.wishes(…, myUserId)` drops it anyway.
  Others' lists show "Ich besorge das", "Besorgst du" + "Freigeben", or
  "<Name> besorgt das". Links are https only.

### Haushaltsbuch (`expenses`, parents, mockup AppExpenses)

- `GET expenses?month=YYYY-MM`, `POST/PATCH/DELETE expenses[/<id>]`, `POST
  expenses/settle {month}` ("Erledigt" — a record only), `POST
  expenses/unsettle {month}` ("Wieder öffnen"; any change to the month
  reopens it, too). The total shown is the shared spending (`totals.shared`),
  with the overall total below when it differs; `transfers` is always a
  list. An expense recorded from a shopping list carries its `listId`.
- Screen: month switcher (no future months), total "gemeinsam
  ausgegeben", category bar + legend (`Money.shares`), settlement card
  "Ausgleich: Marc → Anna 48,20 €" (one line per transfer with 3+
  parents) with "Erledigt", the expenses (payer avatar, category, "nicht
  geteilt"), floating "+ Ausgabe".
- After "Ich gehe einkaufen" a parent gets the snackbar "Einkauf
  erfassen?" when the module is on; "Erfassen" opens a new expense
  (category Lebensmittel, the list name as note) — `FamilieDest.Expenses(listId)`.

### Pinnwand & Packlisten (`board`)

- `GET board`; notes `POST/PATCH/DELETE board/notes[/<id>]` (text,
  colour, pinned, optional end date → `expiresAt` = end of that day in the
  device's zone, as ISO UTC); checklists `POST board/checklists
  {templateId | title}`, `DELETE board/checklists/<id>`, items `POST
  …/items {text}`, `PATCH …/items/<itemId> {done}`, `DELETE …`;
  `POST board/checklists/<id>/reset` ("Alle wieder offen"); templates
  `POST board/templates {checklistId, title}`, `DELETE
  board/templates/<id>`.
- Screen: "Notizen" (two-column grid, pinned first) and "Listen"
  (expandable checklists, tick shown at once, "Als Vorlage speichern",
  new list from a template). Delete of a note: its author and parents.

### Essensplan (`meals`)

- `GET meals?week=YYYY-Www` (always seven days, `MealWeek`), `PUT/DELETE
  meals/<date>/<lunch|dinner>`, `POST …/to-shopping {listId}`.
- Screen: week switcher ("KW 42"), Monday … Sunday with Mittag / Abend;
  edit dish, note, who cooks, ingredients (one per line); "Zutaten auf
  die Liste" (shopping module on; the list is picked when there are
  several) — the server answers `{added, skipped}` (ingredients already
  open on the list are skipped), shown as a toast; the app then syncs the
  shopping lists. Start screen: "Essen: Pfannkuchen".

### Anwesenheit (`presence`)

- Consent: "Anwesenheit teilen" in the own profile (section "Familie",
  only while the plugin is there and the module is on; reached from
  Settings → Profil or a tap on the own avatar on the start tab or in the
  Familie tab — since app 1.33, before that in the app settings and on
  the presence screen). Switching on asks first (what is sent, to whom,
  that it runs in the background); `PUT presence/consent {share}` — only
  when the server took it is it stored on the phone (`PresenceStore`,
  bound to the login). The server's own flag (`GET presence` → `me.share`,
  `overview.presence.share`) wins on app start, on the start screen and
  when the presence screen opens. `POST presence` answers 409
  `no_consent` (the switch goes off on the phone too) and 429 `too_soon`
  (retried).
- Sensing (`PresenceReporter.checkNow`, decisions in `PresenceLogic`) is
  its own home check, independent of "Vertrauenswürdige WLANs" and of the
  VPN — no GPS, no new permission: `GET /api/v1/client/home-network` with
  `local=<ipv4>[,…]`, the phone's private (RFC 1918) IPv4 addresses on its
  Wi-Fi / Ethernet networks (`TrustedNetworkMonitor.localAddresses()`,
  LinkProperties of the non-VPN networks, at most 4). It goes the normal
  way: through the tunnel while the VPN is up, directly otherwise. The
  server answers `same_lan` for its own LAN (tunnel only) and, from core
  1.167, for the LAN behind one of its gateways (`site: {type: 'gateway',
  name}`; the phone's public address equals the gateway's endpoint and a
  `local` address lies in a LAN the gateway routes — also without VPN).
  `same_lan` true → home; false through the tunnel, or from a server that
  sends `site` (1.167+) → away; a direct false from an older server (it
  says false to every direct request), 404, errors → can't tell, the last
  state stays (no flapping). Mobile data only (no Wi-Fi, no LAN address)
  → away without asking; no network → can't tell.
- When it asks: only while I share (consent + module on); on every
  network or tunnel change (the network callback of
  `TrustedNetworkMonitor`, 5 s debounce, a newer change cancels a running
  check), a failed check is retried after 20 s and 60 s, and a new state
  is checked once more after 45 s (the server may still see the old
  endpoint right after a change). As the fallback while the process is
  gone, a periodic WorkManager job (`PresenceCheckWorker`, 15 min,
  network constraint) checks, and when that brings a state not reported
  yet it waits out the two minutes inside the job, checks again and
  queues the report.
- The switch shows "zuhause" / "unterwegs" / "Netz wird geprüft" and a
  short note when the server can't tell: older than 1.167 (home only in
  the server's own LAN and with VPN), without the check (404), or
  unreachable (the last state stays).
- Reporting (`PresenceReporter`, started with the app process from
  `FamilieStartup`): a state must hold 2 minutes (`debounce`), it is sent only
  with consent and the module on and only when it differs from the last
  report — except the first report after sharing goes on (nothing
  reported yet): the phone checks at once (no 5 s settle) and reports the
  first clear answer without the two minutes (`PresenceLogic.reportFirstNow`),
  so the family does not see "unbekannt" for minutes; every later change
  keeps the debounce. `POST presence {home, at}` (`at` = when the state began) goes
  through a unique WorkManager job (network constraint, the newest state
  replaces a waiting one, `too_soon` / 429 retried with backoff, a state
  already reported is not sent again). Consent off, module off or leaving
  the family cancels it and the periodic check; a new consent sends the
  current state again.
- Start screen (app 1.33): no presence card and no "zuhause" /
  "unterwegs" text under the avatars — the ring colour shows it. Parents
  get a small link "Anwesenheit & Regeln" under the member row to the
  presence screen (who is home since when, who does not share, the rules
  hint, a row to the own switch in the profile); children have no entry
  (nothing there they can change). Rules (smart home, notify) are set in
  the portal / admin page; their notify messages (`presence_rule`) open
  the presence screen.

### Start screen (1.2)

Cards when the modules are on: Essen heute, Pinnwand
(pinned notes, open lists), Taschengeld (child: balance and next payout;
parents: sum and open requests), Wunschlisten (own wishes, what I am
getting), Haushaltsbuch (parents: this month's shared total, settled).

### Device checks for 1.2

Presence with the screen off, with "Vertrauenswürdige WLANs" off: arrive
home → "zuhause" on the other phones after ~2–3 minutes, with the VPN on
and off; leave on mobile data → "unterwegs"; Wi-Fi flapping shorter than
2 minutes must not report. With a server behind a gateway (core 1.167+):
"Heimnetz über Gateway „…“" on the trusted-networks screen, and presence
"zuhause" at home with VPN off. App killed: the periodic check reports
within ~15 minutes. "Annehmen" / "Ablehnen" of `allowance_convert` from the lock
screen. The "Einkauf erfassen?" snackbar after "Ich gehe einkaufen".


## Familie 1.3: avatars and presence ring (app 1.32)

Needs GateControl core 1.168+ (profile pictures and favourite colours of
the GateControl accounts) and Familie 1.3. Every member object the app
gets (`GET me` → `me`, `members[]`; `GET presence` → `members[]`;
`GET overview` → `presence.members[]`) carries the face fields:

| Field | Meaning |
|---|---|
| `avatarUrl` | `/avatars/<id>.<ext>` (capability URL, no login, immutable) or null |
| `color` | palette key of the favourite colour (until 1.2: a hex colour set on the admin page) |
| `colorHex` | `#RRGGBB` of the favourite colour |
| `initials` | initials as GateControl makes them |
| `presence` | `home` / `away` / `unknown`, null = not shared or module off |

- Parsing (`FamilieJson.face`, `memberColor`): the member colour is
  `colorHex`, else a `color` that is a hex value (older plugin), else the
  palette colour of the key. `MemberFace` is null when the plugin sends
  none of the fields (1.0–1.2): those members keep the old initials in
  the member colour. Avatar paths must match `/avatars/<16–128 url-safe
  characters>.(png|jpg|jpeg|webp)`; anything else is dropped. Emergency
  card and ride drivers only carry `{userId, name}`: their faces come from
  the members list (`FamilieProfile.faceOf`).
- Rendering: `FamilieAvatar(…, face, presence)` hands members with a face
  to the shared `GcAvatar` (ui/components): the picture, or the initials
  in white on the favourite colour, inside a ring — at home the favourite
  colour, away red `#E5484D`, unknown / not shared / module off a thin
  neutral border. The screen reader reads "Anna – zu Hause" /
  "Anna – unterwegs". Used on the start screen (member row: the own
  avatar first, then the others; under each the name and, for the last
  3 hours, "im Laden" / "angekommen 13:10"; the others' ring takes the
  overview's presence when it has it), the presence screen (ring from
  `GET presence`), locate, rides, tasks (stars), household book,
  emergency card and the child's own screen.
- The own avatar in the Familie tab (member row, presence screen) is
  drawn like the start tab header (`FamilieMemberAvatar`, ring from
  `MyAvatarViewModel`: what this phone senses, else the last report), so
  the two tabs never disagree; the others' rings come from the server
  (`FamilieMemberRow`, unit-tested). A tap on the own avatar opens the
  own profile.
- Pictures are loaded by `AvatarLoader` (no image library): from the
  server address the app uses for `/api/v1/client/…`, over the same
  OkHttp client, only from that server (an absolute URL on another host
  is ignored), cached in memory and in `cacheDir/avatars` keyed by the
  URL (a new picture gets a new URL, so nothing goes stale; at most 64
  files).
- Own picture and favourite colour are changed in the app (Settings →
  Profil, or a tap on the own avatar on the start tab or in the Familie
  tab), see `profile/` and `ui/profile/`: `GET/PUT/DELETE
  /api/v1/client/me/…`. Since app 1.33 the profile also holds
  "Anwesenheit teilen" (see above).
  The start tab shows the own avatar with the own presence ring while
  "Anwesenheit teilen" is on and the module is on (what the phone senses,
  else the last report).

### Device checks for 1.3

Change the picture in the app → the new picture on the other phones
after their next refresh, in the portal and on the wall; remove it →
initials on the favourite colour. Change the colour → the ring at home
changes. Leave home with presence sharing on → red ring on the other
phones. With a Familie 1.2 plugin on a 1.168 server: initials as before,
no rings. With a server before 1.168: the "Profil" entry only with the
presence module (then it holds just "Anwesenheit teilen"), the start tab
header shows the initials of the Familie name or "GateControl" + host.

### Device checks for app 1.33

Switch "Anwesenheit teilen" on in the profile → on another phone the
ring turns from neutral to the colour / red within a few seconds (not
after two minutes). Start tab and Familie tab show the same ring for the
own avatar at all times; a tap on either opens the profile. The Familie
start screen has no presence card and no "zuhause" / "unterwegs" text.
