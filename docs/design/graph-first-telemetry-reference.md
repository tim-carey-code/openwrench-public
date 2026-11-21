# M1/M2 Visual Design Reference — Vehicle Telemetry Dashboard

Approved by product owner on 2026-11-10. The user-provided screenshot in the design discussion is the visual source of truth; this specification describes it for developers without embedding a private conversation attachment.

## Visual language
- Dark navy / near-black application canvas, subtle cool-blue panel borders, modest corner radius.
- Dense, professional telemetry console; strong white numbers, restrained blue/green/amber/red traces.
- Crisp typographic hierarchy and consistent spacing; no large decorative banners.
- Desktop/tablet landscape: top strip of compact sensor cards with value, unit, and sparkline; primary workspace beneath uses a single full-width graph column at every size. Compact cards reflow above it; no permanent sidebar squeezes plots.
- Graphs must remain the primary visual priority even when compact cards are shown.

## Reference composition (illustrative, not all implemented)
1. Title/status header: Vehicle Telemetry Dashboard; connection, acquisition, recording, source status.
2. Top telemetry strip: RPM, coolant, fuel level, battery voltage where supported; do not invent unavailable signals.
3. Main full-width time-series modules: RPM over time; multi-series temperatures; fuel trims and related signals.
4. Deferred data quality concept: per-signal missing/stale/error rates derived from actual receipts and time windows, not arbitrary percentages.
5. Deferred correlation/analysis concept: only when actual aligned data supports it.
6. Deferred alerts concept: evidence-based diagnostics with explicit severity and timestamps, not hard-coded thresholds or fabricated warnings.

## M1
- Graph-first presentation of existing readings using existing data flow.
- Visible labeled X/Y axes with units and correct timestamps.
- Compact toolbar; preserve Values / Graphs / Both if currently exposed.
- Preserve sensor availability states, disconnection handling, and historical provenance.

## M2
- Presentation-only full-width graph modules and compact cards.
- Hide/show, pin, collapse/expand, reorder, and configurable graph heights.
- Keep module configuration separate from PID selection, polling, recorder, and connection lifecycle.
- Responsive layouts and accessible controls; persist layouts only after safe migration strategy.

## Explicit exclusions
- Digital driving gauge cluster is later work, not part of M1/M2.
- No invented values, alert conditions, missing-data percentages, or unsupported PIDs.
- No change to OBD polling or recording semantics without separate review.

## Acceptance
- Compare screenshots at tablet landscape and desktop widths to the user-supplied reference.
- Verify graph prominence, compact cards, color/contrast, axes, and dense modular composition.
- Run existing tests, simulator checks, and Android APK build; do not merge before user approval.
