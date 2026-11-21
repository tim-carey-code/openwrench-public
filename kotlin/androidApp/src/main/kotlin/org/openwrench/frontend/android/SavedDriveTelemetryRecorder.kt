package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder

/**
 * Bounded retained telemetry from the existing reader, independent of health assessment
 * and display selection. Advertised scalar identities remain present even before a receipt.
 */
internal class SavedDriveTelemetryRecorder(
    private val startedAt: Long,
    supported: Set<Int>,
) {
    val pids = supported.intersect(ObdDecoder.scalarPids).sorted()
    private val capacity = minOf(1_200, DriveHistoryCodec.MAX_EXTENDED_POINTS / pids.size.coerceAtLeast(1))
    // Adapt chart resolution to retain a useful twenty-minute window without dropping channels.
    private val bucketMillis = ((RETENTION_MILLIS + capacity - 1) / capacity + 999) / 1_000 * 1_000
    private val points = mutableMapOf<Int, List<LiveHealthChartPoint>>()
    private val previous = mutableMapOf<Int, Pair<Long, Boolean>>()
    private val summaries = mutableMapOf<Int, LiveHealthDriveSignal>()
    private var elapsed = 0L
    private var omittedBuckets = 0L
    private var replacedReceipts = 0L
    private var stopped = false

    fun offer(reading: NativeReading, at: Long) {
        if (stopped || reading.pid !in pids || at < startedAt) return
        val offset = at - startedAt
        if (offset < elapsed || previous[reading.pid]?.first == offset) return
        val value = when (val decoded = reading.value) {
            null -> null
            is DecodedReading.Scalar -> decoded.value.takeIf {
                DriveHistoryCodec.validTelemetryValue(reading.pid, it)
            } ?: return
            else -> return
        }
        elapsed = offset
        val pid = reading.pid
        val metadata = ObdDecoder.catalog.getValue(pid)
        if (value != null) {
            val old = summaries[pid]
            summaries[pid] = LiveHealthDriveSignal(pid, metadata.name, metadata.unit,
                ((old?.sampleCount ?: 0) + 1).coerceAtMost(1_000_000_000),
                old?.firstValue ?: value, value, minOf(old?.minimum ?: value, value),
                maxOf(old?.maximum ?: value, value), old?.firstOffsetMillis ?: offset, offset)
        }
        val old = points[pid].orEmpty()
        val recent = old.filter { offset - it.offsetMillis <= RETENTION_MILLIS }
        omittedBuckets += old.size - recent.size
        val prior = previous[pid]
        val breaks = value == null || prior == null || !prior.second || offset - prior.first > 10_000
        val point = LiveHealthChartPoint(offset, value, breaks)
        val next = if (recent.lastOrNull()?.offsetMillis?.div(bucketMillis) == offset / bucketMillis) {
            replacedReceipts++
            recent.dropLast(1) + point.copy(breakBefore = breaks || recent.last().breakBefore || recent.last().value == null)
        } else recent + point
        omittedBuckets += (next.size - capacity).coerceAtLeast(0)
        points[pid] = next.takeLast(capacity)
        previous[pid] = offset to (value != null)
    }

    fun stop(at: Long) {
        if (stopped) return
        elapsed = maxOf(elapsed, (at - startedAt).coerceAtLeast(0))
        stopped = true
    }

    fun applyTo(health: LiveHealthDriveReview): LiveHealthDriveReview {
        val end = maxOf(elapsed, health.elapsedMillis)
        var expired = 0L
        val series = pids.map { pid ->
            val metadata = ObdDecoder.catalog.getValue(pid)
            val old = points[pid].orEmpty()
            val kept = old.filter { end - it.offsetMillis <= RETENTION_MILLIS }
            expired += old.size - kept.size
            LiveHealthChartSeries(pid, metadata.name, metadata.unit, kept)
        }
        val retained = series.flatMap { it.points }
        return health.copy(elapsedMillis = end,
            signals = pids.mapNotNull(summaries::get), series = series,
            retainedFromOffsetMillis = retained.minOfOrNull { it.offsetMillis },
            retainedToOffsetMillis = retained.maxOfOrNull { it.offsetMillis },
            retentionNotes = health.retentionNotes + listOf(
                "Saved telemetry retains all ${pids.size} advertised, decodable scalar channels independently of visible graphs. Empty channels have no received samples; they are not zero.",
                "Latest actual receipt per ${bucketMillis / 1_000}-second bucket; at most $capacity points per channel, 20,000 total and the latest 20 minutes. At 55 channels charts retain one actual receipt per four-second bucket, not an unrestricted full-trip recording.",
                "${omittedBuckets + expired} older telemetry buckets omitted by retention bounds; $replacedReceipts earlier same-bucket receipts replaced. No channel is removed to fit the point budget. Full-monitor numeric aggregates remain.",
                "Archive size remains limited to 2 MB; save failures are reported and existing history is preserved. Receipt times are sequential, not synchronized ECU samples."
            ))
    }

    private companion object { const val RETENTION_MILLIS = 20 * 60_000L }
}
