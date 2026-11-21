package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val MAX_ELM_RESPONSE_BYTES = 4096

/** Accumulates exactly one ASCII ELM response, retaining its terminating prompt. */
internal class ElmFrameAccumulator {
    private val text = StringBuilder()
    private var complete = false

    fun append(bytes: ByteArray, count: Int): String? {
        require(count in 0..bytes.size)
        check(!complete)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            if (value >= 0x7f || (value < 0x20 && value != 9 && value != 10 && value != 13)) {
                throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Adapter returned non-ASCII or control data.")
            }
            if (complete) {
                if (value != 32 && value != 9 && value != 10 && value != 13) {
                    throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Unexpected data followed the adapter prompt.")
                }
                continue
            }
            if (text.length == MAX_ELM_RESPONSE_BYTES) {
                throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Adapter response exceeded 4096 bytes.")
            }
            text.append(value.toChar())
            complete = value == '>'.code
            if (!complete && text.length == MAX_ELM_RESPONSE_BYTES) {
                throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Adapter response reached 4096 bytes without a prompt.")
            }
        }
        return if (complete) text.toString() else null
    }
}

/** Commands have already passed the native protocol allowlist; reject framing injection here. */
internal fun frameElmCommand(command: String): ByteArray {
    if (command.isBlank() || command.length > 64 || command.any { it.code !in 0x20..0x7e }) {
        throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Invalid adapter command framing.")
    }
    return (command + '\r').toByteArray(Charsets.US_ASCII)
}

internal const val LIVE_READ_GAP_MILLIS = 20L

/** The ELM prompt signals readiness. Keep setup/probing conservative, shorten Mode 01 read gaps. */
internal class ExchangePacer(
    private val nowMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastCompletion: Long? = null
    private var previousReadReady = false

    fun beforeWrite(command: String = "") {
        val completed = lastCompletion ?: return
        val gap = if (previousReadReady && isLiveRead(command)) LIVE_READ_GAP_MILLIS else 200L
        while (true) {
            val remaining = gap - (nowMillis() - completed)
            if (remaining <= 0) return
            sleepMillis(remaining)
        }
    }

    fun completed(command: String = "", prompt: Boolean = false) {
        lastCompletion = nowMillis()
        previousReadReady = prompt && isLiveRead(command)
    }

    private fun isLiveRead(command: String): Boolean = command.length == 4 && command.startsWith("01") &&
        command.drop(2).toIntOrNull(16)?.let { it in org.openwrench.nativecore.ObdDecoder.scalarPids || it == 0x01 || it == 0x03 } == true
}

/** Closing an Android transport is what aborts native blocking socket/USB operations. */
internal class IoDeadline(timeoutMillis: Long, private val operation: String, abort: () -> Unit) {
    private val state = AtomicInteger(0)
    init { require(timeoutMillis in 1..60_000) }

    private val future = timers.schedule({
        if (state.compareAndSet(0, 2)) runCatching(abort)
    }, timeoutMillis, TimeUnit.MILLISECONDS)

    fun finish() {
        state.compareAndSet(0, 1)
        future.cancel(false)
        checkTimeout()
    }

    fun checkTimeout(cause: Throwable? = null) {
        if (state.get() == 2) {
            throw NativeDiagnosticException(ErrorKind.TIMEOUT, "$operation timed out; reconnect the adapter.", cause)
        }
    }

    companion object {
        private val timers = ScheduledThreadPoolExecutor(2) { task ->
            Thread(task, "OpenWrench-transport-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** Failed releases retain their resource so a new connection cannot hide uncertain cleanup. */
internal class PendingReleases<T>(private val retryable: Boolean = true, private val release: (T) -> Unit) {
    private val pending = linkedSetOf<T>()
    private val failed = linkedSetOf<T>()
    @Synchronized fun attempt(resource: T) {
        pending.add(resource)
        // Some Android wrappers mark themselves closed before releasing the native handle.
        // A later no-op close cannot prove that an earlier exception actually released it.
        if (!retryable && resource in failed) return
        try { release(resource); pending.remove(resource); failed.remove(resource) }
        catch (_: Exception) { failed.add(resource) }
    }
    @Synchronized fun requireReleased() {
        if (pending.isNotEmpty()) throw NativeDiagnosticException(ErrorKind.TRANSPORT,
            if (retryable) "Adapter release is unconfirmed; retry release before connecting."
            else "Bluetooth release is unconfirmed. Close the application process before reconnecting.")
    }
    @Synchronized fun retry() {
        pending.toList().forEach(::attempt)
        requireReleased()
    }
}
