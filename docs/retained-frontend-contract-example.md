# Fictional frontend service reference for Nick

This is a copyable, executable **JVM test reference**, not UI, a coordinator, a production factory or runtime activation. Base: reviewed disabled Android boundary `917a02ce0b44a9ac47dd8ce33da9b5b589442673`. The integration surface is the existing module-internal `RetainedInvestigationService` / `RetainedAction` / `RetainedFrontendState`. No production source changes are needed.

Read [RetainedFrontendContractTest](../kotlin/androidApp/src/test/kotlin/org/openwrench/frontend/android/RetainedFrontendContractTest.kt), especially `fictionalWorkflowUsesActionsAndObservedStatesThroughCorrectionPreviewDetachAndReopen`. It injects an explicitly fictional archive and an isolated new temporary root, constructs the existing owner once, collects `owner.state`, and dispatches actions. It never calls `repository.execute`. The small `send` helper advances the controlled JVM scheduler; it is test scaffolding, not another runtime coordinator or an Android waiting mechanism.

The observed sequence is empty list/open → complaint create → explicit case choices/attachment → reported context, observations, hypotheses and question A → typed inconclusive result → explicit correction of A → second case/question/result B → exact-context preview → detach → new owner/reopen after prior IO completion. All fixture bytes and accounts are invented, cases omit VIN, actual synthetic scan/drive origins stay synthetic, and a click account remains inconclusive for sealing. No fixture is promoted to qualified real measurements or causal certainty.

## Frontend calls and observations

Nick supplies the retained lifetime/root and an explicit source port; the source is not discovered. In the future frontend, collect state in its normal lifecycle-aware observer and handle completion before dispatching dependent operations. Never copy `runCurrent`, the filesystem fault injectors, temporary-root cleanup or the fake source into production. A minimal call shape, with variables obtained from the preceding completed result:

```kotlin
val owner = RetainedInvestigationService(repository, retainedScope)
val states = owner.state
owner.dispatch(RetainedAction.ListNew)
// Later, from user intent:
owner.dispatch(RetainedAction.Create(newId, complaintEvent))
owner.dispatch(RetainedAction.CaseChoices)
// Later, after selecting a Cases choice and receiving the current Opened snapshot:
val attach = RetainedAction.Attach(newId, snapshot.byteToken, chosen.selection, intent)
owner.dispatch(attach)
// Later, append validated ordinary/question/manual/correction events using NEW current tokens.
val action = RetainedAction.Append(newId, snapshot.byteToken, attributedEvent)
owner.dispatch(action)
// Explicit selections, including UNKNOWN reports or UNRESOLVED fields, never guessed defaults:
owner.selectPreview(RetainedPreviewSelection(newId, selectedContext))
owner.dispatch(RetainedAction.Preview(newId, snapshot.byteToken,
    selectedContext.request(RetainedInvestigationCodec.revision(snapshot.document))))
```

These snippets describe separate event-handler invocations, **not** an immediately executable synchronous sequence. Observe ACCEPTED/BUSY/INACTIVE/SELECTION_MISMATCH and `loading`/typed `outcome`. BUSY is not queued. `Opened` may be EMPTY/READY/RECOVERY_REQUIRED/BLOCKED. Preview has a distinct Ready/Stale/Invalid/Unsupported result; opening a valid journal is not a recommendation pass. The service is currently module-internal; these calls are for Nick's code in the existing Android module.

### Drafts and immutable requests

The service does not own editable form drafts. Frontend-owned immutable drafts and their request IDs must survive rejection. Tests demonstrate:

- Nonfinite input produces the existing field-specific issue and preserves the submitted action and saved snapshot. After an explicit field edit, the still-unused request/outcome IDs can be retained. The test asserts NaN with `isNaN` rather than relying on equality of NaN.
- A stale source preserves the attachment intent and chosen digest. A fresh `CaseChoices` read supplies a different archive token, even for whitespace-only replacement. Only explicit reselection changes that selection; this is not an automatic retry.
- A lost acknowledgment after a real temporary-file publish returns IO_OR_ACK_UNKNOWN. Inspect the committed/pending identities and replay the **same immutable action**. Already committed identical content is acknowledged once, despite the older submitted token. Never generate a second result ID to work around uncertainty.
- A stale preview byte token returns STALE_DOCUMENT; changing only its byte token still leaves stale canonical content and returns typed preview Stale. No hidden rebase or append occurs.

### Selection, Back and owner lifetime

Notify every selector change through `selectPreview`, including null on cancel. The selection-change test invalidates delivery after admission but before the scheduled computation executes; it is a deterministic owner-admission check, not a claim of blocked-provider thread cancellation. `detach` removes delivered previews and suppresses late presentation, while admitted durable IO retains ownership. Reopen only after prior IO has completed, then deliberately reselect context. A reopened owner has no selection, so dispatching the old preview is SELECTION_MISMATCH. Screen navigation must not create competing owners over an admitted durable write.

### Recovery and export

The service reference injects a publish failure to leave a valid pending extension. It observes `InspectRecovery`, explicitly exports both exact committed/pending originals through a fresh in-memory sink, verifies their hashes, rejects incorrect observed recovery tokens, and only then recovers with the observed identities. Originals are not re-encoded or silently discarded. A separate failed destination case returns EXPORT_INCOMPLETE and preserves the saved snapshot.

The in-memory export sink models the `OriginalExportSink` contract only. It supplies no Android durability/receipt semantics; generic provider export remains unsupported. Use the reviewed [Android boundary](android-investigation-boundary.md) for later explicit NEW app-private sessions, preserving partial and MATCHED_ACK_UNCONFIRMED receipts. Neither sink authorizes sharing opaque original bytes without a privacy decision.

## Verification boundary

Focused offline JVM tests only, one worker/512MiB and no concurrent heavy work. Each test owns a fresh fictional temporary directory and cleans up only that directory. No real archive, installed history, production root, picker/grant, UI, emulator, physical device, vehicle command, GPT/API or network runtime is accessed. Independent review must inspect the exact final local commit. This reference does not change production activation or publication permissions.

Actual Android IO remains NOT_RUN: both prior API35 images raised guest RAM and were stopped before APK installation. API24, real provider grants/revocation, abrupt power loss and actual UI interaction remain unqualified. Signing-key/old-Nick-history retention are not release gates; no permission to overwrite/delete user data follows.

## Local check evidence

Final focused command from this worktree (JDK17/SDK35, offline, serialized):

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*RetainedFrontendContractTest' --tests '*RetainedInvestigationCandidateTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

28 tests passed:8 new service-contract examples and20 retained-candidate regressions, zero failures/errors/skips. Guarded run2 preflight5018MiB available/minimum3356MiB, no safety stop. Build compiled Android main/test Kotlin as a dependency; no APK, lint or full aggregate suite was run. Test log, XML and resource receipt are preserved under `<checkout>/build/local-qa/retained-frontend-contract-tests-2/`.

Run1 compiled but one new test incorrectly expected OTHER_CASE for a result while an exact different question was selected. Existing policy gives OTHER_QUESTION precedence. The corrected test verifies that focused-question result plus an additional question-unscoped service preview returning OTHER_CASE. No production policy changed. Run1 log/failing XML remain preserved in the sibling `retained-frontend-contract-tests-1` directory; no failed evidence was overwritten. Independent review inspects source/evidence; it is not an independent test rerun.
