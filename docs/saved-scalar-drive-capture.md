# Saved scalar drive capture and replay

## Scope and predecessor

This focused follow-up depends on PR #115's advertised-scalar acquisition policy.
It adds retained scalar telemetry to ordinary Vehicle Health drives and diagnosis-linked
saved drives. It does not change the decoder catalog, wire commands, polling scheduler,
health assessment rules, or before/after scan policy.

A recording includes every scalar PID advertised by the selected ECU and accepted by
the decoder (55 in the current catalog). It does not claim that every vehicle supports
55 PIDs. Structured ECU/fuel-system status remains in the existing health evidence path;
it is not coerced into scalar graphs. Graph visibility, pins and display selection do
not determine which acquired receipts are saved.

The regular Vehicle Health Start action explicitly requests the predecessor's
all-supported-scalar policy. The user's ordinary watch selection is restored when
recording stops. Diagnosis start/resume already use that policy.

## Separate telemetry and health inputs

The existing single reader delivers actual completed receipts. A separate bounded
telemetry recorder retains scalar series and full-monitor numeric aggregates. Only the
unchanged health input set reaches health context, warnings and assessment. A scalar-only
ECU can therefore produce a saved replay without invented health context.

The saved archive keeps canonical units, original monitor-relative receipt offsets,
NO DATA/nulls and segment breaks. A channel with no completed receipt remains present
with an empty series. Replay displays it as not sampled, never zero or vehicle support.

## Explicit retention policy

This is bounded retained evidence, not a lossless or unrestricted full-trip recording.

- At most 55 scalar identities, 1,200 points per channel and 20,000 points overall.
- Latest 20 minutes; stable per-channel budgets keep every admitted channel represented.
- Latest actual receipt in each time bucket. Bucket duration adapts to catalog size:
  one second for up to 16 channels and four seconds at 55 channels.
- Actual receipt timestamps are retained; buckets do not create synchronized samples,
  interpolation or fabricated observations. A missing receipt or hidden NO DATA in a
  bucket still causes a break before the recovery point.
- The archive/report states the bucket duration, older buckets omitted and earlier
  same-bucket receipts replaced. Numeric start/end/min/max/count cover admitted valid
  receipts across the full monitor, including receipts omitted from charts.
- The existing 2 MB archive and 25 MB/20-drive history limits remain. Existing save
  failures, retry and history-rotation behavior are unchanged; no oversized file is
  silently accepted or converted into a partial channel catalog.

The diagnosis receipt CSV remains separately bounded and does not use chart decimation.
Old recordings cannot acquire absent channels retroactively.

## Compatibility and playback

The existing schema 7 already supports this scalar catalog and budget; no new schema
or migration is required. Readers for versions 1–6 retain their original validation
rules. New archives that fit the old shape may still use version 6; extended archives
require a build that understands version 7.

Choose Replay in the dashboard (or Demo a saved drive in connection controls),
then choose a local saved drive or import an archive. This existing route loads the
adaptive/scrollable dashboard and its PIDs controls, supporting 24 or more selected
replay PIDs concurrently (up to all retained channels). With at least 24 retained
channels, tablet Values defaults to a six-column dense grid at 1280×800 and normal
font size, allowing at least 24 complete readings in one viewport. Tiles retain
PID identity, value/unit, freshness and tap-to-inspect. Table view remains available.
Narrow windows and sufficiently enlarged text fall back to the original readable scrolling
table instead of shrinking 24 charts or text. More than 24 channels remain scrollable.
History's linked health charts and warning bookmarks intentionally remain the existing health-summary
subset; use dashboard replay for the complete retained scalar catalog.
The tablet acceptance gate requires 24 complete value tiles above the fold,
not merely 24 selected IDs; phone/large-text fallback has no such visibility claim. Each graph
keeps its own units/scales; hiding/reordering/collapsing modules affects presentation
only. Pause/resume, background pause, restart and source replacement retain their
existing semantics. Dashboard replay has no seek control; this change does not add
one. The separate health-summary chart cursor is unchanged.

## Verification scope

Added Kotlin regressions exercise full-catalog decoder-valid fictional capture,
ordinary controller capture with a reduced live selection, diagnosis-linked ZIP
import, file save/reload, supported subsets, empty/null channels, zero, gaps,
invalid/out-of-order receipts, long-window bounds and 24 selected replay modules.
The old-format fixture intentionally remains a nine-series fixture.

Android import review imports the explicit 55-channel synthetic recording,
asserts all 55 remain selected and at least 24 complete Values tiles are simultaneously
visible at 1280×800/fontScale 1. It also exercises table/grid switching, inspection/Back,
phone and 2× font fallback, Values/Dashboard/Graphs, pause,
background/stop and gap recovery. That playback evidence must be rerun on the final
follow-up head; it is not physical-vehicle capture evidence.

Local source preparation has no Kotlin/build/emulator pass: this executor cannot
download Gradle and has no Android SDK/emulator. Script results and later exact-head
CI must be reported separately. No vehicle, installed history or signing material
was accessed or changed.
