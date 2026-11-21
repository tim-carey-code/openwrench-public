# Contextual fuel-correction analysis — assessment pack 3, evidence policy 1

This increment consumes the existing serialized Health receipt stream. It adds
no requests, transport owner, scan, vehicle writes, runtime dependency or network
service. Shared Kotlin owns pairing and qualification; Android owns presentation
and archive integration. Monitoring lifecycle, source/generation checks, capacity,
and PR #93's fuel-trim preset remain unchanged. A recorder belongs to exactly one
monitor instance/source/session; stop freezes it and a new monitor starts empty.

## Pairing and continuity

Only advertised **and** admitted inputs qualify. Banks are independent: 0106/0107
for Bank 1, 0108/0109 for Bank 2. Receipts before monitor start, out of chronological
order, or with duplicate per-PID timestamps are ignored, as in the existing monitor.
Different PIDs can arrive at the same millisecond. No chart, aggregate or report
text is an input.

Pairing is chronological and greedy, without selecting values: the latest
unmatched actual receipt of each same-bank trim is paired when their separation
is **at most 2000 ms**. A newer same-PID receipt replaces an unmatched one; an
expired unmatched receipt is discarded. Each receipt is consumed once. Both
receipt offsets and their exact separation are retained (separation derives from
the two stored offsets). Opposite-order STFT/LTFT arrival is supported. This is
bounded acquisition-time proximity, not simultaneous ECU sampling.

Unavailable/NO DATA, non-scalar, non-finite or out-of-decoder-range trim attempts
break that bank's candidate and clear its unmatched receipts. An older available
receipt cannot bypass an invalid latest attempt. Context validity is checked
before and after each incoming receipt, so refreshing an expired input cannot
bridge its gap. A context boundary also clears unmatched trims: a pair cannot
straddle a motion/fuel transition or a disqualifying numeric change. Each unmatched
trim carries its own RPM/load/coolant bounds from the supporting context at its
receipt through every subsequent context update. An excursion that returns before
the counterpart arrives still breaks the candidate. Allowed intervening extrema
keep their actual receipt times, including observations before pair completion.
Replacement and expiry discard the unmatched receipt's bounds. A new candidate
uses its first pair's bounds independently of any preceding candidate, including
pattern, screening-band, direction, duration and pair-count boundaries. The previous qualified interval remains saved.

An interval requires **at least 10 independent pairs**, with pair completion
times spanning **at least 30000 ms**, and consecutive completion gaps **at most
6000 ms**. Cached receipts never increase that count or extend that duration.
Short intervals are never joined. Every candidate is capped at **60000 ms from
the first trim receipt** and **256 pairs**; the next pair starts a separate
candidate, which must qualify independently. Classification, LTFT screening-band,
learned-direction and context transitions also start separate candidates.

## Qualified observed conditions

Every pair needs the latest RPM, speed, calculated load, coolant and fuel status,
no older than **10000 ms at pair completion**. Supporting context is retained
with its original receipt offsets, including repeated uses. RPM must be positive;
speed must be nonnegative. Decoder-domain bounds apply to every scalar: RPM ≤16383.75, speed ≤255 km/h,
load 0..100%, coolant −40..215 °C, trims −100..99.21875%. These encoding bounds
are not mechanical operating limits.

Bank 1 requires system 1's exact `closed loop: oxygen-sensor feedback` state and
system 2 either that state or `engine off`. Bank 2 conservatively requires both
fields in that exact closed-loop state. These fields are not assumed to map to
banks. Fault-feedback, open-loop warm-up, load/deceleration fuel-cut, unknown,
unavailable and stale states cannot enter a qualifying interval.

Motion is either **Stationary with engine running** (speed zero) or **Moving**
(speed positive). These labels do not establish stabilized idle or steady cruise.
Within an interval the complete observed RPM range must be ≤**300 RPM**, load
range ≤**10 percentage points**, and coolant range ≤**3 °C**. Context updates that
exceed these bounds break the candidate even between trim pairs. Actual context
minima/maxima accumulate across every update, including opposite excursions
between pairs, and retain their original receipt offsets in typed interval bounds. Each of RPM,
load, coolant, speed and fuel status additionally needs ≥**4 distinct supporting
receipt times spanning ≥20000 ms** among the paired evidence. Repeated cached
observations alone cannot establish stability. Coolant range and first-to-last
change are shown; neither bounded coolant variation nor closed loop proves full
warm-up. Accessory state, fuel type and adaptation history remain unknown.

These are conservative, versioned **engineering evidence-quality tolerances**,
chosen to exclude transients and thin asynchronous evidence within the existing
cadence. They are not validated mechanical diagnostic limits. Some valid real
operation will remain unqualified. Context at pair completion describes recorded
support, not a synchronized snapshot throughout the trim separation.

## Classification and interpretation

Every pair in an interval must have the same relationship:

