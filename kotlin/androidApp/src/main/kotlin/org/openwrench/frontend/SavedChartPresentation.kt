package org.openwrench.frontend

import org.openwrench.frontend.android.LiveHealthChartPoint
import org.openwrench.frontend.android.LiveHealthChartSeries
import org.openwrench.frontend.android.LiveHealthDriveReview
import kotlin.math.abs
import kotlinx.coroutines.delay

/** A cursor advances through recorded time, never generating/interpolating receipts. */
internal data class SavedChartPlayback(val cursor: Long, val running: Boolean = false) {
    fun play(start: Long, end: Long): SavedChartPlayback =
        SavedChartPlayback(if (cursor >= end) start else cursor.coerceIn(start, end), end > start)
    fun pause() = copy(running = false)
    fun advance(elapsedMillis: Long, end: Long): SavedChartPlayback {
        if (!running) return this
        val next = cursor + elapsedMillis.coerceIn(0, (end - cursor).coerceAtLeast(0))
        return SavedChartPlayback(next, next < end)
    }
}

/** Stops on the flag even when lifecycle stop prevents a new composition/cancellation. */
internal suspend fun runSavedChartPlayback(
    initial: SavedChartPlayback,
    end: Long,
    shouldContinue: () -> Boolean,
    clockMillis: () -> Long,
    onCursor: (Long) -> Unit,
): SavedChartPlayback {
    var playback = initial
    if (!shouldContinue()) return playback.pause()
    onCursor(playback.cursor)
    var previousTick = clockMillis()
    while (playback.running) {
        delay(33)
        // ON_STOP may suspend recomposition before LaunchedEffect is cancelled.
        if (!shouldContinue()) return playback.pause()
        val now = clockMillis()
        playback = playback.advance(now - previousTick, end)
        previousTick = now
        onCursor(playback.cursor)
    }
    return playback
}

/** Repeated pause preserves the first snapshot, not later arriving receipts. */
internal fun freezeChartReview(frozen: LiveHealthDriveReview?, current: LiveHealthDriveReview) = frozen ?: current

internal fun savedChartBounds(series: LiveHealthChartSeries, start: Long, end: Long, fit: Boolean): Pair<Double, Double>? {
    val values = series.points.filter { !fit || it.offsetMillis in start..end }
        .mapNotNull { it.value?.takeIf(Double::isFinite) }
    val minimum = values.minOrNull() ?: return null
    val maximum = values.maxOrNull() ?: return null
    val padding = if (minimum == maximum) maxOf(abs(minimum) * .02, .5) else 0.0
    return (minimum - padding) to (maximum + padding)
}

internal fun savedChartReceipt(points: List<LiveHealthChartPoint>, cursor: Long): LiveHealthChartPoint? =
    points.minByOrNull { abs(it.offsetMillis - cursor) }
        ?.takeIf { abs(it.offsetMillis - cursor) <= 2_000L }
