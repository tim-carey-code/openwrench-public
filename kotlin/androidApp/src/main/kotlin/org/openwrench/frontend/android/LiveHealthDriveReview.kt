package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder

/** Real receipt points share one monitor-relative timeline. Null and breakBefore preserve data gaps. */
data class LiveHealthChartPoint(val offsetMillis: Long, val value: Double?, val breakBefore: Boolean = false)
data class LiveHealthChartSeries(
    val pid: Int, val name: String, val unit: String?, val points: List<LiveHealthChartPoint>,
    val maximumLinkGapMillis: Long = 10_000L,
)
data class LiveHealthDriveSignal(
    val pid: Int, val name: String, val unit: String?, val sampleCount: Long,
    val firstValue: Double, val lastValue: Double, val minimum: Double, val maximum: Double,
    val firstOffsetMillis: Long, val lastOffsetMillis: Long,
)
data class LiveHealthDriveCondition(
    val condition: String, val sampleCount: Long, val signals: List<LiveHealthDriveSignal>,
)
data class LiveHealthEventSample(
    val pid: Int, val name: String, val unit: String?, val offsetMillis: Long,
    val value: Double?, val displayValue: String, val sessionGeneration: Long,
)
data class LiveHealthWarningBookmark(
    val id: Long, val offsetMillis: Long, val title: String, val observation: String,
    val initialObservation: Boolean, val collectionStatus: String, val complete: Boolean,
    val preWindowMillis: Long, val postWindowMillis: Long,
    val samples: List<LiveHealthEventSample>, val samplesDropped: Long,
    val sessionGeneration: Long, val instanceId: Long,
    val preWindowTruncated: Boolean = false, val postWindowTruncated: Boolean = false,
) {
    /** Bounded event traces never infer continuity across omitted quality markers. */
    fun chartSeries(): List<LiveHealthChartSeries> = chartPids.mapNotNull { pid ->
        val rows = samples.filter { it.pid == pid }
        if (rows.isEmpty()) return@mapNotNull null
        val metadata = ObdDecoder.catalog.getValue(pid)
        val bounded = samplesDropped > 0 || preWindowTruncated || postWindowTruncated
        val points = rows.mapIndexed { index, row ->
            val previous = rows.getOrNull(index - 1)
            LiveHealthChartPoint(row.offsetMillis, row.value,
                bounded || previous == null || previous.value == null || row.value == null ||
                    row.offsetMillis - previous.offsetMillis > 10_000)
        }
        LiveHealthChartSeries(pid, metadata.name, metadata.unit, points)
    }
}
data class LiveHealthDriveReview(
    val instanceId: Long, val startedAtMonotonicMillis: Long, val elapsedMillis: Long,
    val highlights: List<String>, val signals: List<LiveHealthDriveSignal>,
    val conditions: List<LiveHealthDriveCondition>, val series: List<LiveHealthChartSeries>,
    val bookmarks: List<LiveHealthWarningBookmark>, val retentionNotes: List<String>,
    val droppedBookmarks: Long, val retainedFromOffsetMillis: Long?, val retainedToOffsetMillis: Long?,
    val assessment: DriveHealthAssessment? = null,
) {
    /** Aging a presentation never turns an absent receipt into a sample. */
    fun present(elapsed: Long): LiveHealthDriveReview {
        val end = elapsed.coerceAtLeast(0)
        val retained = series.map { series ->
            series.copy(points = series.points.filter { it.offsetMillis <= end && end - it.offsetMillis <= CHART_RETENTION_MILLIS })
        }
        val points = retained.flatMap { it.points }
        return copy(elapsedMillis = end, series = retained, assessment = assessment?.present(end),
            bookmarks = bookmarks.map { bookmark ->
                if (!bookmark.complete && end >= bookmark.offsetMillis + EVENT_WINDOW_MILLIS)
                    bookmark.copy(complete = true, collectionStatus = endedWindowStatus(bookmark, "Post-window elapsed"))
                else bookmark
            }, retainedFromOffsetMillis = points.minOfOrNull { it.offsetMillis },
            retainedToOffsetMillis = points.maxOfOrNull { it.offsetMillis })
    }

    fun reportText(): String = buildString {
        appendLine("Drive review: monitor instance $instanceId; elapsed $elapsedMillis ms")
        assessment?.let { appendLine(it.reportText()) }
        highlights.forEach { appendLine("Recap: $it") }
        signals.forEach { appendLine(it.reportLine()) }
        conditions.forEach { condition ->
            appendLine("Observed ${condition.condition}: ${condition.sampleCount} valid scalar receipts (not continuous driving duration)")
            condition.signals.forEach { appendLine(it.reportLine()) }
        }
        retentionNotes.forEach { appendLine("Retention: $it") }
        series.forEach { series ->
            appendLine("Chart 01${series.pid.hex()}: ${series.points.size} actual retained receipt points, ${series.points.firstOrNull()?.offsetMillis ?: "none"}..${series.points.lastOrNull()?.offsetMillis ?: "none"} ms")
        }
        appendLine("Warning bookmarks: ${bookmarks.size} retained; $droppedBookmarks older bookmarks dropped")
        bookmarks.forEach { bookmark ->
            appendLine("Bookmark ${bookmark.id} at +${bookmark.offsetMillis} ms: ${bookmark.title}; ${bookmark.observation}")
            appendLine("${bookmark.collectionStatus}; pre span=${bookmark.preWindowMillis}ms; post span=${bookmark.postWindowMillis}ms; ${bookmark.samplesDropped} retained-window receipts decimated")
            if (bookmark.preWindowTruncated) appendLine("Pre-window was limited by receipt retention; omitted earlier receipts are not included in that decimation count.")
            val preview = if (bookmark.samples.size <= 64) bookmark.samples else (0 until 64).map { index ->
                bookmark.samples[index * (bookmark.samples.size - 1) / 63]
            }
            appendLine("Showing ${preview.size} of ${bookmark.samples.size} retained actual receipts, spread across the window; full retained window is available in the app.")
            preview.forEach { sample -> appendLine("+${sample.offsetMillis}ms 01${sample.pid.hex()} ${sample.name}: ${sample.displayValue.take(180)}") }
        }
    }
}

