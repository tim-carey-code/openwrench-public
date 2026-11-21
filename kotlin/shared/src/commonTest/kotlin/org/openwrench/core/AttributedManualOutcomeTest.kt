package org.openwrench.core

import kotlin.test.*

/** Fictional metadata, no procedure or vehicle experiment. */
class AttributedManualOutcomeTest {
    private val known = ReportedDetail(ReportKnowledge.REPORTED, "Fictional externally recorded reference; not an OEM specification")
    private fun outcome() = AttributedManualOutcome("typed", 1, 20, "Fictional author", "Record report",
        ManualOutcomeContext("question", "case", OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 1),
            OwnerReportSelection(ReportSelection.UNRESOLVED), OwnerReportSelection(ReportSelection.UNRESOLVED), emptyList(), emptyList()),
        ManualOutcomeOrigin.AUTHOR_REPORTED_MANUAL, ManualFaultMode.UNWANTED_FLOW_WHEN_EXPECTED_SEALED,
        ManualOutcomeMethod.SEALING_FLOW_MEASUREMENT, DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE,
        known, known, known, known, ReportedApplicability.OWNER_REPORTED_MATCH, 10, "Fictional manual attribution",
        listOf(AttributedManualValue(0.0, ManualValueUnit.MILLILITRES_PER_MINUTE)),
        listOf(InvestigationEffect("hypothesis", HypothesisEffect.WEAKENS, "Only this mode under reported conditions")))

    @Test fun evenCompleteMetadataRemainsAnAuthorAssertionWithNoCauseOrRepairVerdict() {
        val s = outcome().scopeAssessment()
        assertEquals(ManualScopeUse.SCOPED_AUTHOR_ASSERTION_ONLY, s.use)
        assertTrue(s.gaps.isEmpty())
        assertTrue(s.limitations.any { "not verified" in it })
        assertTrue(s.limitations.any { "intermittent" in it })
        assertTrue(s.limitations.any { "No failed-component or repair-success" in it })
    }
    @Test fun clicksContinuityAcknowledgmentAndDecayNeverEstablishSealingEvenWithDeclaredNormal() {
        for (method in listOf(ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT, ManualOutcomeMethod.ELECTRICAL_CONTINUITY,
            ManualOutcomeMethod.PRESSURE_DECAY_ONLY, ManualOutcomeMethod.DOCUMENT_REVIEW)) {
            for (declared in listOf(DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE)) {
                val r = outcome().copy(method = method, declaredOutcome = declared)
                assertEquals(ManualScopeUse.NON_DISCRIMINATING_FOR_SEALING_FLOW, r.scopeAssessment().use)
                assertEquals(outcome().authorEffects, r.authorEffects) // attribution retained, never promoted
            }
        }
    }
    @Test fun syntheticNeverBecomesRealEvidenceAndUnknownOriginStaysIncomplete() {
        assertEquals(ManualScopeUse.SYNTHETIC_ONLY, outcome().copy(origin = ManualOutcomeOrigin.SYNTHETIC).scopeAssessment().use)
        assertEquals(ManualScopeUse.INCOMPLETE_OR_INCONCLUSIVE, outcome().copy(origin = ManualOutcomeOrigin.UNKNOWN).scopeAssessment().use)
    }
    @Test fun missingMetadataAndUnknownsAreExplicitAndNotZero() {
        val unknown = ReportedDetail(ReportKnowledge.UNKNOWN)
        val incomplete = listOf(outcome().copy(observer = unknown), outcome().copy(methodDetail = unknown),
            outcome().copy(conditions = unknown), outcome().copy(criteriaReference = unknown), outcome().copy(observedAtEpochMillis = null),
            outcome().copy(applicability = ReportedApplicability.UNKNOWN), outcome().copy(applicability = ReportedApplicability.OWNER_REPORTED_MISMATCH),
            outcome().copy(method = ManualOutcomeMethod.UNKNOWN), outcome().copy(faultMode = ManualFaultMode.UNKNOWN),
            outcome().copy(context = outcome().context.copy(catalog = null)), outcome().copy(values = emptyList()),
            outcome().copy(values = listOf(AttributedManualValue(-16.0, ManualValueUnit.UNKNOWN))),
            outcome().copy(values = listOf(AttributedManualValue(0.0, ManualValueUnit.OTHER_REPORTED, "fictional-unit"))))
        incomplete.forEach { assertEquals(ManualScopeUse.INCOMPLETE_OR_INCONCLUSIVE, it.scopeAssessment().use) }
        assertEquals(0.0, outcome().values.single().value)
    }
    @Test fun unknownInconclusiveUnavailableAndInapplicableCannotAssertHypothesisEffects() {
        for (declared in DeclaredManualOutcome.entries.filter { it !in setOf(DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE) }) {
            assertFails { outcome().copy(declaredOutcome = declared).validate() }
            val r = outcome().copy(declaredOutcome = declared, authorEffects = listOf(InvestigationEffect("hypothesis", HypothesisEffect.UNCHANGED, "Unknown")))
            assertEquals(ManualScopeUse.INCOMPLETE_OR_INCONCLUSIVE, r.scopeAssessment().use)
        }
    }
    @Test fun strictMetadataUnitsQuotasAndFiniteValues() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertFails { AttributedManualValue(value, ManualValueUnit.KPA).validate() }
        assertFails { AttributedManualValue(1.0, ManualValueUnit.OTHER_REPORTED).validate() }
        assertFails { AttributedManualValue(1.0, ManualValueUnit.KPA, "unexpected").validate() }
        assertFails { ReportedDetail(ReportKnowledge.UNKNOWN, "guess").validate() }
        assertFails { ReportedDetail(ReportKnowledge.REPORTED).validate() }
        assertFails { OwnerReportSelection(ReportSelection.UNRESOLVED, "report").validate() }
        assertFails { OwnerReportSelection(ReportSelection.SELECTED).validate() }
        assertFails { outcome().copy(author = "x".repeat(4001)).validate() }
        assertFails { outcome().copy(reason = "bad\u0000text").validate() }
        assertFails { outcome().copy(values = List(33) { outcome().values.single() }).validate() }
        assertFails { outcome().copy(authorEffects = outcome().authorEffects + outcome().authorEffects).validate() }
        assertFails { outcome().copy(context = outcome().context.copy(catalog = OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 2))).validate() }
    }
}
