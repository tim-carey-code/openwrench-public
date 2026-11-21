package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeFailureStage
import org.openwrench.nativecore.ObdDecoder

/** Latest connection attempt only. Never receives reply text, VINs or USB serial numbers. */
class UsbDiagnostics(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lines = ArrayDeque<String>()
    private val header = mutableListOf<String>()
    private var driverMetadata: String? = null
    private var hostBaud: Int? = null
    private var startedAt = 0L
    private var generation = 0L
    private data class Timing(var count: Long = 0, var totalMillis: Long = 0, var maxMillis: Long = 0,
        var firstReceipt: Long = 0, var lastReceipt: Long = 0)
    private val timings = sortedMapOf<String, Timing>()

    @Synchronized
    internal fun begin(deviceId: Int, baudRate: Int, controls: UsbControlLines): UsbDiagnosticAttempt {
        lines.clear()
        timings.clear()
        header.clear()
        driverMetadata = null
        hostBaud = null
        startedAt = nowMillis()
        generation++
        header.add("OPENWRENCH USB connection diagnostic")
        header.add("Latest attempt only; memory only. No reply payloads, VINs or device serial numbers.")
        header.add("deviceId=$deviceId baud=${if (baudRate == 0) "AUTO" else baudRate} 8N1 flow=NONE DTR=${controls.dtr} RTS=${controls.rts}")
        val attempt = UsbDiagnosticAttempt(this, generation)
        attempt.event("BEGIN")
        return attempt
    }

    @Synchronized
    internal fun event(attempt: Long, metadata: String) {
        if (attempt != generation) return
        while (lines.size >= MAX_LINES) lines.removeFirst()
        lines.add("+${(nowMillis() - startedAt).coerceAtLeast(0)}ms ${metadata.take(240)}")
    }

    @Synchronized
    internal fun device(attempt: Long, metadata: String) {
        if (attempt == generation) driverMetadata = metadata
    }

    @Synchronized
    internal fun baud(attempt: Long, rate: Int) {
        if (attempt == generation) hostBaud = rate
    }

    @Synchronized
    internal fun completed(attempt: Long, command: String, elapsedMillis: Long) {
        if (attempt != generation || command.length != 4 || !command.startsWith("01")) return
        val pid = command.drop(2).toIntOrNull(16) ?: return
        if (pid !in ObdDecoder.scalarPids && pid != 0x01 && pid != 0x03) return
        val receipt = nowMillis()
        val timing = timings.getOrPut(command) { Timing(firstReceipt = receipt) }
        timing.count++
        timing.totalMillis += elapsedMillis.coerceAtLeast(0)
        timing.maxMillis = maxOf(timing.maxMillis, elapsedMillis)
        timing.lastReceipt = receipt
    }

    private fun timingSummary(): List<String> = if (timings.isEmpty()) emptyList() else
        listOf("Mode 01 exchange timing since connection (includes NO DATA and pauses; not ECU sample rates):") +
            timings.map { (command, timing) ->
                "command=$command completed=${timing.count} meanExchangeMs=${timing.totalMillis / timing.count}" +
                    " maxExchangeMs=${timing.maxMillis} meanCompletionIntervalMs=" +
                    if (timing.count > 1) "${(timing.lastReceipt - timing.firstReceipt) / (timing.count - 1)}" else "unknown"
            }

    @Synchronized
    fun snapshot(): String = if (header.isEmpty()) "No USB connection attempt has been made." else
        (header + listOfNotNull(driverMetadata, hostBaud?.let { "Current host baud=$it" }) + timingSummary() + lines).joinToString("\n")

    private companion object { const val MAX_LINES = 180 }
}

/** Late cleanup of an old connection cannot contaminate the next connection's diagnostic. */
internal class UsbDiagnosticAttempt(private val diagnostics: UsbDiagnostics, private val generation: Long) {
    fun event(metadata: String) = diagnostics.event(generation, metadata)

    fun completed(command: String, elapsedMillis: Long) = diagnostics.completed(generation, command, elapsedMillis)

    /** Only typed metadata crosses this boundary; exception messages may contain vehicle replies. */
    fun sessionFailure(stage: NativeFailureStage, kind: ErrorKind) {
        event("session result=failed stage=$stage kind=$kind")
    }

    fun device(driver: String, vendorId: Int, productId: Int) {
        val name = driver.filter { it.isLetterOrDigit() || it == '_' }.take(64)
        val metadata = "driver=$name VID=%04X PID=%04X port=0".format(vendorId, productId)
        diagnostics.device(generation, metadata)
        event(metadata)
    }

    fun baud(rate: Int) = diagnostics.baud(generation, rate)
}
