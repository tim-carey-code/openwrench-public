package org.openwrench.core

import kotlin.test.*

class InvestigationQuestionCatalogTest {
    @Test fun catalogIsCompleteUniquelyIdentifiableAndEveryQuestionDistinguishesAlternatives() {
        val catalog = InvestigationQuestionCatalog
        assertEquals(InvestigationQuestionId.entries.toSet(), catalog.questions.map { it.id }.toSet())
        assertEquals(catalog.questions.size, catalog.questions.map { it.question }.distinct().size)
        catalog.questions.forEach { question ->
            assertTrue(question.alternatives.size >= 2)
            assertTrue(question.distinguishingOutcomes.isNotBlank())
            assertTrue(question.limitations.isNotEmpty())
            assertTrue(question.sourceIds.all { id -> catalog.sources.any { it.id == id } })
            val proposal = question.proposal(listOf("h1", "h2"))
            assertEquals(question.id, catalog.identify(proposal))
            assertNull(catalog.identify(proposal.copy(question = "Edited custom question")))
            if (question.requiresOemProcedure) {
                assertEquals(InvestigationTestKind.MANUAL_RESULT_ONLY, proposal.kind)
                assertTrue(question.prerequisites.any { "OEM procedure" in it })
            }
            assertFalse(proposal.kind == InvestigationTestKind.ACTUATOR_UNSUPPORTED)
        }
    }
    @Test fun explicitAnswerHelpersRemainReportsAndCustomProseIsNeverParsed() {
        val catalog = InvestigationQuestionCatalog
        StartingBehavior.entries.forEach { value ->
            val report = catalog.startingReport(value, "Owner report at an unknown observation time")
            assertEquals(InvestigationObservationKind.REPORTED, report.kind)
            assertNull(report.evidence)
            assertEquals(value, catalog.startingValue(report))
            assertNull(catalog.startingValue(report.copy(kind = InvestigationObservationKind.MANUALLY_OBSERVED)))
        }
        assertNull(catalog.startingValue(InvestigationStep.Complaint("2013 Legacy 3.6R: cold start, normal speed, -16%, both banks")))
        assertFails { catalog.startingReport(StartingBehavior.ROUGH_RUNNING, "") }
        SymptomTiming.entries.forEach { assertEquals(it, catalog.timingValue(catalog.timingReport(it, "Owner reported"))) }
        AdaptationHistory.entries.forEach { assertEquals(it, catalog.adaptationValue(catalog.adaptationReport(it, "Owner reported"))) }
    }
    @Test fun catalogKeepsIndirectTestsNormalResultScopeAndOemApplicabilityExplicit() {
        val catalog = InvestigationQuestionCatalog
        val vapor = catalog.question(InvestigationQuestionId.VAPOR_SEALING)
        assertTrue(vapor.limitations.any { "click" in it && "continuity" in it && "sealing/flow" in it })
        assertContains(vapor.distinguishingOutcomes, "tested conditions")
        assertTrue(catalog.question(InvestigationQuestionId.FUEL_DELIVERY).limitations.any { "Pressure decay alone" in it })
        assertTrue(catalog.sources.all { "Subaru" in it.limitation || "2013 Legacy" in it.limitation })
        assertContains(catalog.sources.single { it.id == "subaru" }.limitation, "Does not establish")
        assertContains(catalog.question(InvestigationQuestionId.REPAIR_FOLLOW_UP).distinguishingOutcomes, "Trim improvement alone")
    }
}
