package org.openwrench.core

/** Offline investigation policy 1. This journal has no transport or executable test API. */
enum class InvestigationObservationKind { REPORTED, MANUALLY_OBSERVED, CASE_SCAN_READING, CASE_FUEL_INTERVAL }
enum class InvestigationTestKind { REVIEW_RETAINED_EVIDENCE, MANUAL_RESULT_ONLY, ACTUATOR_UNSUPPORTED }
enum class InvestigationOutcome { RECORDED, INCONCLUSIVE, UNAVAILABLE, UNSUPPORTED }
enum class HypothesisEffect { SUPPORTS, WEAKENS, UNCHANGED }

/** Selects actual retained evidence, never a chart, report sentence or inferred bank. */
data class InvestigationEvidenceRef(val caseId: String, val kind: InvestigationObservationKind,
    val pid: Int? = null, val findingIndex: Int? = null, val intervalIndex: Int? = null)

sealed interface InvestigationStep {
    data class Complaint(val reported: String) : InvestigationStep
    data class Observation(val kind: InvestigationObservationKind, val description: String,
        val provenance: String, val evidence: InvestigationEvidenceRef? = null) : InvestigationStep
    data class Hypothesis(val explanation: String, val observationIds: List<String>) : InvestigationStep
    /** Describes what an outcome would distinguish; never contains a command or procedure runner. */
    data class ProposedTest(val kind: InvestigationTestKind, val question: String,
        val hypothesisIds: List<String>, val distinguishingOutcomes: String) : InvestigationStep
    data class TestResult(val testId: String, val outcome: InvestigationOutcome, val result: String,
        val provenance: String, val observationIds: List<String>, val effects: List<InvestigationEffect>,
        val supersedesResultId: String? = null) : InvestigationStep
    /** Manual repair/symptom follow-up only; eligibility of numeric comparison is evaluated separately. */
    data class RepairReview(val beforeObservationId: String, val afterObservationId: String,
        val repairReported: String, val symptomReported: String, val provenance: String) : InvestigationStep
}
data class InvestigationEffect(val hypothesisId: String, val effect: HypothesisEffect, val rationale: String)
data class InvestigationRevision(val id: String, val sequence: Int, val recordedAtEpochMillis: Long,
    val author: String, val reason: String, val step: InvestigationStep)

/** Persisted revisions are immutable. A correction appends a result explicitly superseding its predecessor. */
data class DiagnosticInvestigation(val id: String, val revisions: List<InvestigationRevision>, val policyVersion: Int = 1) {
    fun append(id: String, atEpochMillis: Long, author: String, reason: String, step: InvestigationStep): DiagnosticInvestigation =
        copy(revisions = revisions + InvestigationRevision(id, revisions.size + 1, atEpochMillis, author, reason, step))
            .also { it.validate() }

    fun activeResults(): List<InvestigationRevision> {
        validate()
        val superseded = revisions.mapNotNull { (it.step as? InvestigationStep.TestResult)?.supersedesResultId }.toSet()
        return revisions.filter { it.step is InvestigationStep.TestResult && it.id !in superseded }
    }

    fun validate() {
        validId(id); require(policyVersion == 1 && revisions.size in 1..MAX_REVISIONS)
        require(revisions.first().step is InvestigationStep.Complaint) { "Start with a reported complaint" }
        val prior = mutableMapOf<String, InvestigationStep>()
        val superseded = mutableSetOf<String>()
        fun observations(ids: List<String>) {
            require(ids.isNotEmpty() && ids.size <= 32 && ids.distinct().size == ids.size)
            require(ids.all { prior[it] is InvestigationStep.Observation }) { "Observation reference is missing or forward" }
        }
        revisions.forEachIndexed { index, r ->
            validId(r.id); require(r.id !in prior && r.sequence == index + 1 && r.recordedAtEpochMillis > 0)
            text(r.author); text(r.reason)
            when (val s = r.step) {
                is InvestigationStep.Complaint -> text(s.reported)
                is InvestigationStep.Observation -> {
                    text(s.description); text(s.provenance)
                    if (s.kind in setOf(InvestigationObservationKind.REPORTED, InvestigationObservationKind.MANUALLY_OBSERVED))
                        require(s.evidence == null)
                    else {
                        val e = requireNotNull(s.evidence); validId(e.caseId); require(e.kind == s.kind)
                        when (s.kind) {
                            InvestigationObservationKind.CASE_SCAN_READING -> require(e.pid in 0..255 && e.findingIndex == null && e.intervalIndex == null)
                            InvestigationObservationKind.CASE_FUEL_INTERVAL -> require(e.pid == null && (e.findingIndex ?: -1) >= 0 && (e.intervalIndex ?: -1) >= 0)
                            else -> error("Unreachable")
                        }
                    }
                }
                is InvestigationStep.Hypothesis -> { text(s.explanation); observations(s.observationIds) }
                is InvestigationStep.ProposedTest -> {
                    text(s.question); text(s.distinguishingOutcomes)
                    require(s.hypothesisIds.size in 2..32 && s.hypothesisIds.distinct().size == s.hypothesisIds.size) { "A test must distinguish competing hypotheses" }
                    require(s.hypothesisIds.all { prior[it] is InvestigationStep.Hypothesis })
                }
                is InvestigationStep.TestResult -> {
                    val test = prior[s.testId] as? InvestigationStep.ProposedTest ?: error("Missing proposed test")
                    text(s.result); text(s.provenance)
                    if (s.observationIds.isNotEmpty()) observations(s.observationIds)
                    require(s.effects.size <= 32 && s.effects.map { it.hypothesisId }.distinct().size == s.effects.size)
                    if (test.kind == InvestigationTestKind.ACTUATOR_UNSUPPORTED) require(s.outcome == InvestigationOutcome.UNSUPPORTED)
                    s.effects.forEach { effect ->
                        require(effect.hypothesisId in test.hypothesisIds); text(effect.rationale)
                        if (effect.effect != HypothesisEffect.UNCHANGED) {
                            require(s.outcome == InvestigationOutcome.RECORDED && s.observationIds.isNotEmpty())
                            require(s.observationIds.all { (prior[it] as InvestigationStep.Observation).kind != InvestigationObservationKind.REPORTED }) {
                                "Reported claims cannot qualify a hypothesis update"
                            }
                        }
                    }
                    s.supersedesResultId?.let { oldId ->
                        val old = prior[oldId] as? InvestigationStep.TestResult ?: error("Missing superseded result")
                        require(old.testId == s.testId && superseded.add(oldId)) { "Supersede the active result for this test" }
                    }
                    val active = prior.filter { (id, step) -> step is InvestigationStep.TestResult && step.testId == s.testId && id !in superseded }
                    require(active.isEmpty()) { "Repeated results must explicitly supersede the active result" }
                }
                is InvestigationStep.RepairReview -> {
                    observations(listOf(s.beforeObservationId, s.afterObservationId))
                    text(s.repairReported); text(s.symptomReported); text(s.provenance)
                }
            }
            prior[r.id] = r.step
        }
    }
    companion object {
        const val MAX_REVISIONS = 500
        fun validId(id: String) { require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))) }
        private fun text(value: String) { require(value.isNotBlank() && value.length <= 4_000 && value.none { it == '\u0000' }) }
    }
}
