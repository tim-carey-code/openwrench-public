# Manual Android visual review

Refs #120. Tim authorized the human-only option: the general Android visual
workflow is removed, including manual dispatch. Tim or Nick performs visual
acceptance for UI changes and fixes. Do not recreate an automatic visual gate.
Native Android tests/lint/build and applicable saved-drive import/replay checks
are separate and currently run locally under the [validation policy](development.md).

## Exact-head APK checklist

Record this on the PR; mark each item Pass, Fail or Not tested with a reason.
A partial review is not full acceptance. Repeat affected checks after a new head.

- Reviewer (Tim or Nick), date, full PR head SHA, APK artifact/run URL and APK
  SHA-256; confirm the APK was built from that head, rather than a merge preview
  or older download. Build that checkout with the documented JDK/SDK and Gradle
  command in [development](development.md).
  Install with the retained signing identity; follow the
  [upgrade/history gates](automatic-scan-and-upgrade.md). Never uninstall or clear
  storage to bypass a signature mismatch.
- Record device/emulator model, Android/API version, orientation, viewport/density
  and font scale. Inspect phone and tablet layouts at normal and enlarged text
  (including 2×); note any matrix cells not tested.
- Visit Diagnosis, Live Sensors, History, Advanced and nested settings; check
  branding, selected destination, readable labels, clipping/overlap, scrolling,
  reachable controls and touch targets. On tablet, expand/collapse the sidebar
  and verify the choice through navigation and Back without a phantom Back visit.
- Exercise Android Back through dialogs, PID search/keyboard, nested pages and
  root exit; check dismissal order and restored selections. Enter/exit graph
  fullscreen, switch display modes, rotate and resize/narrow then restore the
  window; check sidebar/mode restoration and background/foreground return.
- Check affected graph/workspace controls (selection, pin/collapse, height,
  reorder/hide/recover/reset and saved preferences). Inspect actual pixels and
  interactions, not only hierarchy XML. Watch for wrong window focus, crash/ANR
  dialogs and lost state; attach relevant screenshots/video and logs for failures.
- Use offline Demo or explicitly labeled synthetic fixtures. Do not enumerate or
  connect Bluetooth/USB adapters for this checklist. State source and untested
  accessibility, physical-device and upgrade behavior separately; this review
  does not establish adapter/vehicle qualification.
- Record overall result, evidence links, failures, untested areas and whether
  affected UI is accepted at this SHA. Keep unresolved gaps visible for review.

## Coverage and cost boundary

The removed job took 41m28s and made 201 captures in the investigated #119 run
(issue evidence (private project reference omitted)).
Removal eliminates that job's runner time; it does not eliminate the other CI
jobs' cost. Human effort and turnaround have not been benchmarked.

This was not a baseline pixel-diff suite. Removal also loses automatic general
navigation, Back/IME, sidebar geometry and touch-target assertions, fullscreen,
rotation/resize, focus ownership and crash/ANR checks across its emulator matrix.
Human inspection can miss regressions or omit combinations. Native unit tests
and dedicated replay checks have narrower coverage; they are not equivalent. No successful visual status is manufactured.

Optional `scripts/capture-*.mjs`, `scripts/check-single-column-graphs.mjs` and
`scripts/visual-navigation-assertions.mjs` retain reusable local helpers; they are
not a complete replacement runner and do not execute automatically. Their Node
fixtures test assertions with synthetic input, not Android behavior. Historical
CI evidence remains historical; it does not certify a new APK.
