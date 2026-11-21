# Offline curated investigation recommendations — catalog 1

`InvestigationReasoning.recommend(savedInvestigation, caseId)` is a deterministic,
read-only API over the existing ledger and one explicitly selected frozen case.
It answers which distinguishing question is useful next, why, what retained
references support it, and what evidence/applicability is still missing. It adds
no store, persistence schema, UI, acquisition path, hardware command, runtime
network or dependency. It does not depend on the unmerged #127 workspace or #124
provisional warm screening. No existing investigation or assessment is rewritten.

The portable question catalog and explicit owner-report vocabulary live in shared
Kotlin. The adapter is colocated with `SavedInvestigation` and historic case/drive
models in the Android module because those retained types currently live there;
it has no Compose, Android framework, transport or session dependency. Moving
those types is outside this bounded slice. Run the adapter off the main thread:
it validates the retained snapshot through the existing codecs before deriving
recommendations.

## Calling it from a later UI

```kotlin
val saved = store.load(investigationId) // existing InvestigationStore, on IO
val report = InvestigationReasoning.recommend(saved, selectedRetainedCaseId)
val next = report.nextQuestion
// Render next.question.question, alternatives, why, missingEvidence and limitations.
// Always show historical origin/source for every referenced correctionEvidence.
// next.executable is always false. This is not a vehicle action API.

// A deliberate owner answer can be appended through the existing journal/store:
val ownerAnswer = InvestigationQuestionCatalog.startingReport(
    StartingBehavior.NORMAL_SPEED_LONG_CRANK,
    "Owner reports normal-speed extended cranking; time/conditions not verified"
)
// Append ownerAnswer with the normal author/reason/expected-head workflow.
// Record reported timing separately using timingReport(...).

// Once the user has linked observations to competing hypotheses:
val proposed = next.question.proposal(listOf(firstHypothesisId, secondHypothesisId))
// Append the ProposedTest normally; then record TestResult with that exact test ID.
```

The returned order represents prerequisite workflow, **not cause likelihood**.
Nothing is appended automatically. Recompute after an explicit revision; identical
snapshot/selection inputs yield identical output. `startingReport`, `timingReport`
and `adaptationReport` create REPORTED observations. Exact catalog vocabulary is
recognized only as an attributed owner answer; arbitrary complaint/result prose,
vehicle names, percentages and words such as “normal” are never parsed into facts.
Conflicting answers require clarification or explicit selection of an existing
answer revision via `selectedStartingRevisionId` / `selectedTimingRevisionId`.
All conflicting reports remain returned and retained. Selection is not verification.

## Evidence and questions

- First clarify slow/no crank, normal-speed long crank, starts/stalls, rough running
  or unknown. Slow/no crank stays on a separate scope question; later running
  fuel correction does not establish a cranking-system cause.
- Separate reported starting-event timing from retained engine-running closed-loop
  receipts. Even “same occasion” is an owner report. No cold-ignition, fully-warm
  or current-condition conclusion follows from an interval/coolant number.
- Only retained paired intervals from the existing known assessment pack/policy
  provide qualified correction evidence. The historic codec remains the authority
  for counts, independent receipts, span, freshness, availability and framing of
  typed evidence. No thresholds are duplicated or weakened here; chart points,
  scan scalars, reported -16% and incomplete runs cannot supply paired intervals.
  Negative follow-up uses the existing interval screening cue and direction,
  never a new vehicle normal range. All intervals remain separately inspectable.
- Missing other-bank intervals mean **no qualified evidence available**, not normal
  fuel correction and not proof that the bank is supported/unsupported. The
  archived evidence does not establish every missing PID's applicability/admission.
- For a bank-pattern question, select the earliest concern-relevant compatible
  interval pair in the same retained drive: known matching observed ECU source,
  same origin/policy/fuel/motion state, overlapping receipt times and overlapping
  RPM/load/coolant bounds. No cross-case or cross-drive joining. Same/differing
  pattern and learned direction motivate shared versus bank-specific questions,
  never a common/local component verdict or synchronized ECU-time claim.
- Input plausibility, unwanted vapor and unwanted fuel-delivery questions require
  explicit vehicle/engine/ECU applicability, an exact OEM procedure/specifications,
  safe method, compatible tools, conditions and independent result provenance.
  These prerequisites remain missing here; the API offers question text, not
  a live procedure or permission to perform it. No MAF threshold, pressure spec,
  universal warm threshold or actuator compatibility is supplied.

Every measured basis retains its original case/finding/interval reference, drive,
origin, source and receipt/context evidence. SYNTHETIC, UNKNOWN and hardware-origin
historical records stay labeled; even HARDWARE is retained metadata, not
independently authenticated provenance. No fixture becomes real-vehicle evidence.

