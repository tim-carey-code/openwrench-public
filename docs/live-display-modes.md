# Graphs, Values and Both

The Live sensors screen has three display modes in the unified icon bar’s mode menu:

- **Graphs** uses the screen for the chosen PID traces, without a current-value
  strip. Each trace still identifies its unit and current data availability.
- **Values** shows a compact grid of every supplied catalog entry. **All PIDs**
  includes unsupported and unchecked entries so missing coverage is explicit.
  **Selected** follows the same numeric PID selection as the graph picker.
  Tap a value to open its existing detail page. Structured/text values may be
  shortened in the grid; details retain the supplied content.
- **Both** places the current value and its chart together for each selected PID.
  Graphs are full-width cards in one vertically scrollable column on phones,
  tablets and landscape windows, including full-screen telemetry. Cards are composed
  lazily; selecting more PIDs does not create an unbounded layout.

Value cards use stable catalog order, rather than moving when readings arrive
or expire. A normal 390dp phone has two columns; wider screens use up to six.
System text size reduces the column count and lets labels grow. The icon bar stays pinned at one 48dp row; menus accommodate large text
and overflow controls, while captions and data scroll below. Missing, stale, unsupported, unchecked, disconnected and failed readings
show a dash and their own status, never an old number or an inferred zero. Age
is shown only when supplied; otherwise it remains unknown.

The unified icon bar contains mode, PID selection, chart pause, time, scale,
height, full-screen and settings actions. Narrow windows move secondary actions
into settings. Values exposes All/Selected through its filter menu and excludes
chart-only actions, including from overflow. Switching back
restores the current chart settings. Paused charts stay paused while current
numeric values continue to update. Mode switches, details and navigation retain
PID selection and chart state within the current source/session. Changing the
source/content resets that state; it is not a saved acquisition preference.
All charts use actual retained history, with gaps and no fabricated samples.

The Diagnosis screen uses a clearer phase/source heading, wrapping time/drive/
reading/scan statistics, one stable primary action and secondary session options.
The start/finish guide is expandable; source limits, errors, missing evidence,
recording safety instructions and same-vehicle confirmation remain explicit.

No transport, controller, diagnostic rule, recording, storage format or
acquisition scheduling changes are included. Displaying a PID does not request
it from the vehicle. Use **Manage live acquisition** to change the existing read
plan. Controls should only be used while parked.
