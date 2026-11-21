# Fixture owner preview integration for Nick

Local-only follow-on based on draft #134
`38c3ab58a799e6fc3f95545d76ea42a53f89b7da` (pure preview), stacked on #133
`9581705264e82483a543c93031a3c082baec6b7c` (combined fixture owner). This wires the
existing pure consumer into the **fixture** store/coordinator only. No production
root/factory, general writer, migration, UI, server or vehicle operation is added.
Publication is not authorized. Dependency and Nick branches are unchanged.

## Frontend contract

`CombinedAction.Preview(expectedToken, request)` now carries the full
`CombinedPreviewRequest`: canonical content revision, case ID, explicit starting,
timing and adaptation selections and optional exact question ID. The old
case-only action is replaced, without silently inventing default selected answers.

`CombinedState.Ready` describes valid storage, **not preview success**. Its optional
`preview: CombinedPreviewDelivery` contains the exact submitted Preview action and
one typed pure result: Ready, Stale(content revision), Invalid(field issues) or
Unsupported(mapping). A raw-file-token mismatch uses the existing top-level
`CombinedState.Stale` with the actual current snapshot and submitted action.
Do not render any of those as an empty/normal diagnostic result.

```kotlin
// Retained fixture coordinator, StateFlow bound by Nick's frontend:
owner.dispatch(CombinedAction.Load)
// After observing CombinedState.Ready:
val snapshot = ready.snapshot
val selection = CombinedPreviewSelection(
    caseId = selectedCaseId,
    startingReport = OwnerReportSelection(ReportSelection.SELECTED, startingId),
    timingReport = OwnerReportSelection(ReportSelection.SELECTED, timingId),
    adaptationReport = OwnerReportSelection(ReportSelection.SELECTED, adaptationId),
    questionRevisionId = selectedQuestionId, // optional; exact question, not just catalog ID
)
owner.selectPreview(selection) // also call on every subsequent context change
val request = selection.request(CombinedRecommendationPreview.revision(snapshot.document))
val action = CombinedAction.Preview(snapshot.token, request)
val admission = owner.dispatch(action)
// Observe state, then inspect Ready.preview?.result by its sealed type.
// Loading means retain draft/context; it contains no displayed preview.
// On cancel/cleared context: owner.selectPreview(null).
// On Back/leave: owner.detach(); on return Load and deliberately reselect.
```

Compute content revision off the UI thread using the pure API. UNKNOWN selections
retain their exact selected report IDs; unresolved selections stay explicitly
unresolved and do not infer the latest answer. A selection object with omitted
owner fields uses UNRESOLVED, never normal/negative. The preview policy, qualified
receipts, active correction chains, unsupported mappings and method limitations
are unchanged from the [pure adapter](combined-recommendation-preview.md).

`selectPreview` is the owner's selection-notification boundary: the frontend must
call it whenever case/question/starting/timing/adaptation selection changes,
including while IO is busy. Changing a selection synchronously removes an already
delivered preview and invalidates an in-flight one. Identical selection updates
are no-ops, so rebinding/recomposition does not erase valid results. Clearing the
selection is explicit. External UI changes cannot be inferred by an owner that
was never notified.

Dispatch returns ACCEPTED, BUSY, INACTIVE or SELECTION_MISMATCH. Preview dispatch
requires its selectors to equal the notified selection; it never silently changes
that selection or rebases a stale request. BUSY requests are not queued and do not
replace the admitted operation. For a new selection while busy, notify selection
first and deliberately retry a new request after completion. Repeated identical
busy requests do not cancel the original valid operation. Seeing a completion
state also releases admission atomically, permitting the next action.

## Token, delivery and persistence invariants

- `expectedToken` is SHA256 of the exact committed file bytes. It is compared
  first under the existing process-wide storage lock. A mismatch never computes
  a preview or writes/rebases the document.
