# Figma dashboard on native Android

This integration brings the presentation from
PR #64 (private project reference omitted), source commit
`1da5aa0d19670301728230ee68cbba2dd2033419`, into the native Android application
introduced by PR #67 at `447d41e02f3151c19557fee78e17df6c01c5dc98`.

The original visual fidelity audit (not included in this snapshot)
and reference image (not included in this snapshot)
remain available at that source revision. Those earlier desktop renders are
historical design evidence, not verification of this Android build.

## Integration

The presentation now lives in `kotlin/androidApp/src/main/kotlin/org/openwrench/frontend`:

- `DashboardTheme` supplies the charcoal canvas, blue navigation/action accents,
  teal telemetry, typography and panel shapes.
- `DiagnosticWorkspace` supplies the sidebar, semicircular RPM instrument,
  compact sensor grid, source labels and searchable inventory.
- `WorkspaceTelemetry` supplies four independently scaled telemetry strips.
- `DashboardPresentation` and `DashboardVisualState` contain display-only
  formatting, unit conversion, plot selection, bounds and responsive layout.
- `DashboardApp` connects these views to the existing native `DashboardActions`
  and Android session controls.

The native session engine, Bluetooth/USB transports, controller, allowlists,
decoder catalog, polling intervals and lifecycle ownership retain the PR #67
implementation. No desktop module, Python dependency or HTTP client is restored.
Settings continues to expose native adapter selection, optional VIN reads,
snapshot capture and document-picker import/export. Source changes and Start/Stop
live data remain explicit. Pinning, metric/imperial conversion, plotted-channel
selection and Pause visuals do not start or change acquisition.

Imported snapshots are labeled historical throughout the workspace. Timed
recordings retain recorded provenance and their existing playback controls.
Frozen plots reset when their recording or snapshot content changes so a newly
imported file cannot display observations from a previous file. Missing, stale,
unsupported and disconnected values remain distinct; a real zero stays visible.

Pause visuals freezes both the observation window and its scale. Each telemetry
strip and its axis label share the same filtered 60-second history, including
during replay gaps when no new observation arrives. Metric/imperial conversion
applies to scalar instruments; structured sensor fields retain their original
values and units (for example, the Celsius fields in native PIDs 0x67/0x68).

Navigation destinations and recording actions without a native implementation
remain disabled. The RPM arc's 0–8000 display range is presentation geometry,
not an inferred vehicle-specific limit.

## Verification

Run the native project checks with JDK 17 and Android SDK 35:

```sh
cd kotlin
./gradlew :shared:jvmTest :shared:testAndroidHostTest :shared:assembleAndroidMain \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

The port retains PR #64's presentation/visual-state tests and adds coverage for
native snapshot source labels. Existing controller, decoder, framing, snapshot,
pin and replay tests remain part of the Android workflow.
Regression tests cover structured temperature snapshots in imperial mode,
paused plot scale stability, and replay extremes aging out of the plotted window.

Tim or Nick reviews UI changes with the exact-head APK using the
[manual visual review checklist](manual-visual-review.md). The former Android
visual-review emulator workflow has been removed; local capture/assertion helpers
remain optional. No automatic general phone/tablet/large-text screenshot,
foreground, crash or navigation matrix runs. Native tests/lint/build and the
separate saved-drive replay workflow remain in place, with narrower coverage.

### On-demand graph choices

Live stream analysis keeps its PID catalog hidden behind the hamburger button
in the panel header. Tap **Choose PID graphs** to open a searchable, scrollable
selector. Tap anywhere on a PID row to add or remove that graph; the selection
applies immediately. **Done**, Android Back, or tapping outside closes the menu
without changing the selections. Reopening starts with an unfiltered list.
Unknown and unsupported statuses remain visible, and selecting graphs never
changes acquisition or polling. The existing graph viewport and compact All
Signals rows are unchanged.

The selector reserves up to 380 dp for the scrolling PID list (previously
232 dp), with a smaller title, shorter helper labels and 4 dp section gaps.
This provides room for five standard PID rows while scrolling in normal
portrait layouts, including extra headroom for partially scrolled rows. PID
labels still wrap and respect system font scaling; row touch targets are not
shrunk. Short screens, landscape, the keyboard and enlarged fonts can show
fewer rows, with the outer content remaining scrollable and Done available.
