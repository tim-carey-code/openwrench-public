package org.openwrench.frontend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal enum class LiveToolbarAction { MODE, PIDS, PAUSE, RANGE, FIT, EXPAND, VALUES_FILTER, FULLSCREEN, SETTINGS }
internal data class LiveToolbarLayout(val inline: List<LiveToolbarAction>, val overflow: List<LiveToolbarAction>)

/** Icons have fixed 48dp targets; font scaling affects menus/tooltips, never shrinks targets. */
internal fun liveToolbarLayout(widthDp: Float, mode: LiveDisplayMode): LiveToolbarLayout {
    val chart = mode != LiveDisplayMode.VALUES
    val priority = listOf(LiveToolbarAction.FULLSCREEN, LiveToolbarAction.SETTINGS, LiveToolbarAction.MODE, LiveToolbarAction.PIDS) +
        if (chart) listOf(LiveToolbarAction.PAUSE, LiveToolbarAction.RANGE, LiveToolbarAction.FIT, LiveToolbarAction.EXPAND)
        else listOf(LiveToolbarAction.VALUES_FILTER)
    val slots = if (widthDp.isFinite()) (widthDp / 48).toInt().coerceAtLeast(2) else 2
    val visible = priority.take(slots).toSet()
    val order = LiveToolbarAction.entries.filter { it in priority }
    return LiveToolbarLayout(order.filter { it in visible }, order.filter { it !in visible })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTelemetryToolbar(rows: List<DashboardSignal>, plotIds: List<String>, state: LiveDisplayState,
    paused: Boolean, imperial: Boolean, onToggle: (String) -> Unit, onPause: () -> Unit,
    onUnits: () -> Unit, onChoose: () -> Unit, onFuelTrims: (() -> Unit)?, onManageModules: (() -> Unit)? = null, source: DashboardSource? = null) {
    var menu by remember { mutableStateOf<LiveToolbarAction?>(null) }
    var picker by remember { mutableStateOf(false) }
    if (picker) GraphPickerDialog(rows, plotIds, onToggle, archived = source == DashboardSource.CIVIC_REPLAY) { picker = false }
    // Dismiss stale chart menus when another presentation route changes mode.
    LaunchedEffect(state.mode) { menu = null }
    Surface(color = DashboardPanel, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, DashboardBorder)) {
        BoxWithConstraints(Modifier.fillMaxWidth().semantics { isTraversalGroup = true; contentDescription = "Live sensor controls" }) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val modeStyle = MaterialTheme.typography.labelLarge
            val modeWidth = listOf("Both", "Graphs", "Values").map {
                with(density) { measurer.measure(AnnotatedString(it), style = modeStyle, softWrap = false).size.width.toDp() }
                    .plus(24.dp).coerceAtLeast(64.dp)
            }.reduce { total, width -> total + width }
            val labeledModes = maxWidth >= modeWidth + 144.dp
            val layout = liveToolbarLayout(maxWidth.value - if (labeledModes) modeWidth.value - 48 else 0f, state.mode)
            fun description(action: LiveToolbarAction): String = when (action) {
                LiveToolbarAction.MODE -> "Display mode: ${state.mode.label}. Choose Graphs, Values or Both"
                LiveToolbarAction.PIDS -> "Choose PID graphs: ${plotIds.size} selected"
                LiveToolbarAction.PAUSE -> if (paused) "Resume chart display only; acquisition is unchanged" else "Pause chart display only; acquisition is unchanged"
                LiveToolbarAction.RANGE -> "Chart time range: ${state.range.seconds} seconds"
                LiveToolbarAction.FIT -> "Fit vertical scales: ${if (state.fitScale) "on" else "off"}"
                LiveToolbarAction.EXPAND -> if (state.expanded) "Compact chart height" else "Expand chart height"
                LiveToolbarAction.VALUES_FILTER -> if (state.selectedValuesOnly) "Values: selected PIDs (${plotIds.size})" else "Values: all PIDs (${rows.size})"
                LiveToolbarAction.FULLSCREEN -> if (state.fullScreen) "Exit full screen" else "Enter full screen"
                LiveToolbarAction.SETTINGS -> "Sensor settings and more controls"
            }
            fun active(action: LiveToolbarAction): Boolean = when (action) {
                LiveToolbarAction.FIT -> state.fitScale
                LiveToolbarAction.EXPAND -> state.expanded
                LiveToolbarAction.PAUSE -> paused
                LiveToolbarAction.VALUES_FILTER -> state.selectedValuesOnly
                LiveToolbarAction.FULLSCREEN -> state.fullScreen
                else -> false
            }
            fun act(action: LiveToolbarAction) {
                menu = null
                when (action) {
                    LiveToolbarAction.MODE, LiveToolbarAction.RANGE, LiveToolbarAction.VALUES_FILTER, LiveToolbarAction.SETTINGS -> menu = action
                    LiveToolbarAction.PIDS -> picker = true
                    LiveToolbarAction.PAUSE -> onPause()
                    LiveToolbarAction.FIT -> state.fitScale = !state.fitScale
                    LiveToolbarAction.EXPAND -> state.expanded = !state.expanded
                    LiveToolbarAction.FULLSCREEN -> if (state.fullScreen) state.exitFullScreen() else state.enterFullScreen()
                }
            }
            // Only pathological sub-96dp windows scroll horizontally; all normal widths use overflow.
            Row(Modifier.fillMaxWidth().then(if (maxWidth < 96.dp) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
                layout.inline.forEach { action ->
                    if (action == LiveToolbarAction.MODE && labeledModes) {
                        Row(Modifier.width(modeWidth)) {
                            listOf(LiveDisplayMode.BOTH, LiveDisplayMode.GRAPHS, LiveDisplayMode.VALUES).forEach { mode ->
                                Surface(color = if (state.mode == mode) MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.small) {
                                    TextButton(onClick = { state.mode = mode; menu = null },
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = state.mode == mode }) {
                                        Text(mode.label, style = modeStyle, softWrap = false,
                                            color = if (state.mode == mode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(description(action)) } }, state = rememberTooltipState()) {
                        Surface(color = if (active(action)) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            shape = MaterialTheme.shapes.small) {
                            IconButton(onClick = { act(action) }, modifier = Modifier.size(48.dp).semantics {
                                contentDescription = description(action)
                                if (action in listOf(LiveToolbarAction.FIT, LiveToolbarAction.EXPAND, LiveToolbarAction.PAUSE,
                                        LiveToolbarAction.FULLSCREEN, LiveToolbarAction.VALUES_FILTER)) {
                                    selected = active(action)
                                    stateDescription = when (action) {
                                        LiveToolbarAction.PAUSE -> if (paused) "Charts paused; current values continue" else "Charts running"
                                        LiveToolbarAction.EXPAND -> if (state.expanded) "Expanded charts" else "Compact charts"
                                        else -> description(action)
                                    }
                                }
                            }) { LiveToolbarGlyph(action, state, paused) }
                        }
                    }
                }
            }
            DropdownMenu(menu != null, { menu = null }) {
                when (menu) {
                    LiveToolbarAction.MODE -> LiveDisplayMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) },
                            modifier = Modifier.semantics { selected = state.mode == mode },
                            leadingIcon = { ModeGlyph(mode) },
                            trailingIcon = { if (state.mode == mode) Text("✓") },
                            onClick = { state.mode = mode; menu = null })
                    }
                    LiveToolbarAction.RANGE -> GraphTimeRange.entries.forEach { range ->
                        DropdownMenuItem(text = { Text("${range.seconds} seconds") },
                            modifier = Modifier.semantics { selected = state.range == range },
                            trailingIcon = { if (state.range == range) Text("✓") },
                            onClick = { state.range = range; menu = null })
                    }
                    LiveToolbarAction.VALUES_FILTER -> listOf(false, true).forEach { selectedOnly ->
                        DropdownMenuItem(text = { Text(if (selectedOnly) "Selected PIDs (${plotIds.size})" else "All PIDs (${rows.size})") },
                            modifier = Modifier.semantics { selected = state.selectedValuesOnly == selectedOnly },
                            trailingIcon = { if (state.selectedValuesOnly == selectedOnly) Text("✓") },
                            onClick = { state.selectedValuesOnly = selectedOnly; menu = null })
                    }
                    LiveToolbarAction.SETTINGS -> {
                        layout.overflow.forEach { action ->
                            DropdownMenuItem(text = { Text(description(action)) }, leadingIcon = { LiveToolbarGlyph(action, state, paused) },
                                trailingIcon = { if (active(action)) Text("✓") }, onClick = { act(action) })
                        }
                        if (layout.overflow.isNotEmpty()) HorizontalDivider()
                        if (onManageModules != null) DropdownMenuItem(text = { Text("Manage modules") }, onClick = { menu = null; onManageModules() })
                        DropdownMenuItem(text = { Text("Manage live acquisition") }, onClick = { menu = null; onChoose() })
                        DropdownMenuItem(text = { Text(if (imperial) "Use metric units" else "Use imperial units") }, onClick = { menu = null; onUnits() })
                        if (onFuelTrims != null) DropdownMenuItem(text = { Text("Show fuel trims") }, onClick = { menu = null; onFuelTrims() })
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ModeGlyph(mode: LiveDisplayMode) = WorkspaceIcon(when (mode) {
    LiveDisplayMode.GRAPHS -> WorkspaceIcon.WAVE
    LiveDisplayMode.VALUES -> WorkspaceIcon.GRID
    LiveDisplayMode.BOTH -> WorkspaceIcon.BOTH
}, MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))

@Composable
private fun LiveToolbarGlyph(action: LiveToolbarAction, state: LiveDisplayState, paused: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    when (action) {
        LiveToolbarAction.MODE -> ModeGlyph(state.mode)
        LiveToolbarAction.PIDS -> WorkspaceIcon(WorkspaceIcon.MENU, ink, Modifier.size(22.dp))
        LiveToolbarAction.PAUSE -> ChartControlGlyph(if (paused) ChartControlIcon.PLAY else ChartControlIcon.PAUSE, ink)
        LiveToolbarAction.RANGE -> ChartControlGlyph(ChartControlIcon.TIME, ink)
        LiveToolbarAction.FIT -> ChartControlGlyph(ChartControlIcon.FIT, ink)
        LiveToolbarAction.EXPAND -> ChartControlGlyph(if (state.expanded) ChartControlIcon.COMPACT else ChartControlIcon.EXPAND, ink)
        LiveToolbarAction.VALUES_FILTER -> WorkspaceIcon(WorkspaceIcon.FILTER, ink, Modifier.size(22.dp))
        LiveToolbarAction.FULLSCREEN -> WorkspaceIcon(if (state.fullScreen) WorkspaceIcon.EXIT_FULLSCREEN else WorkspaceIcon.FULLSCREEN, ink, Modifier.size(22.dp))
        LiveToolbarAction.SETTINGS -> WorkspaceIcon(WorkspaceIcon.SETTINGS, ink, Modifier.size(22.dp))
    }
}