- `request.expectedRevision` is the canonical validated document content digest.
  It is checked by the pure API, independently. Different JSON whitespace can
  change the byte token without changing this digest; neither substitutes for
  the other. Both are retained on the delivered request/result.
- Pure Invalid/Unsupported/Stale results stay typed within the delivery. Their
  submitted fields remain available for correction; no replacement conclusion
  or automatic selector is generated.
- Admission, publication and selection invalidation share a coordinator guard.
  A result attaches only when its captured selection generation still matches.
  Selection changes during computation produce a valid storage Ready without
  the old preview. Every admitted load/create/append/recover/preview begins with
  Loading and clears a prior preview. No older preview job can overlap a newer
  admitted action under the existing single-admission contract.
- Detach invalidates all presentation delivery, clears selection/preview and
  retains IO ownership until the admitted operation completes. Canceled reads
  do not deliver. Durable writes remain NonCancellable; changing selection does
  not cancel or erase them. Reload and reselect after returning.
- Preview runs on cancelable IO. It only inspects existing fixture storage and
  calls the pure adapter. Absent storage is rejected without creating a directory;
  pending/corrupt/future state remains blocked/visible without recovery, discard,
  stage, sync or publish. Unexpected read/preview errors remain unavailable.
- Create/append/recover staging, phase-aware uncertain-write classification,
  idempotent immutable request replay and explicit observed-digest recovery are
  unchanged. All data in this namespace remains fictional/synthetic only.

The injectable preview function is an internal deterministic fault/scheduling test
seam, defaulting to the real pure adapter. It is not an executable vehicle test or
production plugin API. Manual values/effects never enter qualified OBD evidence.

## Remaining boundaries

No UI design or actual frontend integration is delivered here. Nick must render
typed states and notify selection changes through the retained owner. Runtime
factory/production authority, non-fictional acquisition/attachment, recovery/export
UX and general history/migration remain separately unsupported/unactivated. Legacy
unscoped and custom/unmapped outcomes remain explicit unsupported traces; no OEM
procedure, actuator compatibility or automatic cause/repair certainty is added.
Signing/history retention is not reinstated as a release gate; user-data deletion
or overwrite is not authorized.

Scripted tests use fresh temporary fictional directories, controlled dispatchers
and one small blocked-read thread to exercise actual ordering. They do not prove
Android UI behavior, filesystem/power-loss qualification or vehicle capability.
No emulator, device/install, hardware, GPT, network runtime or visual CI is used.

Local verification (JDK17/SDK35, offline, serialized one worker, 512 MiB heap):

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*FixturePreviewIntegrationTest' --tests '*CombinedFixtureOwnerTest' \
  --tests '*CombinedRecommendationPreviewTest' --tests '*CombinedInvestigationTest' \
  --tests '*SyntheticInvestigationPilotTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

78 tests passed: 15 new integration,18 owner,17 pure preview,13 combined-domain
and15 fixed-pilot; zero failures/errors/skips. Initial focused run also passed.
Final log: `/tmp/openwrench-fixture-preview-final.log`; app unit XML/HTML reports
are in this worktree. Tests cover exact byte/content distinction (including
whitespace), unknown/unresolved and invalid/unsupported results, absent/pending
read-only behavior, repeated/BUSY and stale-selection admission, each selector's
invalidation, detached/canceled/blocked cross-thread delivery, load/append clearing
and unchanged durable write recovery/idempotence regressions. Completion and
admission release are guarded together. Storage-level stale/recovery/unavailable
states remain storage information, never a delivered old preview; render those
separately from Ready.preview.

Full shared/app aggregate suites, lint, APK assembly, Android UI/runtime and
power-loss checks were not run for this bounded fixture-contract slice. Android
main/test Kotlin compiled as part of focused tests; no visual/hardware or
production qualification is claimed. Independent review must inspect the exact
local commit; implementer tests are not a reviewer rerun. Base CI does not cover
this new local change.
