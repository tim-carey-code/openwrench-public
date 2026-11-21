package org.openwrench.core

import kotlin.test.*

class DiagnosticInvestigationTest {
    private fun initial() = DiagnosticInvestigation("investigation", listOf(
        InvestigationRevision("complaint", 1, 100, "Reporter", "Initial report", InvestigationStep.Complaint(
            "Reported 2013 Legacy 3.6R cold-start complaint, roughly -16% corrections; unverified."))))
    private fun DiagnosticInvestigation.add(id: String, step: InvestigationStep) = append(id, 101, "Analyst", "New evidence", step)
    private fun prepared(kind: InvestigationTestKind = InvestigationTestKind.MANUAL_RESULT_ONLY) = initial()
        .add("reported", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Both banks said to be negative", "User report; not verified"))
        .add("manual", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Synthetic manual observation: click heard", "Invented fixture; click does not establish sealing or flow"))
        .add("h1", InvestigationStep.Hypothesis("Purge leakage could contribute; causal scope unknown", listOf("reported")))
        .add("h2", InvestigationStep.Hypothesis("Fuel delivery or measurement explanation remains open", listOf("reported")))
        .add("test", InvestigationStep.ProposedTest(kind, "What evidence distinguishes these explanations?", listOf("h1", "h2"), "Sealing/flow evidence differs from an audible click; no live procedure supplied"))
    private fun result(outcome: InvestigationOutcome, observations: List<String>, effect: HypothesisEffect = HypothesisEffect.UNCHANGED,
        supersedes: String? = null) = InvestigationStep.TestResult("test", outcome, "Synthetic recorded result", "Manual fixture, not authenticated hardware evidence",
        observations, listOf(InvestigationEffect("h1", effect, "Limited to the recorded observation; cause remains unproven")), supersedes)

    @Test fun reportDoesNotBecomeMeasurementAndNoBankIsInferred() {
        val journal = prepared()
        assertFails { journal.add("result", result(InvestigationOutcome.RECORDED, listOf("reported"), HypothesisEffect.SUPPORTS)) }
        assertFails { journal.add("result", result(InvestigationOutcome.RECORDED, emptyList(), HypothesisEffect.SUPPORTS)) }
        assertEquals(InvestigationObservationKind.REPORTED, (journal.revisions[1].step as InvestigationStep.Observation).kind)
    }
    @Test fun unsupportedUnavailableInconclusiveCannotSupportOrWeaken() {
        for (outcome in listOf(InvestigationOutcome.INCONCLUSIVE, InvestigationOutcome.UNAVAILABLE, InvestigationOutcome.UNSUPPORTED)) {
            for (effect in listOf(HypothesisEffect.SUPPORTS, HypothesisEffect.WEAKENS))
                assertFails { prepared().add("result", result(outcome, listOf("manual"), effect)) }
            prepared().add("result", result(outcome, emptyList())).validate()
        }
    }
    @Test fun correctionRetainsHistoryAndExplicitlySupersedesOnlyActiveResult() {
        val first = prepared().add("result1", result(InvestigationOutcome.RECORDED, listOf("manual"), HypothesisEffect.WEAKENS))
        assertFails { first.add("result2", result(InvestigationOutcome.INCONCLUSIVE, emptyList())) }
        val corrected = first.add("result2", result(InvestigationOutcome.INCONCLUSIVE, emptyList(), supersedes = "result1"))
        assertEquals(first.revisions, corrected.revisions.take(first.revisions.size))
        assertEquals(listOf("result2"), corrected.activeResults().map { it.id })
        assertFails { corrected.add("result3", result(InvestigationOutcome.INCONCLUSIVE, emptyList(), supersedes = "result1")) }
    }
    @Test fun actuatorProposalCannotBeRecordedAsExecutedOrSupplyHypothesisEffects() {
        val j = prepared(InvestigationTestKind.ACTUATOR_UNSUPPORTED)
        for (outcome in InvestigationOutcome.entries.filter { it != InvestigationOutcome.UNSUPPORTED })
            assertFails { j.add("result", result(outcome, emptyList())) }
        j.add("result", result(InvestigationOutcome.UNSUPPORTED, emptyList())).validate()
    }
    @Test fun missingForwardWrongTypeDuplicateAndInvalidRevisionsAreRejected() {
        val j = prepared()
        assertFails { initial().add("h", InvestigationStep.Hypothesis("Unproven explanation", listOf("future"))) }
        assertFails { initial().add("h", InvestigationStep.Hypothesis("Unproven explanation", listOf("complaint"))) }
        assertFails { j.add("h1", InvestigationStep.Complaint("Duplicate")) }
        assertFails { j.copy(policyVersion = 2).validate() }
        assertFails { j.copy(revisions = j.revisions.map { it.copy(sequence = 1) }).validate() }
        assertFails { j.add("../escape", InvestigationStep.Complaint("Invalid id")) }
        assertFails { j.add("single", InvestigationStep.ProposedTest(InvestigationTestKind.MANUAL_RESULT_ONLY, "Question", listOf("h1"), "Outcomes")) }
        assertFails { j.add("bad", InvestigationStep.Observation(InvestigationObservationKind.CASE_FUEL_INTERVAL, "Missing bank interval", "Fixture")) }
    }
}
