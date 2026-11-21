package org.openwrench.frontend.android

import org.openwrench.core.*

enum class BankEvidenceRelationship { MISSING_QUALIFIED_EVIDENCE, INCOMPARABLE, COMPARABLE_SAME_PATTERN, COMPARABLE_DIFFERENT_PATTERN }
enum class AttributedJudgment { SUPPORTING, CONTRADICTING, INCONCLUSIVE }
data class ReportedReasoningAnswer<T>(val revisionId: String, val value: T, val provenance: String)
data class RetainedCorrectionBasis(val reference: InvestigationEvidenceRef, val driveId: String,
    val origin: DriveEvidenceOrigin, val sourceEcu: String?, val interval: FuelCorrectionInterval) {
    val removesFuelBeyondExistingCue get() = interval.screened && FuelCorrectionRecorder.direction(interval.pairs.first().long.value) == -1
    val description get() = "Historical $origin Bank ${interval.bank}: qualified paired STFT ${interval.pairs.minOf { it.short.value }}..${interval.pairs.maxOf { it.short.value }}%; " +
        "LTFT ${interval.pairs.minOf { it.long.value }}..${interval.pairs.maxOf { it.long.value }}%; receipt offsets ${interval.firstOffsetMillis}..${interval.lastOffsetMillis} ms; " +
        "coolant ${interval.contextBounds.coolant.minimum.value}..${interval.contextBounds.coolant.maximum.value} C; policy ${interval.policyVersion}; observed ECU ${sourceEcu ?: "unknown"}. No cold/warm or current-condition inference."
}
data class ReasoningHypothesisJudgment(val hypothesisId: String, val status: AttributedJudgment, val authorRationale: String)
data class ReasoningResult(val revisionId: String, val questionRevisionId: String,
    val catalogQuestionId: InvestigationQuestionId?, val outcome: InvestigationOutcome,
    val author: String, val provenance: String, val reportedResult: String, val supersedesResultId: String?, val observationIds: List<String>,
    val judgments: List<ReasoningHypothesisJudgment>, val limitation: String)
data class InvestigationRecommendation(val question: InvestigationQuestion, val why: String,
    val evidence: List<InvestigationEvidenceRef>, val missingEvidence: List<String>, val activeResultIds: List<String>) {
    /** Descriptive API only. Even supplied OEM information would not authorize or execute a vehicle action. */
    val executable get() = false
}
data class RepairReasoningTrack(val revisionId: String, val reportedRepair: String, val reportedSymptom: String,
    val provenance: String, val fuelCorrectionOutcome: String = "NOT_EVALUATED: use existing compatible-evidence comparison separately; never a repair verdict")
data class InvestigationReasoningReport(val caseId: String, val catalogVersion: Int,
    val startingReports: List<ReportedReasoningAnswer<StartingBehavior>>, val selectedStartingReport: ReportedReasoningAnswer<StartingBehavior>?,
    val timingReports: List<ReportedReasoningAnswer<SymptomTiming>>, val selectedTimingReport: ReportedReasoningAnswer<SymptomTiming>?,
    val correctionEvidence: List<RetainedCorrectionBasis>, val bankRelationship: BankEvidenceRelationship,
    val comparedIntervals: List<InvestigationEvidenceRef>, val activeResults: List<ReasoningResult>,
    val repairTracks: List<RepairReasoningTrack>, val adaptationReports: List<ReportedReasoningAnswer<AdaptationHistory>>,
    val recommendations: List<InvestigationRecommendation>, val limitations: List<String>) {
    /** Ordering is evidence/prerequisite workflow, never a ranking of causes or their probabilities. */
    val nextQuestion get() = recommendations.first()
}

/** Deterministic, read-only adapter over the existing frozen case/ledger. Run off the UI thread.
 * Historic codec validation remains the only authority admitting qualified intervals; no chart/prose reconstruction.
 */
object InvestigationReasoning {
    fun recommend(saved: SavedInvestigation, caseId: String,
        selectedStartingRevisionId: String? = null, selectedTimingRevisionId: String? = null): InvestigationReasoningReport =
        recommendContext(saved, caseId, selectedStartingRevisionId, selectedTimingRevisionId, inferUnselected = true)

    /** Combined preview requires deliberate context selection; unresolved never means choose the latest report. */
    internal fun recommendExplicitContext(saved: SavedInvestigation, caseId: String,
        selectedStartingRevisionId: String?, selectedTimingRevisionId: String?): InvestigationReasoningReport =
        recommendContext(saved, caseId, selectedStartingRevisionId, selectedTimingRevisionId, inferUnselected = false)

