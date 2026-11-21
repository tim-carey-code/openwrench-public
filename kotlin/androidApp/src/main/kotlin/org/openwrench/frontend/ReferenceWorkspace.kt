package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import org.openwrench.core.SignalValue

internal val WorkspaceNavy = Color(0xFF101B30)
internal val WorkspaceHeader = Color(0xFF111E34)
internal val WorkspaceLine = Color(0xFF263650)
internal fun workspaceChannelInk(id: String): Color = when (id) {
    "rpm" -> Color(0xFF61A4FF)
    "coolant" -> Color(0xFF7FCA54)
    "ambient-air", "ambient-air-temperature" -> Color(0xFF44D5C2)
    "catalyst-temperature" -> Color(0xFFB491F4)
    "engine-oil-temperature" -> Color(0xFFE64E66)
    "speed" -> Color(0xFF44D5C2)
    "control-module-voltage" -> Color(0xFFE64E66)
    "throttle" -> Color(0xFFB491F4)
    "fuel-level", "load", "intake-air" -> Color(0xFFECC86B)
    "map", "maf" -> Color(0xFF5ECBDC)
    else -> Color(0xFFBAA2ED)
}

@Composable
internal fun ReferencePanel(title: String, modifier: Modifier = Modifier, unit: String = "", ink: Color = Color(0xFFE0E6F0),
    onInspect: (() -> Unit)? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(5.dp), color = Color.Transparent, border = BorderStroke(1.dp, WorkspaceLine)) {
        Column(Modifier.background(Brush.linearGradient(listOf(WorkspaceNavy, Color(0xFF0B1425))))) {
            Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(WorkspaceHeader, Color(0xFF0E1728))))
                .then(if (onInspect != null) Modifier.clickable(role = Role.Button, onClickLabel = "Inspect $title", onClick = onInspect) else Modifier)
                .heightIn(min = if (onInspect != null) 48.dp else if (subtitle != null) 43.dp else 30.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0E6F0))
                    subtitle?.let { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = DashboardMuted) }
                }
                if (unit.isNotEmpty()) Text(unit, color = ink, fontSize = 10.sp)
            }
            HorizontalDivider(color = WorkspaceLine)
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
        }
    }
}

/** Natural-height grid; the right rail spans the complete desktop dashboard, never the lower middle panel. */
@Composable
private fun ReferenceGrid(columns: Int, cards: @Composable () -> Unit, left: @Composable () -> Unit,
    middle: @Composable () -> Unit, rail: @Composable () -> Unit) {
    val railDp = (176 * LocalDensity.current.fontScale.coerceIn(1f, 2f)).dp
    Layout(content = { cards(); left(); middle(); rail() }, modifier = Modifier.fillMaxWidth()
        .semantics { contentDescription = "Reference dashboard grid" }) { children, constraints ->
        val width = constraints.maxWidth
        // A retained LazyColumn can remeasure this item with transient zero/narrow constraints.
        // Do not measure text children at zero width or reuse a stale fixed-column subtraction.
        if (width == 0) return@Layout layout(0, 0) {}
        val widths = referenceGridWidths(width, columns, 8.dp.roundToPx(), railDp.roundToPx())
        val gap = widths.gap
        if (widths.columns == 1) {
            val measured = children.map { it.measure(Constraints.fixedWidth(width)) }
            val order = listOf(0, 3, 1, 2) // cards, status rail, left stack, middle stack
            return@Layout layout(width, measured.sumOf { it.height } + gap * 3) {
                var y = 0
                order.forEach { index -> measured[index].placeRelative(0, y); y += measured[index].height + gap }
            }
        }
        val railWidth = widths.rail; val mainWidth = widths.main
        val leftWidth = widths.left; val middleWidth = widths.middle
        val top = children[0].measure(Constraints.fixedWidth(mainWidth))
        val left = children[1].measure(Constraints.fixedWidth(leftWidth))
        val middle = children[2].measure(Constraints.fixedWidth(middleWidth))
        val bodyTop = top.height + gap
        val mainHeight = bodyTop + maxOf(left.height, middle.height)
        val rail = children[3].measure(Constraints(minWidth = railWidth, maxWidth = railWidth,
            minHeight = if (widths.columns == 3) mainHeight else 0))
        val middleTop = if (widths.columns == 3) bodyTop else bodyTop + rail.height + gap
        layout(width, maxOf(bodyTop + left.height, middleTop + middle.height, if (widths.columns == 3) rail.height else 0)) {
            top.placeRelative(0, 0); left.placeRelative(0, bodyTop)
            middle.placeRelative(leftWidth + gap, middleTop)
            rail.placeRelative(if (widths.columns == 3) mainWidth + gap else leftWidth + gap, if (widths.columns == 3) 0 else bodyTop)
        }
    }
}

