# Combined investigation contract for frontend integration

Local follow-on: [pure combined recommendation preview](combined-recommendation-preview.md)
can consume a validated Ready document without activating the store Preview action.

This is an **internal, fixture-only on-device Kotlin API**. It is local work on top
of reconciled pilot #132 `1e25dcbb9aed97a3478fd84eaad383a4582b56ab`, including
canonical workspace #129 and separate dormant storage #131. Original #127 is
superseded; guided assessment #124 is not included. No existing PR branch is
rewritten. Publication of this new slice is not authorized.

## What Nick can call

`CombinedInvestigation` contains initial immutable synthetic case copies and a
single ordered event list. `CombinedEvent.Ordinary` retains the existing
`InvestigationRevision`; `Manual` retains `AttributedManualOutcome`; `Attachment`
records a deliberately copied case with author, reason, provenance and an explicit
owner vehicle assertion (including UNKNOWN). Event IDs, request IDs and global
sequences are unique. Cases cannot be replaced. All references resolve against
prior events/cases. The overall ceiling is 500 events, ten retained cases and 25 MB
per encoded snapshot. One isolated root holds one fixture document, not a history
browser or a general storage factory.

The deterministic `CombinedInvestigationFixtures` packet exposes:

- `partialBankSeed()`: explicitly fictional complaint and retained synthetic Bank1
  interval; no qualified Bank2 evidence, no verified cold-event attribution.
- `reportedOnlySeed()`: no qualified drive assessment; the complaint remains an
  account, not a measured finding. Unknown ECU attribution stays null/visible.
- `questionAPrefix()`, `cycleEvents()`, `completedCycle()`: golden request/record IDs
  and times, question A, result A, later question B, deliberate case B attachment,
  result B and explicit correction of A. Same-vehicle/event compatibility between
  case copies remains UNKNOWN; the fixture is not a two-bank comparison.
- `nextCycleAction(snapshot)`: deterministic caller-retained action for scripted
  frontend integration. It rejects a nonmatching prefix rather than rebasing it.
- `coordinator(fixtureRoot, scope, io)`: explicit fixture root/lifetime only. There
  is no MainActivity/application factory or UI activation.

The API is internal pending contract review. No server, network, vehicle transport,
GPT service or physical data is required. Nick can use these calls from frontend
code in the Android module without inventing a second diagnosis store:

```kotlin
// fixtureRoot must be a fresh, explicitly chosen synthetic test directory.
val owner = CombinedInvestigationFixtures.coordinator(fixtureRoot, retainedScope)
val states = owner.state // StateFlow<CombinedState>
owner.dispatch(CombinedAction.Load)
// Wait for Empty; creation is deliberate, never a load fallback:
val create = CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())
owner.dispatch(create)
// Once Ready, preserve the token and exact action while editing/retrying:
val snapshot = (states.value as CombinedState.Ready).snapshot
val action = CombinedInvestigationFixtures.nextCycleAction(snapshot)
owner.dispatch(action)
// A lost acknowledgment: Load, inspect, then retry THIS same action.
// A Stale response: preserve the draft; explicitly re-evaluate against current.
```

`CombinedAdmission` distinguishes ACCEPTED, BUSY and INACTIVE synchronously; an
admitted write owns IO until completion even after `detach()`. Detach invalidates
presentation delivery only. On return, call Load after admission completes. The
scope must be retained for the desired owner lifetime. If it is canceled, construct
a new owner and Load; no canceled presentation reports a false successful write.
An ATOMIC coroutine start enters the cleanup path even if cancellation races
admission. Writes use NonCancellable IO, reads/previews remain cancelable. One
process-wide store lock serializes recreated owners too.

## Stable states and actions

| State | Frontend meaning |
| --- | --- |
| Loading(previous snapshot?) | An operation was admitted; retain draft/context; do not submit a second mutation |
| Empty | No committed or pending fixture exists; no automatic creation |
| Ready(snapshot, replayedRequest) | Validated exact token, ordered history, question references, active results and explicit fictional/source notices |
| ValidationRejected(snapshot?, submitted, issues) | Preserve submitted draft; use stable issue code/field plus readable message; no new bytes staged |
| Stale(expectedToken, snapshot, submitted) | Preserve draft; current full-byte token differs; no automatic retry/rebase |
| RecoveryRequired(snapshot?, pending) | Prior and staged bytes retained; explicit matching observed-digest recovery only; BLOCKED is never normal/empty |
| Unavailable(reason, preserved, detail, unknownField/value) | Unsupported/corrupt/unreadable, uncertain write acknowledgment or deliberately unimplemented capability; never fallback to synthetic success |

Snapshot exposes exact history, `questions`, `activeResults` and `sources`; null
source addresses mean unknown, never inferred common identity. Unknown future
schema/event kinds/attachment assertion values retain their raw value in explicit
unavailable or blocked-pending state. Unknown values in the reused strict nested
codecs fail closed with their diagnostic detail and preserved original bytes;
they are not dropped. Future callers must show the unknown/unavailable state.

Actions are Load, Create(seed), Append(expectedToken, immutable event),
Recover(observedCommittedDigest?, observedPendingDigest), and a read-only
head/case-bound Preview. The local [fixture preview integration](fixture-preview-integration.md)
now supplies explicit selectors/content revision and a typed pure-preview delivery.
Storage Ready does not by itself imply preview success. Existing legacy reasoning
and the fixed pilot preview remain separate APIs; typed values are never projected
into their measured observations.

## Validation and persistence invariants

