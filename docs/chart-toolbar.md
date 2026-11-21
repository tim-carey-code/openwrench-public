# Consistent chart controls

Graph Focus and the linked drive charts use one shared toolbar above their plots:
**Pause / Play → Time → Fit scale → Expand**. At large font sizes or narrow widths,
controls wrap into two or one columns in the same reading order. Targets are at
least 64dp high, selected scale/size states are exposed to accessibility, and
range choices expose their selection.

## Live view

**Pause** freezes chart history and scales only. Current numeric values still
update, and polling/recording are unchanged. **Resume** catches up to the current
retained history. Selecting a PID that was absent at pause leaves its paused
trace empty. The caption retains simulator/snapshot/replay provenance even when
paused; it never claims recording is active just because a chart is visible.

Time selects 15, 30 or 60 seconds. Fit scale uses visible actual observations;
turning it off restores existing signal display bounds. Expand affects all
plots; Compact restores 72dp plots. Both heights grow with system text size.

## Saved drive and linked charts

**Play** moves the shared cursor through the selected retained time window at
1× elapsed time. It is a review control, not vehicle replay or acquisition.
Readouts still use the nearest actual receipt within two seconds and show its
receipt time. Gaps are never filled and measurements are never interpolated.
Playback stops at the end; Play there restarts from the window's beginning.
Scrubbing, choosing another range/window/drive, leaving the view, or backgrounding
the app stops playback. Repeated Play/Pause never modifies stored evidence.

Time provides 15, 30 and 60 seconds, 5 minutes and All retained. A duration
selects the trailing part of retained history, not an invented earlier period.
Bookmark bounds are clamped to actual retained timestamps. Fit scale uses
visible observations; with Fit off, each signal keeps its all-retained scale.
Expand/Compact affects every plot and uses the same sizes as Graph Focus.

When this component is showing an ongoing collection rather than a saved drive,
the first control becomes **Pause / Resume** and freezes the displayed review.
The collection, storage and current session keep running. Source/session keys
prevent a frozen review or playback cursor leaking into another drive.

No controller, transport, wire-command, polling or storage-schema changes are
part of this feature. No runtime dependency or fabricated demonstration trace
was added. Inspect charts only while safely parked.