@Composable
internal fun ReferenceWorkspace(rows: List<DashboardSignal>, layout: GraphFirstLayout, graphModules: List<WorkspaceModule>,
    frozen: List<DashboardSignal>?, imperial: Boolean, range: GraphTimeRange, fitScale: Boolean, expanded: Boolean,
    onSelect: (String) -> Unit, gridColumns: Int, columns: Int, fontScale: Float, roles: List<OverviewRole>, source: DashboardSource) {
    val cardModules = layout.ordered(WorkspaceModuleKind.COMPACT_VALUE)
    val valueModules = layout.visible(WorkspaceModuleKind.COMPACT_VALUE, rows.map { it.definition.id })
    val selectedRows = valueModules.mapNotNull { m -> rows.firstOrNull { it.definition.id == m.sensorId } }
    val statusRows = selectedRows.filter { it.definition.isGraphable() }.take(9)
    val cards: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (roles.isEmpty()) ReferencePanel("No visible PID cards") { Text("Choose PIDs or restore hidden cards in PIDs → Configure dashboard.", fontSize = 12.sp) }
            roles.chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { role -> ReferenceValueCard(role, rows.firstOrNull { it.definition.id == role.id },
                        cardModules.firstOrNull { it.sensorId == role.id }?.expanded != false, frozen, imperial, range, fitScale,
                        Modifier.weight(1f), onSelect, source) }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val left: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val first = graphModules.firstOrNull()
            if (first != null) rows.firstOrNull { it.definition.id == first.sensorId }?.let { row ->
                GraphFocusCard(row, frozen, imperial, range, fitScale, overviewPlotHeightDp(first.height, expanded).dp,
                    false, onSelect, first.expanded, compact = true)
            } else ReferencePanel("Selected PID graph") { Text("No visible selected graphs.", fontSize = 12.sp) }
            val temperatures = overviewTemperatureModules(graphModules, rows)
            ReferenceTemperatures(rows, temperatures, frozen, imperial, range, fitScale, expanded, fontScale)
            ReferenceSignalStates(statusRows, onSelect, source)
        }
    }
    val middle: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReferenceReceipts(statusRows, frozen, imperial, range, fitScale)
            ReferenceScatter(selectedRows, frozen, imperial, range, fitScale, source)
        }
    }
    val rail: @Composable () -> Unit = { ReferenceStatusRail(statusRows, frozen, range, fitScale, imperial) }
    if (gridColumns > 1) ReferenceGrid(gridColumns, cards, left, middle, rail)
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards(); rail(); left(); middle() }
}

