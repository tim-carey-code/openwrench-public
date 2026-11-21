package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder

/** A guided observation is user-reported idle; the app cannot verify Park, load, or accessories. */
enum class IdleHealthPhase { IDLE, CAPTURING, SCANNING }
enum class IdleHealthStatus { COMPLETE, PARTIAL, CANCELLED }

data class IdleHealthSample(
    val reading: NativeReading,
    val sessionGeneration: Long,
    val receivedAtMonotonicMillis: Long,
)

data class IdleHealthSignalSummary(
    val pid: Int,
    val name: String,
    val unit: String?,
    val supported: Boolean,
    val sampleCount: Int,
    val noDataCount: Int,
    val firstValue: Double?,
    val lastValue: Double?,
    val minimum: Double?,
    val maximum: Double?,
    /** Bounded source evidence, retained with receipt times rather than inferred ECU sample times. */
    val samples: List<IdleHealthSample>,
)

data class IdleHealthReport(
    val sessionGeneration: Long,
    val sourceEcu: String?,
    val startedAtWallMillis: Long,
    val completedAtWallMillis: Long,
    val elapsedMillis: Long,
    val status: IdleHealthStatus,
    val signals: List<IdleHealthSignalSummary>,
    /** Present only when the scan completed in this same hardware session. */
    val scan: DiagnosticScanView?,
    val message: String?,
) {
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH guided idle observation")
        appendLine("Status: ${status.name.lowercase()}; user-reported idle condition, not verified by the app")
        appendLine("Session: $sessionGeneration; response source: ${sourceEcu ?: "unaddressed/unknown"}")
        appendLine("Started at epoch ms: $startedAtWallMillis; completed at epoch ms: $completedAtWallMillis")
        appendLine("Observed capture duration: $elapsedMillis ms")
        val advertised = signals.count { it.supported }
        val measured = signals.count { it.sampleCount > 0 }
        appendLine("Evidence coverage: $measured/$advertised advertised target signals returned values; " +
            "${signals.size - advertised} unsupported; ${signals.sumOf { it.sampleCount }} valid timed receipts; " +
            "${signals.sumOf { it.noDataCount }} NO DATA replies")
        appendLine("Scan: " + if (scan == null) "not completed in this session; MIL, codes and readiness unknown"
            else "same-session read-only scan; ${scan.availableCount}/${scan.eligibleCount} values")
        scan?.let {
            val codes = it.storedDtcs?.codes
            val flags = buildList {
                if (it.milOn == true) add("MIL ON")
                if ((it.reportedDtcCount ?: 0) > 0) add("ECU-reported DTC count ${it.reportedDtcCount}")
                if (!codes.isNullOrEmpty()) add("stored codes returned")
            }
            appendLine("ECU attention flags: " + when {
                flags.isNotEmpty() -> flags.joinToString("; ")
                it.milOn == false && it.reportedDtcCount == 0 && codes != null && codes.isEmpty() ->
                    "none reported in this scan"
                else -> "unknown or incomplete"
            })
            appendLine("MIL: ${it.milOn?.let { on -> if (on) "ON" else "OFF" } ?: "unknown"}")
            appendLine("ECU-reported DTC count: ${it.reportedDtcCount ?: "unknown"}")
            appendLine("Mode 03 response source: ${it.storedDtcs?.sourceEcu ?: "unavailable"}")
            appendLine("Stored codes: " + when (codes) {
                null -> "unknown (Mode 03 not captured or NO DATA)"
                else -> if (codes.isEmpty()) "none returned by this response" else codes.joinToString(", ")
            })
            val advertisedMonitors = it.monitors.filter { monitor -> monitor.availability == "true" }
            val incomplete = advertisedMonitors.filter { monitor -> monitor.completion == "false" }
            val unknown = advertisedMonitors.count { monitor -> monitor.completion !in setOf("true", "false") }
            appendLine("Readiness: " + when {
                it.monitors.isEmpty() -> "unknown (monitor status not returned)"
                advertisedMonitors.isEmpty() -> "no advertised monitors returned; completeness unknown"
                else -> "${incomplete.size} advertised monitor(s) incomplete; $unknown completion status(es) unknown"
            } + "; this is a status, not a fault cause")
            it.monitors.forEach { monitor ->
                appendLine("Monitor ${monitor.name}: available=${monitor.availability}, complete=${monitor.completion}")
            }
        }
        appendLine("Timed trends (receipt times are monotonic adapter receipts, not ECU sample times):")
        signals.forEach { signal ->
            appendLine("01${signal.pid.toString(16).uppercase().padStart(2, '0')} ${signal.name}: " + when {
                !signal.supported -> "not advertised by this ECU"
                signal.sampleCount == 0 -> "no valid samples; NO DATA=${signal.noDataCount}"
                else -> "${signal.sampleCount} values, NO DATA=${signal.noDataCount}, " +
                    "first=${signal.firstValue}, last=${signal.lastValue}, min=${signal.minimum}, max=${signal.maximum}" +
                    (signal.unit?.let { " $it" } ?: "") +
                    ", first receipt=${signal.samples.first().receivedAtMonotonicMillis}ms, " +
                    "last receipt=${signal.samples.last().receivedAtMonotonicMillis}ms"
            })
        }
        message?.let { appendLine("Note: $it") }
        appendLine("This report does not establish a fault cause, repair need, or whole-vehicle health.")
    }
}

/** Fixed standard inputs for a first evidence capture; unsupported inputs remain explicit gaps. */
object IdleHealthReportBuilder {
    const val DURATION_MILLIS = 60_000L
    const val READ_SPACING_MILLIS = 500L
    val targetPids = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x42)
    const val MAX_SAMPLES = 160

    fun build(
        sessionGeneration: Long,
        sourceEcu: String?,
        startedAtWallMillis: Long,
        completedAtWallMillis: Long,
        elapsedMillis: Long,
        status: IdleHealthStatus,
        supportedPids: Set<Int>,
        samples: List<IdleHealthSample>,
        scan: DiagnosticScanView?,
        message: String? = null,
    ): IdleHealthReport {
        require(sessionGeneration >= 0 && startedAtWallMillis >= 0 &&
            completedAtWallMillis >= startedAtWallMillis && elapsedMillis >= 0)
        require(samples.size <= MAX_SAMPLES)
        require(samples.all { it.sessionGeneration == sessionGeneration &&
            it.reading.pid in targetPids && it.receivedAtMonotonicMillis >= 0 })
        require(scan == null || scan.status == DiagnosticScanStatus.CAPTURED)
        val summaries = targetPids.map { pid ->
            val metadata = ObdDecoder.catalog.getValue(pid)
            val rows = samples.filter { it.reading.pid == pid }
            val values = rows.mapNotNull { (it.reading.value as? DecodedReading.Scalar)?.value }
            IdleHealthSignalSummary(pid, metadata.name, metadata.unit, pid in supportedPids,
                values.size, rows.size - values.size, values.firstOrNull(), values.lastOrNull(),
                values.minOrNull(), values.maxOrNull(), rows)
        }
        return IdleHealthReport(sessionGeneration, sourceEcu, startedAtWallMillis, completedAtWallMillis, elapsedMillis,
            status, summaries, scan, message)
    }
}
