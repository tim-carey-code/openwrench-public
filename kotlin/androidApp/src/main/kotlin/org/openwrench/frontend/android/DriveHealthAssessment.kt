package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import java.util.Locale
import org.openwrench.core.FuelCorrectionRecorder
import org.openwrench.core.FuelCorrectionInterval

enum class DriveHealthDisposition { REASSURING, REVIEW, OBSERVED, INSUFFICIENT, NOT_ASSESSED }

/** Bounds use fresh supporting receipts at each LTFT receipt, not whole-drive extrema. */
data class DriveHealthWindow(
    val firstOffsetMillis: Long, val lastOffsetMillis: Long, val sampleCount: Int, val condition: String,
    val trimMinimum: Double, val trimMaximum: Double, val rpmMinimum: Double, val rpmMaximum: Double,
    val loadMinimum: Double, val loadMaximum: Double, val coolantMinimum: Double, val coolantMaximum: Double,
) {
    val direction: String get() = when { trimMinimum > 0 -> "adding"; trimMaximum < 0 -> "removing"; else -> "mixed" }
    fun reportText(): String = "Comparison window +$firstOffsetMillis..+$lastOffsetMillis ms ($sampleCount LTFT receipts): " +
        "LTFT $trimMinimum..$trimMaximum%; RPM $rpmMinimum..$rpmMaximum; load $loadMinimum..$loadMaximum%; coolant $coolantMinimum..$coolantMaximum °C; $condition"
}

/** A conclusion about bounded recorded evidence, never a component certification. */
data class DriveHealthFinding(
    val checkId: String, val title: String, val disposition: DriveHealthDisposition,
    val summary: String, val evidence: List<String>, val nextSteps: List<String>,
    val limitations: List<String>, val firstOffsetMillis: Long? = null,
    val lastOffsetMillis: Long? = null, val sampleCount: Long = 0,
    val context: String = "", val freshnessMillis: Long? = null,
    val comparisonWindow: DriveHealthWindow? = null,
    val fuelIntervals: List<FuelCorrectionInterval> = emptyList(),
)

data class DriveHealthAssessment(
    val packId: String, val packVersion: String, val evaluatedAtOffsetMillis: Long,
    val findings: List<DriveHealthFinding>, val sourceRefs: List<String>, val notes: List<String>,
) {
    /** Time can withdraw a reassuring status, but cannot erase a recorded warning or add evidence. */
    fun present(offsetMillis: Long): DriveHealthAssessment {
        val at = offsetMillis.coerceAtLeast(evaluatedAtOffsetMillis)
        return copy(evaluatedAtOffsetMillis = at, findings = findings.map { finding ->
            val last = finding.lastOffsetMillis
            if ((finding.disposition == DriveHealthDisposition.REASSURING || finding.checkId == "readiness") && finding.freshnessMillis != null &&
                last != null && at - last > finding.freshnessMillis) finding.copy(
                disposition = DriveHealthDisposition.INSUFFICIENT,
                summary = "The last status observation is too old to support status at this report point.",
                limitations = (finding.limitations + "Last supporting receipt was ${at - last} ms before this report point.").take(16))
            else finding
        })
    }

    fun reportText(): String = buildString {
        appendLine("Vehicle health assessment: $packId/$packVersion; recorded evidence through +$evaluatedAtOffsetMillis ms")
        findings.forEach { finding ->
            appendLine("${finding.disposition}: ${finding.title} — ${finding.summary}")
            if (finding.sampleCount > 0) appendLine("${if (finding.fuelIntervals.isEmpty()) "Supporting receipts" else "Independent paired measurements"}: ${finding.sampleCount}; +${finding.firstOffsetMillis}..+${finding.lastOffsetMillis} ms; ${finding.context}")
            finding.evidence.forEach { appendLine("Evidence: $it") }
            finding.comparisonWindow?.let { appendLine(it.reportText()) }
            finding.fuelIntervals.forEach { appendLine(it.evidenceText()) }
            finding.nextSteps.forEach { appendLine("Next: $it") }
            finding.limitations.forEach { appendLine("Limit: $it") }
        }
        notes.forEach { appendLine("Assessment note: $it") }
        sourceRefs.forEach { appendLine("Assessment reference: $it") }
    }
}