@Composable
private fun ReferenceValueCard(role: OverviewRole, row: DashboardSignal?, expanded: Boolean,
    frozen: List<DashboardSignal>?, imperial: Boolean, range: GraphTimeRange, fitScale: Boolean,
    modifier: Modifier, onSelect: (String) -> Unit, source: DashboardSource) {
    val ink = workspaceChannelInk(role.id)
    val trace = row?.let { graphFocusTrace(it, frozen, imperial, range, fitScale) }
    val value = row?.let { workspaceValuePresentation(it, imperial) }
    Surface(modifier.heightIn(min = 108.dp).semantics { contentDescription = "${row?.definition?.label ?: role.label} value card" }
        .then(if (row != null) Modifier.clickable(role = Role.Button, onClickLabel = "Inspect ${row.definition.label}") { onSelect(row.definition.id) } else Modifier),
        shape = RoundedCornerShape(5.dp), color = Color.Transparent, border = BorderStroke(1.dp, WorkspaceLine)) {
        Column(Modifier.background(Brush.linearGradient(listOf(WorkspaceNavy, Color(0xFF0B1425)))).drawWithContent {
            drawContent()
            drawLine(Color(0xFF3975BF), androidx.compose.ui.geometry.Offset(1.dp.toPx(), 5.dp.toPx()),
                androidx.compose.ui.geometry.Offset(1.dp.toPx(), size.height - 5.dp.toPx()), 2.dp.toPx())
        }.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(role.label, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(when (source) { DashboardSource.DEMO, DashboardSource.SCRIPTED -> "DEMO"; DashboardSource.HARDWARE -> "SOURCE"; else -> "HIST" },
                    Modifier.border(1.dp, WorkspaceLine, RoundedCornerShape(3.dp)).padding(3.dp), fontSize = 8.sp, color = DashboardMuted)
            }
            ScalarValue(value?.value ?: "—", value?.unit ?: role.unit, ink)
            HorizontalDivider(color = WorkspaceLine)
            if (expanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    val summary = trace?.let(::receiptSummary)
                    Column(Modifier.weight(1f)) {
                        Text(value?.status ?: "Not supplied", fontSize = 10.sp, lineHeight = 12.sp, color = DashboardMuted)
                        if (summary?.minimum != null) Text("${SignalValue.Number(summary.minimum).compactDisplay()}–${SignalValue.Number(summary.maximum!!).compactDisplay()}", fontSize = 10.sp, lineHeight = 12.sp, color = DashboardMuted)
                    }
                    Box(Modifier.weight(1f).height(24.dp)) {
                        if (row != null && trace != null && trace.segments.isNotEmpty()) InstrumentTrace(row, ink, Modifier.fillMaxSize(), presentation = trace, windowMillis = range.millis, filled = true)
                        else HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = ink.copy(alpha = .4f))
                    }
                }
            } else Text("COLLAPSED", fontSize = 10.sp, color = DashboardMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceTemperatures(rows: List<DashboardSignal>, modules: List<WorkspaceModule>, frozen: List<DashboardSignal>?,
    imperial: Boolean, range: GraphTimeRange, fitScale: Boolean, expanded: Boolean, fontScale: Float) {
    val overlay = workspaceTemperatureOverlay(rows, modules, frozen, imperial, range, fitScale)
    val unit = if (imperial) "°F" else "°C"
    ReferencePanel("Temperatures", Modifier.fillMaxWidth().heightIn(min = (166 * fontScale).dp), unit = unit,
        subtitle = if (overlay.compatibleClock) "Display-time histories · ${range.seconds} s" else "Shared display clock unavailable") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            modules.forEach { module -> rows.firstOrNull { it.definition.id == module.sensorId }?.let { row ->
                Text(shortWorkspaceLabel(row) + " " + row.instrumentValue(imperial) + " " + unit +
                    if (!module.expanded) " · collapsed" else "", fontSize = 10.sp, color = workspaceChannelInk(row.definition.id))
            } }
        }
        val height = modules.filter { it.expanded }.maxOfOrNull { temperaturePlotHeightDp(it.height, expanded, fontScale) }
        if (modules.isEmpty() || height != null) GraphFocusPlot("Temperatures", unit, overlay.axis, range,
            (height ?: temperaturePlotHeightDp(WorkspaceGraphHeight.STANDARD, expanded, fontScale)).dp, Color(0xFF7FCA54), frozen != null, compact = true,
            layers = overlay.layers.map { layer -> GraphPlotLayer(rows.first { it.definition.id == layer.id }.definition.label, layer.trace, workspaceChannelInk(layer.id)) })
        else Text("Temperature traces collapsed", fontSize = 10.sp, color = DashboardMuted)
    }
}

