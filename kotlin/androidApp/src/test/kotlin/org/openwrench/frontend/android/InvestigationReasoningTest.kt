package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.*
import kotlin.test.*

/** Invented receipts only: reported Nick-like narrative is never evidence of a real vehicle fault. */
class InvestigationReasoningTest {
    private val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    private fun assessment(count: Int = 11, secondBank: Boolean = false, secondLong: Double = -16.0,
        secondStart: Long = 100, secondRpm: Double = 900.0, stale: Boolean = false,
        repeated: Boolean = false): DriveHealthAssessment {
        val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
        data class Event(val at: Long, val reading: NativeReading)
        val events = mutableListOf<Event>()
        fun run(bank: Int, start: Long, rpm: Double, long: Double) {
            repeat(count) { index ->
                val at = start + index * 3_000
                fun scalar(pid: Int, value: Double, offset: Long) { events += Event(at + offset, NativeReading(pid, DecodedReading.Scalar(value))) }
                if (!stale || index == 0) {
                    scalar(12, rpm, 0); scalar(13, 0.0, 1); scalar(4, 30.0, 2); scalar(5, 90.0, 3)
                    events += Event(at + 4, NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to FuelCorrectionRecorder.CLOSED))))
                }
                scalar(if (bank == 1) 6 else 8, if (long < 0) -5.0 else 5.0, 5)
                scalar(if (bank == 1) 7 else 9, long, 6)
            }
        }
        run(1, 100, 900.0, -16.0)
        if (secondBank) run(2, secondStart, secondRpm, secondLong)
        events.distinct().sortedBy { it.at }.forEach { e ->
            r.offer(e.reading, e.at)
            if (repeated) repeat(5) { r.offer(e.reading, e.at) }
        }
        r.stop((events.maxOfOrNull { it.at } ?: 0) + 5_000)
        return r.snapshot()
    }
    private fun saved(a: DriveHealthAssessment = assessment(), source: String? = "7E8", origin: DriveEvidenceOrigin = DriveEvidenceOrigin.SYNTHETIC): SavedInvestigation {
        val base = caseDriveFixture()
        val review = base.review.copy(assessment = a, elapsedMillis = maxOf(base.review.elapsedMillis, a.evaluatedAtOffsetMillis))
        val drive = base.copy(review = review, sourceEcu = source, origin = origin,
            report = "OPENWRENCH live health monitoring\nSYNTHETIC fixture only\n" + review.reportText())
        val s = caseScanFixture().let { it.copy(capture = it.capture!!.copy(origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        val c = DiagnosticCaseBuilder.create(s, drive, true, 4_000)
        return SavedInvestigation(DiagnosticInvestigation("reasoning", listOf(InvestigationRevision("complaint", 1, 5_000,
            "Synthetic reporter", "Reported narrative, not evidence", InvestigationStep.Complaint(
                "Reported 2013 Subaru Legacy 3.6R cold-start difficulty, about -16%, both banks allegedly affected; not verified")))), listOf(c))
    }
    private fun SavedInvestigation.add(id: String, step: InvestigationStep) = copy(journal = journal.append(id, 6_000, "Synthetic analyst", "New attributed evidence", step))
    private fun classified(s: SavedInvestigation, starting: StartingBehavior = StartingBehavior.NORMAL_SPEED_LONG_CRANK) = s
        .add("starting", InvestigationQuestionCatalog.startingReport(starting, "Owner report, not a measured cranking speed"))
        .add("timing", InvestigationQuestionCatalog.timingReport(SymptomTiming.DIFFERENT_OCCASIONS, "Owner reports earlier starting difficulty and later running readings"))
    private fun assess(s: SavedInvestigation) = InvestigationReasoning.recommend(s, s.cases.single().id)

    @Test fun narrativeNeverSuppliesStartingClassificationOrBothBankEvidence() {
        val report = assess(saved())
        assertEquals(InvestigationQuestionId.CLARIFY_START, report.nextQuestion.question.id)
        assertNull(report.selectedStartingReport)
        assertEquals(setOf(1), report.correctionEvidence.map { it.interval.bank }.toSet())
        assertEquals(BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE, report.bankRelationship)
        assertTrue(report.correctionEvidence.all { it.origin == DriveEvidenceOrigin.SYNTHETIC && "Historical SYNTHETIC" in it.description })
        assertTrue(report.recommendations.filter { it.evidence.isNotEmpty() }.all { "historical SYNTHETIC" in it.why })
    }
    @Test fun slowCrankStaysDistinctFromRunningCorrectionAndTimingRemainsReported() {
        val slow = assess(classified(saved(), StartingBehavior.SLOW_OR_NO_CRANK))
        assertEquals(InvestigationQuestionId.CRANK_SCOPE, slow.nextQuestion.question.id)
        assertFalse(slow.recommendations.any { it.question.requiresOemProcedure })
        for (starting in listOf(StartingBehavior.NORMAL_SPEED_LONG_CRANK, StartingBehavior.STARTS_THEN_STALLS, StartingBehavior.ROUGH_RUNNING)) {
            val s = saved().add("starting", InvestigationQuestionCatalog.startingReport(starting, "Owner"))
            assertEquals(InvestigationQuestionId.SEPARATE_EVENTS, assess(s).nextQuestion.question.id)
            assertEquals(InvestigationQuestionId.BANK_COVERAGE, assess(classified(saved(), starting)).nextQuestion.question.id)
        }
    }
    @Test fun absentShortStaleAndRepeatedReceiptsUseExistingQualificationInsteadOfReportedNumbers() {
        for (a in listOf(assessment(0), assessment(6), assessment(stale = true))) {
            val report = assess(classified(saved(a)))
            assertTrue(report.correctionEvidence.isEmpty())
            assertEquals(InvestigationQuestionId.QUALIFY_CORRECTION, report.nextQuestion.question.id)
            assertFalse(report.recommendations.any { it.question.requiresOemProcedure })
        }
        val original = assess(classified(saved(assessment())))
        val duplicate = assess(classified(saved(assessment(repeated = true))))
        assertEquals(original.correctionEvidence.map { it.interval.pairs.size }, duplicate.correctionEvidence.map { it.interval.pairs.size })
        val shortRepeated = assess(classified(saved(assessment(count = 6, repeated = true))))
        assertTrue(shortRepeated.correctionEvidence.isEmpty())
        val measuredPositive = assess(classified(saved(caseDriveFixture().review.assessment!!)))
        assertTrue(measuredPositive.correctionEvidence.all { !it.removesFuelBeyondExistingCue })
        assertEquals(InvestigationQuestionId.QUALIFY_CORRECTION, measuredPositive.nextQuestion.question.id)
        assertFalse(measuredPositive.recommendations.any { it.question.requiresOemProcedure })
    }
    @Test fun corruptedArchiveClaimsFailClosedWithoutDuplicatingQualificationThresholds() {
        val s = saved(); val c = s.cases.single(); val a = c.drive.review.assessment!!
        val index = a.findings.indexOfFirst { it.fuelIntervals.isNotEmpty() }
        val interval = a.findings[index].fuelIntervals.first()
        for (bad in listOf(interval.copy(pairs = interval.pairs.take(6)),
            interval.copy(pairs = interval.pairs.map { it.copy(rpm = it.rpm.copy(offsetMillis = 0)) }),
            interval.copy(pairs = List(interval.pairs.size) { interval.pairs.first() }))) {
            val findings = a.findings.mapIndexed { i, f -> if (i == index) f.copy(fuelIntervals = listOf(bad)) else f }
            val changed = s.copy(cases = listOf(c.copy(drive = c.drive.copy(review = c.drive.review.copy(assessment = a.copy(findings = findings))))))
            assertFails { assess(changed) }
        }
    }
    @Test fun comparableBanksMotivateQuestionsWithoutSharedOrLocalFaultVerdicts() {
        val same = assess(classified(saved(assessment(secondBank = true))))
        assertEquals(BankEvidenceRelationship.COMPARABLE_SAME_PATTERN, same.bankRelationship)
        assertEquals(InvestigationQuestionId.SHARED_VS_BANK_SPECIFIC, same.nextQuestion.question.id)
        assertEquals(2, same.comparedIntervals.size)
        val different = assess(classified(saved(assessment(secondBank = true, secondLong = 16.0))))
        assertEquals(BankEvidenceRelationship.COMPARABLE_DIFFERENT_PATTERN, different.bankRelationship)
        assertContains(different.nextQuestion.why, "not proof")
        assertFalse(same.recommendations.any { it.executable })
        same.recommendations.filter { it.question.requiresOemProcedure }.forEach {
            assertTrue(it.missingEvidence.any { gap -> "OEM procedure" in gap })
            assertTrue(it.question.limitations.isNotEmpty())
        }
    }
    @Test fun unknownSourceNonOverlappingTimeAndDifferentContextStayIncomparable() {
        val base = assessment(secondBank = true)
        val differentContext = base.copy(findings = base.findings.map { finding ->
            if (finding.checkId != "fuel-pattern-bank-2") finding else finding.copy(fuelIntervals = finding.fuelIntervals.map { interval ->
                fun FuelObservation.shift() = copy(value = value + 1_600.0)
                interval.copy(pairs = interval.pairs.map { it.copy(rpm = it.rpm.shift()) }, contextBounds = interval.contextBounds.copy(
                    rpm = FuelCorrectionBounds(interval.contextBounds.rpm.minimum.shift(), interval.contextBounds.rpm.maximum.shift())))
            })
        })
        for (s in listOf(saved(differentContext), saved(assessment(secondBank = true), source = null),
            saved(assessment(secondBank = true, secondStart = 60_000)),
            saved(assessment(secondBank = true, secondStart = 60_000, secondRpm = 2_500.0)))) {
            val report = assess(classified(s))
            assertEquals(BankEvidenceRelationship.INCOMPARABLE, report.bankRelationship)
            assertEquals(InvestigationQuestionId.BANK_COMPARABILITY, report.nextQuestion.question.id)
            assertTrue(report.comparedIntervals.isEmpty())
        }
    }
    @Test fun explicitReportSelectionDoesNotEraseConflictingOwnerHistoryOrAuthenticateIt() {
        val s = classified(saved()).add("other", InvestigationQuestionCatalog.startingReport(StartingBehavior.STARTS_THEN_STALLS, "Later conflicting owner account"))
        assertEquals(InvestigationQuestionId.CLARIFY_START, assess(s).nextQuestion.question.id)
        val selected = InvestigationReasoning.recommend(s, s.cases.single().id, selectedStartingRevisionId = "starting")
        assertEquals(2, selected.startingReports.size)
        assertEquals("starting", selected.selectedStartingReport!!.revisionId)
        assertEquals(StartingBehavior.NORMAL_SPEED_LONG_CRANK, selected.selectedStartingReport.value)
        assertFails { InvestigationReasoning.recommend(s, s.cases.single().id, selectedStartingRevisionId = "complaint") }
    }
    @Test fun manualJudgmentsRemainAttributedAndCorrectionsRemoveOnlySupersededActiveResults() {
        var s = classified(saved())
        s = s.add("manual", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED,
            "Purge click, continuity and command acknowledgment only; no flow measurement", "Synthetic author entry; no hardware"))
            .add("h1", InvestigationStep.Hypothesis("Unwanted vapor delivery might contribute", listOf("manual")))
            .add("h2", InvestigationStep.Hypothesis("Input bias remains possible", listOf("manual")))
            .add("q", InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("h1", "h2")))
        val positive = InvestigationStep.TestResult("q", InvestigationOutcome.RECORDED, "Author claims support from click", "Manual synthetic judgment", listOf("manual"),
            listOf(InvestigationEffect("h1", HypothesisEffect.SUPPORTS, "Author's claim; not sealing proof")))
        s = s.add("r1", positive)
        val supporting = assess(s)
        assertEquals(AttributedJudgment.SUPPORTING, supporting.activeResults.single().judgments.single().status)
        assertEquals(InvestigationQuestionId.RESULT_SCOPE, supporting.nextQuestion.question.id)
        assertContains(supporting.activeResults.single().limitation, "not verified causation")
        s = s.add("r2", positive.copy(outcome = InvestigationOutcome.INCONCLUSIVE, result = "No sealing/flow evidence", supersedesResultId = "r1",
            effects = listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "Click alone is inconclusive"))))
        val corrected = assess(InvestigationCodec.decode(InvestigationCodec.encode(s)))
        assertEquals(listOf("r2"), corrected.activeResults.map { it.revisionId })
        assertEquals("r1", corrected.activeResults.single().supersedesResultId)
        assertEquals(AttributedJudgment.INCONCLUSIVE, corrected.activeResults.single().judgments.single().status)
        assertEquals(InvestigationQuestionId.VAPOR_SEALING, corrected.activeResults.single().catalogQuestionId)
        val vapor = corrected.recommendations.single { it.question.id == InvestigationQuestionId.VAPOR_SEALING }
        assertContains(vapor.why, "INCONCLUSIVE"); assertTrue(vapor.missingEvidence.isNotEmpty()); assertFalse(vapor.executable)
        s = s.add("r3", positive.copy(result = "Author reports normal result under recorded conditions", supersedesResultId = "r2",
            effects = listOf(InvestigationEffect("h1", HypothesisEffect.WEAKENS, "Only the tested mode under tested conditions"))))
        val contradicted = assess(s).activeResults.single()
        assertEquals(AttributedJudgment.CONTRADICTING, contradicted.judgments.single().status)
        assertContains(contradicted.limitation, "intermittent/unknown")
        assertEquals(10, s.journal.revisions.size)
    }
    @Test fun repairSymptomFuelCorrectionAndAdaptationHistoryNeverCollapseIntoOneOutcome() {
        var s = classified(saved())
        s = s.add("before", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Before repair report", "Owner"))
            .add("after", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Trim looked better after reset", "Owner"))
            .add("repair", InvestigationStep.RepairReview("before", "after", "Reported repair, unverified", "Cold-start symptom not rechecked", "Owner report"))
            .add("adaptation", InvestigationQuestionCatalog.adaptationReport(AdaptationHistory.RESET_OR_CHANGED_REPORTED, "Owner reports reset; no command executed"))
        val original = InvestigationCodec.encode(s)
        val report = assess(s)
        assertContains(report.repairTracks.single().reportedSymptom, "not rechecked")
        assertContains(report.repairTracks.single().fuelCorrectionOutcome, "NOT_EVALUATED")
        assertEquals(AdaptationHistory.RESET_OR_CHANGED_REPORTED, report.adaptationReports.single().value)
        assertTrue(report.recommendations.any { it.question.id == InvestigationQuestionId.REPAIR_FOLLOW_UP })
        assertEquals(original, InvestigationCodec.encode(s))
    }
}
