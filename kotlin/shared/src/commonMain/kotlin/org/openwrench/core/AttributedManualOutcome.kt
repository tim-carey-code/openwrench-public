package org.openwrench.core

/** Dormant contract vocabulary only. None of these types is a vehicle action or qualified OBD input. */
enum class ManualFaultMode { UNWANTED_FLOW_WHEN_EXPECTED_SEALED, UNKNOWN }
enum class ManualOutcomeMethod { SEALING_FLOW_MEASUREMENT, CLICK_OR_ACKNOWLEDGMENT, ELECTRICAL_CONTINUITY, PRESSURE_DECAY_ONLY, DOCUMENT_REVIEW, UNKNOWN }
enum class DeclaredManualOutcome { NORMAL_REPORTED_FOR_SCOPE, ABNORMAL_REPORTED_FOR_SCOPE, INCONCLUSIVE, UNAVAILABLE, NOT_APPLICABLE, UNKNOWN }
enum class ManualOutcomeOrigin { AUTHOR_REPORTED_MANUAL, SYNTHETIC, UNKNOWN }
enum class ReportKnowledge { REPORTED, UNKNOWN }
enum class ReportSelection { SELECTED, UNRESOLVED }
enum class ReportedApplicability { OWNER_REPORTED_MATCH, OWNER_REPORTED_MISMATCH, UNKNOWN }
enum class ManualValueUnit { KPA, LITRES_PER_MINUTE, MILLILITRES_PER_MINUTE, OTHER_REPORTED, UNKNOWN }
enum class ManualScopeUse { SCOPED_AUTHOR_ASSERTION_ONLY, NON_DISCRIMINATING_FOR_SEALING_FLOW, INCOMPLETE_OR_INCONCLUSIVE, SYNTHETIC_ONLY }

data class ReportedDetail(val knowledge: ReportKnowledge, val text: String? = null) {
    fun validate() { if (knowledge == ReportKnowledge.UNKNOWN) require(text == null) else manualContractText(requireNotNull(text)) }
}
data class OwnerReportSelection(val status: ReportSelection, val revisionId: String? = null) {
    fun validate() { if (status == ReportSelection.UNRESOLVED) require(revisionId == null) else DiagnosticInvestigation.validId(requireNotNull(revisionId)) }
}
data class OutcomeCatalogReference(val questionId: InvestigationQuestionId, val version: Int)
data class ManualOutcomeContext(val questionRevisionId: String, val caseId: String,
    val catalog: OutcomeCatalogReference?, val startingReport: OwnerReportSelection, val timingReport: OwnerReportSelection,
    val observationIds: List<String>, val retainedEvidence: List<InvestigationEvidenceRef>,
    val adaptationReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED)) {
    fun validate() {
        DiagnosticInvestigation.validId(questionRevisionId); DiagnosticInvestigation.validId(caseId)
        startingReport.validate(); timingReport.validate(); adaptationReport.validate()
        require(observationIds.size <= 32 && observationIds.distinct().size == observationIds.size)
        observationIds.forEach(DiagnosticInvestigation::validId)
        require(retainedEvidence.size <= 32 && retainedEvidence.distinct().size == retainedEvidence.size)
        retainedEvidence.forEach { require(it.caseId == caseId) { "One explicitly selected retained case per outcome scope" } }
        catalog?.let { require(it.version == InvestigationQuestionCatalog.VERSION && it.questionId == InvestigationQuestionId.VAPOR_SEALING) { "Only catalog-1 sealing/flow scope is supported" } }
    }
}
/** Externally recorded manual value; no PID, decoder or conversion into a fuel interval exists. */
data class AttributedManualValue(val value: Double, val unit: ManualValueUnit, val reportedUnit: String? = null) {
    fun validate() {
        require(value.isFinite())
        if (unit == ManualValueUnit.OTHER_REPORTED) manualContractText(requireNotNull(reportedUnit), 100) else require(reportedUnit == null)
    }
}
data class ManualScopeAssessment(val use: ManualScopeUse, val gaps: List<String>, val limitations: List<String>)