/** Never split a scalar between digits. Long values reflow their unit and remain horizontally readable. */
@Composable
internal fun ScalarValue(value: String, unit: String, ink: Color, maximumFontSize: Int = 32) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val base = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
        val width = constraints.maxWidth
        val font = listOf(maximumFontSize, (maximumFontSize - 4).coerceAtLeast(12), (maximumFontSize - 8).coerceAtLeast(12)).distinct().firstOrNull { size ->
            measurer.measure(AnnotatedString(value), style = base.copy(fontSize = size.sp), softWrap = false).size.width <= width
        } ?: (maximumFontSize - 8).coerceAtLeast(12)
        val measured = measurer.measure(AnnotatedString(value), style = base.copy(fontSize = font.sp), softWrap = false).size.width
        val unitWidth = measurer.measure(AnnotatedString(unit), style = MaterialTheme.typography.labelSmall, softWrap = false).size.width
        val inline = measured + unitWidth + with(density) { 8.dp.roundToPx() } <= width
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    style = base.copy(fontSize = font.sp, lineHeight = (font + 3).sp), softWrap = false, maxLines = 1, color = Color(0xFFF2F6FC))
                if (inline && unit.isNotBlank()) Text(unit, Modifier.padding(bottom = 4.dp), fontSize = 11.sp, color = ink)
            }
            if (!inline && unit.isNotBlank()) Text(unit, fontSize = 11.sp, color = ink)
        }
    }
}

@Composable
private fun ReferenceReceipts(rows: List<DashboardSignal>, frozen: List<DashboardSignal>?, imperial: Boolean,
    range: GraphTimeRange, fitScale: Boolean) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ReferencePanel("Signal Integrity", Modifier.fillMaxWidth().heightIn(min = (242 * fontScale).dp),
        subtitle = "Received observations · ${range.seconds} s per signal") {
        rows.take(7).forEach { row ->
            val bins = receiptBins(graphFocusTrace(row, frozen, imperial, range, fitScale), range.millis)
            Row(Modifier.fillMaxWidth().heightIn(min = 19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(shortWorkspaceLabel(row), Modifier.weight(.8f), fontSize = 11.sp)
                Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    bins.forEach { count -> Box(Modifier.weight(1f).height(12.dp).border(1.dp, WorkspaceLine)
                        .background(if (count > 0) workspaceChannelInk(row.definition.id).copy(alpha = .75f) else Color.Transparent)
                        .semantics { contentDescription = "$count received observations" }) }
                }
            }
        }
        if (rows.isEmpty()) Text("No selected numeric PIDs", fontSize = 12.sp)
        Text("Outlined = no retained receipt. Not a missing-sample rate.", fontSize = 10.sp, color = DashboardMuted)
    }
}

@Composable
private fun ReferenceStatusRail(rows: List<DashboardSignal>, frozen: List<DashboardSignal>?, range: GraphTimeRange, fitScale: Boolean, imperial: Boolean) {
    ReferencePanel("Data Quality", Modifier.fillMaxWidth(), subtitle = "Retained observations") {
        rows.forEach { row ->
            val trace = graphFocusTrace(row, frozen, imperial, range, fitScale)
            val count = receiptSummary(trace).count
            Column(Modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(shortWorkspaceLabel(row), Modifier.weight(.9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        receiptBins(trace, range.millis).forEach { n -> Box(Modifier.weight(1f).height(7.dp).border(1.dp, WorkspaceLine)
                            .background(if (n > 0) workspaceChannelInk(row.definition.id).copy(alpha = .7f) else Color.Transparent)) }
                    }
                    Text(count.toString(), fontSize = 10.sp)
                }
                Text(row.status, fontSize = 10.sp, color = DashboardMuted)
            }
            HorizontalDivider(color = WorkspaceLine)
        }
        Text("${range.seconds} s / signal. Expected sample count unknown; loss % unavailable.", fontSize = 10.sp, color = DashboardMuted)
    }
}

