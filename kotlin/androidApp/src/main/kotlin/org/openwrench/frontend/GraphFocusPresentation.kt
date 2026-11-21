package org.openwrench.frontend

import kotlin.math.abs

internal enum class GraphTimeRange(val seconds: Int) {
    FIFTEEN(15), THIRTY(30), SIXTY(60);
    val millis: Long get() = seconds * 1000L
}

/** Compact plots sit close to the earlier 50dp strips, with room for labeled axes. */
internal fun graphFocusPlotHeightDp(expanded: Boolean, fontScale: Float): Float =
    (if (expanded) 280f else 72f) * fontScale.coerceAtLeast(1f)

/** A viewport over retained receipts. It never changes acquisition or creates observations. */
internal fun graphFocusTrace(
    row: DashboardSignal,
    frozen: List<DashboardSignal>?,
    imperial: Boolean,
    range: GraphTimeRange,
    fitScale: Boolean,
): InstrumentTracePresentation {
    // A PID arriving after Pause must not silently fall back to live history.
    val history = if (frozen == null) row else frozen.firstOrNull { it.definition.id == row.definition.id }
    val trace = instrumentTracePresentation(history, strip = true, imperial = imperial)
    val start = trace.nowMillis - range.millis
    val visible = trace.segments.map { segment -> segment.filter { it.atMillis >= start && it.value?.isFinite() == true } }
        .filter { it.isNotEmpty() }
    val values = visible.flatten().mapNotNull { it.value }
    val bounds = if (fitScale && values.isNotEmpty()) {
        val minimum = values.min()
        val maximum = values.max()
        val padding = maxOf((maximum - minimum) * .1, abs(maximum) * .01, .1)
        (minimum - padding) to (maximum + padding)
    } else trace.bounds
    return trace.copy(segments = visible, bounds = bounds)
}

/** Graph-first workspace only; linked diagnosis charts retain their existing sizing contract. */
internal fun workspacePlotHeightDp(expanded: Boolean, fontScale: Float): Float =
    (if (expanded) 280f else 180f) * (fontScale.takeIf { it.isFinite() } ?: 1f).coerceIn(1f, 2f)

internal fun compactOverviewIds(available: List<String>, preferred: List<String>, columns: Int): List<String> =
    (preferred + available).distinct().filter { it in available }.take(columns.coerceIn(1, 4))

/** Axis offsets use the source clock only for differences, never as session age or wall time. */
internal fun workspaceTimeFraction(atMillis: Long, windowEnd: Long, windowMillis: Long): Float =
    ((atMillis - (windowEnd - windowMillis)).toDouble() / windowMillis).toFloat()

internal fun workspaceTimeCaption(paused: Boolean): String =
    "Time · seconds before this signal's window end" + if (paused) " · Chart frozen" else ""

/** Global chart expansion temporarily raises every visible, expanded graph to Tall. */
internal fun workspaceModuleHeightDp(height: WorkspaceGraphHeight, allExpanded: Boolean, fontScale: Float): Float =
    (if (allExpanded) WorkspaceGraphHeight.TALL.dp else height.dp) *
        (fontScale.takeIf { it.isFinite() } ?: 1f).coerceIn(1f, 2f)
