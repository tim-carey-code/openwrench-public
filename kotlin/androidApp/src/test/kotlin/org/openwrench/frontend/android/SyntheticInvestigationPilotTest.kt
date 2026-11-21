package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import org.openwrench.frontend.SyntheticPilotPresentation
import org.openwrench.nativecore.ScanOrigin
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SyntheticInvestigationPilotTest {
    private fun root(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ow-fictional-pilot-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun draft() = SyntheticOutcomeDraft(author = "Fictional tester", reason = "Fictional account", provenance = "Invented fixture account only")
    private fun pending(dir: File) = File(dir, "synthetic-investigation-pilot-v1/${ManualOutcomeSandboxStore.DIRECTORY}/${SyntheticInvestigationSeed.ID}.pending")
    private fun committed(dir: File) = File(pending(dir).parentFile, "${SyntheticInvestigationSeed.ID}.json")
    private fun failedPublish() = object : OutcomeSandboxFiles() { override fun publish(pending: File, committed: File) { throw IOException("Injected publish failure") } }

    @Test fun seedIsFixedFictionalBankOneEvidenceWithoutInferringBankTwoOrColdEvent() {
        val saved = SyntheticInvestigationSeed.saved(); saved.validate()
        assertEquals(SyntheticInvestigationSeed.ID, saved.journal.id)
        assertEquals("00000000-0000-4000-8000-000000000004", saved.cases.single().id)
        assertNull(saved.cases.single().scan.vin)
        assertEquals(ScanOrigin.SYNTHETIC, saved.cases.single().scan.capture!!.origin)
        assertEquals(DriveEvidenceOrigin.SYNTHETIC, saved.cases.single().drive.origin)
        val p = SyntheticPilotReasoning.preview(ManualOutcomeContractCodec.readLegacy(SyntheticInvestigationSeed.legacyJson))
        assertEquals(BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE, p.basis.bankRelationship)
        assertTrue(p.basis.correctionEvidence.isNotEmpty()); assertTrue(p.basis.correctionEvidence.all { it.interval.bank == 1 && it.origin == DriveEvidenceOrigin.SYNTHETIC })
        assertTrue(p.basis.correctionEvidence.all { it.interval.pairs.all { pair -> pair.long.value == -16.0 } })
        assertEquals(SymptomTiming.DIFFERENT_OCCASIONS, p.basis.selectedTimingReport!!.value)
        assertContains((saved.journal.revisions.first().step as InvestigationStep.Complaint).reported, "not Nick's capture")
    }
    @Test fun readDoesNotCreateAndExplicitStartIsIdempotentWithoutReadingInstalledHistories() = root { dir ->
        val owner = File(dir, "investigations-v1/owner.json").also { it.parentFile!!.mkdirs(); it.writeText("Unreadable fictional owner sentinel") }
        val sentinel = owner.readBytes()
        val repo = SyntheticPilotRepository(dir)
        assertNull(repo.inspect().committed); assertFalse(File(dir, "synthetic-investigation-pilot-v1").exists())
        val first = repo.start(); val bytes = committed(dir).readBytes()
        assertEquals(first, repo.start()); assertContentEquals(bytes, committed(dir).readBytes())
        assertEquals(first, SyntheticPilotRepository(dir).inspect())
        assertContentEquals(sentinel, owner.readBytes())
        assertEquals(setOf("investigations-v1", "synthetic-investigation-pilot-v1"), dir.list()!!.toSet())
    }
    @Test fun unknownsAndManualValuesRemainSyntheticWithExactAttributionAndNoObdQualification() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val seed = repo.start().committed!!
        val d = draft().copy(value = "-16", unit = ManualValueUnit.OTHER_REPORTED, otherUnit = "reported percent; not OBD")
        val next = repo.append(seed.digest, d, 100000, "typed1").committed!!
        val r = next.contract.outcomes.single()
        assertEquals(ManualOutcomeOrigin.SYNTHETIC, r.origin); assertEquals(ManualOutcomeMethod.UNKNOWN, r.method)
        assertEquals(ReportedDetail(ReportKnowledge.UNKNOWN), r.criteriaReference); assertNull(r.observedAtEpochMillis)
        assertEquals("adaptation", r.context.adaptationReport.revisionId)
        assertEquals("Fictional tester", r.author); assertEquals("Invented fixture account only", r.provenance)
        assertEquals(seed.contract.legacySnapshot(), next.contract.legacySnapshot())
        assertEquals(SyntheticPilotReasoning.preview(seed.contract).basis.correctionEvidence, SyntheticPilotReasoning.preview(next.contract).basis.correctionEvidence)
    }
    @Test fun twoQuestionCorrectionChainsAndHistoryKeepReadableQuestionAndOriginalAttribution() = root { dir ->
        val repo = SyntheticPilotRepository(dir); var s = repo.start().committed!!
        s = repo.append(s.digest, draft(), 100000, "a").committed!!
        s = repo.append(s.digest, draft().copy(questionId = "sealing-later"), 100001, "b").committed!!
        s = repo.append(s.digest, draft().copy(corrects = "a", reason = "Correct fictional account"), 100002, "c").committed!!
        assertEquals(mapOf("sealing-first" to "c", "sealing-later" to "b"), s.contract.activeResultIds())
        assertEquals(listOf("a", "b", "c"), s.contract.outcomes.map { it.id })
        for (r in s.contract.outcomes) {
            val text = SyntheticPilotPresentation.result(s.contract, r)
            assertContains(text, "Question answered: ${r.context.questionRevisionId}")
            assertContains(text, "Source: ${r.provenance}"); assertContains(text, "origin SYNTHETIC")
        }
        assertContains(SyntheticPilotPresentation.result(s.contract, s.contract.outcomes.last()), "Corrects: a")
        assertEquals(setOf("b", "c"), SyntheticPilotReasoning.preview(s.contract).active.map { it.id }.toSet())
        val bytes = committed(dir).readBytes()
        assertFails { repo.append(s.digest, draft().copy(corrects = "a"), 100003) }
        assertFails { repo.append(s.digest, draft().copy(corrects = "b"), 100003) }
        assertContentEquals(bytes, committed(dir).readBytes())
    }
    @Test fun indirectMethodsStayNonDiscriminatingRegardlessOfDeclaredNormalOrAbnormal() = root { dir ->
        val repo = SyntheticPilotRepository(dir); var s = repo.start().committed!!
        var prior: String? = null
        for (method in listOf(ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT, ManualOutcomeMethod.ELECTRICAL_CONTINUITY, ManualOutcomeMethod.PRESSURE_DECAY_ONLY)) {
            for (outcome in listOf(DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE)) {
                val id = "result${s.contract.outcomes.size}"
                s = repo.append(s.digest, draft().copy(method = method, outcome = outcome, corrects = prior), 100000, id).committed!!
                prior = id
                val p = SyntheticPilotReasoning.preview(s.contract)
                assertEquals(InvestigationQuestionId.RESULT_SCOPE, p.nextQuestion.id)
                assertTrue(p.gaps.any { "not sealing/flow proof" in it })
                assertEquals(ManualScopeUse.SYNTHETIC_ONLY, p.active.single().scopeAssessment().use)
            }
        }
    }
    @Test fun scopedAssertionsMotivateCompetingQuestionWithoutClosingHypothesesOrProvingCause() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start().committed!!
        val complete = draft().copy(faultMode = ManualFaultMode.UNWANTED_FLOW_WHEN_EXPECTED_SEALED, method = ManualOutcomeMethod.SEALING_FLOW_MEASUREMENT,
            outcome = DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, observer = "Fictional observer", methodDetail = "Invented external account, no live procedure",
            conditions = "Invented bounded occasion", criteria = "Fictional document reference, not an OEM specification", applicability = ReportedApplicability.OWNER_REPORTED_MATCH,
            observedAt = "2026-01-01T12:00:00Z", value = "0", unit = ManualValueUnit.MILLILITRES_PER_MINUTE, vaporEffect = HypothesisEffect.WEAKENS,
            effectReason = "Author asserts only this tested mode under fictional conditions")
        val next = repo.append(s.digest, complete, 100000, "normal").committed!!
        val p = SyntheticPilotReasoning.preview(next.contract)
        assertEquals(InvestigationQuestionId.INPUT_PLAUSIBILITY, p.nextQuestion.id)
        assertTrue(p.gaps.isEmpty()); assertContains(p.why, "does not establish leakage as the full cause")
        assertEquals(HypothesisEffect.WEAKENS, p.active.single().authorEffects.first().effect)
        assertEquals(ManualScopeUse.SYNTHETIC_ONLY, p.active.single().scopeAssessment().use)
        assertEquals(s.contract.legacySnapshot(), next.contract.legacySnapshot())
        assertTrue(p.basis.activeResults.isEmpty()) // typed author assertions never converted into qualified legacy results
    }
    @Test fun invalidInputsAndUnsupportedEditingFailBeforeAnyStoredChange() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start().committed!!; val bytes = committed(dir).readBytes()
        val invalid = listOf(draft().copy(questionId = "arbitrary-question"), draft().copy(author = ""), draft().copy(provenance = ""), draft().copy(reason = ""),
            draft().copy(observedAt = "not-a-time"), draft().copy(value = "NaN"), draft().copy(value = "Infinity"), draft().copy(value = "one"),
            draft().copy(value = "1", unit = ManualValueUnit.OTHER_REPORTED), draft().copy(vaporEffect = HypothesisEffect.SUPPORTS, effectReason = "Unknown does not support"),
            draft().copy(outcome = DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, vaporEffect = HypothesisEffect.WEAKENS))
        for (d in invalid) {
            assertFails { repo.append(s.digest, d, 100000) }
            assertContentEquals(bytes, committed(dir).readBytes()); assertFalse(pending(dir).exists())
        }
    }
    @Test fun invalidAndNonfiniteManualValuesGiveSpecificFeedbackWithoutChangingDraftOrBytes() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val snapshot = repo.start().committed!!
        val before = committed(dir).readBytes()
        for (input in listOf("NaN", "Infinity", "-Infinity", "1e999", "not a number", "--16")) {
            val d = draft().copy(value = input)
            assertContains(requireNotNull(d.manualValueError()), "finite manual value")
            val error = assertFailsWith<IllegalArgumentException> { repo.append(snapshot.digest, d, 100000) }
            assertEquals(d.manualValueError(), error.message)
            assertEquals(input, d.value) // rejected draft is not cleared or coerced
            assertEquals("Fictional tester", d.author); assertEquals("Invented fixture account only", d.provenance)
            assertContentEquals(before, committed(dir).readBytes()); assertFalse(pending(dir).exists())
        }
        for (input in listOf("", " ", "0", "-16", "1.25e2")) assertNull(draft().copy(value = input).manualValueError())
        val valid = repo.append(snapshot.digest, draft().copy(value = "-16"), 100001).committed!!
        assertEquals(-16.0, valid.contract.outcomes.single().values.single().value)
        assertEquals(ManualOutcomeOrigin.SYNTHETIC, valid.contract.outcomes.single().origin)
        assertEquals(snapshot.contract.legacyJson, valid.contract.legacyJson)
    }
    @Test fun coordinatorRetainsDraftAndSavedEvidenceAfterSpecificNumericFailureThenAcceptsCorrection() = runTest {
        val dir = Files.createTempDirectory("ow-pilot-number-feedback").toFile()
        try {
            val repo = SyntheticPilotRepository(dir); val initial = repo.start().committed!!
            val before = committed(dir).readBytes(); val dispatcher = StandardTestDispatcher(testScheduler)
            val c = SyntheticPilotCoordinator(repo, this, dispatcher) { 100000 }
            c.load(); runCurrent()
            val invalid = draft().copy(value = "NaN", reason = "Retain my draft reason")
            c.append(initial.digest, invalid); runCurrent()
            assertEquals(invalid.manualValueError(), c.state.value.error)
            assertEquals(initial, c.state.value.storage!!.committed)
            assertContentEquals(before, committed(dir).readBytes()); assertFalse(pending(dir).exists())
            val corrected = invalid.copy(value = "-16")
            c.append(initial.digest, corrected); runCurrent()
            assertNull(c.state.value.error)
            val saved = c.state.value.storage!!.committed!!.contract.outcomes.single()
            assertEquals(-16.0, saved.values.single().value); assertEquals(invalid.reason, saved.reason)
            assertEquals(invalid.author, saved.author); assertEquals(invalid.provenance, saved.provenance)
        } finally { dir.deleteRecursively() }
    }
    @Test fun staleRetryAndDoubleSubmissionCannotDuplicateOrOverwrite() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start().committed!!
        val next = repo.append(s.digest, draft(), 100000, "saved"); val bytes = committed(dir).readBytes()
        assertFails { SyntheticPilotRepository(dir).append(s.digest, draft(), 100000, "duplicate") }
        assertContentEquals(bytes, committed(dir).readBytes()); assertEquals(next, repo.inspect())
    }
    @Test fun pendingSaveIsNotAutoFinishedOrErasedAndCanBeExplicitlyRecovered() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start().committed!!
        assertFails { SyntheticPilotRepository(dir, failedPublish()).append(s.digest, draft(), 100000, "pending") }
        val p = repo.inspect(); assertNotNull(p.pending)
        assertEquals(p, repo.start()); assertEquals(p, repo.inspect())
        assertFails { repo.append(s.digest, draft(), 100001) }
        val recovered = repo.recover(s.digest, p.pending!!.digest!!)
        assertNull(recovered.pending); assertEquals("pending", recovered.committed!!.contract.outcomes.single().id)
    }
    @Test fun pilotRejectsNonSyntheticPendingBeforePublicationAndPreservesBothFiles() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start().committed!!
        assertFails { SyntheticPilotRepository(dir, failedPublish()).append(s.digest, draft(), 100000, "pending") }
        val c = ManualOutcomeContractCodec.decode(pending(dir).readText())
        pending(dir).writeText(ManualOutcomeContractCodec.encode(c.copy(outcomes = c.outcomes.map { it.copy(origin = ManualOutcomeOrigin.AUTHOR_REPORTED_MANUAL) })))
        val before = committed(dir).readBytes(); val staged = pending(dir).readBytes(); val state = repo.inspect()
        assertFails { repo.recover(s.digest, state.pending!!.digest!!) }
        assertContentEquals(before, committed(dir).readBytes()); assertContentEquals(staged, pending(dir).readBytes())
    }
    @Test fun partialOrChangedSeedIsPreservedWithNoFallbackMigrationOrDeletion() = root { dir ->
        val repo = SyntheticPilotRepository(dir); val s = repo.start()
        pending(dir).writeText("{partial")
        assertFails { repo.recover(s.committed!!.digest, repo.inspect().pending!!.digest!!) }
        assertEquals("{partial", pending(dir).readText())
        val original = committed(dir).readText()
        committed(dir).writeText(original.replace("fictional-pilot-v1", "fictional-pilot-v2"))
        val changed = committed(dir).readBytes()
        assertFails { repo.inspect() }; assertFails { repo.start() }
        assertContentEquals(changed, committed(dir).readBytes())
    }
    @Test fun coordinatorRejectsRepeatedAdmissionAndReloadsSavedSyntheticState() = runTest {
        val dir = Files.createTempDirectory("ow-pilot-coordinator").toFile()
        try {
            val repo = SyntheticPilotRepository(dir); val dispatcher = StandardTestDispatcher(testScheduler)
            val c = SyntheticPilotCoordinator(repo, this, dispatcher) { 100000 }
            c.load(); runCurrent(); assertNull(c.state.value.storage!!.committed)
            c.start(); c.start(); runCurrent()
            val s = c.state.value.storage!!.committed!!
            c.append(s.digest, draft()); c.append(s.digest, draft()); runCurrent()
            assertEquals(1, c.state.value.storage!!.committed!!.contract.outcomes.size)
            c.load(); runCurrent(); assertNotNull(c.state.value.preview)
            assertEquals(1, c.state.value.preview!!.active.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun leavingOrCancelingDeliveryDoesNotLoseAdmittedSaveOrDeliverLatePreview() = runTest {
        for (cancelScope in listOf(false, true)) {
            val dir = Files.createTempDirectory("ow-pilot-cancel").toFile()
            try {
                val normal = SyntheticPilotRepository(dir); val s = normal.start().committed!!
                val dispatcher = StandardTestDispatcher(testScheduler); val child = CoroutineScope(SupervisorJob() + dispatcher)
                lateinit var c: SyntheticPilotCoordinator
                val files = object : OutcomeSandboxFiles() {
                    override fun stage(file: File, bytes: ByteArray) {
                        if (cancelScope) child.cancel() else c.leave()
                        super.stage(file, bytes)
                    }
                }
                c = SyntheticPilotCoordinator(SyntheticPilotRepository(dir, files), child, dispatcher) { 100000 }
                c.load(); runCurrent(); val before = c.state.value.storage
                c.append(s.digest, draft()); runCurrent()
                assertEquals(1, normal.inspect().committed!!.contract.outcomes.size)
                assertEquals(before, c.state.value.storage); assertNull(c.state.value.preview)
                child.cancel()
            } finally { dir.deleteRecursively() }
        }
    }
}
