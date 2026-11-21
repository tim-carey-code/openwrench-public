# Full-screen telemetry

In **Live sensors**, tap the **Enter full screen** corner icon to give Graphs, Values or Both the
workspace. The side/bottom navigation and larger connection panel are replaced
by a compact persistent status strip. Android's system bars and safe areas stay
available; this is a focused app view, not an immersive lock or screen-awake mode.

- The **Exit full screen** corner icon, Android Back or Escape restores the workspace. Back/Escape first
  closes signal details if open; a second Back/Escape leaves full screen.
  Modal PID/range/mode menus dismiss normally before the screen's Back action.
- The mode menu switches **Graphs / Values / Both**. The PID hamburger and sensor
  options stay available. Chart controls keep Pause/Resume, Time, Fit and
  Expand/Compact. These actions, mode, PID selection, settings and full-screen
  exit share one pinned icon bar with 48dp targets and descriptive tooltips.
  Actions that do not fit move to the settings menu; no second control row is added.
- Entering/leaving full screen does not change PID selection, units, range,
  display pause, scale or expanded plots. Switching views and inspecting a
  signal retain those choices. Changing source/connection resets display state
  as before. Leaving Live sensors or selecting gauges ends full screen.
- Current hardware status shows connection, acquisition and receipt freshness.
  Only supported, supplied readings younger than the existing five-second
  expiry count as current, and only while connected and acquiring. The oldest
  current receipt is shown alongside any stale count. This is not ECU sample
  time, a vehicle health assessment, or a guarantee that all PIDs are fresh.
- Simulator, snapshot and recorded replay have explicit non-live labels. Status
  is always calculated from current supplied rows, never frozen chart history.
  Diagnosis recording and chart pause are labeled separately when applicable.
- At large fonts/short landscape, the status text has its own bounded scroll
  area while the unified control bar and exit stay fixed. Captions and plots
  scroll below the bar. Details and Android safe areas remain
  usable. Inspect the screen and change controls only while safely parked.

No transport, controller, diagnostic rule, polling, storage, schema, permissions
or dependency changes are included. No command is issued by entering/exiting.

## Separate preview

The `telemetryPreview` build installs as **OpenWrench Telemetry Preview**, package
`org.openwrench.android.telemetrypreview`. It does not replace regular OpenWrench,
Chart Preview, Graph Preview or USB Preview and cannot access their private
history. A new separate package is necessary because the earlier Chart Preview
signing key is not present in this execution environment. Never uninstall or
clear an existing app to bypass an installation failure. Close its adapter
session before using the preview with hardware.

Graphs and Both use the same full-width, single-column lazy list as the normal
Live sensors view at every window size. Values retains its adaptive grid.
