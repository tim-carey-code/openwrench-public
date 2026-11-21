package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder

/** Bounded, in-memory evidence for one live run. Never stores adapter reply text or VIN. */
class LiveDiagnosticLog(private val nowMillis: () -> Long) {
    private data class PidStats(
        var values: Int = 0,
        var noData: Int = 0,
        var totalReadMillis: Long = 0,
        var maxReadMillis: Long = 0,
        var intervalCount: Int = 0,
        var totalIntervalMillis: Long = 0,
        var maxIntervalMillis: Long = 0,
        var lastReceipt: Long? = null,
        var latestValue: String? = null,
    )

    private val recent = ArrayDeque<String>()
    private val stats = sortedMapOf<Int, PidStats>()
    private var generation = -1L
    private var startedAt = 0L
    private var selectedEcu: String? = null
    private var selection = emptyMap<Int, WatchRate>()
    private var running = false

    @Synchronized
    fun opening(ticket: Long) {
        generation = ticket
        selectedEcu = null
        selection = emptyMap()
        stats.clear()
        recent.clear()
        startedAt = nowMillis()
        running = false
        event("connection opening")
    }

    @Synchronized
    fun connected(ticket: Long, ecu: String?) {
        if (ticket != generation) opening(ticket)
        selectedEcu = ecu
        event("session connected")
    }

    @Synchronized
    fun start(ticket: Long, watched: Map<Int, WatchRate>) {
        if (ticket != generation) return
        selection = watched.toMap()
        stats.clear()
        recent.clear()
        startedAt = nowMillis()
        running = true
        event("live start selected=${selection.size}")
    }

    @Synchronized
    fun selectionChanged(ticket: Long, watched: Map<Int, WatchRate>) {
        if (ticket != generation || !running || selection == watched) return
        selection = watched.toMap()
        event("live selection updated selected=${selection.size}")
    }

    @Synchronized
    fun completed(ticket: Long, reading: NativeReading, beganAt: Long, receivedAt: Long) {
        if (ticket != generation || !running) return
        val pid = reading.pid
        if (pid !in ObdDecoder.scalarPids && pid != 0x01 && pid != 0x03) return
        val value = when (val decoded = reading.value) {
            is DecodedReading.Scalar -> decoded.value.toString()
            is DecodedReading.Structured -> "structured"
            null -> null
        }
        val elapsed = (receivedAt - beganAt).coerceAtLeast(0)
        val row = stats.getOrPut(pid) { PidStats() }
        if (value == null) row.noData++ else row.values++
        row.totalReadMillis += elapsed
        row.maxReadMillis = maxOf(row.maxReadMillis, elapsed)
        row.lastReceipt?.let { previous ->
            if (receivedAt > previous) {
                val gap = receivedAt - previous
                row.intervalCount++
                row.totalIntervalMillis += gap
                row.maxIntervalMillis = maxOf(row.maxIntervalMillis, gap)
            }
        }
        row.lastReceipt = receivedAt
        row.latestValue = value
        event("read command=${command(pid)} result=${if (value == null) "NO_DATA" else "VALUE"}" +
            " value=${value ?: "—"} elapsedMs=$elapsed")
    }

    @Synchronized
    fun stop(ticket: Long, reason: String) {
        if (ticket != generation || !running) return
        running = false
        event("live stop reason=$reason")
    }

    @Synchronized
    fun failure(ticket: Long, kind: String) {
        if (ticket != generation) return
        running = false
        event("session failure kind=${kind.filter { it.isLetterOrDigit() || it == '_' }.take(40)}")
    }

    @Synchronized
    fun snapshot(): String {
        if (generation < 0) return "No live connection has been made in this app process."
        val now = nowMillis()
        val header = listOf(
            "OPENWRENCH live data diagnostic",
            "Latest live run only; memory only. Decoded Mode 01 values are included. No VIN or raw adapter replies.",
            "session=$generation selectedEcu=${selectedEcu ?: "unaddressed"} running=$running elapsedMs=${(now - startedAt).coerceAtLeast(0)}",
            "selected=" + selection.entries.sortedBy { it.key }.joinToString(",") { "${command(it.key)}:${it.value.intervalMillis}ms" },
            "Completed read intervals include NO DATA; they are not ECU sample timestamps.",
        )
        val totals = stats.map { (pid, row) ->
            val count = row.values + row.noData
            "command=${command(pid)} values=${row.values} noData=${row.noData}" +
                " meanReadMs=${row.totalReadMillis / count} maxReadMs=${row.maxReadMillis}" +
                " meanReceiptIntervalMs=${if (row.intervalCount == 0) "unknown" else row.totalIntervalMillis / row.intervalCount}" +
                " maxReceiptIntervalMs=${if (row.intervalCount == 0) "unknown" else row.maxIntervalMillis}" +
                " latestValue=${row.latestValue ?: "—"}" +
                " latestAgeMs=${row.lastReceipt?.let { (now - it).coerceAtLeast(0) } ?: "unknown"}"
        }
        return (header + "Per-PID summary:" + totals + "Recent events (oldest first):" + recent).joinToString("\n")
    }

    private fun event(message: String) {
        if (recent.size == MAX_EVENTS) recent.removeFirst()
        recent.addLast("+${(nowMillis() - startedAt).coerceAtLeast(0)}ms $message")
    }

    private fun command(pid: Int) = "01${pid.toString(16).uppercase().padStart(2, '0')}"

    private companion object { const val MAX_EVENTS = 320 }
}
