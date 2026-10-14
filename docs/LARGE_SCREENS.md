# Tablets, foldables and landscape

The app adapts to the window it gets, not to the device type. One decision
point: `GcWindowLayout.compute()` in `ui/adaptive/GcWindowLayout.kt`, made once
in `AppNavigation` and handed down via `LocalGcWindowLayout`.

| Window | Navigation | Home | Settings, messages |
|---|---|---|---|
| < 600 dp wide (phone portrait) | bottom bar | one column | list, page on its own screen |
| 600–719 dp (small tablet portrait) | rail on the left | one column | as on phones, pages centred (max 720 dp) |
| ≥ 720 dp (tablet, unfolded foldable, phone landscape) | rail | two columns: connection left, details and tiles right | list left, page right |

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
- Links into a settings page (split tunnelling tile, logs, location,
  notifications) open the settings tab with that page selected on wide
  windows (`settings?pane=…`), else the page's own route.
- A tapped push message opens beside the inbox (`inbox?select=…`) on wide
  windows, else as its own screen.
- Familie tab (`docs/FAMILIE.md`): the start screen as list, the picked
  screen (shopping, tasks, locating, …) beside it (`familie?dest=…`); on
  phones each screen is its own route (`familie/page?dest=…`).
- Single-column pages (setup, network, remote desktop, quick actions) stay
  centred with a maximum width instead of stretching.

Screenshots of all of this on tablet, foldable and phone emulators:
`.github/workflows/layout-shots.yml` (on PRs touching the layout, or by hand),
results on the `layout-screens` branch.
