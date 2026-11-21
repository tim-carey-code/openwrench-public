# Disabled retained-investigation owner candidate

This is one consolidated **local-only, disabled** on-device Kotlin candidate for
new investigations. Base: reviewed fixture wiring
`5e45a3b0d910fc482da684fee0130c32ad646bbd`, on pure-preview #134
`38c3ab58a799e6fc3f95545d76ea42a53f89b7da`. No production/UI code references this
service/repository and no runtime/app factory exists. It is not release activation
or a migration. Publication is not authorized.

## One chosen authority

For NEW candidate IDs only, `RetainedInvestigationRepository` owns
`retained-investigations-candidate-v1/<id>.json` and optional `<id>.pending`.
The exact schema is `openwrench.retained-investigation-candidate/1`. An ID is stable
and validated with the existing restricted journal ID grammar. The store never
opens/listens to/discovers `investigations-v1`, existing case histories, fixture
stores or pilot stores. No dual writes, import, overwrite, delete, discard or
automatic upgrade exists. The real workspace continues using `InvestigationStore`
in `investigations-v1`; this candidate has not replaced that authority.

`RetainedInvestigationService` is the retained serialized IO/lifetime owner that
Nick can bind to. Construction requires an explicit caller-selected candidate
root, explicit source port and scope; there is no application default. A future
production root/factory and recovery/export UI need separate authorization/review.
Do not instantiate it against installed app data during this task.

`RetainedInvestigation` owns a single global append-only revision trail. It starts
with a complaint only, then deliberate case attachment events. Ordinary questions,
typed attributed outcomes and exact corrections can interleave with later
questions/cases. Every attachment carries the selected source's exact archive
byte digest and canonical immutable case digest. No frozen schema1 prefix is
rewritten. Existing journal/manual/reference/receipt validators are shared, not
reimplemented with new qualification thresholds.

## Fixture versus retained origin contracts

The separate `CombinedInvestigation` fixture envelope still requires SYNTHETIC
cases and outcomes and its schema/guard remain intact. The retained envelope
preserves the actual archived scan/drive origin enums, observed ECU or null,
recorded times, qualified receipts and author manual-origin declarations. It does
not relabel those records as synthetic to pass the fixture validator. It also
does not authenticate them as real evidence: HARDWARE is retained metadata, not
hardware qualification; mixed/UNKNOWN origins and null ECU remain explicit.
Unrecognized versions/enums fail closed, never become fallback synthetic data.

Preview exposes `originPolicy=RETAINED_ORIGINS_UNVERIFIED`; the legacy
`fictionalOnly` flag indicates whether the envelope guarantees synthetic-only
inputs, **not** whether retained evidence is authentic. Only the selected case
supplies measured evidence. Manual values/effects remain attributed assertions,
never OBD intervals, cause probabilities or repair verdicts. Unknown/inconclusive
results remain unknown, and click/continuity/pressure decay limitations remain.

All new tests use fictional byte streams, including explicitly labeled **MODELED
REAL-ORIGIN FICTIONAL TEST ONLY** cases carrying invented HARDWARE session/timing
metadata. That exercises the archive contract, not hardware or a private capture.

## Frontend service and actions

```kotlin
// Dependency injection only; no production factory is supplied.
val repository = RetainedInvestigationRepository(candidateRoot, explicitCaseSource)
val owner = RetainedInvestigationService(repository, retainedScope)
val state = owner.state // StateFlow<RetainedFrontendState>
owner.dispatch(RetainedAction.ListNew)
owner.dispatch(RetainedAction.Open(id)) // EMPTY/READY/RECOVERY_REQUIRED/BLOCKED inspection
// Sequentially, after each completed state:
owner.dispatch(RetainedAction.Create(newId, complaintEvent))
owner.dispatch(RetainedAction.CaseChoices) // fresh validated explicit source read
owner.dispatch(RetainedAction.Attach(newId, byteToken, chosen.selection, attachmentIntent))
owner.dispatch(RetainedAction.Append(newId, byteToken, ordinaryOrTypedEvent))
// Typed correction explicitly references the active same-question result.
val selection = RetainedPreviewSelection(newId, selectedContext)
owner.selectPreview(selection)
owner.dispatch(RetainedAction.Preview(newId, byteToken,
    selectedContext.request(RetainedInvestigationCodec.revision(document))))
```

Dispatch is one admitted operation, with ACCEPTED/BUSY/INACTIVE/
SELECTION_MISMATCH. BUSY is not queued. State has `loading` plus typed `outcome`:
Listing(metadata only), Cases, Opened(inspection), Previewed(typed pure result),
Exported(manifest), or Rejected(typed failure, submitted action, inspection and
field issues where supplied by validators). Storage EMPTY is distinct from
BLOCKED or unavailable source. No failure selects simulator/synthetic fallback.

Call `selectPreview` on each case/question/report selection edit (including null
on cancel), and `detach` on leave. New admitted actions clear a prior preview;
selection changes invalidate pending delivery without canceling admitted writes.
Admission release and completion publication share one guard. Writes/recovery and
export finish their admitted IO despite canceled presentation; reads/previews are
cancelable. Reopen/reselect after returning. The scope/root must be retained by
the frontend owner; construction per recomposition is inappropriate.

## Fresh retained case selection

