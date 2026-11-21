package org.openwrench.nativecore

/** Origin is fixed at publication. Historical viewing never changes acquisition provenance. */
enum class ScanOrigin { HARDWARE, SIMULATOR, IMPORTED, IMPORTED_SIMULATOR, SYNTHETIC }

/** No ECU event time or per-reading timestamps are inferred from retrieval clocks.
 * Session IDs identify an app connection only, never a vehicle. Imported acquisition clocks
 * remain unknown; publishedAt is the local import/publication time in that case.
 */
data class ScanCapture(
    val captureId: String,
    val origin: ScanOrigin,
    val sessionId: String?,
    val sourceEcu: String?,
    val startedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val durationMillis: Long?,
    val publishedAtEpochMillis: Long,
    val eligiblePids: Set<Int>,
    val readings: List<NativeReading>,
    /** An absent service means NOT CAPTURED, never an available empty response. */
    val services: List<DiagnosticCodeRead>,
    /** Null means NOT CAPTURED. The original ECU capture time is always unknown. */
    val frameZero: FreezeFrameRead?,
    val schemaVersion: Int = 1,
)
