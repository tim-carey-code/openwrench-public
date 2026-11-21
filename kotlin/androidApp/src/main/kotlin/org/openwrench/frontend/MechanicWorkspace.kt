package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Dashboard composition only; all source/session controls keep their existing owners. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechanicOverview(all: List<DashboardSignal>, shown: List<DashboardSignal>, selected: List<DashboardSignal>, layout: GraphFirstLayout,
    frozen: List<DashboardSignal>?, imperial: Boolean, range: GraphTimeRange, fitScale: Boolean,
    source: DashboardSource, shell: WorkspaceShellActions?, width: Float, fontScale: Float, onSelect: (String) -> Unit) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
    val faults = mechanicFaultSummary(source, shell?.diagnosticScan, now)
    val ids = mechanicCardIds(layout, shown)
    val columns = mechanicCardColumns(width, fontScale, layout.cardDensity)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Diagnostic dashboard" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = WorkspaceNavy, border = BorderStroke(1.dp, WorkspaceLine), shape = RoundedCornerShape(5.dp)) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DIAGNOSTIC DASHBOARD", fontSize = 11.sp, color = TelemetryInk, fontWeight = FontWeight.Bold)
                Text(faults.mil, fontSize = 11.sp)
                Text(faults.context, fontSize = 11.sp, color = DashboardMuted)
            }
        }
        MechanicDataStatus(selected, source, frozen != null)
        if (ids.isEmpty()) ReferencePanel("No visible value cards") {
            Text("Restore cards or choose signals in PIDs. Acquisition is unchanged.", fontSize = 12.sp)
        }
        ids.chunked(columns).forEach { group ->
            MechanicEvenRow(columns, group.map { id ->
                { modifier ->
                    val row = all.first { it.definition.id == id }
                    MechanicValueCard(row, mechanicCell(id, all, shown, layout, imperial),
                        layout.modules.firstOrNull { it.sensorId == id && it.kind == WorkspaceModuleKind.COMPACT_VALUE }?.expanded != false,
                        frozen, imperial, range, fitScale, modifier, onSelect)
                }
            })
        }
        val panels: List<@Composable (Modifier) -> Unit> = listOf(
            { modifier -> MechanicFuelPanel(all, shown, layout, imperial, source, shell, modifier, onSelect) },
            { modifier -> MechanicAirPanel(all, shown, layout, imperial, modifier, onSelect) },
            { modifier -> MechanicFaultPanel(faults, modifier) { shell?.onNavigate?.invoke(DashboardPage.DIAGNOSTICS) } },
        )
        val panelColumns = mechanicPanelColumns(width, fontScale)
        panels.chunked(panelColumns).forEach { group ->
            MechanicEvenRow(group.size, group)
        }
    }
}

@Composable
private fun MechanicValueCard(row: DashboardSignal, value: MechanicCell, expanded: Boolean,
    frozen: List<DashboardSignal>?, imperial: Boolean, range: GraphTimeRange, fitScale: Boolean,
    modifier: Modifier, onSelect: (String) -> Unit) {
    val ink = workspaceChannelInk(row.definition.id)
    ReferencePanel(when (row.definition.id) {
        "rpm" -> "RPM"; "coolant" -> "Coolant Temp"; "speed" -> "Vehicle Speed"; "load" -> "Calculated Load"
        "intake-air" -> "Intake Air Temp"; "control-module-voltage" -> "Module Voltage"; else -> row.definition.label
    }, modifier.heightIn(min = 124.dp).semantics { contentDescription = "${row.definition.label} value card" },
        onInspect = { onSelect(row.definition.id) }) {
        val rpmGauge = row.definition.id == "rpm" && expanded
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ScalarValue(value.value, value.unit, if (value.available) Color(0xFFF3F6FA) else DashboardMuted, 30)
                MechanicStatus(value)
            }
            // Share the value/age block instead of adding gauge height above the age.
            if (rpmGauge) MechanicMiniGauge(row.takeIf { value.available }, Modifier.width(66.dp).height(48.dp))
        }
        if (expanded) {
            if (rpmGauge) Box(Modifier.heightIn(min = 18.dp), contentAlignment = Alignment.CenterStart) {
                Text("Gauge: display scale only", fontSize = 9.sp, color = DashboardMuted)
            } else {
                val trace = graphFocusTrace(row, frozen, imperial, range, fitScale)
                InstrumentTrace(row, ink, Modifier.fillMaxWidth().height(18.dp), presentation = trace, windowMillis = range.millis)
            }
        } else Text("Collapsed", fontSize = 10.sp, color = DashboardMuted)
    }
}

