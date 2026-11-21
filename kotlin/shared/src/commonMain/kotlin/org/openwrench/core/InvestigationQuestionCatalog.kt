package org.openwrench.core

/** Versioned questions and explicit owner-report vocabulary, not OEM procedures or executable tests. */
enum class StartingBehavior(val label: String) {
    SLOW_OR_NO_CRANK("slow or no crank"), NORMAL_SPEED_LONG_CRANK("normal-speed long crank"),
    STARTS_THEN_STALLS("starts then stalls"), ROUGH_RUNNING("rough running"), UNKNOWN("unknown")
}
enum class SymptomTiming(val label: String) {
    DIFFERENT_OCCASIONS("different occasions"), SAME_OCCASION_REPORTED("same occasion reported, not verified"), UNKNOWN("unknown")
}
enum class AdaptationHistory(val label: String) { UNCHANGED_REPORTED("unchanged reported"), RESET_OR_CHANGED_REPORTED("reset or changed reported"), UNKNOWN("unknown") }
enum class InvestigationQuestionId {
    CLARIFY_START, CRANK_SCOPE, SEPARATE_EVENTS, QUALIFY_CORRECTION, BANK_COVERAGE,
    BANK_COMPARABILITY, SHARED_VS_BANK_SPECIFIC, INPUT_PLAUSIBILITY, VAPOR_SEALING,
    FUEL_DELIVERY, RESULT_SCOPE, REPAIR_FOLLOW_UP
}
data class InvestigationCatalogSource(val id: String, val title: String, val url: String,
    val scope: String, val limitation: String)
data class InvestigationQuestion(val id: InvestigationQuestionId, val question: String,
    val alternatives: List<String>, val distinguishingOutcomes: String,
    val prerequisites: List<String>, val limitations: List<String>, val sourceIds: List<String>,
    val requiresOemProcedure: Boolean = false) {
    fun proposal(hypothesisIds: List<String>): InvestigationStep.ProposedTest {
        require(hypothesisIds.size in 2..32 && hypothesisIds.distinct().size == hypothesisIds.size)
        return InvestigationStep.ProposedTest(if (requiresOemProcedure) InvestigationTestKind.MANUAL_RESULT_ONLY else
            InvestigationTestKind.REVIEW_RETAINED_EVIDENCE, question, hypothesisIds.toList(), distinguishingOutcomes)
    }
}

