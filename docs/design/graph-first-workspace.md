# Graph-first diagnostic workspace

The reference-fidelity follow-up makes **Both** a dense instrument dashboard.
At wide readable sizes it uses a four-card strip over a 45% left chart/status
stack, 36% receipt panel and 19% status rail; a lower-right relationship panel
spans the latter two columns. It uses thin bordered header strips, midnight navy
surfaces and stable blue RPM, green coolant, amber fuel and rose voltage accents.
Those colors identify channels, never vehicle-health thresholds.

The four role slots are RPM, coolant, fuel level and control-module voltage.
Missing roles show no value or invented trace and do not create selectable PIDs.
Module voltage is the ECU-supplied PID, not a battery-health measurement. Reference roles are defaults, with absent roles shown as informational placeholders.
Saved value-card order, pins or visibility edits take precedence across every
sensor: the first four visible cards occupy the top strip and the rest follow
below. Reset restores the reference defaults. Collapse preferences remain intact.
Values preserves the complete saved card order. Graph order remains configurable.

**Graphs** keeps full-width plots. Both reflows to a vertically scrolling layout
on narrow or enlarged-text windows; receipt/status analysis panels follow the graph stack.
Typical 800–1024 dp landscape windows use a two-column version of this dashboard; the three-column composition requires at least 1080 dp after chrome and text scaling. Below 680 effective dp, panels stack without shrinking text. The large driving gauge remains deferred. Compact navigation recovers dashboard
width. The one toolbar, full-screen exit, source status and diagnostic navigation
remain functional. The reference image's percentages, alert rules and scatter
analysis are not implemented. Following the user's clarification, these panel
shapes contain useful current PID values, units, status and genuine small
histories instead of large unimplemented-analysis placeholders. Up to eight
supplemental value modules fit this panel; remaining cards stay in the lazy
scrolling list. Additional Both graphs use a compact two-column arrangement
where readable, while Graphs and full screen remain full width.

Plots retain real receipt gaps, units and numeric axes. Time ticks are seconds
before **that signal's** current window end. Native uptime, replay offsets and
wall-clock timestamps are not interchangeable; no raw uptime is labeled as
session age and independently anchored graphs are not claimed to be aligned.
The inspector retains receipt/source details. Pause freezes chart history while
current values and acquisition may continue. The global Expand control raises
expanded graph modules to Tall; Compact restores each module's selected height.

## M2: Manage modules

Open Settings → Manage modules in the same toolbar in normal or full screen.
Graph modules and Value cards are independent: a numeric signal may have both.
Non-numeric readings offer value cards, without unusable graph controls.

- Shown/Hidden changes presentation only. Hidden modules remain in this editor.
- Pin first puts that module before other modules of the same kind. This is
  separate from existing sensor pins in the inspector and does not select PIDs.
- Expanded/Collapsed shows or hides module contents. Collapsed modules retain
  title, units and current status, and can be expanded again in the editor.
- Move up/down changes order inside the same kind and pin group. Cards and graphs
  keep their distinct responsive sections; Graphs mode always occupies full width; wide Both uses the reference grid.
- Compact, Standard and Tall use bounded 120, 180 and 280 dp plot heights, with
  bounded accommodation for larger text. Wide Both uses denser 80, 120 and 220 dp presets; global expansion remains 280 dp. Chart point retention stays unchanged.
- Apply saves. Cancel, Back and outside-card dismissal discard the draft. Reset
  stages defaults; only Apply commits the reset, including settings for signals
  absent from the current source. Controls are standard keyboard/focus-accessible
  Compose buttons/chips with at least 48 dp requested touch targets.

Graph module visibility is intersected with the existing graph PID selection.
A shown but unselected graph does not request new data. The Values-only selected
PID filter still works. All-hidden states explain how to recover modules.

## Persistence and lifecycle

A dedicated `workspace-modules-v1` preferences file stores a version-1 JSON layout.
It does not alter history, recordings, sensor pins or acquisition preferences.
Decoding is bounded to 131,072 characters and 256 modules (128 signal identities).
Unknown versions, malformed fields and corrupt data display safe defaults and a
notice; nothing is overwritten until Apply. Duplicate keys normalize to the
first module, unknown-to-current-source modules are omitted from rendering, and
new signals receive safe defaults. Applying a partial replay's edits merges them
into the saved layout, retaining other source signals' preferences and order.

Disk reads/writes run off the main thread. The committed layout is owned above
navigation and reloaded after activity recreation. Draft editor changes are
intentionally discarded on recreation or leaving that workspace; reopening
loads committed preferences. Returning from background without recreation keeps
the draft. A source/catalog change discards the old draft, not committed settings.
A failed save reports an error and leaves the currently displayed layout intact.
Apply is guarded synchronously, including queued taps after a successful write.
The saving flag is released in finally on failure/cancellation; a new editor can
reload a write that finished during activity cancellation. Move reads the latest
draft when clicked, rather than applying a previously calculated list.
No layout setting changes acquisition, diagnosis, recording or source lifecycle.

## Validation boundary

Model tests cover repeated operations, independent graph/card identities, bounds,
corrupt/unknown versions, partial-source persistence, selection filtering and
reset. Existing tests cover source/freshness/zero/gaps/pause/import behavior.
The former visual emulator workflow exercised modes, expansion,
hide/recover/reorder, pin/collapse/heights/reset, Back, background return, window
configuration changes, phone/tablet and large text. These checks now depend on
[human review](../manual-visual-review.md); optional local checker fixtures alone
are not Android visual evidence. TalkBack,
physical-tablet gestures and upgrade/history preservation remain separate gates.

No fabricated diagnostic alerts, quality percentages, correlations or unsupported
live PIDs are introduced. An explicitly selected **Synthetic chart fixture ·
offline** scenario supplies 61 fictional one-second points for existing scalar
PID identities, including four intentional null gaps. It is UI demonstration,
not a vehicle recording or diagnostic evidence. Default static demo and real
source acquisition remain unchanged; the fixture never adds fuel-level support.
The private reference image is not distributed in Git or source artifacts.
