package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openwrench.core.*

@Composable
internal fun InstrumentTrace(row: DashboardSignal?, ink: Color, modifier: Modifier, strip: Boolean = false,
    presentation: InstrumentTracePresentation = instrumentTracePresentation(row, strip = strip), windowMillis: Long = TREND_WINDOW_MILLIS, filled: Boolean = false) {
    val (now, segments, bounds) = presentation
    val observationCount = segments.sumOf { it.size }
    Box(modifier.then(if (strip) Modifier.background(DashboardCanvas) else Modifier)) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = (row?.definition?.label ?: "Unavailable sensor") + ": " + observationCount + " received observations; gaps remain empty"
        }) {
            val span = (bounds.second - bounds.first).coerceAtLeast(.00001)
            fun point(value: TrendPoint): Offset {
                val y = checkNotNull(value.value)
                return Offset(((value.atMillis - (now - windowMillis)).toDouble() / windowMillis * size.width).toFloat(),
                    (size.height - 4.dp.toPx() - ((y - bounds.first) / span * (size.height - 8.dp.toPx()))).toFloat())
            }
            segments.forEach { segment ->
                val path = Path()
                segment.forEachIndexed { index, value -> val position = point(value); if (index == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y) }
                if (segment.size > 1) {
                    if (filled) {
                        val area = Path().apply { addPath(path); lineTo(point(segment.last()).x, size.height); lineTo(point(segment.first()).x, size.height); close() }
                        drawPath(area, Brush.verticalGradient(listOf(ink.copy(alpha = .28f), ink.copy(alpha = .04f))))
                    }
                    drawPath(path, ink, style = Stroke(1.6.dp.toPx()))
                }
                else segment.firstOrNull()?.let { drawCircle(ink, 2.dp.toPx(), point(it)) }
            }
        }
        if (observationCount == 0 && strip) Text("No timed observations", Modifier.align(Alignment.Center), color = DashboardMuted, fontSize = 9.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamAnalysis(plots: List<DashboardSignal>, plotIds: List<String>, rows: List<DashboardSignal>,
    frozen: List<DashboardSignal>?, imperial: Boolean, modifier: Modifier, onChoose: () -> Unit,
    onToggle: (String) -> Unit, diagnostic: DashboardSignal?, onSelect: (String) -> Unit, wideHeader: Boolean = false, source: DashboardSource = DashboardSource.HARDWARE,
    sharedControls: Boolean = false, range: GraphTimeRange = GraphTimeRange.SIXTY, fitScale: Boolean = false, expanded: Boolean = false) {
    WorkspacePanel(modifier.heightIn(min = 408.dp), spread = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (source) {
                    DashboardSource.CIVIC_REPLAY -> "Recorded stream analysis"
                    DashboardSource.SNAPSHOT -> "Snapshot analysis"
                    else -> "Live stream analysis"
                }, style = MaterialTheme.typography.titleMedium)
                Text(if (frozen == null) "Aligned plots · independent scales · ${range.seconds}-second window" else "Plots paused · cards show current availability",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!sharedControls) GraphPickerMenu(rows, plotIds, onToggle)
            if (!sharedControls) Text("${range.seconds} s", Modifier.padding(horizontal = 12.dp).width(50.dp).border(1.dp, DashboardBorder, RoundedCornerShape(5.dp)).padding(10.dp), fontSize = 11.sp)
            if (wideHeader) Spacer(Modifier.weight(.43f))
        }
        if (!sharedControls) TextButton(onChoose) { Text("Manage live acquisition") }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            plots.forEach { row -> Text("■ " + row.definition.label, color = channelInk(row.definition.id), fontSize = 10.sp) }
        }
        Spacer(Modifier.height(1.dp))
        // A finite viewport is required inside the dashboard's outer LazyColumn.
        // Only visible traces are composed, regardless of the selected catalog size.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (expanded) 560.dp else 280.dp)
            .semantics { contentDescription = "Scrollable selected graphs" },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plots, key = { it.definition.id }) { row ->
                val trace = remember(row, frozen, imperial, range, fitScale) {
                    graphFocusTrace(row, frozen, imperial, range, fitScale)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(99.dp)) {
                        Text(row.definition.label, color = channelInk(row.definition.id), fontSize = 10.sp, lineHeight = 14.sp)
                        val bounds = trace.bounds
                        Text(SignalValue.Number(bounds.first).compactDisplay() + "–" + SignalValue.Number(bounds.second).compactDisplay() + " " + row.instrumentUnit(imperial),
                            fontSize = 8.sp, lineHeight = 12.sp, color = DashboardMuted)
                    }
                    InstrumentTrace(row, channelInk(row.definition.id), Modifier.weight(1f).height(if (expanded) 140.dp else 50.dp), true, trace, range.millis)
                    Column(Modifier.width(68.dp), horizontalAlignment = Alignment.End) {
                        Text(if (row.definition.id == "rpm") groupInstrumentNumber(row.instrumentValue(imperial)) else row.instrumentValue(imperial), style = TelemetryNumber.copy(fontSize = 12.sp), color = channelInk(row.definition.id))
                        Text(row.instrumentUnit(imperial), fontSize = 9.sp, color = DashboardMuted)
                    }
                }
            }
        }
        if (plots.isEmpty()) Box(Modifier.fillMaxWidth().height(218.dp), contentAlignment = Alignment.Center) {
            Text("Choose display streams. Plot selection never starts acquisition.", fontSize = 11.sp, color = DashboardMuted)
        }
        Row(Modifier.fillMaxWidth().padding(start = 107.dp, end = 76.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (4 downTo 0).map { if (it == 0) "now" else "−${range.seconds * it / 4} s" }.forEach { Text(it, fontSize = 8.sp, color = DashboardMuted) }
        }
        HorizontalDivider(color = DashboardBorder, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIcon(WorkspaceIcon.SHIELD, TelemetryInk, Modifier.size(13.dp))
            Text("Scroll graphs to see all selected streams. History stays bounded; source polling is unchanged.", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (diagnostic == null) "△ Diagnostic counts not supplied" else "△ Supplied diagnostic display · " + diagnostic.visibleValue(),
                Modifier.weight(1f), fontSize = 10.sp, color = DashboardAmber)
            TextButton(onClick = { diagnostic?.let { onSelect(it.definition.id) } }, modifier = Modifier.height(24.dp), enabled = diagnostic != null, contentPadding = PaddingValues(0.dp)) {
                Text("Review diagnostics →", fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun SensorInventory(rows: List<DashboardSignal>, pins: List<String>, plots: List<String>, imperial: Boolean,
    modifier: Modifier, onPin: (String) -> Unit, onSelect: (String) -> Unit, onPlots: (List<String>) -> Unit) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<SignalCategory?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf("coolant") }
    val filtered = filterSignals(rows, search, null, null).filter { category == null || it.definition.category() == category }
    val ordered = filtered.sortedBy { if (it.definition.id == expandedId) 0 else 1 }
    val available = rows.filter { it.definition.isAcquirable() }
    WorkspacePanel(modifier.heightIn(min = 351.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sensor inventory", style = MaterialTheme.typography.titleMedium)
            Text(rows.count { it.availability == SignalAvailability.AVAILABLE }.toString() + " available", fontSize = 10.sp, color = TelemetryInk)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).background(DashboardCanvas, RoundedCornerShape(5.dp))
            .border(1.dp, DashboardBorder, RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkspaceIcon(WorkspaceIcon.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(14.dp))
            BasicTextField(search, { search = it }, Modifier.weight(1f).semantics { contentDescription = "Search sensors or PID" },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp), singleLine = true, cursorBrush = SolidColor(TelemetryInk),
                decorationBox = { field -> if (search.isEmpty()) Text("Search sensors or PID...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); field() })
        }
        Box {
            TextButton({ categoryMenu = true }, Modifier.fillMaxWidth().height(26.dp), contentPadding = PaddingValues(0.dp)) {
                Text(category?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All categories", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                WorkspaceIcon(WorkspaceIcon.CHEVRON, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(13.dp))
            }
            DropdownMenu(categoryMenu, { categoryMenu = false }) {
                (listOf(null) + SignalCategory.entries).forEach { choice ->
                    DropdownMenuItem(text = { Text(choice?.name ?: "All categories") }, onClick = { category = choice; categoryMenu = false })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ onPlots(available.map { it.definition.id }) }, modifier = Modifier.height(24.dp), enabled = available.isNotEmpty(), contentPadding = PaddingValues(0.dp)) { Text(if (rows.any { it.retainedReplay }) "Graph retained channels" else "Graph all supported", fontSize = 10.sp) }
            TextButton({ onPlots(emptyList()) }, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(0.dp)) { Text("Clear Selection", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(rows.size.toString() + " catalog entries · " + pins.size + " pinned · " + plots.size + " plotted\nSelection changes plots only; scroll graphs to see more.",
            fontSize = 9.sp, lineHeight = 13.sp, color = DashboardMuted)
        HorizontalDivider(color = DashboardBorder)
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { row ->
                Row(Modifier.fillMaxWidth().clickable { expandedId = row.definition.id }, verticalAlignment = Alignment.CenterVertically) {
                    Text((if (row.definition.id == expandedId) "⌄ " else "› ") + row.definition.label, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp)
                    if (row.definition.isGraphable()) Checkbox(
                        checked = row.definition.id in plots,
                        onCheckedChange = { onPlots(togglePlot(plots, row.definition.id)) },
                        modifier = Modifier.semantics { contentDescription = "Graph " + row.definition.label })
                    PinButton(row, row.definition.id in pins, onPin)
                }
                Text(listOfNotNull(row.definition.pid, row.supportDescription, row.status).joinToString(" · "),
                    fontSize = 10.sp, color = DashboardMuted)
                if (row.definition.id == expandedId) {
                    Row(Modifier.clickable(role = Role.Button, onClickLabel = "Inspect " + row.definition.label) { onSelect(row.definition.id) }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.instrumentValue(imperial), style = TelemetryNumber.copy(fontSize = 24.sp, lineHeight = 32.sp))
                        Text(row.instrumentUnit(imperial), fontSize = 12.sp, color = channelInk(row.definition.id))
                        Text("• " + row.status.uppercase(), Modifier.border(1.dp, DashboardBorder, RoundedCornerShape(3.dp)).padding(4.dp),
                            fontSize = 9.sp, color = if (row.availability == SignalAvailability.AVAILABLE) DashboardGreen else DashboardAmber)
                    }
                    Text("PID " + (row.definition.pid ?: "not supplied") + " · " + row.shortAge(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Observed update", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.observedFrequency(), fontSize = 10.sp)
                    }
                    Text(row.reason, fontSize = 9.sp, lineHeight = 13.sp, color = DashboardMuted)
                }
            }
            if (ordered.isEmpty()) Text("No matching sensors", fontSize = 11.sp, color = DashboardMuted)
        }
    }
}