@Composable
private fun ReferenceSignalStates(rows: List<DashboardSignal>, onSelect: (String) -> Unit, source: DashboardSource) {
    ReferencePanel(if (source == DashboardSource.DEMO || source == DashboardSource.SCRIPTED) "Demo notices" else "Signal status", Modifier.fillMaxWidth(),
        subtitle = if (source == DashboardSource.DEMO || source == DashboardSource.SCRIPTED) "Synthetic observations · not vehicle alerts" else "Receipt states · no diagnostic thresholds inferred") {
        rows.take(3).forEach { row ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onSelect(row.definition.id) }, verticalAlignment = Alignment.CenterVertically) {
                Text(shortWorkspaceLabel(row), Modifier.weight(1f), fontSize = 11.sp)
                Text(row.status, Modifier.weight(1f), fontSize = 11.sp, color = DashboardMuted)
                Text(row.shortAge(), fontSize = 10.sp, color = DashboardMuted)
            }
            HorizontalDivider(color = WorkspaceLine)
        }
    }
}

@Composable
private fun ReferenceScatter(rows: List<DashboardSignal>, frozen: List<DashboardSignal>?, imperial: Boolean,
    range: GraphTimeRange, fitScale: Boolean, source: DashboardSource) {
    val rpmRow = rows.firstOrNull { it.definition.id == "rpm" && it.definition.unit == "rpm" }
    val throttleRow = rows.firstOrNull { it.definition.id == "throttle" && it.definition.unit == "%" }
    val points = workspaceSourceScatter(source, rows, frozen, imperial, range, fitScale)
    val verifiedClock = source == DashboardSource.CIVIC_REPLAY || source == DashboardSource.DEMO &&
        listOfNotNull(rpmRow, throttleRow).size == 2 && listOfNotNull(rpmRow, throttleRow).all { it.reason == SYNTHETIC_TRACE_REASON }
    val top = maxOf(4000.0, kotlin.math.ceil((points.maxOfOrNull { it.rpm } ?: 0.0) / 1000) * 1000)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ReferencePanel("RPM vs Throttle", Modifier.fillMaxWidth().heightIn(min = (270 * fontScale).dp),
        subtitle = if (verifiedClock) "Exact retained-offset pairs · ${range.seconds} s" else "Receipt pairing unavailable for this source") {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(48.dp).height(160.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                listOf(top, top / 2, 0.0).forEach { Text(SignalValue.Number(it).compactDisplay(), fontSize = 10.sp, color = DashboardMuted) }
            }
            Box(Modifier.weight(1f).height(160.dp)) {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "RPM versus throttle: ${points.size} exact retained-offset pairs; no correlation or diagnostic inference" }) {
                    repeat(5) { n ->
                        val y = size.height * n / 4
                        drawLine(WorkspaceLine, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
                    }
                    points.forEach { p -> drawCircle(Color(0xFF61A4FF).copy(alpha = .8f), 2.5.dp.toPx(),
                        androidx.compose.ui.geometry.Offset((p.throttle / 100 * size.width).toFloat(), ((1 - p.rpm / top) * size.height).toFloat())) }
                }
                if (points.isEmpty()) Text(if (!verifiedClock) "Verified receipt pairs unavailable" else if (rpmRow == null || throttleRow == null) "Select RPM and throttle to inspect pairs" else "No exact retained-offset pairs",
                    Modifier.align(Alignment.Center).padding(8.dp), fontSize = 12.sp, color = DashboardMuted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", fontSize = 10.sp); Text("Throttle (%)", fontSize = 10.sp, color = DashboardMuted); Text("100%", fontSize = 10.sp)
        }
        Text("Y: engine speed (rpm) · ${points.size} retained pairs · not ECU synchronization" + if (frozen != null) " · chart paused" else "", fontSize = 10.sp, color = DashboardMuted)
    }
}

internal fun shortWorkspaceLabel(row: DashboardSignal): String = overviewRoles.firstOrNull { it.id == row.definition.id }?.label
    ?: when (row.definition.id) {
        "maf" -> "MAF"; "load" -> "Load"; "intake-air" -> "Intake"; "ambient-air-temperature" -> "Ambient"
        else -> row.definition.label.removeSuffix(" temperature").removeSuffix(" position")
    }
