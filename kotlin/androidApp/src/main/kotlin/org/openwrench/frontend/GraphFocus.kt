package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openwrench.core.SignalValue

/** One responsive workspace for live, retained and explicitly synthetic readings. */
@Composable
internal fun GraphFocusDashboard(
    rows: List<DashboardSignal>, plots: List<DashboardSignal>, plotIds: List<String>, source: DashboardSource,
    frozen: List<DashboardSignal>?, imperial: Boolean, displayState: LiveDisplayState,
    onToggle: (String) -> Unit, onPause: () -> Unit,
    onUnits: () -> Unit, onChoose: () -> Unit, onSelect: (String) -> Unit,
    onFuelTrims: (() -> Unit)?, fuelTrimMessage: String?, pins: List<String>, onPin: (String) -> Unit, customOrder: Boolean,
    workspaceLayout: WorkspaceLayoutPreferences, shell: WorkspaceShellActions? = null,
) {
    val mode = displayState.mode; val range = displayState.range; val fitScale = displayState.fitScale
    val expanded = displayState.expanded
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val catalogIds = rows.map { it.definition.id }
    val preferredIds = (mechanicTopIds + pins + plotIds + catalogIds).distinct().filter { it in catalogIds }
    val graphableIds = rows.filter { it.definition.isGraphable() }.map { it.definition.id }
    val defaults = remember(preferredIds, graphableIds) { GraphFirstLayout.fromSensorIds(preferredIds, graphableIds) }
    val read = remember(workspaceLayout.raw, preferredIds, graphableIds) { WorkspaceLayoutCodec.decode(workspaceLayout.raw, preferredIds, graphableIds) }
    val layout = read.layout
    val displayRows = rows.filter { !layout.hideUnavailable || it.availability == SignalAvailability.AVAILABLE }
    val graphModules = layout.visible(WorkspaceModuleKind.GRAPH, plotIds).filter { m -> displayRows.any { it.definition.id == m.sensorId } }
    val eligibleValues = liveValueRows(displayRows, plotIds, displayState.filtersValues())
    val valueModules = layout.visible(WorkspaceModuleKind.COMPACT_VALUE, eligibleValues.map { it.definition.id })
    val values = valueModules.mapNotNull { module -> eligibleValues.firstOrNull { it.definition.id == module.sensorId } }
    val overviewRows = displayRows.filter { it.definition.id in plotIds }
    var denseValues by rememberSaveable { mutableStateOf(true) }
    var editing by remember(source, catalogIds) { mutableStateOf(false) }
    var picker by remember(source, catalogIds) { mutableStateOf(false) }
    if (editing) WorkspaceModuleEditor(layout, defaults, rows, plotIds, workspaceLayout,
        onSelection = { displayState.plotSelection = it },
        resetSelection = mechanicDefaultSelection(rows, pins),
        onDismiss = { editing = false })
    if (picker) GraphPickerDialog(rows, plotIds, onToggle, archived = source == DashboardSource.CIVIC_REPLAY) { picker = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF070D18))) {
        val viewportWidth = maxWidth.value
        val showLastSample = workspaceValuesShowLastSample(viewportWidth, fontScale)
        val graphContentWidth = (viewportWidth - 18f).coerceAtLeast(0f)
        val valueColumns = denseReplayValueColumns(graphContentWidth, fontScale, values)
        val dense = denseValues && valueColumns > 0
        val fullWidthGraphs = displayState.fullScreen || expanded
        val graphColumns = mechanicGraphColumns(graphContentWidth, fontScale, fullWidthGraphs)
        val mechanicGraphGroups = mechanicGraphRows(graphModules, graphContentWidth, fontScale, fullWidthGraphs, paired = true)
        Column(Modifier.fillMaxSize()) {
            if (displayState.fullScreen) LiveTelemetryToolbar(rows, plotIds, displayState, frozen != null, imperial,
                onToggle, onPause, onUnits, onChoose, onFuelTrims,
                onManageModules = if (workspaceLayout.ready) ({ editing = true }) else null, source = source)
            else FigmaWorkspaceHeader(source, shell, displayState, rows, plotIds,
                onPids = { if (workspaceLayout.ready) editing = true }, onPause, onUnits, onChoose, onFuelTrims,
                onGraphPicker = { picker = true })
            key(mode) {
                LazyColumn(Modifier.weight(1f).then(if (mode == LiveDisplayMode.VALUES && !dense) Modifier.widthIn(max = 1118.dp).align(Alignment.CenterHorizontally) else Modifier).fillMaxWidth().semantics { contentDescription = "Scrollable ${mode.label.lowercase()}" },
                    verticalArrangement = Arrangement.spacedBy(if (mode == LiveDisplayMode.VALUES) 0.dp else 8.dp), contentPadding = PaddingValues(9.dp)) {
                    val message = listOfNotNull(fuelTrimMessage, workspaceLayout.error ?: read.notice).joinToString(" · ")
                    if (message.isNotBlank()) item { Text(message, fontSize = 12.sp) }
                    when (mode) {
                        LiveDisplayMode.BOTH -> {
                            item(key = "mechanic-overview") {
                                MechanicOverview(rows, overviewRows, rows.filter { it.definition.id in plotIds }, layout, frozen, imperial, range, fitScale,
                                    source, shell, viewportWidth, fontScale, onSelect)
                            }
                            item { MechanicGraphHeading(frozen != null, range, onPause) }
                            items(mechanicGraphGroups, key = { "mechanic-graph:" + it.first().key }) { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    group.forEach { module ->
                                        key(module.key) {
                                            val row = displayRows.first { it.definition.id == module.sensorId }
                                            Box(Modifier.weight(1f)) {
                                                GraphFocusCard(row, frozen, imperial, range, fitScale,
                                                    mechanicPlotHeight(module.height, expanded, fontScale).dp, true, onSelect,
                                                    module.expanded, compact = true)
                                            }
                                        }
                                    }
                                    if (group.size < graphColumns) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        LiveDisplayMode.GRAPHS -> {
                            items(graphModules.chunked(graphColumns), key = { it.first().key }) { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    group.forEach { module ->
                                        val row = displayRows.first { it.definition.id == module.sensorId }
                                        Box(Modifier.weight(1f)) { GraphFocusCard(row, frozen, imperial, range, fitScale,
                                            workspaceModuleHeightDp(module.height, expanded, fontScale).dp, true, onSelect, module.expanded, compact = !displayState.fullScreen) }
                                    }
                                    if (group.size < graphColumns) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (graphModules.isEmpty()) item { GraphFocusEmpty(plots.isNotEmpty()) }
                        }
                        LiveDisplayMode.VALUES -> {
                            item {
                                Surface(color = WorkspaceNavy, border = BorderStroke(1.dp, WorkspaceLine)) {
                                    Column {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("PID values", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            if (valueColumns > 0) TextButton(onClick = { denseValues = !denseValues }) {
                                                Text(if (dense) "Table view" else "Dense values", fontSize = 12.sp)
                                            } else Text("Latest supplied readings", fontSize = 10.sp, color = DashboardMuted)
                                        }
                                        HorizontalDivider(color = WorkspaceLine)
                                        if (!dense) Row(Modifier.fillMaxWidth().background(Color(0xFF0A1323)).heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("PID", Modifier.weight(1.5f), fontSize = 10.sp, color = DashboardMuted)
                                            Text("VALUE", Modifier.weight(.75f), fontSize = 10.sp, color = DashboardMuted)
                                            if (showLastSample) Text("LAST SAMPLE", Modifier.weight(1f), fontSize = 10.sp, color = DashboardMuted)
                                            if (showLastSample) Text("FRESHNESS", Modifier.weight(.8f), fontSize = 10.sp, color = DashboardMuted)
                                        }
                                    }
                                }
                            }
                            if (dense) {
                                items(values.chunked(valueColumns), key = { "dense-value:" + it.first().definition.id }) { group ->
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        group.forEach { row ->
                                            key(row.definition.id) { Box(Modifier.weight(1f)) { DenseReplayValueCell(row, imperial, onSelect) } }
                                        }
                                        repeat(valueColumns - group.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            } else items(values, key = { "value:" + it.definition.id }) { row ->
                                FigmaValueRow(row, imperial, showLastSample, onSelect)
                            }
                            if (values.isEmpty()) item { LiveValuesEmpty(displayState.filtersValues(), eligibleValues.isNotEmpty()) }
                        }
                    }
                    item { Text(if (frozen != null) "Charts paused; current values continue. Acquisition unchanged." else "Histories use each signal's display timestamps. Gaps stay empty.",
                        Modifier.padding(top = 8.dp), fontSize = 10.sp, color = DashboardMuted) }
                }
            }
        }
    }
}

@Composable
private fun FigmaValueRow(row: DashboardSignal, imperial: Boolean, showLastSample: Boolean, onSelect: (String) -> Unit) {
    val value = workspaceValuePresentation(row, imperial)
    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Inspect ${row.definition.label}") { onSelect(row.definition.id) }, color = WorkspaceNavy) {
        Column(Modifier.drawBehind {
            val line = 1.dp.toPx()
            drawLine(WorkspaceLine, Offset(0f, 0f), Offset(0f, size.height), line)
            drawLine(WorkspaceLine, Offset(size.width, 0f), Offset(size.width, size.height), line)
            drawLine(WorkspaceLine, Offset(0f, size.height), Offset(size.width, size.height), line)
        }.heightIn(min = 49.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(7.dp).height(20.dp).background(workspaceChannelInk(row.definition.id), RoundedCornerShape(2.dp)))
                    Text(row.definition.label, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(.75f)) {
                    if (value.scalar) ScalarValue(value.value, value.unit, workspaceChannelInk(row.definition.id), maximumFontSize = 16)
                    else Text(value.value + " " + value.unit, fontSize = 13.sp, color = workspaceChannelInk(row.definition.id))
                }
                if (showLastSample) Text(row.observedAt ?: "Timestamp unavailable", Modifier.weight(1f), fontSize = 11.sp, color = DashboardMuted)
                if (showLastSample) Text(value.status, Modifier.weight(.8f), fontSize = 11.sp, color = DashboardMuted)
            }
            if (!showLastSample) {
                Text("Freshness: ${value.status}", fontSize = 11.sp, color = DashboardMuted)
                Text(row.observedAt ?: "Timestamp unavailable", fontSize = 10.sp, color = DashboardMuted)
            }
        }
    }
}

@Composable
private fun LiveValueCard(row: DashboardSignal, imperial: Boolean, modifier: Modifier, onSelect: (String) -> Unit,
    trace: InstrumentTracePresentation? = null, windowMillis: Long = TREND_WINDOW_MILLIS, expanded: Boolean = true) {
    val value = liveValuePresentation(row, imperial)
    WorkspacePanel(modifier.semantics { contentDescription = "${row.definition.label} value card" }.clickable(role = Role.Button, onClickLabel = "Inspect ${row.definition.label}") { onSelect(row.definition.id) },
        padding = 10, gap = 3) {
        Text(row.definition.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) Text(value.value, style = if (value.scalar) TelemetryNumber.copy(fontSize = 24.sp, lineHeight = 29.sp)
            else MaterialTheme.typography.bodyMedium,
            color = if (value.available) MaterialTheme.colorScheme.onSurface else DashboardMuted,
            maxLines = if (value.scalar) Int.MAX_VALUE else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (value.unit.isNotEmpty()) Text(value.unit, style = MaterialTheme.typography.labelSmall, color = TelemetryInk)
        Text((if (expanded) "" else "Collapsed · ") + value.status, style = MaterialTheme.typography.labelSmall, color = if (value.available) DashboardMuted else DashboardAmber)
        if (expanded && trace != null) InstrumentTrace(row, TelemetryInk, Modifier.fillMaxWidth().height(24.dp),
            presentation = trace, windowMillis = windowMillis)
    }
}

@Composable
private fun LiveValuesEmpty(selectedOnly: Boolean, hidden: Boolean = false) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(if (hidden) "Value cards hidden" else if (selectedOnly) "No PIDs selected" else "No PID values supplied", style = MaterialTheme.typography.titleMedium)
        Text(if (hidden) "Show cards again in Settings → Manage modules. PID selection and acquisition are unchanged." else if (selectedOnly) "Choose PID graphs above, or switch to All PIDs. Selection only changes this display."
            else "Choose a source or review the acquisition settings. Missing readings are never filled in.", style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun graphFocusCaption(source: DashboardSource, paused: Boolean): String {
    val provenance = when (source) {
        DashboardSource.DEMO, DashboardSource.SCRIPTED -> "Simulated values · no invented chart history"
        DashboardSource.SNAPSHOT -> "Historical snapshot · no live acquisition"
        DashboardSource.CIVIC_REPLAY -> "Recorded receipts · gaps stay empty"
        else -> "Receipt history · gaps stay empty"
    }
    return if (paused) "$provenance · Charts paused; current values continue; acquisition unchanged"
        else "$provenance · Pause freezes charts only"
}

@Composable
private fun GraphFocusEmpty(hidden: Boolean = false) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(if (hidden) "Graph modules hidden" else "Choose PID graphs", style = MaterialTheme.typography.titleMedium)
        Text(if (hidden) "Show graphs again in Settings → Manage modules. PID selection and acquisition are unchanged." else "Use the menu at the top to select numeric PIDs. Graph selection never starts acquisition.",
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun GraphFocusCard(row: DashboardSignal, frozen: List<DashboardSignal>?, imperial: Boolean,
    range: GraphTimeRange, fitScale: Boolean, plotHeight: androidx.compose.ui.unit.Dp, showValue: Boolean, onSelect: (String) -> Unit, moduleExpanded: Boolean = true, compact: Boolean = false) {
    val trace = remember(row, frozen, imperial, range, fitScale) { graphFocusTrace(row, frozen, imperial, range, fitScale) }
    val ink = workspaceChannelInk(row.definition.id)
    val value = liveValuePresentation(row, imperial)
    ReferencePanel(row.definition.label, Modifier.fillMaxWidth().then(if (compact) Modifier.clickable(role = Role.Button, onClickLabel = "Inspect ${row.definition.label}") { onSelect(row.definition.id) } else Modifier), unit = row.instrumentUnit(imperial), ink = ink,
        onInspect = if (compact) null else ({ onSelect(row.definition.id) }),
        subtitle = if (compact) ((if (showValue) value.value + " " + value.unit + " · " else "") + value.status + if (!moduleExpanded) " · Collapsed" else "") else null) {
        if (showValue && !compact) {
            Text(value.value + if (value.unit.isBlank()) "" else " ${value.unit}",
                style = TelemetryNumber.copy(fontSize = 25.sp, lineHeight = 30.sp),
                color = if (value.available) MaterialTheme.colorScheme.onSurface else DashboardMuted)
        }
        if (!compact) Text((if (moduleExpanded) "" else "Collapsed · ") + value.status + if (showValue && frozen != null) " · current value; chart paused" else "",
            style = MaterialTheme.typography.labelSmall, color = if (value.available) DashboardMuted else DashboardAmber)
        if (moduleExpanded) GraphFocusPlot(row.definition.label, row.instrumentUnit(imperial), trace, range, plotHeight, ink, frozen != null, compact)
    }
}

internal data class GraphPlotLayer(val label: String, val trace: InstrumentTracePresentation, val ink: Color)

@Composable
internal fun GraphFocusPlot(label: String, unit: String, trace: InstrumentTracePresentation, range: GraphTimeRange,
    height: androidx.compose.ui.unit.Dp, ink: androidx.compose.ui.graphics.Color, paused: Boolean, compact: Boolean = false,
    layers: List<GraphPlotLayer> = listOf(GraphPlotLayer(label, trace, ink))) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val axisWidth = (58 * fontScale).dp
    val ticks = (0..4).map { trace.bounds.second - (trace.bounds.second - trace.bounds.first) * it / 4 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) Text("Value" + if (unit.isBlank()) "" else " ($unit)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(axisWidth).height(height).padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ticks.forEach { Text(SignalValue.Number(it).compactDisplay(), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1) }
            }
            Box(Modifier.weight(1f).height(height).background(DashboardCanvas)) {
                Canvas(Modifier.fillMaxSize().semantics {
                    stateDescription = layers.joinToString("; ") { "${it.label}: ${it.trace.segments.sumOf { segment -> segment.size }} observations" }
                    contentDescription = "$label: ${layers.sumOf { layer -> layer.trace.segments.sumOf { it.size } }} received observations in ${range.seconds} seconds; gaps remain empty" + if (paused) "; paused" else ""
                }) {
                    val edge = 6.sp.toPx()
                    val plotHeight = size.height - edge * 2
                    val span = (trace.bounds.second - trace.bounds.first).coerceAtLeast(.00001)
                    repeat(5) { i ->
                        val y = edge + plotHeight * i / 4
                        drawLine(DashboardBorder, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                        val x = size.width * i / 4
                        drawLine(DashboardBorder.copy(alpha = .5f), Offset(x, edge), Offset(x, size.height - edge), 1.dp.toPx())
                    }
                    fun position(point: TrendPoint) = Offset(
                        workspaceTimeFraction(point.atMillis, trace.nowMillis, range.millis) * size.width,
                        (edge + plotHeight * (1 - (checkNotNull(point.value) - trace.bounds.first) / span)).toFloat())
                    layers.forEach { layer -> layer.trace.segments.forEach { segment ->
                        val path = Path()
                        segment.forEachIndexed { index, point ->
                            val at = position(point)
                            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                        }
                        if (segment.size > 1) {
                            val fill = Path().apply {
                                addPath(path)
                                lineTo(position(segment.last()).x, size.height - edge)
                                lineTo(position(segment.first()).x, size.height - edge)
                                close()
                            }
                            if (layers.size == 1) drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(layer.ink.copy(alpha = .24f), layer.ink.copy(alpha = .02f))))
                            drawPath(path, layer.ink, style = Stroke(2.dp.toPx()))
                        }
                        else segment.firstOrNull()?.let { drawCircle(layer.ink, 2.dp.toPx(), position(it)) }
                    } }
                }
                if (layers.all { it.trace.segments.isEmpty() }) Text("No timed observations", Modifier.align(Alignment.Center).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−${range.seconds} s", fontSize = 10.sp, color = DashboardMuted)
            Text("−${range.seconds / 2.0} s", fontSize = 10.sp, color = DashboardMuted)
            Text("0 s", fontSize = 10.sp, color = DashboardMuted)
        }
        if (!compact) Text(workspaceTimeCaption(paused), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