- |LTFT| ≤3%: small learned correction; STFT is still shown separately.
- |LTFT| >3% and |STFT| ≤3%: learned correction with comparatively small STFT.
- Both magnitudes >3%, same sign: STFT reinforces the learned adjustment.
- Both magnitudes >3%, opposite signs: STFT opposes the learned adjustment.

The ±3% band is a noise-tolerant descriptive application policy, not a normal
range. The established **sustained LTFT beyond ±10%** screening cue remains
separate and unchanged; crossing its strict bands starts another paired interval.
No combined/total correction is calculated. Positive, negative and zero learned direction
are retained separately from relationship classification.

Multiple conditions are described as different if motion differs or the gap
between their RPM/load/coolant ranges exceeds 300 RPM/10 points/3 °C respectively.
Correction differs descriptively if relationship or learned direction differs,
or separated STFT/LTFT ranges differ by >3 percentage points. These rules do not
attribute a difference to RPM, load or a component. A claim of correction differing
across conditions requires the same interval pair to satisfy both difference
policies; differences from unrelated interval pairs are not combined. If no second distinct
qualified condition exists, the finding says so. The latest specific collection
requirement is shown alongside any earlier qualified evidence.

Each bank retains **up to 8 typed intervals**: the earliest retained paired LTFT
screening concern is pinned; remaining slots hold the newest intervals, in
chronological order. An active qualified candidate updates its own interval,
without duplicating pairs into another interval. The existing first LTFT-only
concern also remains unchanged. Dropped intervals cannot support an absence
claim. Raw paired values, context values, receipt offsets, bank, state, relationship
and policy version are retained to reproduce the classification. Stable bounded
ranges are never synthesized from trip extrema.

Guidance targets a next measurement: repeat opposing trims to observe adaptation,
check whether reinforcing STFT persists, or compare a learned adjustment in a
second naturally observed qualified condition. Symptoms and vehicle-specific
service information determine any mechanical test. Confidence concerns the
observed relationship only; its cause remains unestablished. No component failure,
fault probability, universal service procedure or guided engine operation is
inferred.

## Presentation, archives and comparisons

Health retains the current panel styling. Each bank has one contextual finding,
with expandable interval summaries and individual STFT/LTFT receipt times and
separations. Existing ECU/readiness/fuel-state/first-LTFT findings remain. The
copied report includes conditions, ranges, independent counts, limitations and
targeted checks. Source/session labels and historical presentation use the
existing monitor and saved-drive envelope; synthetic QA is labeled explicitly.

New assessments use `standard-obd-drive/3`. New archives write
`openwrench.drive-history/4`, adding `fuel_intervals` to findings, with policy-1
raw evidence rows. Strict bounds, chronology, uniqueness, qualification, context
freshness, decoder domains and classification are validated when saving/loading.
Initial bounds can include context up to 10000 ms before the first trim receipt,
as well as intervening observations before the first pair completes; extrema outside
that freshness boundary or after the interval remain invalid.
The existing 2 MB file/25 MB total budgets remain. Versions 1/2/3 remain readable;
original findings keep their original rule version and missing intervals stay
empty. Reopening does not reevaluate a drive or resume acquisition. Earlier APKs
cannot read format 4. Existing unknown/corrupt-file protection remains.

DriveComparison explicitly handles pack 3. After same-vehicle confirmation and
matching observed ECU source, two pack-3 drives compare the **earliest compatible
retained interval pair**, without choosing the strongest correction. Policy,
motion and both fuel states must match, with overlapping RPM/load/coolant ranges.
The result states its selection scope; matching a relationship does not establish
a recurring cause or equal whole-drive behavior. Missing or incompatible paired
intervals remain insufficient, without fallback to aggregate inference.

Mixed pack-2/3 comparisons deliberately use only their common legacy typed LTFT
windows, under the prior gates, and explicitly cannot compare STFT relationships.
Pack 1, missing or unknown assessment versions receive descriptive original-result
review only. No missing intervals are reconstructed from old windows, min/max
aggregates, report text or decimated charts. IDs stay unique; the existing single
`comparisonWindow` is not overloaded with paired intervals.

## Primary references and applicability

