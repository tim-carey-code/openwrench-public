package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.ObdDecoder
import org.openwrench.nativecore.StoredDtcRead
import org.openwrench.nativecore.DiagnosticContextRead

enum class DiagnosticScanStatus { NO_SESSION, STOP_LIVE, READY, COLLECTING, CAPTURED, HISTORICAL, SIMULATED, IMPORTED, IMPORTED_SIMULATED }

data class DiagnosticScanRow(val pid: Int, val label: String, val unit: String?,
    val value: DecodedReading?)
data class DiagnosticMonitorView(val name: String, val availability: String, val completion: String)

data class DiagnosticScanView(
    val status: DiagnosticScanStatus,
    val canRun: Boolean,
    val eligibleCount: Int,
    val availableCount: Int,
    val noDataCount: Int,
    val capturedMillis: Long?,
    val milOn: Boolean?,
    val reportedDtcCount: Int?,
    val storedDtcs: StoredDtcRead?,
    val monitors: List<DiagnosticMonitorView>,
    val rows: List<DiagnosticScanRow>,
    val warningContext: DiagnosticContextRead? = null,
) {
    /** User-invoked clipboard report. No VIN or raw adapter frames are included. */
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH read-only diagnostic scan")
        appendLine("Status: ${status.name.lowercase().replace('_', ' ')}")
        appendLine("Mode 01: $eligibleCount eligible, $availableCount values, $noDataCount NO DATA")
        appendLine("MIL: ${milOn?.let { if (it) "ON" else "OFF" } ?: "unknown"}")
        appendLine("ECU-reported DTC count: ${reportedDtcCount ?: "unknown"}")
        appendLine("Mode 03 source: ${storedDtcs?.sourceEcu ?: "unavailable"}")
        val codes = storedDtcs?.codes
        appendLine("Stored codes: " + when {
            warningContext != null -> "see separately labeled code-service replies below"
            storedDtcs == null -> "not captured"
            codes == null -> "NO DATA (unknown)"
            codes.isEmpty() -> "none returned in this response"
            else -> codes.joinToString(", ")
        })
        monitors.forEach { appendLine("Monitor ${it.name}: available=${it.availability}, complete=${it.completion}") }
        rows.forEach { row ->
            val pid = row.pid.toString(16).uppercase().padStart(2, '0')
            val value = when (val reading = row.value) {
                null -> "NO DATA"
                is DecodedReading.Scalar -> "${reading.value}${row.unit?.let { " $it" } ?: ""}"
                is DecodedReading.Structured -> reading.fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            appendLine("01$pid ${row.label}: $value")
        }
        warningContext?.let { appendLine(it.reportText()) }
        appendLine("Identifiers and measurements do not establish a fault cause. VIN omitted.")
    }
}

/** Presents the existing capability-gated Mode 01 sweep without inferring a fault from a value. */
object DiagnosticScanDelivery {
    fun present(state: NativeUiState): DiagnosticScanView {
        val hardware = state.origin == ReadingOrigin.HARDWARE && state.connected &&
            !state.faulted && !state.cleanupUncertain
        val current = hardware && state.snapshotSessionGeneration == state.sessionGeneration
        val status = when {
            state.snapshotInProgress && hardware -> DiagnosticScanStatus.COLLECTING
            state.snapshot != null && state.origin == ReadingOrigin.SIMULATOR -> DiagnosticScanStatus.SIMULATED
            state.snapshot != null && state.origin == ReadingOrigin.REPLAY && state.replaySimulated ->
                DiagnosticScanStatus.IMPORTED_SIMULATED
            state.snapshot != null && state.origin == ReadingOrigin.REPLAY -> DiagnosticScanStatus.IMPORTED
            state.snapshot != null && current -> DiagnosticScanStatus.CAPTURED
            state.snapshot != null -> DiagnosticScanStatus.HISTORICAL
            !hardware -> DiagnosticScanStatus.NO_SESSION
            state.liveRunning -> DiagnosticScanStatus.STOP_LIVE
            else -> DiagnosticScanStatus.READY
        }
        val rows = (if (state.snapshotInProgress) emptyList() else state.snapshot.orEmpty()).map { reading ->
            DiagnosticScanRow(reading.pid, ObdDecoder.catalog.getValue(reading.pid).name,
                ObdDecoder.catalog.getValue(reading.pid).unit, reading.value)
        }
        val monitor = rows.firstOrNull { it.pid == 0x01 }?.value as? DecodedReading.Structured
        val fields = monitor?.fields.orEmpty()
        val monitors = (0 until 16).mapNotNull { index ->
            val prefix = "monitors.$index."
            fields[prefix + "name"]?.let { name ->
                DiagnosticMonitorView(name, fields[prefix + "available"] ?: "unknown",
                    fields[prefix + "complete"] ?: "unknown")
            }
        }
        val eligibleCount = when {
            state.snapshot != null && state.origin != ReadingOrigin.HARDWARE -> state.snapshot.size
            state.snapshot != null -> state.snapshotEligiblePids.size
            hardware -> state.supported.intersect(ObdDecoder.snapshotPids)
                .intersect(ObdDecoder.catalog.keys).minus(ObdDecoder.rangeBases).size
            else -> 0
        }
        return DiagnosticScanView(status, hardware && !state.liveRunning && !state.busy &&
            !state.stopPending, eligibleCount,
            rows.count { it.value != null }, rows.count { it.value == null },
            state.snapshotCapturedMillis,
            fields["mil_on"]?.toBooleanStrictOrNull(),
            fields["dtc_count"]?.toIntOrNull(),
            if (state.snapshotInProgress) null else state.storedDtcs,
            monitors, rows, if (state.snapshotInProgress || state.origin != ReadingOrigin.HARDWARE) null else state.diagnosticContext)
    }
}