private fun Int.hex() = toString(16).uppercase().padStart(2, '0')
private fun LiveHealthDriveSignal.reportLine() = "01${pid.hex()} $name: $sampleCount receipts; start=$firstValue; end=$lastValue; min=$minimum; max=$maximum${unit?.let { " $it" } ?: ""}; first=+$firstOffsetMillis ms; last=+$lastOffsetMillis ms"

private const val CHART_RETENTION_MILLIS = 20 * 60_000L
private const val CHART_POINTS_PER_PID = 1_200
private const val EVENT_WINDOW_MILLIS = 15_000L
private const val MAX_BOOKMARKS = 12
private const val EVENT_SAMPLES = 512
private const val PREBUFFER_SAMPLES = 512
private val chartPids = listOf(0x0C, 0x04, 0x10, 0x06, 0x07, 0x08, 0x09, 0x05, 0x0D)

/** Full-trip aggregates, decimated chart receipts, and bounded warning windows share the one reader. */
internal class LiveHealthDriveRecorder(
    private val generation: Long, private val instanceId: Long, private val startedAt: Long,
    private val retainCharts: Boolean = true,
) {
    private var totals = emptyMap<Int, LiveHealthDriveSignal>()
    private var conditions = emptyMap<String, Map<Int, LiveHealthDriveSignal>>()
    private var chart = emptyMap<Int, List<LiveHealthChartPoint>>()
    private var previousChartReceipt = emptyMap<Int, Pair<Long, Boolean>>()
    private var prebuffer = emptyList<LiveHealthEventSample>()
    private var lastPrebufferDropOffset: Long? = null
    private var bookmarks = emptyList<LiveHealthWarningBookmark>()
    private var droppedBookmarks = 0L
    private var nextBookmark = 1L
    private var mil: Boolean? = null
    private var milAt: Long? = null
    private var dtcCount: Int? = null
    private var countAt: Long? = null
    private val fuelFault = mutableMapOf<String, Boolean>()
    private val fuelAt = mutableMapOf<String, Long>()
    private var stopped = false
    private var lastOffset = 0L

    fun offer(reading: NativeReading, at: Long, context: LiveHealthContextKey) {
        if (stopped || at < startedAt) return
        val offset = at - startedAt
        if (offset < lastOffset) return
        lastOffset = offset
        val metadata = ObdDecoder.catalog.getValue(reading.pid)
        val value = (reading.value as? DecodedReading.Scalar)?.value?.takeIf(Double::isFinite)
        if (value != null) {
            totals = totals + (reading.pid to aggregate(totals[reading.pid], reading.pid, value, offset))
            val condition = conditions[context.condition].orEmpty()
            conditions = conditions + (context.condition to (condition +
                (reading.pid to aggregate(condition[reading.pid], reading.pid, value, offset))))
        }
        if (retainCharts && reading.pid in chartPids) recordChart(reading.pid, value, offset)
        val sample = LiveHealthEventSample(reading.pid, metadata.name, metadata.unit, offset, value,
            when (val decoded = reading.value) {
                null -> "NO DATA"
                is DecodedReading.Scalar -> "${decoded.value}${metadata.unit?.let { " $it" } ?: ""}"
                is DecodedReading.Structured -> if (reading.pid == 0x01)
                    "MIL=${decoded.fields["mil_on"] ?: "unknown"}; DTC count=${decoded.fields["dtc_count"] ?: "unknown"}"
                    else decoded.fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }, generation)
        val recent = prebuffer.filter { offset - it.offsetMillis <= EVENT_WINDOW_MILLIS } + sample
        if (recent.size > PREBUFFER_SAMPLES) lastPrebufferDropOffset = recent[recent.size - PREBUFFER_SAMPLES - 1].offsetMillis
        prebuffer = recent.takeLast(PREBUFFER_SAMPLES)
        bookmarks = bookmarks.map { bookmark ->
            when {
                bookmark.complete -> bookmark
                offset <= bookmark.offsetMillis + EVENT_WINDOW_MILLIS -> appendSample(bookmark, sample)
                else -> bookmark.copy(complete = true, collectionStatus = endedWindowStatus(bookmark, "Post-window elapsed"))
            }
        }
        val warnings = warnings(reading, offset)
        if (warnings.isNotEmpty()) {
            val pre = prebuffer.filter { it.offsetMillis >= offset - EVENT_WINDOW_MILLIS && it.offsetMillis <= offset }
            val preSpan = offset - (pre.firstOrNull()?.offsetMillis ?: offset)
            val startup = offset < EVENT_WINDOW_MILLIS
            val initial = warnings.any { it.second }
            val preTruncated = lastPrebufferDropOffset?.let { it >= offset - EVENT_WINDOW_MILLIS } == true
            val bookmark = LiveHealthWarningBookmark(nextBookmark++, offset,
                if (initial) "Warning first observed" else "Warning change observed",
                warnings.joinToString("; ") { it.first } + ". Receipt time is not fault-onset time.",
                initial, (if (startup) "Pre-window starts at monitor startup. " else "") +
                    (if (preTruncated) "Pre-window limited by receipt retention bounds. " else "") +
                    "Collecting up to 15 seconds of following receipts",
                false, preSpan, 0, pre, 0, generation, instanceId,
                preWindowTruncated = preTruncated)
            if (bookmarks.size == MAX_BOOKMARKS) droppedBookmarks++
            bookmarks = (bookmarks + bookmark).takeLast(MAX_BOOKMARKS)
        }
    }

    fun stop(at: Long, reason: String) {
        if (stopped) return
        stopped = true
        lastOffset = (at - startedAt).coerceAtLeast(lastOffset)
        bookmarks = bookmarks.map { bookmark ->
            if (bookmark.complete) bookmark
            else if (lastOffset >= bookmark.offsetMillis + EVENT_WINDOW_MILLIS)
                bookmark.copy(complete = true, collectionStatus = endedWindowStatus(bookmark, "Post-window elapsed before monitoring stopped"))
            else bookmark.copy(complete = true, collectionStatus = endedWindowStatus(bookmark, "Partial post-window: $reason"))
        }
    }

    fun snapshot(): LiveHealthDriveReview {
        val series = chartPids.map { pid ->
            val metadata = ObdDecoder.catalog.getValue(pid)
            LiveHealthChartSeries(pid, metadata.name, metadata.unit, chart[pid].orEmpty())
        }
        val allPoints = series.flatMap { it.points }
        val sorted = totals.values.sortedBy { LiveHealthInputs.scalarPids.indexOf(it.pid) }
        val conditionViews = conditions.entries.sortedBy { when (it.key) {
            "Moving" -> 0; "Stationary with engine running" -> 1; "Engine stopped" -> 2; else -> 3
        } }.map { (condition, signals) -> LiveHealthDriveCondition(condition, signals.values.sumOf { it.sampleCount }, signals.values.sortedBy { it.pid }) }
        val highlights = buildList {
            val moving = conditions["Moving"].orEmpty()
            val stationary = conditions["Stationary with engine running"].orEmpty()
            val movingCount = moving.values.sumOf { it.sampleCount }
            val stationaryCount = stationary.values.sumOf { it.sampleCount }
            add(if (movingCount > 0) "Moving operation contributed $movingCount valid scalar receipts."
                else "No valid scalar readings have been observed in a moving context yet.")
            add(if (stationaryCount > 0) "Stationary operation with the engine running contributed $stationaryCount valid scalar receipts."
                else "No valid scalar readings have been observed in a stationary engine-running context yet.")
            totals[0x05]?.let { add("Coolant was ${it.firstValue} °C at its first receipt and ${it.lastValue} °C at its latest; observed range ${it.minimum}–${it.maximum} °C.") }
            for (pid in listOf(0x0C, 0x04, 0x06, 0x07)) {
                val a = stationary[pid]; val b = moving[pid]
                if (a != null && b != null) add("${a.name}: stationary observed range ${a.minimum}–${a.maximum}; moving ${b.minimum}–${b.maximum}${a.unit?.let { " $it" } ?: ""}. Different loads and fuel-control states may contribute.")
            }
            if (conditions["Unknown"].orEmpty().isNotEmpty()) add("Some readings lacked fresh motion context and remain in an Unknown bucket.")
            add("Ranges describe sampled operation and do not establish normal limits or a component diagnosis.")
        }
        return LiveHealthDriveReview(instanceId, startedAt, lastOffset, highlights, sorted, conditionViews,
            series, bookmarks, listOfNotNull(
                "Full-monitor start/end/min/max use actual valid scalar receipts; condition counts do not measure continuous driving time.",
                if (retainCharts) "Charts retain the latest actual receipt in each one-second bucket, up to 1,200 points per series from the latest 20 minutes. Earlier chart points are discarded; full-monitor aggregates remain." else null,
                "All series use milliseconds since this monitor started. Values are sequential receipts, not synchronized ECU snapshots. Breaks mark NO DATA and receipt gaps; a shared cursor must display nearest-receipt offsets.",
                "Warning bookmarks mark first observations or changes in reported ECU state, never proven fault onset. Up to 12 bookmarks retain up to 512 actual surrounding receipts each, requesting 15 seconds before and after. Bounds or missing data can shorten coverage.",
                "No automatic Mode 03 request, VIN collection, distance estimate, or whole-vehicle diagnosis is performed. Drive archives retain this bounded evidence, not an unrestricted recording."
            ), droppedBookmarks, allPoints.minOfOrNull { it.offsetMillis }, allPoints.maxOfOrNull { it.offsetMillis })
    }

    private fun aggregate(old: LiveHealthDriveSignal?, pid: Int, value: Double, offset: Long): LiveHealthDriveSignal {
        val metadata = ObdDecoder.catalog.getValue(pid)
        return LiveHealthDriveSignal(pid, metadata.name, metadata.unit, (old?.sampleCount ?: 0) + 1,
            old?.firstValue ?: value, value, minOf(old?.minimum ?: value, value), maxOf(old?.maximum ?: value, value),
            old?.firstOffsetMillis ?: offset, offset)
    }

    private fun recordChart(pid: Int, value: Double?, offset: Long) {
        val old = chart[pid].orEmpty().filter { offset - it.offsetMillis <= CHART_RETENTION_MILLIS }
        val previous = previousChartReceipt[pid]
        val breaks = value == null || previous == null || !previous.second || offset - previous.first > 10_000
        val point = LiveHealthChartPoint(offset, value, breaks)
        val points = if (old.lastOrNull()?.offsetMillis?.div(1_000) == offset / 1_000)
            old.dropLast(1) + point.copy(breakBefore = breaks || old.last().breakBefore || old.last().value == null)
            else old + point
        chart = chart + (pid to points.takeLast(CHART_POINTS_PER_PID))
        previousChartReceipt = previousChartReceipt + (pid to (offset to (value != null)))
    }

    private fun warnings(reading: NativeReading, offset: Long): List<Pair<String, Boolean>> {
        val fields = (reading.value as? DecodedReading.Structured)?.fields ?: return emptyList()
        return buildList {
            if (reading.pid == 0x01) {
                fields["mil_on"]?.toBooleanStrictOrNull()?.let { current ->
                    if (current && mil != true) {
                        val initial = mil == null || milAt == null || offset - milAt!! > LiveHealthInputs.ECU_AGE_MILLIS
                        add((if (initial) "MIL ON first observed; prior state absent or separated by a status gap"
                            else "MIL ON observed after a recent OFF receipt") to initial)
                    }
                    mil = current; milAt = offset
                }
                fields["dtc_count"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { current ->
                    if (current > 0 && (dtcCount == null || current > dtcCount!!)) {
                        val initial = dtcCount == null || countAt == null || offset - countAt!! > LiveHealthInputs.ECU_AGE_MILLIS
                        add((if (initial) "ECU-reported DTC count $current first observed; timing of any increase is unknown"
                            else "ECU-reported DTC count increased from $dtcCount to $current between receipts") to initial)
                    }
                    dtcCount = current; countAt = offset
                }
            }
            if (reading.pid == 0x03) for ((system, value) in fields.filterKeys { it.startsWith("system_") }) {
                val current = value.contains("fault", ignoreCase = true)
                if (current && fuelFault[system] != true) {
                    val previousAt = fuelAt[system]
                    val initial = fuelFault[system] == null || previousAt == null || offset - previousAt > LiveHealthInputs.CONTEXT_AGE_MILLIS
                    add((if (initial) "$system fault state first observed: $value"
                        else "$system changed to an ECU-reported fault state: $value") to initial)
                }
                fuelFault[system] = current; fuelAt[system] = offset
            }
        }
    }
}

private fun appendSample(bookmark: LiveHealthWarningBookmark, sample: LiveHealthEventSample): LiveHealthWarningBookmark {
    val joined = bookmark.samples + sample
    // Retain actual observations while decimating an overloaded window. No values are averaged.
    val capped = if (joined.size > EVENT_SAMPLES) joined.filterIndexed { index, _ -> index % 2 == 0 || index == joined.lastIndex } else joined
    val dropped = bookmark.samplesDropped + joined.size - capped.size
    val post = sample.offsetMillis - bookmark.offsetMillis
    return bookmark.copy(samples = capped, samplesDropped = dropped,
        preWindowMillis = bookmark.offsetMillis - (capped.firstOrNull()?.offsetMillis ?: bookmark.offsetMillis),
        postWindowMillis = post.coerceIn(0, EVENT_WINDOW_MILLIS),
        preWindowTruncated = bookmark.preWindowTruncated || dropped > bookmark.samplesDropped,
        postWindowTruncated = bookmark.postWindowTruncated || dropped > bookmark.samplesDropped)
}

private fun endedWindowStatus(bookmark: LiveHealthWarningBookmark, reason: String): String = buildString {
    append(reason)
    append("; retained actual receipt spans ${bookmark.preWindowMillis} ms before / ${bookmark.postWindowMillis} ms after first observation")
    if (bookmark.offsetMillis < EVENT_WINDOW_MILLIS) append("; pre-window shortened by monitor startup")
    if (bookmark.preWindowMillis < minOf(EVENT_WINDOW_MILLIS, bookmark.offsetMillis)) append("; pre-window has missing or bounded earlier receipts")
    if (bookmark.preWindowTruncated) append("; pre-window was limited by receipt retention bounds")
    if (bookmark.postWindowTruncated) append("; post-window was decimated by receipt retention bounds")
    if (bookmark.samplesDropped > 0) append("; ${bookmark.samplesDropped} retained-window receipts omitted by bounds")
    append("; sampling gaps may remain")
}
