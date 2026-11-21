package org.openwrench.frontend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun AvailabilityLabel(row: DashboardSignal) {
    Text(row.status, style = MaterialTheme.typography.labelLarge,
        color = if (row.availability in setOf(SignalAvailability.STALE, SignalAvailability.ERROR, SignalAvailability.UNAVAILABLE))
            MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ReadingSummary(row: DashboardSignal) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.value?.compactDisplay() ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        row.definition.unit?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AvailabilityLabel(row)
        Text(row.ageMillis?.let { "${row.ageLabel}: ${it / 1000}.${(it % 1000) / 100} s" } ?: "${row.ageLabel} unknown",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Pure rendering of retained observations. Dots are samples; separate lines never cross a gap. */
@Composable
internal fun SignalGraph(row: DashboardSignal, expanded: Boolean = false) {
    val now = row.trendNowMillis ?: 0
    val segments = remember(row.trend, now) { row.trend.segments(now) }
    val points = segments.flatten()
    val values = points.mapNotNull { it.value }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val span = (high - low).takeIf { it > 0 } ?: 1.0
    val ink = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val summary = if (points.isEmpty()) "No timed observations. No trend generated."
        else "${points.size} received observations in the last 60 seconds; ${segments.size} separate segments. Range $low to $high ${row.definition.unit.orEmpty()}. Gaps are missing data."
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(if (expanded) 220.dp else 64.dp)
            .semantics { contentDescription = "${row.definition.label} trend. $summary" }) {
            val pad = 4.dp.toPx()
            val w = (size.width - pad * 2).coerceAtLeast(1f)
            val h = (size.height - pad * 2).coerceAtLeast(1f)
            repeat(3) { index ->
                val y = pad + h * index / 2
                drawLine(grid, Offset(pad, y), Offset(pad + w, y), 1f)
            }
            fun offset(point: TrendPoint) = Offset(
                pad + ((point.atMillis - (now - TREND_WINDOW_MILLIS)).toDouble() / TREND_WINDOW_MILLIS * w).toFloat(),
                pad + h - (((checkNotNull(point.value) - low) / span) * h).toFloat())
            segments.forEach { segment ->
                segment.zipWithNext().forEach { (a, b) -> drawLine(ink, offset(a), offset(b), 2.dp.toPx()) }
                segment.forEach { drawCircle(ink, if (expanded) 3.dp.toPx() else 2.dp.toPx(), offset(it)) }
            }
        }
        if (points.isEmpty()) Text("No timed observations", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (expanded) {
            Text("60 s window · ${points.size} observations · gaps stay empty", style = MaterialTheme.typography.bodySmall)
            Text("Range: $low – $high ${row.definition.unit.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("Receipt / recording timeline. Dots mark observations; no interpolated samples.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