`RetainedCaseSource.openArchive()` is the only source port. It must explicitly
open the chosen saved-case archive; no installed-history reader is supplied.
Reads are closed and bounded at 25MB, UTF8 round-trips strictly, and the existing
case/scan/drive codecs validate the whole archive. They already require VIN-omitted
case copies. An invalid/missing source returns SOURCE_UNAVAILABLE, not empty or a
negative measurement. A valid empty archive returns empty choices.

Each choice includes `SelectedCaseRevision(caseId, archiveToken, caseDigest)` and
the validated retained copy. A NEW Attach reopens and revalidates the source and
requires both digests and exact case ID to match. Even a whitespace-only archive
change makes the old archive selection stale. The immutable copy and digest
binding are persisted together with author, reason, provenance and separate owner
vehicle assertion (including UNKNOWN). Later source changes do not alter history.
This establishes the observed source revision, not freshness of a live vehicle or
a common vehicle/event with other cases.

A retry of an already committed identical attachment request is recognized before
another source read. This is necessary after lost acknowledgment: later source
deletion/change must not duplicate or invalidate an already durable event.
Reusing a request ID with different payload rejects. Duplicate case/event IDs,
wrong/forward references, stale document tokens and invalid effects reject before
staging. Raw attachment payloads through Append are forbidden: use Attach.

## Recovery and lossless export

Every inspection exposes committed/pending filename, size and full digest when
safely readable; snapshot validity is separate. Invalid/future/diverged originals
remain BLOCKED, visible and unmodified. Recovery requires the exact observed
committed and pending digests plus a validated seed, identical duplicate or
single-event extension of the exact prior log. It re-syncs before same-directory
publish; there is no discard or automatic repair. Invalid committed data cannot
be silently replaced by a valid pending record.

Writes use exclusive pending creation, flush/fsync and rename. Any exception
after staging begins is IO_OR_ACK_UNKNOWN, regardless of exception class, with
fresh inspection. Partial stages remain blocked. Publish followed by lost
acknowledgment can reveal the committed event on reload; retry the same immutable
request instead of generating new IDs. Serialization is process-local only.
Directory fsync, abrupt power loss, noncooperating writers and external filesystem
replacement races are not qualified.

Export is `ExportOriginals(observedIdentities, sink)` with a fresh observation
preflight. It streams original bytes, checks byte count and SHA256 during copying,
and returns an exact original manifest only on success. It does not decode,
re-encode or repair blocked files. The explicit trusted `OriginalExportSink` must
open NEW, non-alias destinations, never overwrite source/user files. No production
sink/document-picker factory exists. Failure can leave an incomplete new export
destination and returns EXPORT_INCOMPLETE; originals remain intact. Inspect the
destination before deciding on another export. No automatic retry/discard exists.

Limits: ten IDs, 500 events and ten retained cases per document, decoded snapshot
25MB, at most committed+pending copies per ID. Hash/export streams are bounded to
50MB per original; larger/nonregular/link originals stay visible and preserved
but cannot be exported by this bounded candidate. Unknown flat namespace filenames
are visible/exportable with observed identity; writes reject unexpected entries.
No arbitrary paths or traversal IDs are allowed. List returns metadata, not ten
full retained document graphs. Namespace overflow blocks; no eviction occurs.
API24-compatible conservative canonical-root/file checks reject symbolic-link
traversal and broken original links. This is not adversarial filesystem or
cross-process locking qualification; production IO/export integration needs review.

## Remaining activation boundaries

Independent exact-head review and scripted workflow checks do not activate this
candidate. Nick still needs his frontend, an accepted application lifetime/root,
explicit source/document-picker and new-export sink integration, blocked-state UX,
privacy/export review and actual Android interaction/storage acceptance. There is
no vehicle acquisition, actuator/control support, GPT/API/server dependency,
network runtime, emulator, visual CI or UI redesign here. Signing-key and old-Nick
history retention are not release gates; that never authorizes data loss.

## Local evidence

JDK17/SDK35, offline, one Gradle worker, in-process Kotlin, 512MiB heap:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*RetainedInvestigationCandidateTest' --tests '*CombinedInvestigationTest' \
  --tests '*CombinedRecommendationPreviewTest' --tests '*FixturePreviewIntegrationTest' \
  --tests '*CombinedFixtureOwnerTest' --tests '*ManualOutcomeContractTest' \
  --tests '*InvestigationReasoningTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

102 tests passed:20 candidate,13 combined-domain,17 pure-preview,15 fixture
preview-integration,18 fixture-owner,10 manual-contract and9 existing reasoning;
zero failures/errors/skips. Log `/tmp/openwrench-retained-final.log`, standard
app unit XML/HTML reports in the candidate worktree. No aggregate shared/app
suite, lint, APK assembly, emulator, installation or Android interaction/power-loss
check was run. Main/test Kotlin compilation is a dependency of the focused task,
not a runtime/UI/hardware pass. API24 compatibility here is source inspection,
not an actual API24 device test.

Initial new-test development exposed test-only syntax errors and incomplete
invented HARDWARE metadata; those fixtures now contain explicit fictional
session/time fields and preserve unknown ECU attribution. Independent source
review found two P2 issues during implementation: API26 filesystem calls on min24,
and unavailable directory enumeration being treated as absence. Both were fixed:
main candidate uses API24-compatible File operations; failed root/namespace
listing returns STORAGE_BLOCKED, never EMPTY. Regressions cover valid/broken
links, listing failure and original-byte preservation. Exact-head independent
review must confirm the final local commit; tests are implementer-run unless
separately stated. No source evidence authorizes production activation.
