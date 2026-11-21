package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import org.openwrench.frontend.InvestigationPresentation
import org.openwrench.nativecore.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Fictional values and scripted state only. Produces sanitized emulator input under build/reports. */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestigationWorkspaceTest {
    private fun syntheticCase(): DiagnosticCase {
        val scan = caseScanFixture().let { it.copy(capture = it.capture!!.copy(origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
        val recorder = DriveHealthAssessmentRecorder(0, inputs, inputs)
        repeat(11) { index ->
            val at = 100 + index * 3_000L
            fun offer(pid: Int, value: Double, offset: Long) = recorder.offer(NativeReading(pid, DecodedReading.Scalar(value)), at + offset)
            offer(12, 900.0, 0); offer(13, 0.0, 1); offer(4, 30.0, 2); offer(5, 90.0, 3)
            recorder.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at + 4)
            offer(6, -3.0, 5); offer(7, -16.0, 6)
        }
        recorder.stop(36_000)
        val base = caseDriveFixture()
        val review = base.review.copy(assessment = recorder.snapshot())
        val drive = base.copy(review = review, report = "OPENWRENCH live health monitoring\nSYNTHETIC fictional evidence\n" + review.reportText())
        return DiagnosticCaseBuilder.create(scan, drive, true, 4_000)
    }
    private fun initial(c: DiagnosticCase) = SavedInvestigation(DiagnosticInvestigation("synthetic-investigation", listOf(
        InvestigationRevision("complaint", 1, 5_000, "Synthetic reporter", "Fictional scenario, not Nick's capture",
            InvestigationStep.Complaint("SYNTHETIC: reported 2013 Legacy 3.6R cold-start complaint and roughly -16% corrections; not a verified vehicle diagnosis.")))), listOf(c))

    @Test fun measuredChoicesPreserveNegativeValuesActualBankAndSourceWithoutInventingMissingBank() {
        val saved = initial(syntheticCase())
        val choices = InvestigationPresentation.choices(saved)
        assertTrue(choices.any { it.label.contains("Bank 1") && it.label.contains("-16.0") })
        assertFalse(choices.any { it.label.contains("Bank 2") })
        assertTrue(choices.all { "SYNTHETIC" in it.provenance && "Historical" in it.provenance })
        val c = saved.cases.single()
        assertFalse(choices.any { it.reference.pid == 12 }) // Missing scan reading is not offered.
        assertTrue(choices.filter { it.reference.pid != null }.all { it.reference.pid in c.scan.capture!!.eligiblePids })
        choices.forEach { choice -> saved.copy(journal = saved.journal.append(UUID.randomUUID().toString(), 6_000,
            "Analyst", "Retain exact evidence", choice.observation())).validate() }
    }
    @Test fun humanProseIsPreservedWhileReferenceIdsReceiveReadableLabels() {
        val hypothesis = InvestigationStep.Hypothesis("A click is not sealing evidence", listOf("click"))
        val detail = InvestigationPresentation.detail(hypothesis) { "#4" }
        assertContains(detail, "A click is not sealing evidence")
        assertContains(detail, "Observation references: #4")
        val unsupported = InvestigationStep.ProposedTest(InvestigationTestKind.ACTUATOR_UNSUPPORTED,
            "Unsupported proposal", listOf("h1", "h2"), "No operation available")
        assertContains(InvestigationPresentation.title(unsupported), "actuator unsupported")
    }
    @Test fun resultsIdentifyTheirExactQuestionInBothActiveAndFullHistoryViews() {
        var saved = initial(syntheticCase())
        fun add(id: String, step: InvestigationStep) { saved = saved.copy(journal = saved.journal.append(id, 6_000,
            "Synthetic analyst", "Question attribution regression", step)) }
        add("observation", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Reported symptoms", "Synthetic unverified report"))
        add("h1", InvestigationStep.Hypothesis("First unproven explanation", listOf("observation")))
        add("h2", InvestigationStep.Hypothesis("Competing unproven explanation", listOf("observation")))
        add("q1", InvestigationStep.ProposedTest(InvestigationTestKind.REVIEW_RETAINED_EVIDENCE, "First distinguishing question",
            listOf("h1", "h2"), "First outcome distinction"))
        add("q2", InvestigationStep.ProposedTest(InvestigationTestKind.REVIEW_RETAINED_EVIDENCE, "Second distinguishing question",
            listOf("h1", "h2"), "Different outcome distinction"))
        fun result(question: String, supersedes: String? = null) = InvestigationStep.TestResult(question,
            InvestigationOutcome.INCONCLUSIVE, "Identical result wording", "Synthetic author review", listOf("observation"),
            listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "Evidence is inconclusive")), supersedes)
        add("r1", result("q1")); add("r2", result("q2")); add("r1-corrected", result("q1", "r1"))
        val questionReferences = saved.journal.revisions.filter { it.step is InvestigationStep.ProposedTest }
            .associate { it.id to "#${it.sequence}" }
        for (fullHistory in listOf(false, true)) {
            val rows = InvestigationPresentation.revisions(saved, fullHistory)
            val results = rows.filter { it.step is InvestigationStep.TestResult }
            assertEquals(if (fullHistory) 3 else 2, results.size)
            assertEquals(setOf("q1", "q2"), results.map { (it.step as InvestigationStep.TestResult).testId }.toSet())
            results.forEach { revision ->
                val step = revision.step as InvestigationStep.TestResult
                val rendered = InvestigationPresentation.detail(step) { id ->
                    saved.journal.revisions.single { it.id == id }.let { "#${it.sequence}" }
                }
                assertContains(rendered, "Question answered: ${questionReferences.getValue(step.testId)}")
                val otherQuestion = if (step.testId == "q1") "q2" else "q1"
                assertFalse(rendered.contains("Question answered: ${questionReferences.getValue(otherQuestion)}"))
            }
        }
    }
    @Test fun createAppendReopenAndCorrectionUseDurableAttributedEvidence() = runTest {
        val dir = Files.createTempDirectory("ow-workspace").toFile()
        try {
            val c = syntheticCase(); DiagnosticCaseStore(dir).add(c)
            val store = InvestigationStore(dir)
            val coordinator = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler), { 6_000 })
            coordinator.reload(); advanceUntilIdle(); assertEquals(1, coordinator.state.value.cases.size)
            coordinator.create(c.id, "Reported cold-start complaint", "Synthetic reporter")
            advanceUntilIdle()
            val created = coordinator.state.value.opened!!
            coordinator.append(created.journal.revisions.last().id, "Analyst", "Attach actual retained interval",
                InvestigationPresentation.choices(created).first { it.reference.kind == InvestigationObservationKind.CASE_FUEL_INTERVAL }.observation())
            advanceUntilIdle()
            val appended = coordinator.state.value.opened!!
            assertEquals(2, appended.journal.revisions.size)
            DiagnosticCaseStore(dir).delete(c.id)
            val recreated = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            recreated.reload(appended.journal.id); advanceUntilIdle()
            assertEquals(appended, recreated.state.value.opened)
            assertEquals(1, recreated.state.value.listings.size)
            assertTrue(recreated.state.value.cases.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun doubleSubmissionAndStaleExternalWriterDoNotDuplicateOrEraseHistory() = runTest {
        val dir = Files.createTempDirectory("ow-workspace-races").toFile()
        try {
            val c = syntheticCase(); DiagnosticCaseStore(dir).add(c)
            val store = InvestigationStore(dir); store.create(initial(c))
            val coordinator = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler), { 6_000 })
            coordinator.reload("synthetic-investigation"); advanceUntilIdle()
            val step = InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Both banks alleged", "Unverified synthetic narrative")
            coordinator.append("complaint", "Analyst", "New reported detail", step)
            coordinator.append("complaint", "Analyst", "Double click", step)
            advanceUntilIdle()
            assertEquals(2, store.load("synthetic-investigation").journal.revisions.size)
            val head = coordinator.state.value.opened!!.journal.revisions.last().id
            store.append("synthetic-investigation", head, "external", 7_000, "Another author", "Independent newer revision", step)
            coordinator.append(head, "Analyst", "Stale view", step); advanceUntilIdle()
            assertContains(coordinator.state.value.error!!, "changed")
            assertEquals(3, store.load("synthetic-investigation").journal.revisions.size)
            coordinator.reload("synthetic-investigation"); advanceUntilIdle()
            assertEquals("external", coordinator.state.value.opened!!.journal.revisions.last().id)
        } finally { dir.deleteRecursively() }
    }
    @Test fun recreatedCreationTokenReopensUncertainCompletionWithoutDuplicateOrRequiringSourceCase() = runTest {
        val dir = Files.createTempDirectory("ow-workspace-create-token").toFile()
        try {
            val c = syntheticCase(); DiagnosticCaseStore(dir).add(c)
            val store = InvestigationStore(dir)
            val first = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler), { 6_000 })
            first.create(c.id, "Synthetic complaint", "Reporter", "stable-creation-token"); advanceUntilIdle()
            DiagnosticCaseStore(dir).delete(c.id)
            val recreated = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            recreated.create(c.id, "Synthetic complaint", "Reporter", "stable-creation-token"); advanceUntilIdle()
            assertEquals(first.state.value.opened, recreated.state.value.opened)
            assertEquals(1, store.list().size)
            recreated.create(c.id, "Changed complaint", "Reporter", "stable-creation-token"); advanceUntilIdle()
            assertNotNull(recreated.state.value.error)
            assertEquals(1, store.list().size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun malformedCaseChoicesDoNotHideIndependentInvestigationsAndCorruptArchivesStayVisible() = runTest {
        val dir = Files.createTempDirectory("ow-workspace-errors").toFile()
        try {
            val c = syntheticCase(); val store = InvestigationStore(dir); store.create(initial(c))
            File(dir, "diagnostic-cases-v1.json").writeText("{broken")
            File(dir, "investigations-v1/broken.json").writeText("{broken")
            File(dir, "investigations-v1/broken").writeText("unfinished")
            File(dir, "investigations-v1/synthetic-investigation").writeText("unknown sibling of a valid archive")
            val coordinator = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            coordinator.reload("synthetic-investigation"); advanceUntilIdle()
            assertNotNull(coordinator.state.value.opened)
            assertContains(coordinator.state.value.error!!, "Case choices unavailable")
            val listings = coordinator.state.value.listings
            assertEquals(4, listings.size); assertEquals(4, listings.map { it.storageKey }.distinct().size)
            assertEquals(3, listings.count { it.error != null })
            assertEquals(2, listings.count { it.id == "synthetic-investigation" }) // Open ID is not the UI row identity.
            assertEquals("{broken", File(dir, "investigations-v1/broken.json").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun missingInputAndInvalidEvidenceDoNotPublishFalseSuccess() = runTest {
        val dir = Files.createTempDirectory("ow-workspace-input").toFile()
        try {
            val c = syntheticCase(); DiagnosticCaseStore(dir).add(c)
            val store = InvestigationStore(dir)
            val coordinator = InvestigationCoordinator(store, DiagnosticCaseStore(dir), this, StandardTestDispatcher(testScheduler))
            coordinator.create(c.id, "", "Analyst"); advanceUntilIdle()
            assertNull(coordinator.state.value.opened); assertTrue(store.list().isEmpty())
            store.create(initial(c)); coordinator.reload("synthetic-investigation"); advanceUntilIdle()
            val invalid = InvestigationStep.Observation(InvestigationObservationKind.CASE_SCAN_READING, "Missing", "Synthetic",
                InvestigationEvidenceRef(c.id, InvestigationObservationKind.CASE_SCAN_READING, pid = 12))
            coordinator.append("complaint", "Analyst", "Cannot qualify missing data", invalid); advanceUntilIdle()
            assertNotNull(coordinator.state.value.error)
            assertEquals(1, coordinator.state.value.opened!!.journal.revisions.size)
            assertEquals(1, store.load("synthetic-investigation").journal.revisions.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun leavingWorkspaceCancelsDeliveryButAdmittedAtomicWriteFinishes() = runTest {
        val dir = Files.createTempDirectory("ow-workspace-cancel").toFile()
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        try {
            val c = syntheticCase(); DiagnosticCaseStore(dir).add(c)
            val queued = mutableListOf<Runnable>()
            val pausedIo = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
            }
            val store = InvestigationStore(dir)
            val coordinator = InvestigationCoordinator(store, DiagnosticCaseStore(dir), owner, pausedIo, { 6_000 })
            coordinator.create(c.id, "Synthetic reported complaint", "Synthetic author")
            runCurrent()
            assertTrue(coordinator.state.value.busy)
            owner.cancel()
            assertEquals(1, queued.size)
            queued.removeAt(0).run()
            runCurrent()
            assertEquals(1, store.list().size)
            assertNull(coordinator.state.value.opened) // No late presentation to the disposed page.
        } finally { owner.cancel(); dir.deleteRecursively() }
    }
    @Test fun syntheticWorkspaceScenarioRetainsInconclusiveResultAndItsCorrectionForEmulatorReview() {
        val c = syntheticCase()
        var saved = initial(c)
        fun add(id: String, step: InvestigationStep) { saved = saved.copy(journal = saved.journal.append(id, 6_000, "Synthetic analyst", "Fictional evidence review", step)) }
        add("reported", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Both banks reportedly negative; unverified", "Fictional user narrative, not a capture"))
        add("measured", InvestigationPresentation.choices(saved).first { it.reference.kind == InvestigationObservationKind.CASE_FUEL_INTERVAL }.observation())
        add("click", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Synthetic manual observation: purge click heard", "Invented fixture; no sealing or flow measurement"))
        add("hypothesis-purge", InvestigationStep.Hypothesis("Purge leakage could contribute; sealing, flow and causal extent unknown", listOf("reported", "measured")))
        add("hypothesis-alternative", InvestigationStep.Hypothesis("Alternative fuel delivery or measurement explanation remains open", listOf("measured")))
        add("question", InvestigationStep.ProposedTest(InvestigationTestKind.REVIEW_RETAINED_EVIDENCE,
            "Does retained evidence distinguish purge leakage from another explanation?", listOf("hypothesis-purge", "hypothesis-alternative"),
            "A click is not sealing or flow evidence. Bank 2 and cold-ignition evidence are missing. No live test procedure is supplied."))
        add("result-original", InvestigationStep.TestResult("question", InvestigationOutcome.UNAVAILABLE, "No sealing evidence available", "Synthetic manual review", emptyList(), emptyList()))
        add("result-corrected", InvestigationStep.TestResult("question", InvestigationOutcome.INCONCLUSIVE,
            "Click recorded, sealing/flow and full causal explanation still unknown", "Synthetic manual review; no hardware access", listOf("click"),
            listOf(InvestigationEffect("hypothesis-purge", HypothesisEffect.UNCHANGED, "No sealing/flow evidence; click alone cannot support leakage")), "result-original"))
        assertEquals(saved, InvestigationCodec.decode(InvestigationCodec.encode(saved)))
        assertEquals(listOf("result-corrected"), saved.journal.activeResults().map { it.id })
        val out = File("build/reports/investigation-workspace-fixture").also { it.mkdirs() }
        File(out, "diagnostic-cases-v1.json").writeText(DiagnosticCaseCodec.encode(listOf(c)))
        File(out, "synthetic-investigation.json").writeText(InvestigationCodec.encode(saved))
    }
}
