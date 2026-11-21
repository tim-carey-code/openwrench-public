package org.openwrench.frontend.android

import org.openwrench.core.*

/** Review-only in-memory contract; no store/path, migration, recommendation or UI activation. */
data class ManualOutcomeContract(val legacyJson: String, val outcomes: List<AttributedManualOutcome>) {
    fun legacySnapshot(): SavedInvestigation = InvestigationCodec.decode(legacyJson)
    fun validate() { checkedActiveResults() }
    fun activeResultIds(): Map<String, String> = checkedActiveResults()
    private fun checkedActiveResults(): Map<String, String> {
        val base = legacySnapshot()
        require(base.journal.revisions.size + outcomes.size <= DiagnosticInvestigation.MAX_REVISIONS)
        val revisions = base.journal.revisions.associateBy { it.id }
        val usedIds = revisions.keys.toMutableSet()
        val active = base.journal.activeResults().associate { it.step.let { s -> (s as InvestigationStep.TestResult).testId } to it.id }.toMutableMap()
        outcomes.forEachIndexed { index, result ->
            result.validate()
            require(result.sequence == base.journal.revisions.size + index + 1 && usedIds.add(result.id)) { "Outcome IDs and sequence must extend the frozen legacy trail" }
            validateManualOutcomeReferences(base, result, revisions)
            val context = result.context
            val previous = active[context.questionRevisionId]
            if (result.supersedesResultId != previous) throw ManualOutcomeFieldValidation("outcome.supersedesResultId",
                "Explicitly supersede only the active result of this same question")
            active[context.questionRevisionId] = result.id
        }
        return active.toMap()
    }
}

/** Shared reference rules with stable field attribution; both old and combined envelopes use them. */
internal class ManualOutcomeFieldValidation(val field: String, message: String) : IllegalArgumentException(message)
internal fun validateManualOutcomeReferences(base: SavedInvestigation, result: AttributedManualOutcome,
    revisions: Map<String, InvestigationRevision> = base.journal.revisions.associateBy { it.id }) {
    fun check(field: String, condition: Boolean, message: String) {
        if (!condition) throw ManualOutcomeFieldValidation(field, message)
    }
    val context = result.context
    val test = revisions[context.questionRevisionId]?.step as? InvestigationStep.ProposedTest
        ?: throw ManualOutcomeFieldValidation("outcome.context.questionRevisionId", "Select a prior proposed question")
    check("outcome.context.questionRevisionId", test.kind != InvestigationTestKind.ACTUATOR_UNSUPPORTED, "Actuator outcomes are outside this contract")
    check("outcome.context.caseId", base.cases.any { it.id == context.caseId }, "Select a prior retained case")
    context.catalog?.let { check("outcome.context.catalog", InvestigationQuestionCatalog.identify(test) == it.questionId, "Catalog reference must match the exact frozen question") }
    fun owner(selection: OwnerReportSelection, field: String, accepts: (InvestigationStep) -> Boolean) {
        selection.revisionId?.let { id ->
            check(field, revisions[id]?.step?.let(accepts) == true,
                "Select a prior reported catalog answer of the correct kind, including explicit UNKNOWN")
        }
    }
    owner(context.startingReport, "outcome.context.startingReport") { InvestigationQuestionCatalog.startingValue(it) != null }
    owner(context.timingReport, "outcome.context.timingReport") { InvestigationQuestionCatalog.timingValue(it) != null }
    owner(context.adaptationReport, "outcome.context.adaptationReport") { InvestigationQuestionCatalog.adaptationValue(it) != null }
    context.observationIds.forEachIndexed { index, id -> check("outcome.context.observationIds[$index]",
        revisions[id]?.step is InvestigationStep.Observation, "Select a prior observation, not a hypothesis or question") }
    context.retainedEvidence.forEachIndexed { index, reference ->
        try { validateManualRetainedEvidence(base, reference) }
        catch (error: Exception) { throw ManualOutcomeFieldValidation("outcome.context.retainedEvidence[$index]", error.message ?: "Invalid retained evidence reference") }
    }
    result.authorEffects.forEachIndexed { index, effect -> check("outcome.authorEffects[$index].hypothesisId",
        effect.hypothesisId in test.hypothesisIds, "Effect must refer to a competing hypothesis of this question") }
}
/** Base codec validates measurements and receipt policy; this only resolves exact references. */
private fun validateManualRetainedEvidence(base: SavedInvestigation, e: InvestigationEvidenceRef) {
    val case = base.cases.singleOrNull { it.id == e.caseId } ?: error("Evidence case is missing")
    when (e.kind) {
        InvestigationObservationKind.CASE_SCAN_READING -> {
            require(e.pid in 0..255 && e.findingIndex == null && e.intervalIndex == null)
            val capture = requireNotNull(case.scan.capture)
            require(e.pid in capture.eligiblePids && capture.readings.singleOrNull { it.pid == e.pid }?.value != null) { "No actual retained scan value" }
        }
        InvestigationObservationKind.CASE_FUEL_INTERVAL -> {
            require(e.pid == null && (e.findingIndex ?: -1) >= 0 && (e.intervalIndex ?: -1) >= 0)
            require(case.drive.review.assessment?.findings?.getOrNull(e.findingIndex!!)?.fuelIntervals?.getOrNull(e.intervalIndex!!) != null) { "No retained qualified interval" }
        }
        else -> error("Reported/manual observations are not retained measurement references")
    }
}