/**
 * Runs only on the existing serialized reader's exact, current-session receipts. No requests,
 * chart interpolation, manufacturer guesses, or data from another monitor enter this recorder.
 */
internal class DriveHealthAssessmentRecorder(
    private val startedAt: Long, private val supported: Set<Int>, private val admitted: Set<Int>,
) {
    private data class Receipt(val value: DecodedReading?, val at: Long)
    private data class TrimPoint(val at: Long, val value: Double, val rpm: Double?, val load: Double?, val coolant: Double?)
    private data class TrimWindow(val key: String, val points: List<TrimPoint>)
    private val latest = mutableMapOf<Int, Receipt>()
    private val fuelCorrection = FuelCorrectionRecorder(startedAt, supported, admitted)
    private val windows = mutableMapOf<Int, TrimWindow>()
    private val qualifiedTrim = mutableMapOf<Int, DriveHealthFinding>()
    private val trimReview = mutableMapOf<Int, DriveHealthFinding>()
    private val reviewDirection = mutableMapOf<Int, String>()
    private var ecuWarning: DriveHealthFinding? = null
    private var fuelWarning: DriveHealthFinding? = null
    private var statusCount = 0L
    private var fuelCount = 0L
    private var firstStatus: Long? = null
    private var firstFuel: Long? = null
    private var comparisonBreakAt = startedAt
    private var lastAt = startedAt
    private var stopped = false

    fun offer(reading: NativeReading, at: Long) {
        // Monotonic readers may complete different PIDs at the same millisecond. A duplicate PID
        // receipt at that instant is not an independent sample and cannot extend a window.
        if (stopped || reading.pid !in admitted || at < startedAt || at < lastAt || latest[reading.pid]?.at == at) return
        fuelCorrection.offer(reading, at)
        for (bank in 1..2) if (trimContext(bank, at) == null) windows.remove(bank)
        if (freshScalar(0x04, at) == null || freshScalar(0x05, at) == null) comparisonBreakAt = at
        lastAt = at
        latest[reading.pid] = Receipt(reading.value, at)
        if (freshScalar(0x04, at) == null || freshScalar(0x05, at) == null) comparisonBreakAt = at
        val fields = (reading.value as? DecodedReading.Structured)?.fields.orEmpty()
        if (reading.pid == 0x01) recordEcu(fields, at)
        if (reading.pid == 0x03) recordFuel(fields, at)
        for (bank in 1..2) {
            val context = trimContext(bank, at)
            if (context == null || windows[bank]?.key?.let { it != context } == true) windows.remove(bank)
            val pid = longTrimPid(bank)
            if (reading.pid != pid) continue
            val value = (reading.value as? DecodedReading.Scalar)?.value?.takeIf(Double::isFinite)
            if (context == null || value == null) { windows.remove(bank); continue }
            val prior = windows[bank]
            val points = (if (prior != null && at - prior.points.last().at <= TRIM_GAP_MILLIS) prior.points else emptyList())
                .filter { at - it.at <= TRIM_WINDOW_MILLIS }.takeLast(MAX_TRIM_POINTS - 1) + TrimPoint(at, value, freshScalar(0x0C, at), freshScalar(0x04, at), freshScalar(0x05, at))
            windows[bank] = TrimWindow(context, points)
            if (points.size >= MIN_TRIM_SAMPLES && at - points.first().at >= MIN_TRIM_SPAN_MILLIS) {
                // A preceding in-band observation must not delay a later, independently complete
                // sustained correction run until the entire 60-second rolling window has expired.
                val tail = when {
                    value > TRIM_SCREEN_PERCENT -> points.takeLastWhile { it.value > TRIM_SCREEN_PERCENT }
                    value < -TRIM_SCREEN_PERCENT -> points.takeLastWhile { it.value < -TRIM_SCREEN_PERCENT }
                    else -> emptyList()
                }
                val evidence = tail.takeIf { it.size >= MIN_TRIM_SAMPLES && at - it.first().at >= MIN_TRIM_SPAN_MILLIS } ?: points
                val finding = trimFinding(bank, context, evidence, at)
                qualifiedTrim[bank] = finding
                if (finding.disposition == DriveHealthDisposition.REVIEW) {
                    val direction = if (value > 0) "adding" else "removing"
                    val previous = trimReview[bank]
                    if (previous == null) { trimReview[bank] = finding; reviewDirection[bank] = direction }
                    else if (previous.comparisonWindow == null && finding.comparisonWindow != null &&
                        previous.context == finding.context && reviewDirection[bank] == direction)
                        trimReview[bank] = previous.copy(comparisonWindow = finding.comparisonWindow)
                }
            }
        }
    }

    fun stop(at: Long) { fuelCorrection.stop(at); lastAt = maxOf(lastAt, at, startedAt); stopped = true }

    fun snapshot(): DriveHealthAssessment {
        val offset = lastAt - startedAt
        return DriveHealthAssessment("standard-obd-drive", "3", offset, listOf(
            ecuFinding(), readinessFinding(), fuelFinding(), bankFinding(1), bankFinding(2),
            fuelPatternFinding(1, fuelCorrection.intervals(1), fuelCorrection.missingRequirement(1)),
            fuelPatternFinding(2, fuelCorrection.intervals(2), fuelCorrection.missingRequirement(2)),
            measuredOnly(0x05, "cooling", "Cooling system", "Coolant measurements alone cannot establish cooling-system health.",
                "Use the vehicle manufacturer's temperature limits and check cooling-system operation if a warning or symptom appears."),
            measuredOnly(0x42, "charging", "Charging system", "Module voltage alone cannot establish battery or charging-system health.",
                "Use vehicle-specific charging strategy and load-test procedures before judging battery or alternator condition."),
            DriveHealthFinding("module-coverage", "Other vehicle systems", DriveHealthDisposition.NOT_ASSESSED,
                "ABS, airbags, body modules, and dedicated transmission-module health have not been assessed.",
                emptyList(), listOf("Use supported module-specific diagnostics for those systems."),
                listOf("This assessment uses standard emissions-related OBD data from one selected response source.")),
        ), SOURCES, listOf(
            "Findings describe this monitor's sampled evidence, not a whole-vehicle grade or a proven failed part.",
            "A REVIEW item is a follow-up cue. INSUFFICIENT means the check has not been supported; it is not a pass.",
            "OBSERVED describes a qualified recorded pattern and does not mean healthy or passed.",
            "Receipt times are sequential adapter receipts, not simultaneous ECU sample times. Earlier warning and qualifying fuel-correction findings remain recorded after later changes.",
            "Fuel-trim screening uses an explicitly disclosed general service cue, not an OEM fault threshold. No warmed-engine state is inferred.",
        )).present(offset)
    }

    private fun recordEcu(fields: Map<String, String>, at: Long) {
        val mil = fields["mil_on"]?.toBooleanStrictOrNull() ?: return
        val count = fields["dtc_count"]?.toIntOrNull()?.takeIf { it in 0..127 } ?: return
        statusCount++
        if (firstStatus == null) firstStatus = at
        if ((mil || count > 0) && ecuWarning == null) ecuWarning = DriveHealthFinding(
            "ecu-warning", "ECU warning status", DriveHealthDisposition.REVIEW,
            "The ECU reported an emissions-related warning during this drive.",
            listOf("MIL ${if (mil) "ON" else "OFF"}; ECU-reported DTC count $count at +${at - startedAt} ms."),
            listOf("When parked, stop live polling and choose Diagnostics → Scan with warning context for code identifiers and available freeze-frame evidence; review them with symptoms and manufacturer guidance."),
            listOf("This records an observed warning, not its onset, cause, or a confirmed failed part.", "A later clear status does not erase this earlier observation."),
            at - startedAt, at - startedAt, 1, "ECU-reported monitor status")
    }

    private fun recordFuel(fields: Map<String, String>, at: Long) {
        val systems = fields.filterKeys { it in setOf("system_1", "system_2") }
        if (systems.isEmpty()) return
        fuelCount++
        if (firstFuel == null) firstFuel = at
        val faults = systems.filterValues { it in FUEL_FAULTS }
        if (faults.isNotEmpty() && fuelWarning == null) fuelWarning = DriveHealthFinding(
            "fuel-control", "Fuel-control status", DriveHealthDisposition.REVIEW,
            "The ECU explicitly reported a fuel-control fault state during this drive.",
            faults.map { "${it.key}: ${it.value} at +${at - startedAt} ms" },
            listOf("When parked, read stored diagnostic codes and investigate the reported fuel-control system using vehicle-specific procedures."),
            listOf("A fuel-system status is not a bank assignment or a failed oxygen-sensor diagnosis.", "Later non-fault states do not erase this recorded fault state."),
            at - startedAt, at - startedAt, 1, "ECU fuel-system status")
    }

    private fun ecuFinding(): DriveHealthFinding {
        ecuWarning?.let { return it }
        missing(listOf(0x01))?.let { return insufficient("ecu-warning", "ECU warning status", it) }
        val receipt = latest[0x01]
        val fields = (receipt?.value as? DecodedReading.Structured)?.fields.orEmpty()
        val clear = fields["mil_on"] == "false" && fields["dtc_count"]?.toIntOrNull() == 0
        if (!clear) return insufficient("ecu-warning", "ECU warning status", "The latest monitor-status attempt has no complete usable MIL and DTC-count observation.")
        return DriveHealthFinding("ecu-warning", "ECU warning status", DriveHealthDisposition.REASSURING,
            "No MIL or nonzero DTC count was reported in the sampled monitor-status observations.",
            listOf("$statusCount valid status receipt(s); latest MIL OFF and ECU-reported DTC count 0."),
            listOf("Review any symptoms even when the ECU has not reported a warning."),
            listOf("Status is sampled, not continuously observed. This does not establish that stored codes, pending faults, or faults in other modules are absent."),
            firstStatus?.minus(startedAt), receipt!!.at - startedAt, statusCount, "ECU monitor status", ECU_AGE_MILLIS)
    }

    private fun readinessFinding(): DriveHealthFinding {
        missing(listOf(0x01))?.let { return insufficient("readiness", "Emissions readiness", it) }
        val receipt = latest[0x01]
        val fields = (receipt?.value as? DecodedReading.Structured)?.fields.orEmpty()
        val monitorKeys = fields.keys.filter { Regex("monitors\\.\\d+\\.name").matches(it) }.sorted()
        val advertised = monitorKeys.map { it.removeSuffix("name") }.filter { fields[it + "available"] == "true" }
        if (advertised.isEmpty()) return insufficient("readiness", "Emissions readiness", "No advertised readiness-monitor completion information is available from the latest status attempt.")
        val incomplete = advertised.filter { fields[it + "complete"] == "false" }.map { fields[it + "name"].orEmpty() }
        val unknown = advertised.count { fields[it + "complete"] !in setOf("true", "false") }
        val disposition = when {
            incomplete.isNotEmpty() -> DriveHealthDisposition.REVIEW
            unknown > 0 -> DriveHealthDisposition.INSUFFICIENT
            else -> DriveHealthDisposition.REASSURING
        }
        return DriveHealthFinding("readiness", "Emissions readiness", disposition,
            when (disposition) {
                DriveHealthDisposition.REVIEW -> "Some ECU self-checks have not completed; their outcome remains unproven."
                DriveHealthDisposition.REASSURING -> "The ECU reports all advertised readiness self-checks complete."
                else -> "Some advertised self-checks have unknown completion status."
            }, listOf("${advertised.size} advertised monitor(s); ${incomplete.size} incomplete; $unknown unknown.") +
                if (incomplete.isNotEmpty()) listOf("Incomplete: ${incomplete.joinToString()}.") else emptyList(),
            if (incomplete.isNotEmpty()) listOf("Recheck readiness after normal operation under the manufacturer's conditions; an incomplete monitor is not a component-failure diagnosis.") else emptyList(),
            listOf("Readiness indicates whether self-checks ran, not an independent pass for every vehicle system.", "Unsupported monitors are excluded, not counted as passed."),
            receipt!!.at - startedAt, receipt.at - startedAt, 1, "Readiness since DTC clear", ECU_AGE_MILLIS)
    }

    private fun fuelFinding(): DriveHealthFinding {
        fuelWarning?.let { return it }
        missing(listOf(0x03))?.let { return insufficient("fuel-control", "Fuel-control status", it) }
        val receipt = latest[0x03]
        val fields = (receipt?.value as? DecodedReading.Structured)?.fields.orEmpty()
        val systems = fields.filterKeys { it in setOf("system_1", "system_2") }
        if (systems.isEmpty() || systems.values.any { it !in KNOWN_FUEL_STATES })
            return insufficient("fuel-control", "Fuel-control status", "A complete known fuel-control state is unavailable from the latest attempt.")
        val closed = systems.values.any { it in CLOSED_LOOP }
        return DriveHealthFinding("fuel-control", "Fuel-control status", DriveHealthDisposition.REASSURING,
            if (closed) "Oxygen-feedback operation was reported without an explicit fuel-control fault state in the sampled replies."
            else "No explicit fuel-control fault state was reported in the sampled replies; open-loop operation alone is not a fault.",
            listOf("$fuelCount valid fuel-status receipt(s).") + systems.map { "${it.key}: ${it.value}" },
            listOf("Interpret fuel trims only in a qualified closed-loop window; warm-up and fuel-cut operation need different context."),
            listOf("This does not test sensor accuracy or prove correct mixture.", "Fuel-system fields do not universally identify cylinder banks; a zero/engine-off second field can mean no active second system."),
            firstFuel?.minus(startedAt), receipt!!.at - startedAt, fuelCount, "ECU fuel-system status", CONTEXT_AGE_MILLIS)
    }

    private fun bankFinding(bank: Int): DriveHealthFinding {
        trimReview[bank]?.let { return it }
        missing(listOf(longTrimPid(bank), 0x0C, 0x0D, 0x03))?.let {
            return insufficient("trim-bank-$bank", "Bank $bank learned fuel correction", it)
        }
        qualifiedTrim[bank]?.let { return it }
        return insufficient("trim-bank-$bank", "Bank $bank learned fuel correction",
            "Not enough continuous, qualified closed-loop fuel-trim evidence has been collected.",
            listOf("Needs at least $MIN_TRIM_SAMPLES actual LTFT receipts over ${MIN_TRIM_SPAN_MILLIS / 1_000} seconds in one stationary/moving context, with no LTFT receipt gap over ${TRIM_GAP_MILLIS / 1_000} seconds.",
                if (bank == 2) "Bank 2 screening additionally requires both reported fuel-system fields to indicate closed-loop feedback; their bank mapping is not assumed."
                else "Fresh positive RPM, speed, and known compatible fuel-system states are required; no warmed-engine state is inferred."))
    }

    private fun trimFinding(bank: Int, key: String, points: List<TrimPoint>, at: Long): DriveHealthFinding {
        val low = points.minOf { it.value }; val high = points.maxOf { it.value }
        val sustained = low > TRIM_SCREEN_PERCENT || high < -TRIM_SCREEN_PERCENT
        val direction = when { low > 0 -> "adding fuel"; high < 0 -> "removing fuel"; else -> "changing correction direction" }
        val shortPid = if (bank == 1) 0x06 else 0x08
        val short = freshScalar(shortPid, at)
        val evidence = listOf("LTFT ${format(low)} to ${format(high)}%; ${points.size} actual receipts over ${at - points.first().at} ms.",
            "The learned correction was $direction relative to the ECU's base fueling.") +
            if (short != null) listOf("Nearby STFT ${format(short)}%; this sequential receipt was not summed with LTFT.") else emptyList()
        return DriveHealthFinding("trim-bank-$bank", "Bank $bank learned fuel correction",
            if (sustained) DriveHealthDisposition.REVIEW else DriveHealthDisposition.OBSERVED,
            if (sustained) "Sustained learned fuel correction merits a qualified follow-up; the ECU was $direction throughout this window."
            else "No sustained learned correction beyond the screening cue was observed in this qualified window.",
            evidence, if (sustained) listOf("Verify the engine is fully warm, compare trims at manufacturer-specified operating points, and review codes and symptoms before choosing a cause or repair.")
                else listOf("Collect additional stationary and moving closed-loop evidence and compare with manufacturer specifications if there are symptoms."),
            listOf("OpenWrench screening cue, informed by general service guidance: sustained LTFT beyond ±10%; this is not a vehicle-specific normal range or an OEM fault threshold.",
                "Published warm-idle guidance cannot establish a driving or vehicle-specific limit. A cue during driving calls for a qualified follow-up only.",
                "Temperature, load, fuel type, adaptation history, and fuel-system architecture can affect trim. Warmth and bank-to-system mapping are not verified.",
                "A negative learned trim means the ECU is removing fuel relative to base fueling; it does not establish that the measured exhaust mixture is currently rich."),
            points.first().at - startedAt, at - startedAt, points.size.toLong(), key,
            comparisonWindow = comparisonWindow(key, points))
    }

    private fun comparisonWindow(key: String, points: List<TrimPoint>): DriveHealthWindow? {
        val qualified = points.takeLastWhile { it.at > comparisonBreakAt && it.rpm != null && it.load != null && it.coolant != null }
        if (qualified.size < MIN_TRIM_SAMPLES || qualified.last().at - qualified.first().at < MIN_TRIM_SPAN_MILLIS) return null
        return DriveHealthWindow(qualified.first().at - startedAt, qualified.last().at - startedAt, qualified.size, key,
            qualified.minOf { it.value }, qualified.maxOf { it.value },
            qualified.minOf { it.rpm!! }, qualified.maxOf { it.rpm!! },
            qualified.minOf { it.load!! }, qualified.maxOf { it.load!! },
            qualified.minOf { it.coolant!! }, qualified.maxOf { it.coolant!! })
    }

    private fun trimContext(bank: Int, at: Long): String? {
        val rpm = freshScalar(0x0C, at) ?: return null
        val speed = freshScalar(0x0D, at) ?: return null
        if (rpm <= 0 || speed < 0) return null
        val receipt = latest[0x03]?.takeIf { at >= it.at && at - it.at <= CONTEXT_AGE_MILLIS } ?: return null
        val fields = (receipt.value as? DecodedReading.Structured)?.fields ?: return null
        val first = fields["system_1"] ?: return null
        val second = fields["system_2"] ?: return null
        if (first !in CLOSED_LOOP || (bank == 2 && second !in CLOSED_LOOP) ||
            (bank == 1 && second !in CLOSED_LOOP && second != "engine off")) return null
        return (if (speed > 0) "Moving" else "Stationary with engine running") +
            "; qualified reported closed-loop context; system 1: $first; system 2: $second"
    }

    private fun freshScalar(pid: Int, at: Long): Double? = latest[pid]
        ?.takeIf { at >= it.at && at - it.at <= CONTEXT_AGE_MILLIS }
        ?.let { (it.value as? DecodedReading.Scalar)?.value }?.takeIf(Double::isFinite)

    private fun measuredOnly(pid: Int, id: String, title: String, summary: String, next: String): DriveHealthFinding {
        val receipt = latest[pid]
        val value = (receipt?.value as? DecodedReading.Scalar)?.value?.takeIf(Double::isFinite)
        return DriveHealthFinding(id, title, DriveHealthDisposition.NOT_ASSESSED, summary,
            if (value == null) listOf(missing(listOf(pid)) ?: "No usable latest measurement was captured.")
            else listOf("Last recorded ${if (pid == 0x05) "coolant temperature" else "control-module voltage"}: ${format(value)} ${if (pid == 0x05) "°C" else "V"} at +${receipt!!.at - startedAt} ms."),
            listOf(next), listOf("Vehicle identity, manufacturer limits, and the necessary dedicated tests have not been established."),
            if (value != null) receipt!!.at - startedAt else null, if (value != null) receipt!!.at - startedAt else null,
            if (value != null) 1 else 0, "Recorded measurement only")
    }

    private fun missing(pids: List<Int>): String? {
        val absent = pids.filter { it !in supported }
        if (absent.isNotEmpty()) return "Required input(s) not advertised by this session: ${absent.joinToString { pidLabel(it) }}."
        val deferred = pids.filter { it !in admitted }
        if (deferred.isNotEmpty()) return "Required input(s) not admitted to acquisition: ${deferred.joinToString { pidLabel(it) }}."
        return null
    }

    private fun insufficient(id: String, title: String, reason: String, evidence: List<String> = emptyList()) =
        DriveHealthFinding(id, title, DriveHealthDisposition.INSUFFICIENT, reason, evidence,
            listOf("Collect supported inputs in normal stationary and moving operation, then review the resulting evidence when parked."),
            listOf("Missing, NO DATA, stale, interrupted, or incompatible-context observations cannot count as a passed check."))

    companion object {
        private const val CONTEXT_AGE_MILLIS = 10_000L
        private const val ECU_AGE_MILLIS = 45_000L
        private const val TRIM_GAP_MILLIS = 6_000L
        private const val MIN_TRIM_SPAN_MILLIS = 30_000L
        private const val MIN_TRIM_SAMPLES = 10
        private const val TRIM_WINDOW_MILLIS = 60_000L
        private const val MAX_TRIM_POINTS = 512
        private const val TRIM_SCREEN_PERCENT = 10.0
        private val CLOSED_LOOP = setOf("closed loop: oxygen-sensor feedback")
        private val FUEL_FAULTS = setOf("open loop: system fault", "closed loop: oxygen-sensor feedback fault")
        private val KNOWN_FUEL_STATES = CLOSED_LOOP + FUEL_FAULTS + setOf("engine off", "open loop: insufficient engine temperature", "open loop: load or deceleration fuel cut")
        private val SOURCES = listOf(
            "https://ww2.arb.ca.gov/resources/fact-sheets/board-diagnostic-ii-obd-ii-systems-fact-sheet",
            "https://www.innova.com/blogs/fix-advices/reading-fuel-trim-in-live-data",
            "https://www.snapon.com/EN/UK/Diagnostics/News-Centre/Technical-Focus-Archive/fuel-trim-adaptation",
            "https://cdr.boschdiagnostics.com/pro/sites/pro/files/tech_1a_global_obd_ii_operators_manual.pdf",
            "https://www.fordservicecontent.dealerconnection.com/pubs/content/~WVX2/~MUS~LEN/15/VX21016.HTM",
            "https://www.fordservicecontent.com/Ford_Content/pubs/content/~WT/~MUS~LEN/3602/tsb04-17-04.htm",
        )
        private fun longTrimPid(bank: Int) = if (bank == 1) 0x07 else 0x09
        private fun pidLabel(pid: Int) = "01" + pid.toString(16).uppercase().padStart(2, '0')
        private fun format(value: Double) = String.format(Locale.US, "%.2f", value)
    }
}
