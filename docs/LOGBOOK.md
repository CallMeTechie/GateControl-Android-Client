# Fahrtenbuch (logbook of the vehicles plugin)

Developer notes: the native logbook screens for the `gatecontrol-skoda`
plugin 1.6.0 (GateControl 1.189.0) and the generic quick action field
`opens` that leads to them. Everything goes through the core server's
client plugin bridge with the device's token, like the door screens
([DOOR.md](DOOR.md)).

## `opens`: quick actions that open an app screen

`GET /api/v1/client/actions` may give an action
`opens: { screen, ref } | null` (`screen` `^[a-z][a-z0-9_-]{0,39}$`, `ref`
≤ 80 chars). `QuickActionRepository.sanitize` keeps it as
`QuickActionItem.opensScreen` / `opensRef` (also in the stored pins, so
widget, shortcuts and tiles know it offline; pins from before have null).

Only screens the app knows count (`QuickActionScreens.target`, pure):
plugin `gatecontrol-skoda` + screen `logbook` → `PushNavTarget.Logbook(vehicleId)`
(`ref` as a positive id, else null = the first own vehicle). Anything else
runs as before. A tap that opens a screen never runs the action:

| Where | How |
|---|---|
| Start tab: widget card | the action is drawn as a full-width button "Öffnen" (its icon, chevron, accent frame) below the icon buttons (`OpenScreenButton`); tap → `QuickActionsViewModel.ask` |
| Start tab: tiles | `ask` as well |
| Home-screen widget, app shortcuts | `QuickActionActivity` (non-exported) |
| Quick Settings tiles | open `QuickActionActivity` (after unlocking) instead of running |

`ask` and `QuickActionActivity` call `QuickActionScreens.open`, which hands
the target to `PushNavigation` (MainActivity reads no intent extras); the
activity then brings up MainActivity. `AppNavigation.openPushTarget`
navigates to `Screen.Logbook` (one logbook at a time). Widget controls
(choice "purpose") still run the action with `control`/`value`.

## API (`/api/v1/client/plugins/gatecontrol-skoda/api/…`, `?lang=de|en`)

| Call | Method |
|---|---|
| `GET app/logbook?vehicle=&month=YYYY-MM` | `LogbookApi.month` |
| `PUT app/logbook/trips/<id>` `{purpose ("" = open), note, startAddress, endAddress}` | `LogbookApi.updateTrip` (only changed fields are sent) |
| `GET app/logbook/export?vehicle=&from=&to=&format=pdf\|csv` | `LogbookApi.export` (the shown month) |

Answers are parsed from the Gson tree (`LogbookJson`, no reflection):
positive ids, `YYYY-MM-DD` dates, `HH:MM` times, finite km, coordinates on
the globe, routes with ≥ 2 points (capped at 400; an unknown `kind` is
drawn as a straight line). Errors: `{ok:false, code}` / HTTP; 403/404 clear
what the app run loaded. Nothing is stored on the device: months live in
memory (`LogbookRepository`, per login and vehicle); an export is written
to `cacheDir/export/` only to hand it to the share sheet (FileProvider).

## Screens

| Route | Screen |
|---|---|
| `logbook?vehicle=` | `LogbookScreen`: top bar (back, title, vehicle chooser with > 1 vehicle, map, export PDF/CSV), month switcher (no future months), sums with the purpose bar, map preview (tap → map), trips by day with quick purpose buttons for open trips, pull to refresh. Wide windows: `LogbookTwoPane` (list + map or the tapped trip beside it). |
| `logbook/map?vehicle=&month=` | `LogbookMapScreen`: all routes of the month. Colours: Arbeitsweg green, Geschäftlich blue, Privat orange, offen grey; `kind=line` (or no route, only start/end) dashed. Trips with the same purpose between the same two places (ends within 150 m, direction ignored; `LogbookLogic.groups`) are drawn once with "n×". Filter chips, month switcher, tap a route or place → sheet with "n Fahrten zeigen" (the list, only those trips) and "Letzte Fahrt". |
| `logbook/trip/<id>?vehicle=&month=` | `LogbookTripScreen`: the route with A/B ("Strecke berechnet" / "Luftlinie"), day, start/destination with times and odometer, distance, purpose (offen/privat/geschäftlich/Arbeitsweg), note (saved on leaving the field or "fertig"), driver, duration, "Start und Ziel ändern". `canEdit=false`: read only. |

The maps are osmdroid with the Familie map's setup (`configureOsm`:
User-Agent = app id, tile cache in the app's cache, OSM attribution
always shown); lines have a white halo. The tiles are light in both
themes, so the route colours on the map are fixed; the rest of the UI uses
the theme tokens.

## Code

| Where | What |
|---|---|
| `logbook/LogbookModels.kt`, `LogbookJson.kt`, `LogbookApi.kt`, `LogbookRepository.kt` | Models, parser, endpoints, in-memory state |
| `logbook/LogbookLogic.kt` | Pure: months, list by day, purpose bar, lines, route groups and places |
| `quickactions/QuickActionScreens.kt` | Pure: which `opens` the app knows |
| `ui/logbook/…` | Screens, view models, `LogbookMapView` (osmdroid) |

Tests: `LogbookJsonTest`, `LogbookLogicTest`, `QuickActionScreensTest`.
