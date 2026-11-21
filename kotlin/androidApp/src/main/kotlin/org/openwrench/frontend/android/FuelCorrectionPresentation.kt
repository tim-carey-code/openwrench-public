package org.openwrench.frontend.android

import org.openwrench.core.*
import java.util.Locale

fun FuelCorrectionInterval.observationText(): String = when (pattern) {
    FuelCorrectionPattern.REINFORCING -> "Short-term correction reinforces the learned adjustment."
    FuelCorrectionPattern.OPPOSING -> "Short-term correction opposes the learned adjustment."
    FuelCorrectionPattern.LEARNED_WITH_SMALL_SHORT -> "Learned correction persists while short-term correction stays comparatively small."
    FuelCorrectionPattern.SMALL_LEARNED -> "Learned correction stays within the policy's small-magnitude band; STFT remains separately recorded."
}

fun FuelCorrectionInterval.evidenceText(): String {
    fun range(values: List<Double>) = "${number(values.min())}..${number(values.max())}"
    fun bound(name: String, b: FuelCorrectionBounds) = "$name ${number(b.minimum.value)} at +${b.minimum.offsetMillis} ms..${number(b.maximum.value)} at +${b.maximum.offsetMillis} ms"
    return "Bank $bank · +$firstOffsetMillis..+$lastOffsetMillis ms · $condition; $system1; system 2: $system2. " +
        "${observationText()} ${pairs.size} independent pairs; separation ${pairs.maxOf { it.separationMillis }} ms maximum. " +
        "STFT ${range(pairs.map { it.short.value })}%; LTFT ${range(pairs.map { it.long.value })}%. " +
        "Observed context extrema, including receipts between pairs: ${bound("RPM", contextBounds.rpm)}; ${bound("load %", contextBounds.load)}; " +
        "${bound("coolant °C", contextBounds.coolant)} (first-to-last paired change ${number(pairs.last().coolant.value - pairs.first().coolant.value)} °C); " +
        "speed ${range(pairs.map { it.speed.value })} km/h. " +
        "Distinct context receipts RPM/load/coolant/speed/fuel: ${listOf(pairs.map { it.rpm.offsetMillis }, pairs.map { it.load.offsetMillis }, pairs.map { it.coolant.offsetMillis }, pairs.map { it.speed.offsetMillis }, pairs.map { it.fuelOffsetMillis }).joinToString("/") { it.distinct().size.toString() }}. " +
        "Evidence policy $policyVersion; bounded observed variation does not establish stabilized idle, steady cruise or full warm-up."
}

