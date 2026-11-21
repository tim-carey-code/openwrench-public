package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException

/** Narrow seam for scripted tests; the Android adapter delegates directly to UsbSerialPort. */
internal interface UsbSerialIo {
    fun checkActive()
    fun setBaudRate(rate: Int)
    /** Returns false only when the driver does not implement hardware purging. */
    fun purgeInput(): Boolean
    fun write(bytes: ByteArray, timeoutMillis: Int)
    fun read(bytes: ByteArray, timeoutMillis: Int): Int
}

/** Connection-only identification probing. Normal vehicle exchanges are never retried. */
internal class UsbSerialSession(
    private val io: UsbSerialIo,
    private val requestedBaud: Int,
    private val diagnostics: UsbDiagnosticAttempt,
    private val nowMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    bufferSize: Int,
) {
    private val buffer = ByteArray(bufferSize)
    private val pacer = ExchangePacer(nowMillis, sleepMillis)
    private var currentBaud = 0
    private var firstReset = true

    init { require(bufferSize >= 64) }

    fun initialize(endsAt: Long) {
        configure(if (requestedBaud == UsbConnectionSettings.AUTO_BAUD) 38400 else requestedBaud, endsAt)
        diagnostics.event("stage=startup-settle settleMs=2000")
        val settledAt = nowMillis() + 2_000
        while (nowMillis() < settledAt) {
            activeRemaining(endsAt)
            // Bounded sleeps permit cancellation during startup without polling USB.
            sleepMillis(minOf(100L, settledAt - nowMillis(), activeRemaining(endsAt).toLong()))
        }
        activeRemaining(endsAt)
        if (requestedBaud == UsbConnectionSettings.AUTO_BAUD) {
            identifyBaud(UsbConnectionSettings.PROBE_BAUD_RATES, endsAt)
        }
        diagnostics.event("stage=ready baud=$currentBaud")
    }

    fun exchange(command: String, endsAt: Long): String {
        val resetRecovery = requestedBaud == UsbConnectionSettings.AUTO_BAUD && firstReset && command == "ATZ"
        if (command == "ATZ") firstReset = false
        if (!resetRecovery) {
            return attempt(command, endsAt, endsAt, tolerateMalformed = false)
                ?: timeout("USB adapter did not return a response; check the selected baud, adapter power and USB connection.")
        }

        // ATZ can restore the adapter's saved UART rate (ELM327 AT BRD / AT Z contract).
        // Give ordinary reset startup time first; all recovery stays inside this same deadline.
        val initial = attempt(command, endsAt, nowMillis() + 2_500, tolerateMalformed = true)
        if (initial != null && isElmIdentity(initial, "ATZ")) return initial
        diagnostics.event("stage=reset-baud-recovery")
        val previousBaud = currentBaud
        // Reserve time for a REAL reset response at the recovered rate. Never return ATI as ATZ.
        val probeEndsAt = endsAt - 2_000
        activeRemaining(probeEndsAt)
        identifyBaud((listOf(38400, 9600, previousBaud) + UsbConnectionSettings.PROBE_BAUD_RATES).distinct(), probeEndsAt)
        return attempt("ATZ", endsAt, endsAt, tolerateMalformed = false)
            ?: timeout("USB adapter did not respond after reset baud recovery; reconnect and check adapter power/settings.")
    }

    private fun identifyBaud(rates: List<Int>, endsAt: Long) {
        for (rate in rates) {
            activeRemaining(endsAt)
            if (!configure(rate, endsAt, skipUnsupported = true)) {
                // A driver can reject a host UART rate. Transport/I/O failures are never a baud miss.
                activeRemaining(endsAt)
                diagnostics.event("stage=probe baud=$rate result=unsupported-rate")
                continue
            }
            repeat(2) { pass ->
                activeRemaining(endsAt)
                diagnostics.event("stage=probe baud=$rate pass=${pass + 1}")
                val response = attempt("ATI", endsAt, nowMillis() + 700, tolerateMalformed = true)
                if (response != null && isElmIdentity(response)) {
                    activeRemaining(endsAt)
                    diagnostics.event("stage=probe baud=$rate result=identified")
                    return
                }
                // The second ATI clears a partial command left by earlier wrong-baud bytes.
                // Only identification is repeated, never an OBD request.
                diagnostics.event("stage=probe baud=$rate result=${if (response == null) "no-valid-frame" else "non-identity"}")
            }
        }
        throw NativeDiagnosticException(ErrorKind.ADAPTER,
            "USB opened, but no ELM-compatible reply was received at the supported baud rates. Check adapter vehicle power, control lines and USB/OTG connection; FTDI alone does not identify the diagnostic protocol.")
    }

    private fun configure(rate: Int, endsAt: Long, skipUnsupported: Boolean = false): Boolean {
        activeRemaining(endsAt)
        val supported = try {
            io.setBaudRate(rate)
            true
        } catch (error: UnsupportedOperationException) {
            if (!skipUnsupported) throw error
            false
        } catch (error: IllegalArgumentException) {
            // Some drivers use IllegalArgumentException for an unsupported positive UART rate.
            // Catch only the parameter call, not cancellation checks or I/O failures.
            if (!skipUnsupported) throw error
            false
        }
        activeRemaining(endsAt)
        if (!supported) return false
        currentBaud = rate
        diagnostics.baud(rate)
        diagnostics.event("stage=serial-parameters baud=$rate")
        return true
    }

    private fun attempt(command: String, endsAt: Long, responseEndsAt: Long, tolerateMalformed: Boolean): String? {
        val operationStarted = nowMillis()
        var received = 0
        var reads = 0
        var emptyReads = 0
        var written = 0
        var prompt = false
        var result = "incomplete"
        // Every caller uses fixed allowlisted commands or the already-validated native command.
        diagnostics.event("command=$command stage=pacing budgetMs=${activeRemaining(endsAt)}")
        try {
            pacer.beforeWrite(command)
            activeRemaining(endsAt)
            diagnostics.event("command=$command stage=purge-input")
            if (!io.purgeInput()) drain(endsAt)
            val bytes = frameElmCommand(command)
            diagnostics.event("command=$command stage=write")
            io.write(bytes, activeRemaining(endsAt))
            written = bytes.size
            diagnostics.event("command=$command stage=read-prompt baud=$currentBaud")
            val frame = ElmFrameAccumulator()
            val readEndsAt = minOf(endsAt, responseEndsAt)
            while (true) {
                activeRemaining(endsAt)
                val remaining = readEndsAt - nowMillis()
                if (remaining <= 0) {
                    result = if (received == 0) "no-payload" else "partial-response"
                    return null
                }
                reads++
                val count = io.read(buffer, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                activeRemaining(endsAt)
                if (count == 0) { emptyReads++; continue }
                check(count in 0..buffer.size) { "USB driver returned an invalid read count" }
                received += count
                val response = try {
                    frame.append(buffer, count)
                } catch (error: NativeDiagnosticException) {
                    if (tolerateMalformed && error.kind == ErrorKind.MALFORMED_RESPONSE) {
                        result = "malformed"
                        return null
                    }
                    throw error
                }
                if (response != null) {
                    prompt = true
                    result = "complete"
                    diagnostics.completed(command, nowMillis() - operationStarted)
                    return response
                }
            }
        } catch (error: Exception) {
            result = when (error) {
                is java.util.concurrent.CancellationException -> "cancelled"
                is NativeDiagnosticException -> error.kind.name
                else -> "io-failure"
            }
            throw error
        } finally {
            diagnostics.event("command=$command result=$result elapsedMs=${nowMillis() - operationStarted} written=$written received=$received reads=$reads emptyReads=$emptyReads prompt=$prompt")
            pacer.completed(command, prompt)
        }
    }

    private fun drain(endsAt: Long) {
        var discarded = 0
        while (true) {
            val count = io.read(buffer, minOf(250, activeRemaining(endsAt)))
            activeRemaining(endsAt)
            if (count == 0) break
            check(count in 0..buffer.size) { "USB driver returned an invalid read count" }
            discarded += count
            if (discarded > MAX_ELM_RESPONSE_BYTES) {
                throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "USB input did not become idle before sending a command.")
            }
        }
        diagnostics.event("stage=purge-input discarded=$discarded")
    }

    private fun activeRemaining(endsAt: Long): Int {
        io.checkActive()
        val remaining = endsAt - nowMillis()
        if (remaining <= 0) timeout("USB connection or command deadline expired; reconnect the adapter.")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun timeout(message: String): Nothing = throw NativeDiagnosticException(ErrorKind.TIMEOUT, message)

    internal companion object {
        fun isElmIdentity(response: String, command: String = "ATI"): Boolean {
            if (response.length > MAX_ELM_RESPONSE_BYTES || response.any {
                    it.code >= 127 || (it.code < 32 && it != '\r' && it != '\n' && it != '\t')
                }) return false
            val trimmed = response.trim(' ', '\r', '\n', '\t')
            if (!trimmed.endsWith('>') || trimmed.count { it == '>' } != 1) return false
            val lines = trimmed.dropLast(1).split('\r', '\n').map(String::trim)
                .filter { it.isNotEmpty() && !it.equals(command, ignoreCase = true) }
            return lines.size == 1 && Regex("ELM327(?:[ /].*)?", RegexOption.IGNORE_CASE).matches(lines.single())
        }
    }
}
