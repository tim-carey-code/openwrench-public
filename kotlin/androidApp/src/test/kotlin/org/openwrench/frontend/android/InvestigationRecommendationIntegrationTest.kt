package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import org.openwrench.nativecore.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Integration through the actual retained store/coordinator/API, using invented receipts only. */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestigationRecommendationIntegrationTest {
    private fun saved(): SavedInvestigation {
        val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
        val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
        repeat(11) { index ->
            val at = 100 + 3_000L * index
            fun scalar(pid: Int, value: Double, offset: Long) = r.offer(NativeReading(pid, DecodedReading.Scalar(value)), at + offset)
            scalar(12, 900.0, 0); scalar(13, 0.0, 1); scalar(4, 30.0, 2); scalar(5, 90.0, 3)
            r.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at + 4)
            scalar(6, -5.0, 5); scalar(7, -16.0, 6)
        }
        r.stop(36_000)
        val base = caseDriveFixture(); val review = base.review.copy(assessment = r.snapshot())
        val drive = base.copy(review = review, report = "OPENWRENCH live health monitoring\nSYNTHETIC only\n" + review.reportText())
        val scan = caseScanFixture().let { it.copy(capture = it.capture!!.copy(origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        val case = DiagnosticCaseBuilder.create(scan, drive, true, 4_000)
        var record = DiagnosticInvestigation("integration", listOf(InvestigationRevision("complaint", 1, 5_000, "Synthetic owner", "Fictional scenario",
            InvestigationStep.Complaint("Reported 2013 Legacy 3.6R cold-start/-16% scenario; not verified"))))
        fun add(id: String, step: InvestigationStep) { record = record.append(id, 6_000, "Synthetic author", "Attributed fixture", step) }
        add("observed", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Invented manual observation, no hardware", "Synthetic fixture"))
        add("h1", InvestigationStep.Hypothesis("Unwanted vapor delivery could contribute", listOf("observed")))
        add("h2", InvestigationStep.Hypothesis("Input/delivery alternative remains open", listOf("observed")))
        return SavedInvestigation(record, listOf(case))
    }
    @Test fun previewIsExplicitReadOnlyAndPreservesSyntheticNegativeAndMissingBankProvenance() = runTest {
        val dir = Files.createTempDirectory("ow-recommendation-preview").toFile()
        try {
            val s = saved(); val store = InvestigationStore(dir)
            store.create(s.copy(journal = s.journal.copy(revisions = s.journal.revisions.take(1))))
            s.journal.revisions.drop(1).forEach { r -> store.append("integration", store.load("integration").journal.revisions.last().id,
                r.id, r.recordedAtEpochMillis, r.author, r.reason, r.step) }
            val file = File(dir, "investigations-v1/integration.json"); val before = file.readBytes()
            val owner = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            owner.reload("integration"); advanceUntilIdle()
            owner.previewRecommendations("h2", s.cases.single().id); advanceUntilIdle()
            val preview = owner.state.value.recommendations!!
            assertEquals("h2", preview.headRevisionId)
            assertEquals(InvestigationQuestionId.CLARIFY_START, preview.report.nextQuestion.question.id)
            assertEquals(BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE, preview.report.bankRelationship)
            assertTrue(preview.report.correctionEvidence.all { it.origin == DriveEvidenceOrigin.SYNTHETIC })
            assertTrue(preview.report.recommendations.none { it.executable })
            assertContentEquals(before, file.readBytes())
            owner.previewRecommendations("h2", "not-retained"); advanceUntilIdle()
            assertNull(owner.state.value.recommendations); assertNotNull(owner.state.value.error)
            assertContentEquals(before, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    private fun persist(s: SavedInvestigation, store: InvestigationStore) {
        store.create(s.copy(journal = s.journal.copy(revisions = s.journal.revisions.take(1))))
        s.journal.revisions.drop(1).forEach { r -> store.append(s.journal.id, store.load(s.journal.id).journal.revisions.last().id,
            r.id, r.recordedAtEpochMillis, r.author, r.reason, r.step) }
    }
    @Test fun ownerAnswersAdoptedQuestionAndAttributedResultRoundTripWithoutExecutingAnything() = runTest {
        val dir = Files.createTempDirectory("ow-recommendation-flow").toFile()
        try {
            val s = saved(); val store = InvestigationStore(dir); persist(s, store)
            val owner = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler), { 7_000 })
            owner.reload("integration"); advanceUntilIdle()
            fun head() = owner.state.value.opened!!.journal.revisions.last().id
            owner.append(head(), "Owner", "Clarify the reported starting behavior",
                InvestigationQuestionCatalog.startingReport(StartingBehavior.NORMAL_SPEED_LONG_CRANK, "Owner report, not measured")); advanceUntilIdle()
            owner.append(head(), "Owner", "Separate earlier symptom from later running observation",
                InvestigationQuestionCatalog.timingReport(SymptomTiming.DIFFERENT_OCCASIONS, "Owner reports separate occasions")); advanceUntilIdle()
            owner.previewRecommendations(head(), s.cases.single().id); advanceUntilIdle()
            val preview = owner.state.value.recommendations!!
            assertEquals(InvestigationQuestionId.BANK_COVERAGE, preview.report.nextQuestion.question.id)
            assertTrue(preview.report.recommendations.single { it.question.id == InvestigationQuestionId.VAPOR_SEALING }.missingEvidence.any { "OEM procedure" in it })
            owner.adoptRecommendedQuestion(preview.headRevisionId, s.cases.single().id, InvestigationQuestionId.VAPOR_SEALING,
                listOf("h1", "h2"), "Analyst", "Distinguish sealing evidence from a click")
            owner.adoptRecommendedQuestion(preview.headRevisionId, s.cases.single().id, InvestigationQuestionId.VAPOR_SEALING,
                listOf("h1", "h2"), "Analyst", "Double tap")
            advanceUntilIdle()
            assertNull(owner.state.value.recommendations)
            val question = owner.state.value.opened!!.journal.revisions.last()
            val proposed = question.step as InvestigationStep.ProposedTest
            assertEquals(InvestigationQuestionId.VAPOR_SEALING, InvestigationQuestionCatalog.identify(proposed))
            assertEquals(InvestigationTestKind.MANUAL_RESULT_ONLY, proposed.kind)
            assertContains(question.reason, s.cases.single().id); assertContains(question.reason, preview.headRevisionId)
            assertEquals(1, owner.state.value.opened!!.journal.revisions.count { it.step is InvestigationStep.ProposedTest })
            owner.append(head(), "Analyst", "Record uncertainty", InvestigationStep.TestResult(question.id, InvestigationOutcome.INCONCLUSIVE,
                "A reported click does not establish sealing or flow", "Synthetic manual review only", listOf("observed"),
                listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "No sealing/flow proof"))))
            advanceUntilIdle()
            owner.previewRecommendations(head(), s.cases.single().id); advanceUntilIdle()
            val recomputed = owner.state.value.recommendations!!.report
            assertEquals(InvestigationOutcome.INCONCLUSIVE, recomputed.activeResults.single().outcome)
            assertContains(recomputed.recommendations.single { it.question.id == InvestigationQuestionId.VAPOR_SEALING }.why, "INCONCLUSIVE")
            val reopened = store.load("integration")
            assertEquals(owner.state.value.opened, reopened)
            assertTrue(reopened.journal.revisions.filter { it.step is InvestigationStep.Observation && it.id != "observed" }
                .all { (it.step as InvestigationStep.Observation).kind == InvestigationObservationKind.REPORTED })
        } finally { dir.deleteRecursively() }
    }
    @Test fun stalePreviewWrongCaseAndUnpreviewedQuestionsCannotWrite() = runTest {
        val dir = Files.createTempDirectory("ow-recommendation-stale").toFile()
        try {
            val s = saved(); val store = InvestigationStore(dir); persist(s, store)
            val owner = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler), { 7_000 })
            owner.reload("integration"); advanceUntilIdle()
            owner.adoptRecommendedQuestion("h2", s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"), "Author", "No preview")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(4, store.load("integration").journal.revisions.size)
            owner.previewRecommendations("h2", s.cases.single().id); advanceUntilIdle()
            owner.adoptRecommendedQuestion("h2", s.cases.single().id, InvestigationQuestionId.CRANK_SCOPE, listOf("h1", "h2"), "Author", "Question absent from this preview")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(4, store.load("integration").journal.revisions.size)
            owner.adoptRecommendedQuestion("h2", s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1"), "Author", "Missing competitor")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(4, store.load("integration").journal.revisions.size)
            owner.adoptRecommendedQuestion("h2", s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"), "Author", " ")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(4, store.load("integration").journal.revisions.size)
            owner.adoptRecommendedQuestion("h2", "another-case", InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"), "Author", "Wrong case")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(4, store.load("integration").journal.revisions.size)
            store.append("integration", "h2", "external", 7_000, "Other author", "Newer revision", InvestigationStep.Complaint("Additional report"))
            owner.adoptRecommendedQuestion("h2", s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"), "Author", "Stale preview")
            advanceUntilIdle(); assertNotNull(owner.state.value.error); assertEquals(5, store.load("integration").journal.revisions.size)
            owner.previewRecommendations("h2", s.cases.single().id); advanceUntilIdle()
            assertNull(owner.state.value.recommendations); assertContains(owner.state.value.error!!, "changed")
        } finally { dir.deleteRecursively() }
    }
    @Test fun leavingDuringPreviewCancelsDeliveryAndPreservesEveryHistoryByte() = runTest {
        val dir = Files.createTempDirectory("ow-preview-cancel").toFile()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        try {
            val s = saved(); val store = InvestigationStore(dir); persist(s, store)
            val file = File(dir, "investigations-v1/integration.json"); val before = file.readBytes()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val pending = mutableListOf<Runnable>()
            var pause = false
            val io = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    if (pause) pending += block else dispatcher.dispatch(context, block)
                }
            }
            val owner = InvestigationCoordinator(store, DiagnosticCaseStore(dir), scope, io)
            owner.reload("integration"); advanceUntilIdle()
            pause = true
            owner.previewRecommendations("h2", s.cases.single().id)
            runCurrent(); assertTrue(owner.state.value.busy); assertEquals(1, pending.size)
            scope.cancel() // Models page disposal; this is not an Android Back interaction test.
            pending.removeAt(0).run(); advanceUntilIdle()
            assertNull(owner.state.value.recommendations)
            assertContentEquals(before, file.readBytes())
            assertEquals(s, store.load("integration"))
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun conflictingReportsRemainAvailableAndExplicitPreviewSelectionDoesNotMutateThem() = runTest {
        val dir = Files.createTempDirectory("ow-recommendation-reports").toFile()
        try {
            var s = saved()
            s = s.copy(journal = s.journal.append("r1", 7_000, "Owner", "First account", InvestigationQuestionCatalog.startingReport(StartingBehavior.NORMAL_SPEED_LONG_CRANK, "First owner account"))
                .append("r2", 8_000, "Owner", "Different account", InvestigationQuestionCatalog.startingReport(StartingBehavior.STARTS_THEN_STALLS, "Conflicting owner account"))
                .append("t1", 9_000, "Owner", "First timing account", InvestigationQuestionCatalog.timingReport(SymptomTiming.SAME_OCCASION_REPORTED, "First unverified timing account"))
                .append("t2", 10_000, "Owner", "Different timing account", InvestigationQuestionCatalog.timingReport(SymptomTiming.DIFFERENT_OCCASIONS, "Conflicting timing account")))
            val store = InvestigationStore(dir); persist(s, store)
            val owner = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            owner.reload("integration"); advanceUntilIdle()
            owner.previewRecommendations("t2", s.cases.single().id); advanceUntilIdle()
            assertNull(owner.state.value.recommendations!!.report.selectedStartingReport)
            owner.previewRecommendations("t2", s.cases.single().id, startingReportId = "r1", timingReportId = "t1"); advanceUntilIdle()
            val report = owner.state.value.recommendations!!.report
            assertEquals("r1", report.selectedStartingReport!!.revisionId)
            assertEquals(2, report.startingReports.size)
            assertEquals(s, store.load("integration"))
            owner.adoptRecommendedQuestion("t2", s.cases.single().id, InvestigationQuestionId.BANK_COVERAGE, listOf("h1", "h2"),
                "Analyst", "Record question using explicitly selected owner reports")
            advanceUntilIdle()
            val reopened = InvestigationStore(dir).load("integration")
            assertContains(reopened.journal.revisions.last().reason, "selected starting report r1;")
            assertContains(reopened.journal.revisions.last().reason, "selected timing report t1.")
            assertEquals(s.journal.revisions, reopened.journal.revisions.dropLast(1))
            val head = reopened.journal.revisions.last().id
            owner.previewRecommendations(head, s.cases.single().id); advanceUntilIdle()
            assertNull(owner.state.value.recommendations!!.report.selectedStartingReport)
            assertNull(owner.state.value.recommendations!!.report.selectedTimingReport)
            owner.adoptRecommendedQuestion(head, s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"),
                "Analyst", "Record unresolved report context explicitly")
            advanceUntilIdle()
            val unresolved = InvestigationStore(dir).load("integration").journal.revisions.last()
            assertContains(unresolved.reason, "selected starting report none/unknown-or-conflicting;")
            assertContains(unresolved.reason, "selected timing report none/unknown-or-conflicting.")
            owner.append(unresolved.id, "Owner", "Starting behavior explicitly unknown",
                InvestigationQuestionCatalog.startingReport(StartingBehavior.UNKNOWN, "Owner does not know cranking behavior"))
            advanceUntilIdle()
            val unknownStarting = owner.state.value.opened!!.journal.revisions.last().id
            owner.append(unknownStarting, "Owner", "Timing explicitly unknown",
                InvestigationQuestionCatalog.timingReport(SymptomTiming.UNKNOWN, "Owner does not know event relationship"))
            advanceUntilIdle()
            val unknownTiming = owner.state.value.opened!!.journal.revisions.last().id
            owner.previewRecommendations(unknownTiming, s.cases.single().id, unknownStarting, unknownTiming); advanceUntilIdle()
            val unknownPreview = owner.state.value.recommendations!!
            assertNull(unknownPreview.report.selectedStartingReport)
            assertNull(unknownPreview.report.selectedTimingReport)
            assertEquals(unknownStarting, unknownPreview.requestedStartingReportId)
            assertEquals(unknownTiming, unknownPreview.requestedTimingReportId)
            owner.adoptRecommendedQuestion(unknownTiming, s.cases.single().id, InvestigationQuestionId.CLARIFY_START, listOf("h1", "h2"),
                "Analyst", "Retain provenance of explicit unknown answers")
            advanceUntilIdle()
            val unknownAudit = InvestigationStore(dir).load("integration").journal.revisions.last().reason
            assertContains(unknownAudit, "selected starting report $unknownStarting;")
            assertContains(unknownAudit, "selected timing report $unknownTiming.")
        } finally { dir.deleteRecursively() }
    }
}