internal fun fuelPatternFinding(bank: Int, intervals: List<FuelCorrectionInterval>, missing: String): DriveHealthFinding {
    val limits = listOf(
        "Pattern confidence applies only to the retained independent receipts. Mechanical cause remains unestablished; no fault probabilities or failed-part diagnosis are assigned.",
        "Policy 1: pair separation ≤2000 ms, pair gap ≤6000 ms, context age ≤10000 ms; ≥10 pairs over ≥30 s; RPM range ≤300, load range ≤10 percentage points, coolant range ≤3 °C. These are engineering evidence tolerances, not mechanical limits.",
        "Each RPM/load/coolant/speed/fuel input needs ≥4 distinct receipts spanning ≥20 s. Stationary is not verified idle; moving is not verified steady cruise. Closed loop and bounded coolant variation do not prove full warm-up.",
        "Up to 8 intervals per bank, each ≤60 s/256 pairs; first paired LTFT screening concern and latest intervals retained. Omitted intervals are not evidence of absence. Several interrupted intervals never form one sustained interval.",
        "The ±3% small-trim band classifies observations only. The separate sustained LTFT ±10% screening cue is not a combined-correction or vehicle-specific limit. No total correction is calculated.",
        "Fuel-system fields do not universally map to cylinder banks. Bank 2 requires both fields to report exact closed-loop feedback.",
    )
    if (intervals.isEmpty()) return DriveHealthFinding("fuel-pattern-bank-$bank", "Bank $bank contextual fuel correction",
        DriveHealthDisposition.INSUFFICIENT, missing, emptyList(),
        listOf("Address the stated input or continuity requirement, then record both trims with RPM, load, coolant, speed and fuel status during normal operation; review when parked."), limits)
    val first = intervals.first(); val last = intervals.last()
    val differentConditions = intervals.any { a -> intervals.any { b -> conditionsDiffer(a, b) } }
    val differentPattern = intervals.any { a -> intervals.any { b -> correctionDiffers(a, b) } }
    val differentAcrossConditions = intervals.any { a -> intervals.any { b -> conditionsDiffer(a, b) && correctionDiffers(a, b) } }
    val summary = when {
        differentAcrossConditions -> "Correction behavior differs across the recorded qualified conditions. ${last.observationText()}"
        differentConditions && !differentPattern -> "The same correction relationship was observed across different qualified conditions. ${last.observationText()}"
        differentPattern -> "Correction behavior changed between qualified intervals; recorded conditions alone do not explain why. ${last.observationText()}"
        else -> last.observationText()
    }
    val next = when (last.pattern) {
        FuelCorrectionPattern.REINFORCING -> "Compare both trims again at the recorded RPM/load and coolant range using vehicle-specific service information; check whether the same-direction STFT persists. Review recorded codes and symptoms before choosing a mechanical test."
        FuelCorrectionPattern.OPPOSING -> "Repeat both trim measurements under the recorded conditions to see whether STFT continues to oppose LTFT or the learned adjustment changes; adaptation history is not known."
        FuelCorrectionPattern.LEARNED_WITH_SMALL_SHORT -> "Compare the retained LTFT with STFT at another naturally observed, qualified RPM/load condition; small STFT does not erase the learned adjustment."
        FuelCorrectionPattern.SMALL_LEARNED -> "Compare STFT and LTFT at another recorded qualified condition if symptoms persist; this observation does not establish fuel-system health."
    }
    return DriveHealthFinding("fuel-pattern-bank-$bank", "Bank $bank contextual fuel correction",
        if (intervals.any { it.screened }) DriveHealthDisposition.REVIEW else DriveHealthDisposition.OBSERVED,
        summary, listOf("${intervals.size} retained independent qualified interval(s); latest: ${last.condition}.",
            if (differentConditions) "Observed condition differences are descriptive; RPM/load is not established as the cause of a correction difference."
            else "A different qualified operating condition has not been established; do not infer a condition-dependent cause.",
            "Latest collection requirement: $missing"),
        listOf(next, "If a repeatable condition difference and symptoms remain, use the applicable manufacturer's diagnostic checks with a qualified technician. Ford's condition-comparison examples have specific vehicle applicability; their thresholds and procedures are not universal."),
        limits, first.firstOffsetMillis, last.lastOffsetMillis, intervals.sumOf { it.pairs.size }.toLong(),
        "Recorded independent same-bank STFT/LTFT pairs", fuelIntervals = intervals)
}

internal fun conditionsDiffer(a: FuelCorrectionInterval, b: FuelCorrectionInterval): Boolean =
    a.condition != b.condition || separatedBounds(a.contextBounds.rpm, b.contextBounds.rpm, FuelCorrectionPolicy.RPM_RANGE) ||
        separatedBounds(a.contextBounds.load, b.contextBounds.load, FuelCorrectionPolicy.LOAD_RANGE) ||
        separatedBounds(a.contextBounds.coolant, b.contextBounds.coolant, FuelCorrectionPolicy.COOLANT_RANGE)
private fun correctionDiffers(a: FuelCorrectionInterval, b: FuelCorrectionInterval): Boolean =
    a.pattern != b.pattern || FuelCorrectionRecorder.direction(a.pairs.first().long.value) != FuelCorrectionRecorder.direction(b.pairs.first().long.value) ||
        separated(a.pairs.map { it.short.value }, b.pairs.map { it.short.value }, FuelCorrectionPolicy.SMALL_TRIM) ||
        separated(a.pairs.map { it.long.value }, b.pairs.map { it.long.value }, FuelCorrectionPolicy.SMALL_TRIM)
private fun separatedBounds(a: FuelCorrectionBounds, b: FuelCorrectionBounds, tolerance: Double) =
    maxOf(b.minimum.value - a.maximum.value, a.minimum.value - b.maximum.value) > tolerance
private fun separated(a: List<Double>, b: List<Double>, tolerance: Double) = maxOf(b.min() - a.max(), a.min() - b.max()) > tolerance
private fun number(value: Double) = String.format(Locale.US, "%.2f", value)
