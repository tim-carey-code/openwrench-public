package org.openwrench.frontend

/** One display window and scale shared by a trace and its axis label. */
internal data class InstrumentTracePresentation(
    val nowMillis: Long,
    val segments: List<List<TrendPoint>>,
    val bounds: Pair<Double, Double>,
)

internal fun instrumentTracePresentation(
    row: DashboardSignal?,
    frozen: DashboardSignal? = null,
    strip: Boolean = false,
    imperial: Boolean = false,
): InstrumentTracePresentation {
    val history = frozen ?: row
    val now = history?.trendNowMillis ?: 0
    val segments = history?.trend?.segments(now).orEmpty()
    val values = segments.flatten().mapNotNull { it.value }
    val minimum = values.minOrNull() ?: 0.0
    val bounds = if (history != null && strip) plotBounds(history, values, imperial)
        else minimum to (values.maxOrNull()?.takeIf { it != minimum } ?: (minimum + 1))
    val displayedSegments = if (strip && imperial) segments.map { segment ->
        segment.map { point -> point.copy(value = point.value?.let {
            convertDisplayValue(it, history?.definition?.unit, true)
        }) }
    } else segments
    return InstrumentTracePresentation(now, displayedSegments, bounds)
}
