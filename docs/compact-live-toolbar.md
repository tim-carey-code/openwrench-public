# Compact live telemetry controls

One pinned 48dp icon bar replaces the separate Live sensors header, mode panel
and chart toolbar. It is shared by normal and full-screen views. The single
full-width vertical lazy graph stack is unchanged.

- Wave/grid/split-view icon: Graphs / Values / Both; its glyph follows the mode.
- Hamburger: the existing searchable PID picker, with selected count in its
  description. It still changes display selection only.
- Pause/play: freeze/resume charts only; current values and acquisition continue.
- Clock: select the existing chart time range; the current choice is checked.
- Fit icon and diagonal arrows: current scale and expanded chart height, with
  active backgrounds/checkmarks and accessibility state descriptions.
- Corner icon: enter/exit full screen. Exit is always direct, not in overflow.
- Gear: settings and any overflow controls, including gauges, units, acquisition
  management and fuel trims when applicable.

Every icon has a descriptive tooltip and screen-reader label. Buttons retain
48dp targets regardless of font size; menu text can wrap and menu content can
scroll. A 336dp bar shows seven actions and moves chart height into settings;
a wide bar shows all eight. Smaller bars progressively move time, pause, PID
and mode into settings, keeping exit and the settings anchor direct. Only
pathological windows narrower than 96dp use horizontal scrolling for those two
essential targets. No large stacked control panels return.

Values has its All/Selected filter instead of chart-only actions, including in
overflow. Switching modes or resizing preserves chart range, fit, height,
selection and pause. Source/freshness status stays explicit; chart/source
captions and fuel-trim messages scroll below the bar. Full-screen source/status
remains in its separate bounded status strip, without a duplicate exit button.
Back/Escape, PID/dialog dismissal, replay controls and session safety gates are
unchanged. Saved-chart controls continue to use their existing toolbar.

## Acceptance still required on Android

Test phone/tablet/landscape and full-screen at normal and large text, including
very short windows: all controls in one row, no clipped targets, overflow and
mode/range menus reachable, selected states correct, long-press/hover tooltips,
keyboard traversal, TalkBack labels and exit after scrolling to the last graph.
Check Values filtering never pauses live values, chart pause/resume leaves
acquisition unchanged, and replay/source/status labels remain correct. Pure
planner tests cannot establish these rendered/interaction properties.

## Side-by-side Toolbar Preview

Build `:androidApp:assembleToolbarPreview`. The launcher is **OpenWrench Toolbar
Preview**, application ID `org.openwrench.android.toolbarpreview`, versionCode 7,
versionName `0.3.5-native-preview-toolbar-preview`. This is a separate app with
separate private storage, not an update to OpenWrench or Telemetry Preview.
Existing apps and history are not replaced, migrated or shared. The production
and Telemetry Preview package IDs remain unchanged. No uninstall or data clearing
is needed to make this separate package distinct. No physical-device install
or history migration is performed by this build task.

Use the explicitly named Toolbar Preview APK rather than the earlier same-package
TEST-ONLY artifact. Its local debug certificate is unrelated to the unavailable
original Telemetry Preview signing key. This build creates no signing-secret
transfer. Close any existing adapter session before independently operating
another app; this task does not qualify physical hardware or concurrent access.
