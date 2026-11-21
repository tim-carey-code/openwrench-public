# Live sensors home and display modes

Live sensors now opens in **Both**, using the supplied screenshot's large engine-speed gauge and sensor value cards above the aligned stream-analysis panel. The same compact mode/settings bar is always available. The compact saved-drive replay status is unchanged.

- **Both:** gauge/card overview, aligned selected streams, then additional sensor value cards. Card sparklines and aligned streams share chart pause, range, scale and units; current numeric values continue to reflect availability when charts are paused.
- **Graphs:** only the selected full-width graph stack, with chart labels, units and receipt/source status. No gauge or sensor value-card section.
- **Values:** gauge and sensor cards, with no chart traces or stream-analysis panel. The selected/all-values preference applies only here; returning to Both restores the full overview without losing the Values preference.

The separate legacy Show gauges route has been removed so there is one consistent mode switch. Navigation, detail views and full-screen exit preserve display selections and chart settings; a new source/session starts in Both without borrowing another source's frozen receipts. Source labels, replay timing, acquisition and storage remain unchanged. The separate paused import-picker work is not included.

The overview uses the existing phone/tablet/desktop breakpoints and larger-text reflow. Eight primary card slots keep the overview bounded; additional values and Graphs items use lazy lists. Both's aligned panel keeps its finite, scrollable graph viewport and all selected streams remain reachable. Card values retain real unavailable/stale states; pins control order/pin state rather than inventing or hiding available readings in this view.

## Preview delivery

The prior Replay Preview signing key was absent following a workspace reset. The separate `liveHomePreview` variant has application ID `org.openwrench.android.livehomepreview` and launcher name **OpenWrench Live Home Preview**. It has separate storage and does not replace any previous app. Do not uninstall or clear existing app data for this preview. This build target has not yet produced an APK: Gradle distribution downloads are blocked by HTTP 504/timeouts. Prior APK identity verification and current limitations are in `docs/evidence/live-sensors-home/verification.md`.