A fresh `openwrench.combined-investigation-fixture/1` envelope and
`combined-investigation-fixture-v1/fixture.json` namespace are isolated from all
installed histories, schema1 files, #131 frozen-tail storage and #132 pilot data.
Only explicitly SYNTHETIC scan/drive case copies and typed outcomes are accepted.
All cases must omit VIN. No import/migration/delete/discard API exists.

At each chronological prefix, ordinary revisions are checked with existing
`DiagnosticInvestigation`/`SavedInvestigation` validators; manual outcomes reuse
`ManualOutcomeContract` validators. Internal **validation-only** projections
normalize sequence numbers for those old validators. No projection is persisted,
returned as a rewritten legacy prefix, used for recommendations or turned into
new measured observations. Global event sequences and original context/author/
source/result/correction payloads remain unchanged. Two codec row-helper pairs were
made internal for reuse; original schema1 and typed-tail envelope shapes remain
unchanged and have exact-byte regression coverage. The same manual reference
validator now supplies stable nested field paths to both old and combined
envelopes; unit/detail/selection checks delegate to the existing value validators.

An explicit typed correction can supersede an active legacy or typed result for
that same question. Once a question has an active typed result, a legacy result
cannot convert it back; correct it with a typed outcome. Other questions remain
independent. Reference checks do not qualify absent/invalid/short evidence anew;
the existing case/receipt validators remain authoritative. Unknown/inconclusive/
unavailable outcomes cannot support or weaken hypotheses. Click, acknowledgment,
continuity and pressure decay are not sealing/flow proof. No normal result proves
repair, symptom resolution or absence of intermittent behavior; manual values do
not become qualified OBD intervals. The original methods/scope assessments remain
available on each typed outcome.

Full-byte SHA256 tokens guard optimistic writes. Exact repeated request payloads
return the committed result even with their old token, handling a lost
acknowledgment without another event. Reusing a request ID with different content
is rejected. Create is likewise retryable only for the same seed/prefix. A stale
new action retains its draft and current snapshot.

Publication uses exclusive pending creation, staged flush/fsync and same-directory
rename. Any uncertain or partial stage is preserved and blocks another write.
Explicit recovery requires both observed digests and a fully validated seed,
duplicate or single-event extension with the exact prior prefix/cases. Recovery
re-syncs the pending file. Invalid, partial, future or diverged stages remain
blocked; there is no automatic cleanup. Rename followed by a lost acknowledgment
returns WRITE_OUTCOME_UNKNOWN; a reload/same-request retry discovers the actual
commit. Bounds apply to the actual read and UTF8 must round-trip without loss.
Maximum disk content is a committed+pending 25 MB copy each for one fixture root;
unknown extra files block writes without removal.

## Evidence and remaining activation work

Focused JVM tests cover the interleaved cycle/reopen, exact prior payloads,
reference/correction rejection, nonfinite/manual scope errors, idempotence/stale
writes, canceled delivery, preserved pending seed/append/partial/diverged/future
states, fsync/publication failures and lost acknowledgments. They use disposable
fictional directories and injected file seams only. They do **not** establish
Android filesystem/power-loss durability, physical hardware qualification, actual
frontend interactions or a UI acceptance pass. No new UI, emulator, installation,
visual CI, aggregate build or production activation is part of this slice.

Next bounded work after contract review: accept the combined owner authority and
frontend draft/action lifetime; add pure combined recommendation consumption that
keeps retained measured evidence separate from active attributed manual assertions,
with exact head/case/owner-answer binding. Then provide an explicitly authorized
production root/factory, recovery/export disposition and frontend integration in
Nick's design. No signing-key or old-Nick-history retention gate is introduced;
this grants no permission to delete or overwrite actual user data.

Local verification (JDK17/SDK35, offline, serialized one worker, 512 MiB heap):

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*CombinedInvestigationTest' --tests '*CombinedFixtureOwnerTest' \
  --tests '*ManualOutcomeContractTest' --tests '*InvestigationStoreTest' \
  --tests '*ManualOutcomeSandboxStoreTest' --tests '*SyntheticInvestigationPilotTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

79 tests passed after review fixes: 13 combined-domain, 18 fixture-owner, 8 legacy
store, 10 typed contract, 15 dormant sandbox and 15 fixed pilot; zero
failures/errors/skips. Log: `/tmp/openwrench-combined-review-fixes.log`. Earlier
initial/final-before-review focused runs also passed (76 in the latter). This compiles Android main/test Kotlin but does not assemble an APK.
Full shared/app aggregate checks, lint, APK assembly and runtime/UI checks were
not run: the assigned slice calls for lightweight serialized fixture verification.
Published dependency CI passes do not cover this new local implementation.
Independent review must inspect the exact resulting local head before readiness.


Independent review of the first implementation `f6ae437` found two P2 issues:
post-publication validation-shaped exceptions could incorrectly yield rejection,
and manual unit/context errors had overly broad field attribution. Fixes track
whether durable IO has started, independent of exception type; after that point
errors yield preserved pending recovery or WRITE_OUTCOME_UNKNOWN, including
IllegalArgumentException/CombinedValidation during create, append and recovery.
Regressions assert reload and same-request replay find one committed event.
Shared manual-reference validation now attributes exact question/case/owner-answer
fields; unit validation reports the specific reportedUnit path and actionable
message. Missing and extra labels, wrong/missing report references and unresolved
selection contradictions have regression coverage. These changes require exact
final-head independent re-review; the original review is not approval of fixes.
