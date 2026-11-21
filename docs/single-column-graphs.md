# Full-width live graphs

Graphs and Both now show one full-width card per selected PID in a single
vertical scrolling list. There is no tablet/landscape breakpoint that puts
charts side by side. Normal and full-screen telemetry share this layout.
The legacy gauge dashboard already uses one scrolling column of stream traces.
Values retains its adaptive grid.

Each chart keeps its label, unit, freshness/availability, axis gutter and gaps.
PID order and selection, pause/resume, time range, fit scale, expanded plot height,
inspection, source labels and saved-drive replay behavior are unchanged.
The list stays lazy and uses each PID ID as its item key. The compact unified control bar stays pinned above the graph viewport at all
window sizes; captions and graph cards scroll below it.

## Layout acceptance on a reliable Android emulator

Use a disposable, authorized emulator and offline demo data only. Select at
least four numeric PIDs, including enough cards to require scrolling. Record
UIAutomator XML before and after scrolling to the last selected graph, along
with screenshots, viewport size, density and font scale. No device connection
is performed by the checker below; it only reads captured XML files.

```sh
node scripts/check-single-column-graphs.mjs 2 1 \
  '["Engine speed","Coolant temperature","Engine load","Throttle position"]' \
  before.xml after.xml
node --test scripts/check-single-column-graphs.test.mjs
```

Use the actual selected graph labels in order, display density multiplier
(not DPI) and font scale from that capture. The checker requires a scrollable
Graphs/Both viewport, full-width canvases after the fixed axis/padding gutter,
vertically non-overlapping graphs, correct selection order and every selected
graph reachable across captures. It rejects the old two-column layout and a
half-width final card. The ten synthetic fixture tests validate the checker;
they do not demonstrate that the application was rendered on Android.

Repeat for phone, tablet and landscape, Graphs and Both, normal/full-screen,
and enlarged text. Inspect labels/touch targets and verify PID toggles,
empty selection, returning from inspection, all toolbar controls, Values mode,
replay pause/restart/stop, rotation and background/foreground behavior.
Screenshots and interaction checks remain required even when XML checks pass.
