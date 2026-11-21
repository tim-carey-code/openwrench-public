package org.openwrench.frontend

import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

data class RetainedInvestigationChoice(val label: String, val provenance: String, val reference: InvestigationEvidenceRef) {
    fun observation() = InvestigationStep.Observation(reference.kind, label, provenance, reference)
}

/** Render retained typed values; descriptive prose never supplies a measurement or an inferred bank. */
object InvestigationPresentation {
    fun choices(saved: SavedInvestigation): List<RetainedInvestigationChoice> = buildList {
        saved.cases.forEach { case ->
            val capture = case.scan.capture!!
            capture.readings.filter { it.value != null && it.pid in capture.eligiblePids }.forEach { reading ->
                val spec = ObdDecoder.catalog.getValue(reading.pid)
                val value = when (val v = reading.value) {
                    is DecodedReading.Scalar -> "${v.value} ${spec.unit.orEmpty()}"
                    is DecodedReading.Structured -> v.fields.entries.joinToString { "${it.key}: ${it.value}" }
                    null -> error("Missing reading")
                }
                add(RetainedInvestigationChoice("Scan ${case.scan.id.take(8)} · ${spec.name}: $value",
                    "Historical ${capture.origin} scan; capture ${capture.captureId}; observed ECU ${reading.sourceEcu ?: "unknown"}; measurement/event time unknown",
                    InvestigationEvidenceRef(case.id, InvestigationObservationKind.CASE_SCAN_READING, pid = reading.pid)))
            }
            case.drive.review.assessment?.findings?.forEachIndexed { findingIndex, finding ->
                finding.fuelIntervals.forEachIndexed { intervalIndex, interval ->
                    val longs = interval.pairs.map { it.long.value }
                    val shorts = interval.pairs.map { it.short.value }
                    add(RetainedInvestigationChoice("Bank ${interval.bank} · ${interval.condition} · STFT ${shorts.min()}..${shorts.max()}%; LTFT ${longs.min()}..${longs.max()}% · ${interval.pairs.size} pairs",
                        "Historical ${case.drive.origin} drive ${case.drive.id}; observed ECU ${case.drive.sourceEcu ?: "unknown"}; pack ${case.drive.review.assessment.packId}/${case.drive.review.assessment.packVersion}; evidence policy ${interval.policyVersion}; receipt offsets ${interval.firstOffsetMillis}..${interval.lastOffsetMillis} ms; RPM ${interval.contextBounds.rpm.minimum.value}..${interval.contextBounds.rpm.maximum.value}; load ${interval.contextBounds.load.minimum.value}..${interval.contextBounds.load.maximum.value}%; coolant ${interval.contextBounds.coolant.minimum.value}..${interval.contextBounds.coolant.maximum.value} C",
                        InvestigationEvidenceRef(case.id, InvestigationObservationKind.CASE_FUEL_INTERVAL, findingIndex = findingIndex, intervalIndex = intervalIndex)))
                }
            }
        }
    }
    fun title(step: InvestigationStep): String = when (step) {
        is InvestigationStep.Complaint -> "Reported complaint"
        is InvestigationStep.Observation -> when (step.kind) {
            InvestigationObservationKind.REPORTED -> "Reported observation · unverified"
            InvestigationObservationKind.MANUALLY_OBSERVED -> "Manual observation · author attributed"
            else -> "Retained measured evidence · historical"
        }
        is InvestigationStep.Hypothesis -> "Hypothesis · unproven"
        is InvestigationStep.ProposedTest -> "Distinguishing question · " + when (step.kind) {
            InvestigationTestKind.REVIEW_RETAINED_EVIDENCE -> "evidence review"
            InvestigationTestKind.MANUAL_RESULT_ONLY -> "manual record only"
            InvestigationTestKind.ACTUATOR_UNSUPPORTED -> "actuator unsupported"
        }
        is InvestigationStep.TestResult -> "Recorded result · ${step.outcome.name.lowercase()}"
        is InvestigationStep.RepairReview -> "Reported repair follow-up · repair not established"
    }
    /** The workspace uses these same rows for its normal and full-history views. */
    fun revisions(saved: SavedInvestigation, fullHistory: Boolean): List<InvestigationRevision> {
        if (fullHistory) return saved.journal.revisions
        val active = saved.journal.activeResults().map { it.id }.toSet()
        return saved.journal.revisions.filter { it.step !is InvestigationStep.TestResult || it.id in active }
    }
    fun detail(step: InvestigationStep, referenceLabel: (String) -> String = { it }): String = when (step) {
        is InvestigationStep.Complaint -> step.reported
        is InvestigationStep.Observation -> "${step.description}\nProvenance: ${step.provenance}"
        is InvestigationStep.Hypothesis -> "${step.explanation}\nObservation references: ${step.observationIds.joinToString { referenceLabel(it) }}"
        is InvestigationStep.ProposedTest -> "${step.question}\nDistinguishing outcomes: ${step.distinguishingOutcomes}\nCompeting hypotheses: ${step.hypothesisIds.joinToString { referenceLabel(it) }}\nDescriptive question only; no vehicle test is executed."
        is InvestigationStep.TestResult -> "Question answered: ${referenceLabel(step.testId)}\n${step.result}\nProvenance: ${step.provenance}\nObservations: ${step.observationIds.joinToString { referenceLabel(it) }.ifEmpty { "None supplied" }}\n" +
            step.effects.joinToString("\n") { "${referenceLabel(it.hypothesisId)}: ${it.effect} — ${it.rationale}" } +
            (step.supersedesResultId?.let { "\nCorrects result ${referenceLabel(it)}; original retained." } ?: "")
        is InvestigationStep.RepairReview -> "${step.repairReported}\nSymptom report: ${step.symptomReported}\nProvenance: ${step.provenance}\nTrim movement alone does not prove repair or symptom resolution."
    }
}
