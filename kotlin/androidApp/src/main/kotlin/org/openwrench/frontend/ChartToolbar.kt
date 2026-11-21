package org.openwrench.frontend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

internal data class ChartRangeOption(val millis: Long?, val label: String)

/** Four controls in reading order, reflowed into complete rows rather than clipped. */
internal fun chartToolbarColumns(availableWidth: Float, minimumCellWidth: Float): Int = when {
    availableWidth >= minimumCellWidth * 4 -> 4
    availableWidth >= minimumCellWidth * 2 -> 2
    else -> 1
}

internal enum class ChartControlIcon { PLAY, PAUSE, TIME, FIT, EXPAND, COMPACT }

/** Shared, presentation-only controls. No acquisition/controller dependency belongs here. */
@Composable
internal fun ChartToolbar(
    transportLabel: String,
    transportDescription: String,
    transportRunning: Boolean,
    transportEnabled: Boolean = true,
    onTransport: () -> Unit,
    rangeOptions: List<ChartRangeOption>,
    selectedRangeMillis: Long?,
    onRange: (Long?) -> Unit,
    fitScale: Boolean,
    onFitScale: () -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    caption: String,
    compact: Boolean = false,
) {
    var rangeMenu by remember { mutableStateOf(false) }
    val rangeLabel = rangeOptions.firstOrNull { it.millis == selectedRangeMillis }?.label ?: "All"
    val timeLabel = if (compact) rangeLabel else "Time $rangeLabel"
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    // Measure alternate state labels too, so Pause/Resume and Expand/Compact do not move controls.
    val minimumWidth = with(density) {
        (listOf(transportLabel, "Resume", "Pause", if (compact) "Fit" else "Fit scale", "Expand", "Compact") +
            rangeOptions.map { if (compact) it.label else "Time ${it.label}" }).maxOf { textMeasurer.measure(it, style, softWrap = false).size.width }.toDp()
    } + if (compact) 20.dp else 24.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = DashboardPanel, shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DashboardBorder)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp).semantics { isTraversalGroup = true }) {
                val columns = chartToolbarColumns(maxWidth.value, minimumWidth.value)
                Column {
                    (0..3).toList().chunked(columns).forEach { group ->
                        Row(Modifier.fillMaxWidth()) {
                            group.forEach { index ->
                                Box(Modifier.weight(1f)) {
                                    when (index) {
                                        0 -> ChartControl(transportLabel, transportDescription,
                                            if (transportRunning) ChartControlIcon.PAUSE else ChartControlIcon.PLAY,
                                            onTransport, enabled = transportEnabled, compact = compact)
                                        1 -> {
                                            ChartControl(timeLabel, "Chart time range: ${rangeOptions.firstOrNull { it.millis == selectedRangeMillis }?.label ?: "All retained"}",
                                                ChartControlIcon.TIME, { rangeMenu = true }, compact = compact)
                                            DropdownMenu(rangeMenu, { rangeMenu = false }) {
                                                rangeOptions.forEach { option ->
                                                    DropdownMenuItem(text = { Text(if (option.millis == null) "All retained" else option.label) },
                                                        modifier = Modifier.semantics { selected = selectedRangeMillis == option.millis },
                                                        onClick = { onRange(option.millis); rangeMenu = false })
                                                }
                                            }
                                        }
                                        2 -> ChartControl(if (compact) "Fit" else "Fit scale", "Fit vertical scales to visible observations",
                                            ChartControlIcon.FIT, onFitScale, active = fitScale,
                                            state = if (fitScale) "Fitted to visible readings" else "Default scale", compact = compact)
                                        3 -> ChartControl(if (expanded) "Compact" else "Expand", "${if (expanded) "Compact" else "Expand"} all charts",
                                            if (expanded) ChartControlIcon.COMPACT else ChartControlIcon.EXPAND,
                                            onExpand, active = expanded, state = if (expanded) "Expanded charts" else "Compact charts", compact = compact)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartControl(label: String, description: String, icon: ChartControlIcon, onClick: () -> Unit,
    enabled: Boolean = true, active: Boolean = false, state: String? = null, compact: Boolean = false) {
    val ink = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        else if (active) TelemetryInk else MaterialTheme.colorScheme.onSurface
    Surface(color = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(7.dp), modifier = Modifier.fillMaxWidth().padding(2.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (state != null) stateDescription = state
            }.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!compact) ChartControlGlyph(icon, ink)
            Text(label, color = ink, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun ChartControlGlyph(icon: ChartControlIcon, ink: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink, p(x1, y1), p(x2, y2), 1.8.dp.toPx(), StrokeCap.Round)
        when (icon) {
            ChartControlIcon.PAUSE -> {
                drawRect(ink, p(.23f, .15f), Size(s * .18f, s * .7f))
                drawRect(ink, p(.59f, .15f), Size(s * .18f, s * .7f))
            }
            ChartControlIcon.PLAY -> drawPath(Path().apply {
                moveTo(.25f * s, .12f * s); lineTo(.84f * s, .5f * s); lineTo(.25f * s, .88f * s); close()
            }, ink)
            ChartControlIcon.TIME -> {
                drawCircle(ink, s * .4f, p(.5f, .5f), style = Stroke(1.8.dp.toPx()))
                line(.5f, .23f, .5f, .5f); line(.5f, .5f, .68f, .62f)
            }
            ChartControlIcon.FIT -> {
                line(.15f, .08f, .15f, .92f); line(.85f, .08f, .85f, .92f)
                line(.18f, .5f, .43f, .5f); line(.43f, .5f, .31f, .37f); line(.43f, .5f, .31f, .63f)
                line(.82f, .5f, .57f, .5f); line(.57f, .5f, .69f, .37f); line(.57f, .5f, .69f, .63f)
            }
            ChartControlIcon.EXPAND -> {
                line(.57f, .43f, .87f, .13f); line(.63f, .13f, .87f, .13f); line(.87f, .13f, .87f, .37f)
                line(.43f, .57f, .13f, .87f); line(.13f, .63f, .13f, .87f); line(.13f, .87f, .37f, .87f)
            }
            ChartControlIcon.COMPACT -> {
                line(.87f, .13f, .57f, .43f); line(.57f, .19f, .57f, .43f); line(.57f, .43f, .81f, .43f)
                line(.13f, .87f, .43f, .57f); line(.19f, .57f, .43f, .57f); line(.43f, .57f, .43f, .81f)
            }
        }
    }
}
