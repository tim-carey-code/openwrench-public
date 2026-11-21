package org.openwrench.frontend.android

import org.openwrench.nativecore.ObdDecoder

/** The finite read-only decoder catalog bounds selection, not an arbitrary display limit. */
val MAX_LIVE_SIGNALS: Int = ObdDecoder.scalarPids.size

enum class WatchRate(val intervalMillis: Long) { HIGH(200), NORMAL(500), LOW(2_000) }

/** Rates describe requested start-to-start spacing; the adapter still determines actual throughput. */
internal fun liveWatchRate(pid: Int): WatchRate = when (pid) {
    0x0C -> WatchRate.HIGH
    0x04, 0x06, 0x08, 0x0A, 0x0B, 0x0D, 0x0E, 0x10, 0x11,
    0x22, 0x23, 0x43, 0x44, 0x47, 0x49, 0x4A -> WatchRate.NORMAL
    else -> WatchRate.LOW
}

/** Include every supported numeric sensor; structured/status data keep their separate scan/health paths. */
internal fun defaultLiveWatch(advertised: Set<Int>): Map<Int, WatchRate> =
    (listOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0F, 0x10, 0x0B, 0x42) + ObdDecoder.scalarPids.sorted())
        .distinct().filter { it in advertised && it in ObdDecoder.scalarPids }.associateWith(::liveWatchRate)

/** Single-reader scheduling. Overdue age gives every selected PID service under overload. */
internal class LivePollSchedule(private val startedAt: Long) {
    private companion object {
        const val RPM_PID = 0x0C
        const val HIGH_LOAD_SELECTION = 13
        const val RPM_GUARD_MILLIS = 400L
        const val FRESHNESS_GUARD_MILLIS = 3_500L
    }
    private data class Receipt(val started: Long, val completed: Long, val misses: Int, val retryAt: Long)
    private val receipts = mutableMapOf<Int, Receipt>()
    private var lastCompletedPid: Int? = null
    private var lastNonRpmPid: Int? = null

    private fun receiptAge(pid: Int, now: Long): Long =
        now - (receipts[pid]?.completed ?: startedAt)

    private fun dueAt(pid: Int, rate: WatchRate, intervalOverrides: Map<Int, Long>): Long = receipts[pid]?.let {
        maxOf(it.started + (intervalOverrides[pid] ?: rate.intervalMillis), it.retryAt)
    } ?: startedAt

    fun next(rates: Map<Int, WatchRate>, now: Long, intervalOverrides: Map<Int, Long> = emptyMap(),
        freshnessGuards: Map<Int, Long> = emptyMap()): Int? {
        val due = rates.keys.filter { now >= dueAt(it, rates.getValue(it), intervalOverrides) }
        val highLoad = rates.size >= HIGH_LOAD_SELECTION
        if (highLoad) {
            // Keep the fastest gauge moving, then rescue other values nearing
            // the five-second display freshness limit. These are not guarantees.
            if (RPM_PID in due && receiptAge(RPM_PID, now) >= RPM_GUARD_MILLIS &&
                (lastCompletedPid != RPM_PID || due.size == 1)) return RPM_PID
            // Optional health context should not wait behind an entire large watch.
            // Alternate these priority reads with ordinary non-RPM service so even
            // timeout-speed adapters cannot starve the rest of the selected catalog.
            // Only due reads participate: requested spacing and NO DATA backoff win.
            if (lastNonRpmPid !in freshnessGuards) {
                due.filter { it != RPM_PID && it in freshnessGuards &&
                    receiptAge(it, now) >= freshnessGuards.getValue(it) }
                    .maxWithOrNull(compareBy<Int> {
                        receiptAge(it, now).toDouble() / freshnessGuards.getValue(it)
                    }.thenBy { -it })?.let { return it }
            }
            due.filter { it != RPM_PID && receiptAge(it, now) >= FRESHNESS_GUARD_MILLIS }
                .maxWithOrNull(compareBy<Int> { receiptAge(it, now) }.thenBy { -it })?.let { return it }
        }
        // Under high load, a slow reply can leave RPM due again immediately.
        // Give another due PID the next slot rather than reading RPM twice.
        val candidates = if (highLoad && lastCompletedPid == RPM_PID && due.size > 1) due - RPM_PID else due
        return candidates.maxWithOrNull(compareBy<Int> {
            val rate = rates.getValue(it)
            val previous = receipts[it]?.completed ?: (startedAt - rate.intervalMillis)
            (now - previous).toDouble() / (intervalOverrides[it] ?: rate.intervalMillis)
        }.thenBy { -rates.getValue(it).ordinal }.thenBy { -it })
    }

    fun completed(pid: Int, started: Long, received: Long, available: Boolean) {
        val misses = if (available) 0 else ((receipts[pid]?.misses ?: 0) + 1).coerceAtMost(5)
        // An advertised PID may repeatedly return NO DATA. Probe it less often instead of
        // spending every fast slot on absence; a valid reply restores its requested rate.
        val backoff = if (misses == 0) 0L else minOf(10_000L, 1_000L shl (misses - 1))
        receipts[pid] = Receipt(started, received, misses, received + backoff)
        lastCompletedPid = pid
        if (pid != RPM_PID) lastNonRpmPid = pid
    }

    fun waitMillis(rates: Map<Int, WatchRate>, now: Long, intervalOverrides: Map<Int, Long> = emptyMap()): Long =
        (rates.minOfOrNull { (pid, rate) -> dueAt(pid, rate, intervalOverrides) - now } ?: 100L).coerceIn(1L, 100L)
}