data class AttributedManualOutcome(val id: String, val sequence: Int, val recordedAtEpochMillis: Long,
    val author: String, val reason: String, val context: ManualOutcomeContext,
    val origin: ManualOutcomeOrigin, val faultMode: ManualFaultMode, val method: ManualOutcomeMethod,
    val declaredOutcome: DeclaredManualOutcome, val observer: ReportedDetail, val methodDetail: ReportedDetail,
    val conditions: ReportedDetail, val criteriaReference: ReportedDetail, val applicability: ReportedApplicability,
    val observedAtEpochMillis: Long?, val provenance: String, val values: List<AttributedManualValue>,
    val authorEffects: List<InvestigationEffect>, val supersedesResultId: String? = null) {
    fun validate() {
        DiagnosticInvestigation.validId(id); require(sequence in 1..DiagnosticInvestigation.MAX_REVISIONS && recordedAtEpochMillis > 0)
        manualContractText(author); manualContractText(reason); manualContractText(provenance)
        context.validate(); observer.validate(); methodDetail.validate(); conditions.validate(); criteriaReference.validate()
        require(observedAtEpochMillis == null || observedAtEpochMillis > 0)
        require(values.size <= 32); values.forEach { it.validate() }
        require(authorEffects.size <= 32 && authorEffects.map { it.hypothesisId }.distinct().size == authorEffects.size)
        authorEffects.forEach {
            DiagnosticInvestigation.validId(it.hypothesisId); manualContractText(it.rationale)
            if (declaredOutcome !in setOf(DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE))
                require(it.effect == HypothesisEffect.UNCHANGED) { "Unknown/inconclusive/unavailable outcomes cannot claim support or weakening" }
        }
        supersedesResultId?.let(DiagnosticInvestigation::validId)
    }
    /** Checks metadata/method discrimination only, never validates a mechanical result or OEM criterion. */
    fun scopeAssessment(): ManualScopeAssessment {
        validate()
        val gaps = buildList {
            if (origin == ManualOutcomeOrigin.UNKNOWN) add("Manual origin is unknown.")
            if (faultMode == ManualFaultMode.UNKNOWN) add("Tested fault mode is unknown.")
            if (method != ManualOutcomeMethod.SEALING_FLOW_MEASUREMENT) add("Click/acknowledgment, continuity, pressure decay or review alone is not sealing/flow proof.")
            if (context.catalog == null) add("Question has no supported sealing/flow catalog binding.")
            if (observer.knowledge == ReportKnowledge.UNKNOWN) add("Observer is unknown.")
            if (methodDetail.knowledge == ReportKnowledge.UNKNOWN) add("Actual external method is unknown.")
            if (conditions.knowledge == ReportKnowledge.UNKNOWN) add("Tested conditions are unknown.")
            if (criteriaReference.knowledge == ReportKnowledge.UNKNOWN) add("Exact criteria/document reference is unknown.")
            if (applicability != ReportedApplicability.OWNER_REPORTED_MATCH) add("Exact vehicle/ECU applicability is unknown or reported mismatched.")
            if (observedAtEpochMillis == null) add("Actual observation time is unknown; recording time is separate.")
            if (values.isEmpty() || values.any { it.unit in setOf(ManualValueUnit.UNKNOWN, ManualValueUnit.OTHER_REPORTED) }) add("Known-unit manual measurement data is incomplete.")
            if (declaredOutcome !in setOf(DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE, DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE)) add("Declared outcome is inconclusive, unavailable, inapplicable or unknown.")
        }
        val use = when {
            origin == ManualOutcomeOrigin.SYNTHETIC -> ManualScopeUse.SYNTHETIC_ONLY
            method in setOf(ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT, ManualOutcomeMethod.ELECTRICAL_CONTINUITY, ManualOutcomeMethod.PRESSURE_DECAY_ONLY, ManualOutcomeMethod.DOCUMENT_REVIEW) -> ManualScopeUse.NON_DISCRIMINATING_FOR_SEALING_FLOW
            gaps.isNotEmpty() -> ManualScopeUse.INCOMPLETE_OR_INCONCLUSIVE
            else -> ManualScopeUse.SCOPED_AUTHOR_ASSERTION_ONLY
        }
        return ManualScopeAssessment(use, gaps, listOf(
            "Declared outcomes and effects are author-attributed, not verified mechanical results or causation; synthetic records are illustrations only.",
            "Reported normal may weaken only the tested fault mode as an author judgment under the stated conditions; intermittent/unknown behavior and competing hypotheses remain open.",
            "Pressure decay alone cannot identify a leaking component. No failed-component or repair-success conclusion is generated.",
            "Manual values never qualify standard-OBD intervals. No OEM threshold, actuator compatibility, executable procedure or vehicle command is supplied."))
    }
}

internal fun manualContractText(text: String, maximum: Int = 4_000) {
    require(text.isNotBlank() && text.length <= maximum && '\u0000' !in text)
}
