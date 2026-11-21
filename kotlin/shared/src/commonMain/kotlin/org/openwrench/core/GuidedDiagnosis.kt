package org.openwrench.core

import org.openwrench.nativecore.NativeReading

/** App-owned provisional screening policy, not an OEM temperature specification or health verdict. */
object GuidedDiagnosisPolicy {
    const val VERSION = 1
    const val MIN_COOLANT_CELSIUS = 72.0
    const val MILESTONE = "Enough information for an initial assessment. More driving may reveal issues that haven't appeared yet. Continue driving if you'd like, or finish when parked."
    const val LIMITS = "Provisional fuel-feedback screening only; unsupported systems, other modules and unobserved conditions remain unassessed. No problem is ruled out. Findings may change with further evidence."
}

enum class GuidedBankStatus { UNAVAILABLE, COLLECTING, QUALIFIED }

data class GuidedBankProgress(
    val bank: Int, val status: GuidedBankStatus, val requirement: String,
    val interval: FuelCorrectionInterval? = null,
)

/** Receipt offsets belong to the containing diagnosis segment, never another drive or wall clock. */
data class GuidedDiagnosisProgress(
    val policyVersion: Int, val scanSucceeded: Boolean, val evaluatedAtOffsetMillis: Long,
    val banks: List<GuidedBankProgress>, val initialAssessmentOffsetMillis: Long?,
) {
    val initialAssessmentAvailable get() = initialAssessmentOffsetMillis != null
    fun reportText(): String = buildString {
        appendLine("Guided screening policy: $policyVersion (provisional)")
        appendLine("Initial scan: ${if (scanSucceeded) "collected; individual unavailable replies remain unknown" else "incomplete or unavailable"}")
        appendLine(when {
            initialAssessmentAvailable -> GuidedDiagnosisPolicy.MILESTONE
            !scanSucceeded -> "Partial assessment: the initial scan is incomplete or unavailable; any qualified bank evidence below remains usable."
            else -> "Partial assessment: the minimum warm, engine-running, closed-loop paired-trim evidence has not qualified."
        })
        banks.forEach { appendLine("Bank ${it.bank}: ${it.status.name.lowercase()}; ${it.requirement}") }
        appendLine(GuidedDiagnosisPolicy.LIMITS)
    }
}

/** Consumes actual receipts only. The existing pair/context engine owns every qualification gate. */
class GuidedDiagnosisRecorder(
    private val startedAt: Long, private val supported: Set<Int>, private val admitted: Set<Int>,
    private val scanSucceeded: Boolean,
) {
    private val fuel = FuelCorrectionRecorder(startedAt, supported, admitted, GuidedDiagnosisPolicy.MIN_COOLANT_CELSIUS)
    // First proof is frozen so later reassuring samples cannot erase or rewrite the milestone.
    private val qualified = mutableMapOf<Int, FuelCorrectionInterval>()
    private var milestone: Long? = null
    private var lastAt = startedAt
    private var stopped = false

    fun offer(reading: NativeReading, at: Long) {
        if (stopped || at < lastAt || at < startedAt) return
        lastAt = at
        fuel.offer(reading, at)
        for (bank in 1..2) if (bank !in qualified) fuel.intervals(bank).firstOrNull()?.let { qualified[bank] = it }
        if (scanSucceeded && qualified.isNotEmpty() && milestone == null) milestone = at - startedAt
    }

    fun snapshot(at: Long = lastAt): GuidedDiagnosisProgress = GuidedDiagnosisProgress(
        GuidedDiagnosisPolicy.VERSION, scanSucceeded, (maxOf(at, lastAt) - startedAt).coerceAtLeast(0),
        (1..2).map { bank ->
            val trims = if (bank == 1) setOf(0x06, 0x07) else setOf(0x08, 0x09)
            val interval = qualified[bank]
            val status = when {
                interval != null -> GuidedBankStatus.QUALIFIED
                !supported.containsAll(trims) -> GuidedBankStatus.UNAVAILABLE
                else -> GuidedBankStatus.COLLECTING
            }
            GuidedBankProgress(bank, status, if (interval != null)
                "Warm engine-running closed-loop interval qualified: ${interval.pairs.size} independent pairs; receipt offsets ${interval.firstOffsetMillis}–${interval.lastOffsetMillis} ms. Historical coverage only."
                else fuel.missingRequirement(bank, maxOf(at, lastAt)), interval)
        }, milestone)

    fun stop(at: Long): GuidedDiagnosisProgress {
        stopped = true
        fuel.stop(at)
        return snapshot(at)
    }
}
