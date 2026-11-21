# Pure combined recommendation preview

Local-only slice based on reviewed draft #133
`9581705264e82483a543c93031a3c082baec6b7c`. The new API is internal on-device Kotlin
for Nick's frontend integration. No existing PR branch, runtime owner, store,
production factory or UI is activated. Publication is not authorized for this
slice. Canonical #129/#132 and separate #131 remain stack dependencies; #127 is
superseded, #124 is separate/not included, and Nick's branches are untouched.

## Callable contract

```kotlin
val document = ready.snapshot.document // from CombinedState.Ready, fixture API
val revision = CombinedRecommendationPreview.revision(document)
val request = CombinedPreviewRequest(
    expectedRevision = revision,
    caseId = selectedCaseId,
    startingReport = OwnerReportSelection(ReportSelection.SELECTED, startingId),
    timingReport = OwnerReportSelection(ReportSelection.SELECTED, timingId),
    adaptationReport = OwnerReportSelection(ReportSelection.SELECTED, adaptationId),
    questionRevisionId = selectedQuestionId, // optional; limits outcome consumption
)
val result = CombinedRecommendationPreview.preview(document, request)
// Ready.report: recommendations + exact active outcome traces and retained basis.
// Stale: preserve draft/selection and re-evaluate; never silently rebase.
// Invalid: stable field issues, no recommendations derived.
// Unsupported: exact custom/actuator question cannot safely map to this catalog.
```

Call off the UI thread: archive/receipt validation and canonical encoding are
bounded but not free. The API is pure: no clock, random IDs, file access, network,
mutation, commands or hidden fallback. The `revision` is SHA256 of the canonical
validated combined document, **not** the fixture store's raw-file byte token.
Whitespace differences in stored JSON can change its byte token without changing
this content revision. Keep both contracts distinct. Every Ready report includes
this content revision, exact request selectors and preview `policyVersion=1`.
Any input content change yields Stale for an old request.

Unresolved owner selections mean unresolved; they never select the latest answer.
Explicitly selected UNKNOWN answers retain their exact revision IDs in `request`
and their reported values/provenance in the retained basis. They remain unknown,
not a negative answer or a fallback to a prior report. The old standalone
`InvestigationReasoning.recommend` preserves its existing default inference;
only the new internal explicit-context path disables that inference.

The optional question focus filters which active outcomes may be consumed, not
the prerequisite ordering of the general evidence questions. Two questions with
the same catalog text are still different exact questions. Custom/actuator focus
returns Unsupported, rather than inventing a mapping or executable capability.

## Evidence and outcome separation

The entire original combined log validates first, including chronological case,
question, effect and correction references. A **read-only context projection**
passes ordinary complaint/observation/hypothesis/question/repair records to the
existing receipt-based recommender. All legacy result payloads are omitted there,
so a superseded legacy assertion cannot reappear as active. Sequence normalization
is only for that legacy validator/API; no projected prefix is saved or returned,
and no typed value/result is converted into a legacy measured observation.

Only the selected retained case supplies the correction basis. Existing archive
and receipt qualification, bank comparability and catalog applicability rules
remain authoritative. No new thresholds or evidence reconstruction exist.
Source origin and both ECU addresses are explicit; null means unknown. An
attachment's separate vehicle assertion (including UNKNOWN) and event ID are
retained; attachment does not establish a shared vehicle, event or conditions.
Combined validation currently accepts synthetic cases/outcomes only; unsupported
origin/absent capture fails closed, never silently becomes another source.

The global active correction map is authoritative. Active traces retain their
actual ordinary or typed event, exact question ID, catalog recognition, scope
assessment and one explicit use status:

| Use | Meaning |
| --- | --- |
| SCOPED_ATTRIBUTED_ONLY | Exact case, optional focused question, owner starting/timing/adaptation references and catalog-bound sealing/flow scope match; still a fictional author assertion |
| OTHER_CASE | No cross-case identity/context inference; not consumed |
| OTHER_QUESTION | Same wording/catalog is not the same question; not consumed |
| OTHER_REPORT_CONTEXT | Different/unresolved report references; not consumed |
| UNSUPPORTED_QUESTION | Edited/custom or missing supported catalog binding; not consumed |
| LEGACY_CONTEXT_UNAVAILABLE | Legacy result lacks typed case/owner-answer scope; preserved but not consumed |

All active traces stay visible, including unmapped results. Superseded result IDs
are listed in original event order; their full payloads and correction chain stay
in the input immutable history at the reported revision. Superseded accounts are
never resurrected when the selected case changes. Preview is not a history store.

Recognized in-scope typed accounts annotate only their associated catalog question
with exact result/question IDs, declaration, method and method/context gaps. When
the existing qualified running-correction path reaches input/delivery questions,
they also motivate RESULT_SCOPE before those technical questions. Starting/timing,
qualification and bank prerequisite ordering is preserved. With no qualified
negative correction, manual -16% or an asserted supporting effect cannot unlock
that path. With no safely mapped result, no result-based recommendation is added.

Known UNKNOWN, unavailable, inconclusive and inapplicable declarations remain
literal outcomes and scope gaps. Author effects remain on the raw attributed
outcome; they do not change measured evidence, rank causes or close hypotheses.
Clicks, acknowledgments, continuity and pressure decay are not sealing/flow proof.
Even normal/abnormal assertions cannot establish a complete causal explanation,
intermittent absence, symptom resolution or repair. OEM procedure/applicability
prerequisites, sources and limitations remain on the existing catalog questions.

## Integration limits and verification

This API remains a pure consumer. The local [fixture owner integration](fixture-preview-integration.md)
now delegates a fully bound Preview action to it with asynchronous delivery and
selection invalidation. Nick may also call the pure API from a validated Ready
document and retain its result against the exact content revision. Neither path
activates production storage/root, recovery/export or frontend behavior. Those
remain separate decisions. No server is needed.

Focused fictional tests cover interleaving/corrections, question and case scope,
context mismatch and explicit UNKNOWN, retained Bank1/unavailable Bank2, missing
correction evidence despite manual values/effects, unsupported question/binding,
unscoped legacy result, invalid references/source/capture, determinism and stale
revisions. Legacy reasoning tests protect existing default behavior and receipt
qualification. No new visual design, emulator, APK installation, physical data,
actuator/GPT/runtime-network integration or visual CI is involved.

Local verification (JDK17/SDK35, offline, serialized one worker, 512 MiB heap):

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$ANDROID_HOME \
./kotlin/gradlew -p kotlin :androidApp:testDebugUnitTest \
  --tests '*CombinedRecommendationPreviewTest' --tests '*InvestigationReasoningTest' \
  --tests '*CombinedInvestigationTest' --tests '*CombinedFixtureOwnerTest' \
  --tests '*SyntheticInvestigationPilotTest' \
  -PopenwrenchUnsigned=true -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs=-Xmx512m -Dorg.gradle.workers.max=1 \
  --no-daemon --offline --console=plain
```

72 tests passed: 17 new preview, 9 existing reasoning, 13 combined-domain,
18 fixture-owner and 15 fixed-pilot; zero failures/errors/skips. Initial focused
preview/reasoning/domain run also passed. Final log:
`/tmp/openwrench-combined-preview-final.log`; standard app test XML/HTML reports
are in this task worktree. Android main/test Kotlin compiled as dependencies of
this test task; no APK was assembled. Full shared/app aggregate suites, lint,
APK assembly, runtime/UI and power-loss checks were not run for this lightweight
pure-domain slice. Base PR CI does not cover new local changes. Exact-head
independent source review is required; implementer tests are not an independent
rerun or physical qualification.
