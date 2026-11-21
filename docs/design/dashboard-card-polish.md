# Dashboard and Diagnosis visual polish

Grouped visual polish based on main e7b572681b90601a1cf54caef31075260b3c5eb8
(tree fa008c2dfdb4732077c986b2fd64f114f717e389), after PR #111 merged.

The supplied tablet screenshot was inspected. It shows a synthetic replay:
the RPM gauge added height above the age, and five supporting Air & Engine
readings were stacked into full-width rows. No reference image is committed.

- RPM now places the value and age beside a compact gauge, with the display-scale
  note in the same footer space used by the neighboring sparklines.
- Visible value cards and Fuel/Air/Fault cards share the greatest natural height
  within their current row. There is no hard cap on text or diagnostic replies.
- Air & Engine uses two columns only when its actual inner width accommodates
  two 176 dp cells after text scaling and the 8 dp gutter; otherwise one column.
  Five readings keep the final half-row, and every existing reading/status and
  inspector action remains present.
- The small row helper measures natural content without intrinsics (the scalar
  component uses BoxWithConstraints), then aligns card surfaces. It retains no
  height state across source changes or rotation and handles transient zero width.
  Single-column rows bypass the measuring copy; Air cells use ordinary Rows.
  Measuring copies in multi-card rows are not placed/drawn/hit-tested and clear
  their semantics. The reviewed card call paths are presentation-only, without
  launched effects or I/O. Device performance/accessibility still need runtime
  checks; there is no claim of measured tablet performance.

No sensor, acquisition, source, saved preference, graph or navigation logic changes.
No package, signing or storage changes. The focused draft PR contains only this
grouped polish and its tests/documentation; merging requires separate approval.

## Diagnosis page

The existing tablet Diagnosis capture was inspected, and its DiagnosisPage source
blob matches pre-change main. Its full-width Set up connection control is replaced
by the existing compact WorkspaceButton with settings icon, wrapping label and
48 dp minimum touch height. The same onSettings navigation callback and visibility
conditions remain.

Get ready/Instructions groups the parked/foreground guidance and an expandable
Connect → Start diagnosis → Finish checklist. The session panel retains its real
source, loading/error state, duration/counts, primary actions and session options.
Diagnostic information has an honest empty prompt before a diagnosis; actual
Analysis/Charts/Evidence remain separated under Diagnostic information or Results.
No new Drive action or automatic diagnosis behavior is introduced. Existing
start/finish, captured-data matching, resume confirmation and result loading are
unchanged. All useful guidance remains, with details disclosed in one place.

## Validation

Focused Kotlin tests cover inner-width/text-scale boundaries, odd rows and
zero/narrow/tablet width partitions. They are added but not executed yet.
The PR handoff records exact-head CI, script/diff checks, independent source review
and actual screenshot inspection. The local preparation passed 62 script tests.

Android compile/tests/lint/assembly are setup-blocked: Gradle 8.13 cannot download
from services.gradle.org (Network is unreachable). This executor has Java 21,
not required JDK 17, and no Android SDK/adb/emulator/KVM. No new Android
screenshot, runtime alignment, large-text or device pass is claimed.

Before publication/readiness, run the required aggregate checks and inspect
actual Android captures: the six-card row, all five Air readings in two columns
beside Fuel/Fault, narrow and 2x-text fallback, long or unavailable labels, source
changes, collapsed cards and inspector/Back. Preserve each reading and its unit,
age and truthful state; natural growth is preferable to clipping.

Also inspect Diagnosis at tablet, phone and 2x text: compact connection CTA and
its settings/Back route, expanded/collapsed instructions, initial/loading states,
active recording, interrupted/paused/saved results and same-vehicle resume. The
old capture is inspection context only; no post-change Android render is claimed.
