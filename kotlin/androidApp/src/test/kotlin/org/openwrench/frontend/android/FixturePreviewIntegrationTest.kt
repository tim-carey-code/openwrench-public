package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FixturePreviewIntegrationTest {
    private fun root(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ow-preview-integration-fictional").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun file(dir: File) = File(dir, "${CombinedFixtureStore.DIRECTORY}/fixture.json")
    private fun pending(dir: File) = File(dir, "${CombinedFixtureStore.DIRECTORY}/fixture.pending")
    private fun seed(dir: File, document: CombinedInvestigation = CombinedInvestigationFixtures.completedCycle()): CombinedSnapshot {
        file(dir).parentFile!!.mkdirs(); file(dir).writeText(CombinedInvestigationCodec.encode(document))
        return assertIs<CombinedState.Ready>(CombinedFixtureStore(dir).execute(CombinedAction.Load)).snapshot
    }
    private fun selected(id: String) = OwnerReportSelection(ReportSelection.SELECTED, id)
    private fun selection(s: CombinedSnapshot) = CombinedPreviewSelection(s.document.initialCases.single().id,
        selected("starting"), selected("timing"), selected("adaptation"), "sealing-first")
    private fun action(s: CombinedSnapshot, selection: CombinedPreviewSelection = selection(s)) =
        CombinedAction.Preview(s.token, selection.request(CombinedRecommendationPreview.revision(s.document)))
    private fun ready(store: CombinedFixtureStore) = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Load))

    @Test fun readOnlyPreviewBindsSubmittedFileTokenContentRevisionAndExplicitSelectors() = root { dir ->
        val s = seed(dir); val bytes = file(dir).readBytes()
        val files = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) { fail("Preview must not stage") }
            override fun sync(file: File) { fail("Preview must not sync") }
            override fun publish(pending: File, committed: File) { fail("Preview must not publish") }
        }
        val request = action(s)
        val result = assertIs<CombinedState.Ready>(CombinedFixtureStore(dir, files).execute(request))
        assertEquals(s, result.snapshot); assertEquals(request, result.preview!!.submitted)
        val p = assertIs<CombinedPreviewResult.Ready>(result.preview.result).report
        assertEquals(request.request, p.request); assertEquals(request.request.expectedRevision, p.revision)
        assertEquals(listOf("correct-a"), p.activeOutcomes.filter { it.use == CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY }.map { it.event.id })
        assertContentEquals(bytes, file(dir).readBytes()); assertFalse(pending(dir).exists())
    }
    @Test fun rawByteTokenAndCanonicalContentRevisionAreIndependentlyChecked() = root { dir ->
        val original = seed(dir); val content = CombinedRecommendationPreview.revision(original.document)
        file(dir).appendText("\n ") // same fictional document, different exact bytes
        val store = CombinedFixtureStore(dir); val s = ready(store).snapshot
        assertNotEquals(original.token, s.token); assertEquals(content, CombinedRecommendationPreview.revision(s.document))
        val bytes = file(dir).readBytes()
        assertIs<CombinedState.Stale>(store.execute(action(s).copy(expectedToken = original.token)))
        val stale = assertIs<CombinedState.Ready>(store.execute(action(s).let { it.copy(request = it.request.copy(expectedRevision = "old-content")) }))
        assertIs<CombinedPreviewResult.Stale>(stale.preview!!.result)
        val valid = assertIs<CombinedState.Ready>(store.execute(action(s)))
        assertIs<CombinedPreviewResult.Ready>(valid.preview!!.result)
        assertContentEquals(bytes, file(dir).readBytes())
    }
    @Test fun unresolvedAndSelectedUnknownRemainExplicitInDeliveredResult() = root { dir ->
        val s = seed(dir); val store = CombinedFixtureStore(dir)
        val unresolved = CombinedPreviewSelection(s.document.initialCases.single().id)
        val result = assertIs<CombinedState.Ready>(store.execute(action(s, unresolved)))
        val p = assertIs<CombinedPreviewResult.Ready>(result.preview!!.result).report
        assertEquals(unresolved, p.request.selection()); assertNull(p.retainedBasis.selectedStartingReport)
        assertEquals(InvestigationQuestionId.CLARIFY_START, p.nextQuestion!!.question.id)
        val selectedResult = assertIs<CombinedState.Ready>(store.execute(action(s)))
        val q = assertIs<CombinedPreviewResult.Ready>(selectedResult.preview!!.result).report
        assertEquals(selected("adaptation"), q.request.adaptationReport)
        assertTrue(q.retainedBasis.adaptationReports.any { it.revisionId == "adaptation" && it.value == AdaptationHistory.UNKNOWN })
        assertNull(q.source.scanEcu); assertNull(q.source.driveEcu)
    }
    @Test fun missingOrWrongSelectionsReturnTypedValidationWithoutChangingStorage() = root { dir ->
        val s = seed(dir); val bytes = file(dir).readBytes(); val store = CombinedFixtureStore(dir)
        for ((field, selection) in listOf("caseId" to selection(s).copy(caseId = "missing"),
            "startingReport" to selection(s).copy(startingReport = selected("manual")),
            "questionRevisionId" to selection(s).copy(questionRevisionId = "starting"))) {
            val result = assertIs<CombinedState.Ready>(store.execute(action(s, selection)))
            assertEquals(field, assertIs<CombinedPreviewResult.Invalid>(result.preview!!.result).issues.single().field)
            assertContentEquals(bytes, file(dir).readBytes()); assertFalse(pending(dir).exists())
        }
    }
    @Test fun customQuestionReturnsExplicitUnsupportedAndDoesNotManufactureAReplacement() = root { dir ->
        val original = CombinedInvestigationFixtures.completedCycle()
        val custom = original.copy(events = original.events.map { event -> when {
            event.id == "sealing-first" -> (event as CombinedEvent.Ordinary).copy(revision = event.revision.copy(
                step = (event.revision.step as InvestigationStep.ProposedTest).copy(question = "Fictional custom unmapped question")))
            event is CombinedEvent.Manual && event.outcome.context.questionRevisionId == "sealing-first" -> event.copy(outcome = event.outcome.copy(context = event.outcome.context.copy(catalog = null)))
            else -> event
        } })
        val s = seed(dir, custom); val result = assertIs<CombinedState.Ready>(CombinedFixtureStore(dir).execute(action(s)))
        assertIs<CombinedPreviewResult.Unsupported>(result.preview!!.result)
    }
    @Test fun absentOrPendingStorageNeverCreatesRecoversOrWritesDuringPreview() = root { dir ->
        val fake = CombinedSnapshot(CombinedInvestigationFixtures.completedCycle(), "absent-token")
        val store = CombinedFixtureStore(dir)
        assertIs<CombinedState.ValidationRejected>(store.execute(action(fake)))
        assertFalse(file(dir).parentFile!!.exists())
        val s = seed(dir); pending(dir).writeText("{partial-fictional")
        val bytes = file(dir).readBytes(); val staged = pending(dir).readBytes()
        assertIs<CombinedState.RecoveryRequired>(store.execute(action(s)))
        assertContentEquals(bytes, file(dir).readBytes()); assertContentEquals(staged, pending(dir).readBytes())
    }
    @Test fun unexpectedPreviewFailureRemainsReadOnlyAndUnavailable() = root { dir ->
        val s = seed(dir); val bytes = file(dir).readBytes()
        val store = CombinedFixtureStore(dir, preview = { _, _ -> throw IOException("Injected preview failure") })
        val result = assertIs<CombinedState.Unavailable>(store.execute(action(s)))
        assertEquals(CombinedUnavailableReason.INVALID_OR_UNREADABLE, result.reason)
        assertContentEquals(bytes, file(dir).readBytes()); assertFalse(pending(dir).exists())
    }
    @Test fun coordinatorRequiresCurrentSelectionAndBusyRepeatedActionsAreNotQueued() = root { dir -> runTest {
        val s = seed(dir); val store = CombinedFixtureStore(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CombinedFixtureCoordinator(store, this, dispatcher)
        assertEquals(CombinedAdmission.SELECTION_MISMATCH, owner.dispatch(action(s)))
        owner.selectPreview(selection(s))
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(action(s)))
        assertIs<CombinedState.Loading>(owner.state.value)
        assertEquals(CombinedAdmission.BUSY, owner.dispatch(action(s)))
        runCurrent()
        val result = assertIs<CombinedState.Ready>(owner.state.value)
        assertIs<CombinedPreviewResult.Ready>(result.preview!!.result)
        owner.selectPreview(selection(s)) // recomposition with identical values must not invalidate
        assertEquals(result, owner.state.value)
        owner.dispatch(CombinedAction.Load)
        assertIs<CombinedState.Loading>(owner.state.value)
        runCurrent(); assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
    } }
    @Test fun selectionChangeDuringAdmittedPreviewDropsDeliveryAndRequiresExplicitRetry() = root { dir -> runTest {
        val s = seed(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CombinedFixtureCoordinator(CombinedFixtureStore(dir), this, dispatcher)
        owner.selectPreview(selection(s)); owner.dispatch(action(s))
        val next = selection(s).copy(caseId = s.document.cases().last().id, questionRevisionId = "sealing-later")
        owner.selectPreview(next)
        assertEquals(CombinedAdmission.BUSY, owner.dispatch(action(s, next)))
        runCurrent(); assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
        assertEquals(CombinedAdmission.SELECTION_MISMATCH, owner.dispatch(action(s)))
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(action(s, next)))
        runCurrent(); assertEquals(next, assertIs<CombinedState.Ready>(owner.state.value).preview!!.submitted.request.selection())
    } }
    @Test fun eachCaseQuestionAndOwnerSelectorInvalidatesPreviouslyDeliveredPreview() = root { dir -> runTest {
        val s = seed(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CombinedFixtureCoordinator(CombinedFixtureStore(dir), this, dispatcher)
        val initial = selection(s)
        val unresolved = OwnerReportSelection(ReportSelection.UNRESOLVED)
        val changes = listOf(initial.copy(caseId = s.document.cases().last().id), initial.copy(questionRevisionId = "sealing-later"),
            initial.copy(startingReport = unresolved), initial.copy(timingReport = unresolved), initial.copy(adaptationReport = unresolved))
        for (selection in changes) {
            owner.selectPreview(initial); owner.dispatch(action(s)); runCurrent()
            assertNotNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
            owner.selectPreview(selection)
            assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
        }
        owner.selectPreview(initial); owner.dispatch(action(s)); runCurrent(); owner.selectPreview(null)
        assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
    } }
    @Test fun selectionChangeInsideReadCannotRaceTheFinalDeliveryCheck() = root { dir -> runTest {
        val s = seed(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var owner: CombinedFixtureCoordinator
        val next = selection(s).copy(timingReport = OwnerReportSelection(ReportSelection.UNRESOLVED))
        val store = CombinedFixtureStore(dir, preview = { document, request ->
            owner.selectPreview(next)
            CombinedRecommendationPreview.preview(document, request)
        })
        owner = CombinedFixtureCoordinator(store, this, dispatcher)
        owner.selectPreview(selection(s)); owner.dispatch(action(s)); runCurrent()
        assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
    } }
    @Test fun detachInsideReadPreventsAnyLatePreviewAndReopenLoadsWithoutIt() = root { dir -> runTest {
        val s = seed(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var owner: CombinedFixtureCoordinator
        val store = CombinedFixtureStore(dir, preview = { document, request -> owner.detach(); CombinedRecommendationPreview.preview(document, request) })
        owner = CombinedFixtureCoordinator(store, this, dispatcher)
        owner.selectPreview(selection(s)); owner.dispatch(action(s)); runCurrent()
        assertIs<CombinedState.Loading>(owner.state.value)
        assertEquals(CombinedAdmission.SELECTION_MISMATCH, owner.dispatch(action(s)))
        owner.dispatch(CombinedAction.Load); runCurrent()
        assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
    } }
    @Test fun canceledReadDoesNotDeliverAndNewOwnerCanReloadUnchangedBytes() = root { dir -> runTest {
        val s = seed(dir); val bytes = file(dir).readBytes(); val dispatcher = StandardTestDispatcher(testScheduler)
        val job = SupervisorJob(); val scope = CoroutineScope(job + dispatcher)
        val owner = CombinedFixtureCoordinator(CombinedFixtureStore(dir), scope, dispatcher)
        owner.selectPreview(selection(s)); owner.dispatch(action(s)); job.cancel(); runCurrent()
        assertIs<CombinedState.Loading>(owner.state.value)
        assertEquals(CombinedAdmission.INACTIVE, owner.dispatch(action(s)))
        val reopened = CombinedFixtureCoordinator(CombinedFixtureStore(dir), this, dispatcher)
        reopened.dispatch(CombinedAction.Load); runCurrent()
        assertNull(assertIs<CombinedState.Ready>(reopened.state.value).preview)
        assertContentEquals(bytes, file(dir).readBytes())
    } }
    @Test fun appendClearsPreviewWhileSelectionChangeDoesNotCancelAdmittedWrite() = root { dir -> runTest {
        val s = seed(dir); val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CombinedFixtureStore(dir); val owner = CombinedFixtureCoordinator(store, this, dispatcher)
        owner.selectPreview(selection(s)); owner.dispatch(action(s)); runCurrent()
        val note = CombinedEvent.Ordinary("request-note", InvestigationRevision("note", 15, 90000, "Fictional", "Later note",
            InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Still an invented account", "Fictional author")))
        owner.dispatch(CombinedAction.Append(s.token, note)); owner.selectPreview(null)
        assertIs<CombinedState.Loading>(owner.state.value)
        runCurrent()
        val result = assertIs<CombinedState.Ready>(owner.state.value)
        assertEquals(15, result.snapshot.document.events.size); assertNull(result.preview)
        assertEquals(15, ready(store).snapshot.document.events.size)
        val oldPreview = assertIs<CombinedState.Stale>(store.execute(action(s)))
        assertEquals(result.snapshot.token, oldPreview.snapshot.token)
    } }
    @Test fun selectionChangeOnAnotherThreadWhileReadIsBlockedCannotDeliverTheOldPreview() = root { dir ->
        val s = seed(dir)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        val store = CombinedFixtureStore(dir, preview = { document, request ->
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            CombinedRecommendationPreview.preview(document, request)
        })
        val owner = CombinedFixtureCoordinator(store, scope, dispatcher)
        try {
            owner.selectPreview(selection(s)); owner.dispatch(action(s))
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val inFlight = job.children.single()
            owner.selectPreview(selection(s).copy(questionRevisionId = "sealing-later"))
            assertEquals(CombinedAdmission.BUSY, owner.dispatch(CombinedAction.Load))
            release.countDown()
            runBlocking { withTimeout(10000) { inFlight.join() } }
            assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
            // Seeing Ready must also mean admission is released, never a stranded BUSY transition.
            assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Load))
            val reload = job.children.toList()
            runBlocking { withTimeout(10000) { reload.forEach { it.join() } } }
            assertNull(assertIs<CombinedState.Ready>(owner.state.value).preview)
        } finally { release.countDown(); job.cancel(); dispatcher.close() }
    }

}