## Result semantics and current ledger boundary

The adapter returns only active results from the ledger's existing supersession
rules, preserving exact test/result references, author, provenance, original result
text, supporting observation IDs, correction reference and reasoned effects.
SUPPORTS/WEAKENS are exposed as **author-attributed** supporting/contradicting
judgments. Other effects or non-RECORDED outcomes remain inconclusive. No result
closes a fault branch, supplies an OEM prerequisite, changes numeric measurement
qualification or proves causation. An active supporting/contradicting judgment
motivates a result-scope question. A catalog question with an active inconclusive
result remains open, with that outcome/reference in its recommendation reason.

A purge click, acknowledgment or continuity is not sealing/flow proof. A sealing
failure would support only the tested mode, not establish the whole causal story.
A normal result weakens only that tested mode under the tested conditions;
intermittent/unknown behavior stays open. Pressure decay alone cannot identify a
leaking component. No free-text result is reinterpreted as a machine-verified pass.

Schema 1 has no typed catalog ID, tested fault mode, OEM procedure identity,
conditions, mechanical result or symptom classification. To avoid an incompatible
schema expansion, catalog 1 supplies exact immutable proposal text and recognizes
only an exact kind/question/outcome-template match. Edited/custom questions stay
custom. A matching template still does not authenticate result content. Persisting
richer typed mechanical outcomes and safely advancing/retiring fault branches is
a concrete future schema/design boundary, not hidden inference in this slice.

Repair/symptom reports, compatible fuel-correction comparison and adaptation/reset
history are separate tracks. This API does not calculate a repair or numeric
before/after outcome: `repairTracks.fuelCorrectionOutcome` explicitly says
NOT_EVALUATED and points to the existing compatible-evidence comparison API.
Reported symptom outcome remains verbatim; reset/adaptation history is separately
reported or unknown. Trim improvement alone proves neither repair nor resolution.

## Sources and applicability

Catalog metadata links these primary documents as illustrative principles, with
scope and limitations available offline. URLs are references, never fetched by
the application:

- [Snap-on Holden Engine Troubleshooter Reference Manual v9.2](https://www.snapon.com/DiagnosticsManuals/PDF/AU/VCS/Holden_Engine_Troubleshooter_Reference_Manual.pdf),
  H017/H018 (PDF pp40–43): retained corrections depend on operating cells/history,
  and rich-correction patterns have competing explanations. This archival Holden
  material supplies no transferable Subaru values, reset advice or procedure.
- [Ford 2013 OBD System Operation Summary](https://www.fordservicecontent.com/ford_content/catalog/motorcraft/OBDSM1300.pdf),
  fuel-monitor section p56: monitor entry conditions illustrate why context
  matters. Ford thresholds/preconditions are not Subaru specifications and are
  not copied into this algorithm.
- [Subaru TechTIPS MC-10219476](https://static.nhtsa.gov/odi/tsbs/2022/MC-10219476-0001.pdf),
  PDF pp2–4: the overview includes 2013-on Legacy, while the test-entry table lists
  2015–22 Legacy/Outback. This does not verify an applicable 2013 Legacy 3.6R
  procedure, sealing specification or actuator support. No bulletin procedure or
  service-mode steps are reproduced or enabled.

Verified against publisher-hosted copies on 2026-11-14. None licenses application
of another vehicle's numeric criteria. OpenWrench's existing receipt qualification
remains separate from these sources' OEM/service guidance.

## Checks and remaining scope

Synthetic tests use a reportedly 2013 Legacy 3.6R starting/-16% narrative, fictional
negative Bank 1/both-bank intervals, positive contradictory measurements, and
missing/short/stale/repeated/corrupt evidence. They cover unknown source, disjoint
time/context, category conflicts, manual support/contradiction/inconclusive
corrections and repair/adaptation separation. No private VIN/capture, emulator,
physical adapter or vehicle is used. No changes to #122/#123/#124/#127 branches
or UI files, commands, allowlists, protocols, reset behavior or GPT integration.

Later UI integration should show provenance, missing prerequisites, catalog
alternatives and manual-result limitations rather than a generic possible-parts
list. Exact-vehicle OEM validation, richer typed results and independently reviewed
scope-specific reasoning are required before claiming a tested fault mode is
resolved. Any future actuator capability remains unsupported, outside this
read-only slice and subject to a separate policy decision and specific permission.

The separate [draft recommendation integration](investigation-recommendation-integration.md)
connects these layers through explicit case/head-bound previews and deliberate
question adoption. It retains all dependency and exact-head visual/review gates.