@Composable
private fun MechanicMiniGauge(row: DashboardSignal?, modifier: Modifier) {
    val gauge = mechanicRpmGauge(row)
    Column(modifier.semantics {
        contentDescription = "RPM display gauge; 0 to ${gauge.maximum.toInt()} rpm; not a vehicle redline" +
            if (gauge.fraction == null) "; reading unavailable" else ""
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val diameter = (minOf(size.width, size.height * 1.1f) - 4.dp.toPx()).coerceAtLeast(0f)
            if (diameter == 0f) return@Canvas
            val origin = Offset((size.width - diameter) / 2, 2.dp.toPx())
            val center = origin + Offset(diameter / 2, diameter / 2)
            drawArc(WorkspaceLine, 135f, 270f, false, origin, Size(diameter, diameter), style = Stroke(5.dp.toPx()))
            repeat(9) { tick ->
                val angle = Math.toRadians((135 + tick * 270.0 / 8))
                val outer = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (diameter / 2)
                val inner = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (diameter / 2 - 5.dp.toPx())
                drawLine(DashboardMuted, inner, outer, 1.dp.toPx())
            }
            gauge.fraction?.let { fraction ->
                drawArc(DashboardBlue, 135f, fraction * 270f, false, origin, Size(diameter, diameter), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                val angle = Math.toRadians((135 + fraction * 270).toDouble())
                val tip = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (diameter / 2 - 6.dp.toPx())
                drawLine(TelemetryInk, center, tip, 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Text("0–${(gauge.maximum / 1000).toInt()}k", fontSize = 9.sp, color = DashboardMuted)
    }
}

@Composable
private fun MechanicCellValue(cell: MechanicCell, modifier: Modifier, onSelect: (String) -> Unit, selectable: Boolean) {
    Column(modifier.heightIn(min = 48.dp).then(if (selectable) Modifier.clickable(role = Role.Button,
        onClickLabel = "Inspect ${cell.label}") { onSelect(cell.id) } else Modifier).padding(horizontal = 6.dp, vertical = 4.dp)
        .semantics(mergeDescendants = true) { contentDescription = "${cell.label}; ${cell.value} ${cell.unit}; ${cell.status}; ${cell.reason}" },
        verticalArrangement = Arrangement.Center) {
        ScalarValue(cell.value, cell.unit, if (cell.available) Color(0xFFE8EDF7) else DashboardMuted, 20)
        MechanicStatus(cell)
    }
}

@Composable
private fun MechanicFuelPanel(all: List<DashboardSignal>, shown: List<DashboardSignal>, layout: GraphFirstLayout,
    imperial: Boolean, source: DashboardSource, shell: WorkspaceShellActions?, modifier: Modifier, onSelect: (String) -> Unit) {
    ReferencePanel("Fuel Control · Fuel Trim", modifier.heightIn(min = 216.dp), subtitle = "Independent Bank 1 / Bank 2 readings") {
        Row(Modifier.fillMaxWidth().background(WorkspaceHeader).padding(vertical = 6.dp)) {
            Text("Bank", Modifier.weight(.65f).padding(start = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("STFT · %", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("LTFT · %", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        mechanicTrimBanks(all, shown, layout, imperial).forEach { bank ->
            HorizontalDivider(color = WorkspaceLine)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bank ${bank.bank}", Modifier.weight(.65f).padding(start = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                MechanicCellValue(bank.short, Modifier.weight(1f), onSelect, all.any { it.definition.id == bank.short.id })
                MechanicCellValue(bank.long, Modifier.weight(1f), onSelect, all.any { it.definition.id == bank.long.id })
            }
        }
        HorizontalDivider(color = WorkspaceLine)
        mechanicFuelStatus(source, all, shell?.diagnosticScan).forEach { Text(it, fontSize = 10.sp, color = DashboardMuted) }
    }
}

@Composable
private fun MechanicAirPanel(all: List<DashboardSignal>, shown: List<DashboardSignal>, layout: GraphFirstLayout,
    imperial: Boolean, modifier: Modifier, onSelect: (String) -> Unit) {
    val ids = mechanicAirIds.filter { id -> all.any { it.definition.id == id } }
    ReferencePanel("Air & Engine", modifier.heightIn(min = 216.dp), subtitle = "Supplied channels · no inferred sensor type") {
        if (ids.isEmpty()) Text("No supporting channels supplied by this source.", fontSize = 12.sp, color = DashboardMuted)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = mechanicAirColumns(maxWidth.value, LocalDensity.current.fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ids.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { id ->
                            val cell = mechanicCell(id, all, shown, layout, imperial)
                            Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Inspect ${cell.label}") { onSelect(id) }
                                .heightIn(min = 48.dp).padding(vertical = 4.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${cell.label}; ${cell.value} ${cell.unit}; ${cell.status}; ${cell.reason}"
                                }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(cell.label, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Box(Modifier.weight(1f)) { ScalarValue(cell.value, cell.unit, Color(0xFFE8EDF7), 16) }
                                    Box(Modifier.weight(1.2f), contentAlignment = Alignment.CenterEnd) { MechanicStatus(cell) }
                                }
                            }
                        }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                    HorizontalDivider(color = WorkspaceLine)
                }
            }
        }
    }
}

@Composable
private fun MechanicFaultPanel(summary: MechanicFaultSummary, modifier: Modifier, onScan: () -> Unit) {
    ReferencePanel("Faults · Last Diagnostic Scan", modifier.heightIn(min = 216.dp), subtitle = summary.context) {
        Text(summary.mil, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(summary.count, fontSize = 10.sp, color = DashboardMuted)
        if (summary.replies.isEmpty()) Text("No code-service response supplied. Unknown is not a clean bill of health.", fontSize = 12.sp, color = DashboardMuted)
        summary.replies.forEach { reply -> HorizontalDivider(color = WorkspaceLine); Text(reply, fontSize = 12.sp) }
        Text("Codes and readings do not identify a failed part.", fontSize = 10.sp, color = DashboardMuted)
        OutlinedButton(onClick = onScan, Modifier.heightIn(min = 48.dp)) { Text("Open diagnostic scan", fontSize = 11.sp) }
    }
}

@Composable
internal fun MechanicGraphHeading(paused: Boolean, range: GraphTimeRange, onPause: () -> Unit) {
    Surface(color = WorkspaceHeader, border = BorderStroke(1.dp, WorkspaceLine), shape = RoundedCornerShape(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Signal Graphs · ${range.seconds} s", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Separate scales · gaps preserved · " + if (paused) "charts frozen, values current" else "acquisition unchanged", fontSize = 10.sp, color = DashboardMuted)
            }
            TextButton(onClick = onPause, Modifier.heightIn(min = 48.dp)) { Text(if (paused) "Resume charts" else "Pause charts", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun MechanicStatus(cell: MechanicCell) {
    val attention = cell.needsDataAttention
    Text(cell.status, Modifier.then(if (attention) Modifier.background(DashboardAmber.copy(alpha = .12f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier),
        fontSize = 11.sp, fontWeight = if (attention) FontWeight.SemiBold else FontWeight.Normal,
        color = if (attention) DashboardAmber else DashboardMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MechanicDataStatus(rows: List<DashboardSignal>, source: DashboardSource, paused: Boolean) {
    val counts = mechanicDataCounts(rows)
    val adverse = counts.total != counts.available
    Surface(color = WorkspaceNavy, border = BorderStroke(1.dp, if (adverse) DashboardAmber.copy(alpha = .65f) else WorkspaceLine), shape = RoundedCornerShape(5.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DATA STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${counts.available}/${counts.total} selected available", fontSize = 11.sp)
                Text("${counts.stale} stale · ${counts.unavailable} unavailable/not sampled · ${counts.disconnected} disconnected · ${counts.errors} read errors",
                    fontSize = 11.sp, color = if (adverse) DashboardAmber else DashboardMuted,
                    fontWeight = if (adverse) FontWeight.SemiBold else FontWeight.Normal)
            }
            Text(mechanicDataProvenance(source, rows) + " · counts include hidden selected channels" + if (paused) " · charts paused" else "",
                fontSize = 10.sp, color = DashboardMuted)
        }
    }
}
