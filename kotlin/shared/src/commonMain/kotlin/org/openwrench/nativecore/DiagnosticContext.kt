package org.openwrench.nativecore

/** Explicit read-only services; no arbitrary service or command can be selected. */
enum class DtcService(val request: String, val response: String, val label: String) {
    STORED("03", "43", "Stored"), PENDING("07", "47", "Pending"), PERMANENT("0A", "4A", "Permanent")
}
enum class DiagnosticReplyStatus { AVAILABLE, NO_DATA, UNSUPPORTED, UNAVAILABLE }
data class DiagnosticCodeRead(val service: DtcService, val status: DiagnosticReplyStatus,
    val codes: List<String> = emptyList(), val sourceEcu: String? = null, val note: String? = null)
data class FreezeFrameRead(val status: DiagnosticReplyStatus, val triggerCode: String? = null,
    val readings: List<NativeReading> = emptyList(), val sourceEcu: String? = null,
    val advertisedPids: Set<Int> = emptySet(), val note: String? = null,
    val unavailablePids: Map<Int, DiagnosticReplyStatus> = emptyMap())
data class DiagnosticContextRead(val codes: List<DiagnosticCodeRead>, val freezeFrame: FreezeFrameRead)

/** Frame zero only. Its support bitmap is independent of current Mode 01 support. */
internal object FreezeFrameDecoder {
    val pids = setOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11)
    val requests = (pids + setOf(0x00, 0x02)).map { "02${it.toString(16).uppercase().padStart(2, '0')}00" }.toSet()
    fun data(payload: String, pid: Int): String {
        require(payload.length % 2 == 0 && payload.all { it in "0123456789ABCDEFabcdef" })
        require(payload.uppercase().startsWith("42${pid.toString(16).uppercase().padStart(2, '0')}00")) {
            "Freeze-frame response mode, PID or frame number mismatch"
        }
        return payload.drop(6).uppercase()
    }
    fun supported(payload: String): Set<Int> = ObdDecoder.supported(0, "4100" + data(payload, 0))
    fun trigger(payload: String): String? {
        val raw = data(payload, 2)
        require(raw.length == 4) { "Freeze-frame trigger must be one DTC pair" }
        return if (raw == "0000") null else StoredDtcDecoder.decode("43$raw", can = false).single()
    }
    fun reading(payload: String, pid: Int): DecodedReading {
        require(pid in pids)
        return ObdDecoder.decode(pid, "41${pid.toString(16).uppercase().padStart(2, '0')}" + data(payload, pid))
    }
}
