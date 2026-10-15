# Tablets, foldables and landscape

The app adapts to the window it gets, not to the device type. One decision
point: `GcWindowLayout.compute()` in `ui/adaptive/GcWindowLayout.kt`, made once
in `AppNavigation` and handed down via `LocalGcWindowLayout`.

| Window | Navigation | Home | Settings, messages |
|---|---|---|---|
| < 600 dp wide (phone portrait) | bottom bar | one column | list, page on its own screen |
| 600–719 dp (small tablet portrait) | rail on the left | one column | as on phones, pages centred (max 720 dp) |
| ≥ 720 dp (tablet, unfolded foldable, phone landscape) | rail | two columns: connection left, quick actions and cards right | list left, page right |

Foldables (postures from `currentWindowAdaptiveInfo()`, material3-adaptive):

- **Flat / fully open:** the fold is ignored; the window width decides.
- **Book posture** (half open, vertical hinge): two panes, split exactly at
  the hinge (`GcTwoPane`), nothing is drawn across it. If the half left of the
  hinge would leave the list under 300 dp next to the rail, the bottom bar
  stays instead.
- **Tabletop** (half open, horizontal hinge): home shows only the connection
  switch above the fold; header, details and tiles scroll below it.
- **Closed** (outer display): a normal phone.

Details:

- In the detail pane (`LocalInDetailPane`), sub pages drop their back arrow;
  the list stays visible beside them.
- The rail (and the bottom bar) holds Start, Netzwerk (Dienste, Pi-hole,
  Remote as segments) and Familie; since 1.35 there is no settings tab and
  no remote tab. The settings are a normal destination with back, opened
  from the profile's gear; they hide the rail like any other sub page.
- Links into a settings page (logs, location, notifications, diagnosis)
  open the settings (list with that page beside it) on wide windows
  (`settings?pane=…`), else the page's own route. The profile always opens
  on its own (it is the way into the settings).
- A tapped push message opens beside the inbox (`inbox?select=…`) on wide
  windows, else as its own screen.
- Familie tab (`docs/FAMILIE.md`): the start screen as list, the picked
  screen (shopping, tasks, locating, …) beside it (`familie?dest=…`); on
  phones each screen is its own route (`familie/page?dest=…`).
- Single-column pages (setup, network with its remote segment, profile,
  quick actions, the Info card picker) stay
  centred with a maximum width instead of stretching.

Screenshots of all of this on tablet, foldable and phone emulators:
`.github/workflows/layout-shots.yml` (on PRs touching the layout, or by hand),
results on the `layout-screens` branch.