object InvestigationQuestionCatalog {
    const val VERSION = 1
    private const val GENERAL = "Question only: no vehicle procedure, executable action, fault verdict or probability."
    private const val OEM = "Obtain the exact vehicle/engine/ECU OEM procedure and specifications, applicable conditions, safe method and compatible tools; none is supplied or verified here."
    val sources = listOf(
        InvestigationCatalogSource("holden", "Snap-on Holden Engine Troubleshooter Reference Manual, v9.2 (2009)",
            "https://www.snapon.com/DiagnosticsManuals/PDF/AU/VCS/Holden_Engine_Troubleshooter_Reference_Manual.pdf",
            "Holden historical reference: adaptation and alternative rich-correction explanations.",
            "Illustrative principles only; not a 2013 Subaru specification, part diagnosis or instruction to reset adaptations."),
        InvestigationCatalogSource("ford", "Ford 2013 MY OBD System Operation Summary, fuel monitor p56",
            "https://www.fordservicecontent.com/ford_content/catalog/motorcraft/OBDSM1300.pdf",
            "Ford monitor entry conditions illustrate context-dependent interpretation.",
            "Ford thresholds and monitor preconditions are not Subaru specifications or OpenWrench warm-up rules."),
        InvestigationCatalogSource("subaru", "Subaru Special Edition TechTIPS, MC-10219476 (2022), pp2-4",
            "https://static.nhtsa.gov/odi/tsbs/2022/MC-10219476-0001.pdf",
            "EVAP overview lists 2013-on Legacy; the test-entry table lists 2015-22 Legacy/Outback.",
            "Does not establish the applicable 2013 Legacy 3.6R procedure, sealing specification or actuator compatibility.")
    )
    private fun q(id: InvestigationQuestionId, question: String, alternatives: List<String>, outcomes: String,
        prerequisites: List<String> = emptyList(), limits: List<String> = emptyList(), sources: List<String> = emptyList(), oem: Boolean = false) =
        InvestigationQuestion(id, question, alternatives, outcomes, prerequisites + if (oem) listOf(OEM) else emptyList(),
            limits + GENERAL, sources, oem)
    val questions: List<InvestigationQuestion> = listOf(
        q(InvestigationQuestionId.CLARIFY_START, "What does the owner mean by difficult starting?",
            listOf("Slow/no crank", "Normal-speed long crank", "Starts then stalls", "Rough running"),
            "Record the owner's category and occasion, or unknown. A later running fuel-correction observation cannot identify the starting category."),
        q(InvestigationQuestionId.CRANK_SCOPE, "Is the reported slow/no-crank complaint distinct from later engine-running evidence?",
            listOf("Cranking-speed/electrical/mechanical scope", "A separate engine-running correction observation"),
            "Clarify the reported cranking behavior before a fuel-correction path. No battery, starter or fuel-system fault is established.",
            limits = listOf("No live cranking procedure or electrical specification is supplied.")),
        q(InvestigationQuestionId.SEPARATE_EVENTS, "Were the reported starting symptoms and retained running correction observed on the same occasion?",
            listOf("Earlier starting symptom", "Later closed-loop running observation"),
            "Record the reported timing separately. Even a same-occasion report does not establish cold-ignition measurements or a shared cause.",
            limits = listOf("No cold-start or fully-warm classification is inferred from a retained interval or coolant number."), sources = listOf("ford")),
        q(InvestigationQuestionId.QUALIFY_CORRECTION, "Does this case retain qualified same-bank fuel-correction evidence?",
            listOf("A reported or isolated negative number", "A retained qualified paired interval"),
            "Only existing receipt-qualified intervals support an observed sustained pattern. Missing/short/stale/duplicate receipts and charts cannot substitute.",
            limits = listOf("No new numeric qualification or universal temperature threshold is introduced.")),
        q(InvestigationQuestionId.BANK_COVERAGE, "Is the other bank applicable, and is comparable qualified evidence actually retained?",
            listOf("Comparable evidence in both banks", "Unavailable, unqualified or inapplicable other-bank data"),
            "Missing bank evidence stays unavailable, never normal. Confirm applicability before considering a shared versus bank-specific question."),
        q(InvestigationQuestionId.BANK_COMPARABILITY, "Do the retained bank intervals share recorded source, time and operating conditions?",
            listOf("Comparable bank observations", "Different or unknown source/time/context"),
            "Use matching policy, fuel/motion state, overlapping receipt times and RPM/load/coolant ranges. Incomparable intervals cannot establish a bank difference."),
        q(InvestigationQuestionId.SHARED_VS_BANK_SPECIFIC, "Do comparable bank patterns motivate a shared-input question or a bank-specific question?",
            listOf("Shared input or delivery explanation", "Bank-specific explanation or remaining context difference"),
            "Matching or differing observed patterns motivate different questions only; neither proves a common or local failed component.",
            prerequisites = listOf("Comparable qualified intervals from both banks in this same retained drive.")),
        q(InvestigationQuestionId.INPUT_PLAUSIBILITY, "What applicable evidence distinguishes input/feedback plausibility from unwanted fuel or vapor delivery?",
            listOf("Air/temperature/feedback input plausibility", "Unwanted fuel or vapor delivery"),
            "An applicable independently corroborated input result may support or weaken only that tested mode. Unknown units, conditions or reference data remain inconclusive.",
            prerequisites = listOf("Retained qualified negative correction; observation time, units, method and engine conditions."),
            limits = listOf("No normal MAF threshold or Subaru reference range is supplied."), sources = listOf("holden", "ford"), oem = true),
        q(InvestigationQuestionId.VAPOR_SEALING, "Does applicable sealing/flow evidence distinguish unwanted vapor delivery from the alternatives?",
            listOf("Unwanted vapor delivery under the tested conditions", "Other delivery/input explanation or intermittent behavior"),
            "Documented sealing/flow failure may support the tested leakage mode, not the whole causal explanation. A passing result weakens only that mode under the tested conditions; intermittent behavior remains open.",
            prerequisites = listOf("Retained qualified negative correction and exact evaporative-system applicability."),
            limits = listOf("A click, command acknowledgment or electrical continuity is not sealing/flow proof. No actuation or service test is executable."), sources = listOf("subaru"), oem = true),
        q(InvestigationQuestionId.FUEL_DELIVERY, "Does applicable fuel-delivery evidence distinguish excess delivery from input bias or vapor delivery?",
            listOf("Unwanted fuel delivery", "Input/feedback bias, vapor delivery or untested intermittent behavior"),
            "Record method, conditions, applicable OEM criteria and the scope of the result. A normal result weakens only the tested fault mode, not every possible delivery problem.",
            prerequisites = listOf("Retained qualified negative correction; exact fuel-system applicability and independent measurement provenance."),
            limits = listOf("Pressure decay alone cannot identify a leaking component. No pressure specification or live test procedure is supplied."), sources = listOf("holden"), oem = true),
        q(InvestigationQuestionId.RESULT_SCOPE, "What fault mode and conditions did the recorded result actually distinguish?",
            listOf("Scoped supporting or contradicting evidence", "Indirect, unknown, intermittent or inconclusive evidence"),
            "Retain author attribution. Do not reinterpret support/weakening as causation; do not treat a click/continuity as flow proof or pressure decay as component identification.",
            limits = listOf("A normal result weakens only the tested mode under the tested conditions. No automatic hypothesis closure.")),
        q(InvestigationQuestionId.REPAIR_FOLLOW_UP, "What changed separately in the reported symptom, comparable correction evidence and adaptation/reset history?",
            listOf("Reported symptom outcome", "Comparable recorded correction outcome", "Adaptation/reset or condition differences"),
            "Record these tracks separately. Trim improvement alone does not establish repair success or symptom resolution; unknown/reset adaptation history remains a comparison limitation.",
            prerequisites = listOf("Explicit same-vehicle confirmation and compatible recorded conditions before numeric comparison."), sources = listOf("holden"))
    )
    fun question(id: InvestigationQuestionId) = questions.single { it.id == id }
    /** Exact v1 template recognition only; edited/custom questions remain custom, never parsed into diagnoses. */
    fun identify(step: InvestigationStep.ProposedTest): InvestigationQuestionId? = questions.singleOrNull { q ->
        val template = q.proposal(step.hypothesisIds)
        template.kind == step.kind && template.question == step.question && template.distinguishingOutcomes == step.distinguishingOutcomes
    }?.id
    fun startingReport(value: StartingBehavior, provenance: String) = report("Reported starting behavior: ${value.label}", provenance)
    fun timingReport(value: SymptomTiming, provenance: String) = report("Reported symptom/correction timing: ${value.label}", provenance)
    fun adaptationReport(value: AdaptationHistory, provenance: String) = report("Reported adaptation/reset history: ${value.label}", provenance)
    private fun report(text: String, provenance: String): InvestigationStep.Observation {
        require(provenance.isNotBlank() && provenance.length <= 4_000 && '\u0000' !in provenance)
        return InvestigationStep.Observation(InvestigationObservationKind.REPORTED, text, provenance)
    }
    fun startingValue(step: InvestigationStep) = (step as? InvestigationStep.Observation)?.takeIf { it.kind == InvestigationObservationKind.REPORTED }
        ?.let { s -> StartingBehavior.entries.singleOrNull { s.description == startingReport(it, "lookup").description } }
    fun timingValue(step: InvestigationStep) = (step as? InvestigationStep.Observation)?.takeIf { it.kind == InvestigationObservationKind.REPORTED }
        ?.let { s -> SymptomTiming.entries.singleOrNull { s.description == timingReport(it, "lookup").description } }
    fun adaptationValue(step: InvestigationStep) = (step as? InvestigationStep.Observation)?.takeIf { it.kind == InvestigationObservationKind.REPORTED }
        ?.let { s -> AdaptationHistory.entries.singleOrNull { s.description == adaptationReport(it, "lookup").description } }
}