    private fun recommendContext(saved: SavedInvestigation, caseId: String,
        selectedStartingRevisionId: String?, selectedTimingRevisionId: String?, inferUnselected: Boolean): InvestigationReasoningReport {
        saved.validate() // Reject malformed/short/stale/deduplicated archive claims before deriving anything.
        val case = saved.cases.singleOrNull { it.id == caseId } ?: error("Select a retained case in this investigation")
        val revisions = saved.journal.revisions
        fun <T> answers(value: (InvestigationStep) -> T?): List<ReportedReasoningAnswer<T>> = revisions.mapNotNull { r ->
            value(r.step)?.let { ReportedReasoningAnswer(r.id, it, (r.step as InvestigationStep.Observation).provenance) }
        }
        val starts = answers(InvestigationQuestionCatalog::startingValue)
        val timings = answers(InvestigationQuestionCatalog::timingValue)
        fun <T> select(values: List<ReportedReasoningAnswer<T>>, id: String?, unknown: T): ReportedReasoningAnswer<T>? {
            if (id != null) return (values.singleOrNull { it.revisionId == id }
                ?: error("Selected report is not an attributed catalog answer")).takeUnless { it.value == unknown }
            if (!inferUnselected) return null
            return values.lastOrNull()?.takeIf { it.value != unknown && values.map { a -> a.value }.distinct().size == 1 }
        }
        val start = select(starts, selectedStartingRevisionId, StartingBehavior.UNKNOWN)
        val timing = select(timings, selectedTimingRevisionId, SymptomTiming.UNKNOWN)
        val assessment = case.drive.review.assessment
        val facts = if (assessment?.packId == "standard-obd-drive" && assessment.packVersion == "3")
            assessment.findings.flatMapIndexed { findingIndex, finding -> finding.fuelIntervals.mapIndexed { intervalIndex, interval ->
                RetainedCorrectionBasis(InvestigationEvidenceRef(case.id, InvestigationObservationKind.CASE_FUEL_INTERVAL,
                    findingIndex = findingIndex, intervalIndex = intervalIndex), case.drive.id, case.drive.origin, case.drive.sourceEcu, interval)
            } }.distinctBy { it.interval } else emptyList()
        val bank1 = facts.filter { it.interval.bank == 1 }
        val bank2 = facts.filter { it.interval.bank == 2 }
        val comparable = bank1.flatMap { a -> bank2.map { b -> a to b } }.firstOrNull { (a, b) -> (a.removesFuelBeyondExistingCue || b.removesFuelBeyondExistingCue) && comparable(a, b) }
        val relationship = when {
            bank1.isEmpty() || bank2.isEmpty() -> BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE
            comparable == null -> BankEvidenceRelationship.INCOMPARABLE
            comparable.first.interval.pattern == comparable.second.interval.pattern &&
                FuelCorrectionRecorder.direction(comparable.first.interval.pairs.first().long.value) ==
                FuelCorrectionRecorder.direction(comparable.second.interval.pairs.first().long.value) -> BankEvidenceRelationship.COMPARABLE_SAME_PATTERN
            else -> BankEvidenceRelationship.COMPARABLE_DIFFERENT_PATTERN
        }
        val active = saved.journal.activeResults().map { revision ->
            val result = revision.step as InvestigationStep.TestResult
            val test = revisions.single { it.id == result.testId }.step as InvestigationStep.ProposedTest
            ReasoningResult(revision.id, result.testId, InvestigationQuestionCatalog.identify(test), result.outcome,
                revision.author, result.provenance, result.result, result.supersedesResultId, result.observationIds, result.effects.map { effect ->
                    ReasoningHypothesisJudgment(effect.hypothesisId, when {
                        result.outcome != InvestigationOutcome.RECORDED -> AttributedJudgment.INCONCLUSIVE
                        effect.effect == HypothesisEffect.SUPPORTS -> AttributedJudgment.SUPPORTING
                        effect.effect == HypothesisEffect.WEAKENS -> AttributedJudgment.CONTRADICTING
                        else -> AttributedJudgment.INCONCLUSIVE
                    }, effect.rationale)
                }, "Author-attributed judgment, not verified causation. A normal result weakens only the tested fault mode under tested conditions; intermittent/unknown behavior remains open. Free text is not interpreted as a passed test.")
        }
        val negative = facts.filter { it.removesFuelBeyondExistingCue }
        val questions = mutableListOf<InvestigationRecommendation>()
        fun add(id: InvestigationQuestionId, why: String, refs: List<InvestigationEvidenceRef> = emptyList(), gaps: List<String> = emptyList()) {
            val q = InvestigationQuestionCatalog.question(id)
            val prior = active.filter { it.catalogQuestionId == id }
            questions += InvestigationRecommendation(q, why + (if (refs.isEmpty()) "" else " Evidence basis: historical ${case.drive.origin}; observed source ${case.drive.sourceEcu ?: "unknown"}.") + if (prior.isEmpty()) "" else
                " Active author-recorded outcomes: ${prior.joinToString { "${it.revisionId}: ${it.outcome}" }}. They do not automatically close this question.",
                refs, gaps + if (q.requiresOemProcedure) q.prerequisites else emptyList(), prior.map { it.revisionId })
        }
        if (start == null) add(InvestigationQuestionId.CLARIFY_START, "Starting behavior is absent, unknown or conflicting. Reported percentages cannot classify a starting complaint.", gaps = listOf("An explicitly attributed starting-behavior report; conflicting reports require deliberate selection."))
        if (start?.value == StartingBehavior.SLOW_OR_NO_CRANK) {
            add(InvestigationQuestionId.CRANK_SCOPE, "The selected owner report is slow/no crank. Running trim evidence does not distinguish the cranking complaint.")
        } else {
            if (timing == null) add(InvestigationQuestionId.SEPARATE_EVENTS, "Cold-start narrative and retained engine-running closed-loop receipts are separate evidence domains.", gaps = listOf("Attributed timing report; no cold-ignition measurements are established."))
            if (negative.isEmpty()) add(InvestigationQuestionId.QUALIFY_CORRECTION,
                "No retained qualified negative-correction interval beyond the existing screening cue is established in this case. Reported -16%, scan points and charts are not substitutes.",
                gaps = listOf("Existing qualified same-bank paired interval, with its original policy and provenance."))
            else {
                if (active.any { r -> r.judgments.any { it.status != AttributedJudgment.INCONCLUSIVE } })
                    add(InvestigationQuestionId.RESULT_SCOPE, "An active result supports or contradicts a hypothesis only as an author judgment; method, tested fault mode and conditions still need review.")
                when (relationship) {
                    BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE -> add(InvestigationQuestionId.BANK_COVERAGE,
                        "A qualified negative interval is retained, but both banks do not have qualified evidence. Absent evidence is unavailable, never normal.", negative.map { it.reference },
                        listOf("Other-bank applicability/advertised support is not established by this archive; qualified comparable evidence is missing."))
                    BankEvidenceRelationship.INCOMPARABLE -> add(InvestigationQuestionId.BANK_COMPARABILITY,
                        "Both banks have intervals, but matching observed source, overlapping receipt time or compatible operating context is missing.", facts.map { it.reference }, listOf("Comparable same-drive bank intervals; no bank-pattern comparison is available."))
                    else -> add(InvestigationQuestionId.SHARED_VS_BANK_SPECIFIC,
                        if (relationship == BankEvidenceRelationship.COMPARABLE_SAME_PATTERN)
                            "The selected comparable bank intervals share recorded pattern/direction. This motivates a shared-input/delivery question, not a shared fault conclusion."
                        else "The selected comparable bank intervals differ in recorded pattern/direction. This motivates a bank-specific/context question, not proof of a local component failure.",
                        listOf(comparable!!.first.reference, comparable.second.reference))
                }
                for (id in listOf(InvestigationQuestionId.INPUT_PLAUSIBILITY, InvestigationQuestionId.VAPOR_SEALING, InvestigationQuestionId.FUEL_DELIVERY))
                    add(id, "Qualified negative correction motivates distinguishing input plausibility from unwanted fuel/vapor delivery. Applicable independent manual evidence is still required.", negative.map { it.reference })
            }
        }
        val repairs = revisions.mapNotNull { r -> (r.step as? InvestigationStep.RepairReview)?.let { RepairReasoningTrack(r.id, it.repairReported, it.symptomReported, it.provenance) } }
        val adaptations = answers(InvestigationQuestionCatalog::adaptationValue)
        if (repairs.isNotEmpty()) add(InvestigationQuestionId.REPAIR_FOLLOW_UP,
            "Repair/symptom reports are retained separately from correction evidence. Adaptation/reset history cannot be inferred from trim movement.",
            gaps = listOf("Independent symptom follow-up, compatible before/after evidence and attributed adaptation/reset history."))
        return InvestigationReasoningReport(caseId, InvestigationQuestionCatalog.VERSION, starts, start, timings, timing,
            facts, relationship, comparable?.let { listOf(it.first.reference, it.second.reference) }.orEmpty(), active, repairs, adaptations,
            questions, listOf("Historical origins remain explicit; SYNTHETIC/UNKNOWN data never becomes hardware qualification. Even HARDWARE is retained metadata, not authenticated provenance.",
                "The complaint and every manual support/contradiction remain attributed reports. No failed component, cause probability or repair success is inferred.",
                "No universal warm threshold, normal MAF value, fuel-pressure specification or actuator compatibility is supplied. No result text is parsed into a machine-verified fault mode."))
    }
    private fun comparable(a: RetainedCorrectionBasis, b: RetainedCorrectionBasis): Boolean {
        val x = a.interval; val y = b.interval
        fun overlap(p: FuelCorrectionBounds, q: FuelCorrectionBounds) = maxOf(p.minimum.value, q.minimum.value) <= minOf(p.maximum.value, q.maximum.value)
        return a.driveId == b.driveId && a.origin == b.origin && a.sourceEcu != null && a.sourceEcu == b.sourceEcu &&
            x.policyVersion == y.policyVersion && x.condition == y.condition && x.system1 == y.system1 && x.system2 == y.system2 &&
            maxOf(x.firstOffsetMillis, y.firstOffsetMillis) <= minOf(x.lastOffsetMillis, y.lastOffsetMillis) &&
            overlap(x.contextBounds.rpm, y.contextBounds.rpm) && overlap(x.contextBounds.load, y.contextBounds.load) && overlap(x.contextBounds.coolant, y.contextBounds.coolant)
    }
}
