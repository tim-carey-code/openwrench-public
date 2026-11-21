package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import kotlin.test.*

class CombinedInvestigationTest {
    private fun cycle() = CombinedInvestigationFixtures.completedCycle()
    private fun resultA() = cycle().events[9] as CombinedEvent.Manual
    @Test fun interleavedCycleKeepsExactQuestionCaseCorrectionAndGlobalSequence() {
        val document = cycle()
        assertEquals((1..14).toList(), document.events.map { it.sequence })
        assertEquals(mapOf("sealing-first" to "correct-a", "sealing-later" to "result-b"), document.activeResultIds())
        val results = document.events.filterIsInstance<CombinedEvent.Manual>()
        assertEquals(listOf("sealing-first", "sealing-later", "sealing-first"), results.map { it.outcome.context.questionRevisionId })
        assertEquals(document.initialCases.single().id, results.last().outcome.context.caseId)
        assertEquals(document.cases().last().id, results[1].outcome.context.caseId)
        assertEquals("result-a", results.last().outcome.supersedesResultId)
        assertEquals(FixtureVehicleAssertion.UNKNOWN, document.events.filterIsInstance<CombinedEvent.Attachment>().single().vehicleAssertion)
        assertEquals(CombinedInvestigationFixtures.questionAPrefix().events, document.events.take(9))
        assertEquals(document, CombinedInvestigationCodec.decode(CombinedInvestigationCodec.encode(document)))
    }
    @Test fun noForwardQuestionCaseOrObservationReferencesAreAccepted() {
        val prefix = CombinedInvestigationFixtures.questionAPrefix()
        val result = resultA()
        for (context in listOf(result.outcome.context.copy(questionRevisionId = "sealing-later"),
            result.outcome.context.copy(caseId = cycle().cases().last().id),
            result.outcome.context.copy(observationIds = listOf("not-yet-recorded")),
            result.outcome.context.copy(observationIds = listOf("vapor")))) {
            assertFailsWith<CombinedValidation> { prefix.append(result.copy(outcome = result.outcome.copy(context = context))) }
        }
    }
    @Test fun correctionsMustSupersedeExactActiveResultAndStayWithinQuestion() {
        val document = cycle()
        val correction = document.events.last() as CombinedEvent.Manual
        val before = document.copy(events = document.events.dropLast(1))
        for (wrong in listOf(null, "result-b", "absent")) assertFailsWith<CombinedValidation> {
            before.append(correction.copy(outcome = correction.outcome.copy(supersedesResultId = wrong)))
        }
        assertEquals("result-a", before.activeResultIds()["sealing-first"])
        val legacy = CombinedEvent.Ordinary("request-illegal-conversion", InvestigationRevision("illegal-conversion", 15, 60000,
            "Fictional", "Cannot back-convert", InvestigationStep.TestResult("sealing-first", InvestigationOutcome.INCONCLUSIVE,
                "Unknown", "Invented", emptyList(), emptyList(), "correct-a")))
        assertFailsWith<CombinedValidation> { document.append(legacy) }
    }
    @Test fun manualValuesNeverAddObservationsOrQualifyMissingBankEvidence() {
        val before = CombinedInvestigationFixtures.questionAPrefix()
        val result = resultA().let { it.copy(outcome = it.outcome.copy(values = listOf(AttributedManualValue(-16.0, ManualValueUnit.OTHER_REPORTED, "invented percent")))) }
        val after = before.append(result)
        assertEquals(before.cases(), after.cases())
        assertEquals(before.events, after.events.filterIsInstance<CombinedEvent.Ordinary>())
        assertEquals(ManualScopeUse.SYNTHETIC_ONLY, result.outcome.scopeAssessment().use)
        val basis = SyntheticPilotReasoning.preview(ManualOutcomeContractCodec.readLegacy(SyntheticInvestigationSeed.legacyJson)).basis
        assertEquals(BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE, basis.bankRelationship)
        assertTrue(basis.correctionEvidence.all { it.interval.bank == 1 })
    }
    @Test fun absentInvalidAndCrossCaseEvidenceCannotBecomeRetainedFindings() {
        val before = CombinedInvestigationFixtures.questionAPrefix()
        val result = resultA()
        for (reference in listOf(
            InvestigationEvidenceRef(before.initialCases.single().id, InvestigationObservationKind.CASE_FUEL_INTERVAL, findingIndex = 0, intervalIndex = 999),
            InvestigationEvidenceRef(before.initialCases.single().id, InvestigationObservationKind.CASE_SCAN_READING, pid = 255),
            InvestigationEvidenceRef("missing-case", InvestigationObservationKind.CASE_SCAN_READING, pid = 5))) {
            assertFailsWith<CombinedValidation> { before.append(result.copy(outcome = result.outcome.copy(context = result.outcome.context.copy(retainedEvidence = listOf(reference))))) }
        }
    }
    @Test fun unknownAndInconclusiveOutcomesCannotAssertHypothesisSupport() {
        val before = CombinedInvestigationFixtures.questionAPrefix()
        val result = resultA()
        for (outcome in listOf(DeclaredManualOutcome.UNKNOWN, DeclaredManualOutcome.INCONCLUSIVE, DeclaredManualOutcome.UNAVAILABLE)) {
            assertFailsWith<CombinedValidation> { before.append(result.copy(outcome = result.outcome.copy(declaredOutcome = outcome,
                authorEffects = listOf(InvestigationEffect("vapor", HypothesisEffect.SUPPORTS, "Invalid assertion"))))) }
        }
        assertFailsWith<CombinedValidation> { before.append(result.copy(outcome = result.outcome.copy(
            authorEffects = listOf(InvestigationEffect("not-a-question-hypothesis", HypothesisEffect.UNCHANGED, "Wrong context"))))) }
    }
    @Test fun invalidNumericValueHasStableFieldIssueAndOriginalPayload() {
        val result = resultA()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val invalid = result.copy(outcome = result.outcome.copy(values = listOf(AttributedManualValue(value, ManualValueUnit.KPA))))
            val error = assertFailsWith<CombinedValidation> { CombinedInvestigationFixtures.questionAPrefix().append(invalid) }
            assertEquals("outcome.values[0].value", error.issue.field)
            assertEquals(CombinedIssueCode.NONFINITE_VALUE, error.issue.code)
        }
    }
    @Test fun frozenCaseReplacementDuplicateIdsAndNonfictionalOriginsAreRejected() {
        val document = cycle()
        val attachment = document.events.filterIsInstance<CombinedEvent.Attachment>().single()
        assertFailsWith<CombinedValidation> { document.copy(events = document.events.take(11) + attachment.copy(retainedCase = document.initialCases.single())).validate() }
        assertFailsWith<CombinedValidation> { document.copy(events = document.events.mapIndexed { i, e -> if (i == 9) (e as CombinedEvent.Manual).copy(requestId = document.events.first().requestId) else e }).validate() }
        assertFailsWith<CombinedValidation> { CombinedInvestigationFixtures.questionAPrefix().append(resultA().copy(outcome = resultA().outcome.copy(origin = ManualOutcomeOrigin.AUTHOR_REPORTED_MANUAL))) }
        assertFailsWith<CombinedValidation> { document.copy(initialCases = listOf(document.initialCases.single().let { it.copy(drive = it.drive.copy(origin = DriveEvidenceOrigin.HARDWARE)) })).validate() }
    }
    @Test fun reportedOnlyFixtureHasNoQualifiedDriveAndUnknownIdentityStaysExplicit() {
        val seed = CombinedInvestigationFixtures.reportedOnlySeed()
        seed.validate()
        assertNull(seed.cases().single().drive.review.assessment)
        val snapshot = CombinedSnapshot(seed, "fictional-token")
        assertTrue(snapshot.sources.single().fictional)
        assertNull(snapshot.sources.single().scanEcu); assertNull(snapshot.sources.single().driveEcu)
        assertTrue(snapshot.activeResults.isEmpty())
    }
    @Test fun strictCodecRejectsFutureDuplicateWrongPrimitiveAndUnknownKindWithoutDroppingIt() {
        val text = CombinedInvestigationCodec.encode(cycle())
        assertFails { CombinedInvestigationCodec.decode(text.replace("\"fixture_only\":true", "\"fixture_only\":\"true\"")) }
        assertFails { CombinedInvestigationCodec.decode(text.replaceFirst("\"id\":", "\"id\":\"duplicate\",\"id\":")) }
        val future = assertFailsWith<CombinedUnsupported> { CombinedInvestigationCodec.decode(text.replace(CombinedInvestigationCodec.SCHEMA, "future/77")) }
        assertEquals("future/77", future.rawValue)
        val kind = assertFailsWith<CombinedUnsupported> { CombinedInvestigationCodec.decode(text.replaceFirst("\"ordinary\"", "\"future-event\"")) }
        assertEquals("future-event", kind.rawValue)
        val status = assertFailsWith<CombinedUnsupported> { CombinedInvestigationCodec.decode(text.replace("\"UNKNOWN\",\"Fictional later", "\"FUTURE_STATUS\",\"Fictional later")) }
        assertEquals("FUTURE_STATUS", status.rawValue)
    }
    @Test fun existingLegacyAndTypedEnvelopesKeepTheirExactBytesAcrossSharedCodecHelpers() {
        val original = SyntheticInvestigationSeed.legacyJson
        assertEquals(original, InvestigationCodec.encode(InvestigationCodec.decode(original)))
        val tail = ManualOutcomeContract(original, listOf(resultA().outcome.copy(sequence = 11)))
        val text = ManualOutcomeContractCodec.encode(tail)
        assertEquals(text, ManualOutcomeContractCodec.encode(ManualOutcomeContractCodec.decode(text)))
        assertEquals(original, ManualOutcomeContractCodec.decode(text).legacyJson)
    }
    @Test fun explicitTypedCorrectionOfLegacyResultRetainsBothOriginalPayloads() {
        val prefix = CombinedInvestigationFixtures.questionAPrefix()
        val legacy = CombinedEvent.Ordinary("request-legacy-a", InvestigationRevision("legacy-a", 10, 50000,
            "Fictional author", "Invented inconclusive account", InvestigationStep.TestResult("sealing-first", InvestigationOutcome.INCONCLUSIVE,
                "Unknown", "Fictional legacy account", emptyList(), emptyList())))
        val typed = resultA().let { it.copy(outcome = it.outcome.copy(sequence = 11, supersedesResultId = "legacy-a")) }
        val complete = prefix.append(legacy).append(typed)
        assertEquals("result-a", complete.activeResultIds()["sealing-first"])
        val reopened = CombinedInvestigationCodec.decode(CombinedInvestigationCodec.encode(complete))
        assertEquals(legacy, reopened.events[9]); assertEquals(typed, reopened.events[10])
    }

    @Test fun manualUnitAndContextErrorsExposeStableNestedPathsWithoutParsingMessages() {
        val prefix = CombinedInvestigationFixtures.questionAPrefix()
        val original = resultA()
        val outcome = original.outcome
        val cases = listOf(
            "outcome.values[0].reportedUnit" to outcome.copy(values = listOf(AttributedManualValue(1.0, ManualValueUnit.OTHER_REPORTED))),
            "outcome.values[0].reportedUnit" to outcome.copy(values = listOf(AttributedManualValue(1.0, ManualValueUnit.KPA, "unexpected"))),
            "outcome.context.startingReport" to outcome.copy(context = outcome.context.copy(startingReport = OwnerReportSelection(ReportSelection.SELECTED, "manual"))),
            "outcome.context.timingReport" to outcome.copy(context = outcome.context.copy(timingReport = OwnerReportSelection(ReportSelection.SELECTED, "starting"))),
            "outcome.context.adaptationReport" to outcome.copy(context = outcome.context.copy(adaptationReport = OwnerReportSelection(ReportSelection.SELECTED, "timing"))),
            "outcome.context.startingReport" to outcome.copy(context = outcome.context.copy(startingReport = OwnerReportSelection(ReportSelection.UNRESOLVED, "starting"))),
            "outcome.context.questionRevisionId" to outcome.copy(context = outcome.context.copy(questionRevisionId = "manual")),
            "outcome.context.caseId" to outcome.copy(context = outcome.context.copy(caseId = "missing-case")))
        for ((field, invalid) in cases) {
            val error = assertFailsWith<CombinedValidation> { prefix.append(original.copy(outcome = invalid)) }
            assertEquals(field, error.issue.field)
            assertTrue(error.issue.message.length > 20)
            assertFalse(error.issue.message.contains("Required value was null"))
        }
    }

}