- [Snap-on: Fuel Trim Adaptation](https://www.snapon.com/EN/UK/Diagnostics/News-Centre/Technical-Focus-Archive/fuel-trim-adaptation)
  describes short/long adaptation and correction direction. It supports joint
  interpretation, not the engineering tolerances selected here.
- [Ford 1999 PCED: Fuel Trim](https://www.fordservicecontent.dealerconnection.com/pubs/content/~WVX2/~MUS~LEN/15/VX21016.HTM)
  describes speed/load adaptation cells and learned correction allowing STFT to
  return toward zero. This is Ford strategy documentation, not a universal ECU
  implementation or numerical normal range.
- [Ford TSB 04-17-4](https://www.fordservicecontent.com/Ford_Content/pubs/content/~WT/~MUS~LEN/3602/tsb04-17-04.htm)
  illustrates comparing correction under different conditions for its specified
  vehicles/codes. Its numerical limits, operating instructions and causal tests
  are **not implemented or transferred to other vehicles**, including a Civic.

## Verification

Deterministic synthetic tests cover pair timing, independent counts, invalid and
missing boundaries, context changes/staleness, stability, bank coverage, retention,
archive restart/legacy preservation and new/mixed-version comparisons. Existing
scripted ownership, capacity and trim-preset regressions remain required. Emulator
QA must cover populated and insufficient findings, expanded raw pairs, saved
history after restart and enlarged text. No synthetic, scripted, compiler or
emulator result qualifies a physical adapter or vehicle.

A later focused physical check should verify real receipt cadence and same-bank
provenance on an already supported adapter, ordinary stationary/moving closed-loop
recording, explicit coverage gaps, finish/save/restart/reopen fidelity, and that
reported conditions match the actual context. Do not induce a fault, issue a
vehicle write or perform a Ford-specific operating procedure for this check.


## Synthetic Android UI inspection (2026-11-02)

Inspected on a disposable Android API 35 emulator, 480×960 pixels at 240 dpi,
with normal and 1.6× system text. A temporary QA activity used the production
assessment panels and saved-history page. A deterministic, explicitly labeled
synthetic archive contained a stationary reinforcing interval followed by a
moving opposing interval, each with 11 independent pairs. Bank 2 was unsupported.
No vehicle or physical adapter was discovered or connected.

Verified the populated summary and targeted next check; expanded interval ranges,
original context-extrema times and individual paired receipt times/separations;
empty/insufficient qualification requirements; and process-stop/restart reopening
from the app's on-disk history store with enlarged text. Long evidence and next
checks wrap and remain scrollable in the existing panel design. Temporary QA
activity, debug manifest and fixture-export test were removed before final checks
and APK generation. Synthetic labels exist only in QA evidence, not production
acquisition code.

Screenshots in the evidence directory (not included in this public snapshot)
show the populated finding, expanded interval, paired receipts, insufficient
state and reopened saved report. These screenshots demonstrate presentation and
persistence behavior, not hardware qualification or a mechanical diagnosis.

The final APK was then installed without QA wiring and the same labeled archive
was opened through normal **History → Open saved drive** navigation. The APK
manifest contains only the normal MainActivity entry point (no QA activity).

Initial PR checks passed with JDK 17 and SDK 35/build-tools 35.0.0 (before the correction below):
`:shared:jvmTest` (154 tests), `:shared:testAndroidHostTest` (149 tests),
`:shared:assembleAndroidMain`, `:androidApp:testDebugUnitTest` (322 tests),
`:androidApp:lintDebug`, and `:androidApp:assembleDebug`. All test suites reported
zero failures/errors. The existing monitor ownership, capacity and fuel-trim
preset tests are included. Build command used `ANDROID_HOME` for the installed
SDK; no project dependency or tool version changed.


## Pending first-pair correction (PR #94)

The original first-pair boundary omitted numeric updates while only one trim was
pending. Focused regressions reproduced false qualification for a 500-RPM excursion
that returned before its counterpart, and equivalent load/coolant cases. Four of
five new core test methods failed on the original code; all pass after preserving
pending receipt bounds. Policy 1 and every numerical threshold remain unchanged.

Synthetic regressions cover both arrival orders and banks, returned excursions,
inclusive allowed variation with exact original extrema, stable controls, pending
replacement/expiry, and new-candidate boundaries (pattern, screening band, learned
direction, 60-second cap, 256-pair cap, pair gap, invalid trim and motion transition).
The reproduced first pair is excluded: ten subsequent pairs ending at 4500 through
31500 ms span only 27 seconds and remain unqualified. A further pair at 34500 ms
qualifies the recovered interval independently. Save/reopen tests recreate the
history store for RPM/load/coolant rejection, allowed extrema and stable evidence
in both arrival orders. Archive tests still reject out-of-window bound receipts.

The full required Gradle command passed with JDK 17 / SDK 35 / build-tools 35.0.0:
`:shared:jvmTest` (159 tests), `:shared:testAndroidHostTest` (154 tests),
`:shared:assembleAndroidMain`, `:androidApp:testDebugUnitTest` (324 tests),
`:androidApp:lintDebug` and `:androidApp:assembleDebug`. All suites reported zero
failures/errors/skips. Lint reported zero errors and the same seven existing
warnings outside the changed files.
No UI design, acquisition, command allowlist, policy threshold, dependency or
archive schema changed. No physical adapter or vehicle was accessed, and no
hardware qualification is inferred. This correction remains draft for re-review
and human review; it is not authorization to merge.
