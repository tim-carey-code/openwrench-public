package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openwrench.core.*

@Composable
internal fun WorkspaceNavigation(selected: Int, expanded: Boolean, onToggleExpanded: () -> Unit, onSelect: (Int) -> Unit) {
    val railWidth = if (expanded) (224 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp else 80.dp
    val labels = listOf("Diagnosis", "Live Sensors", "History", "Advanced")
    val icons = listOf(WorkspaceIcon.CAR, WorkspaceIcon.WAVE, WorkspaceIcon.HISTORY, WorkspaceIcon.SETTINGS)
    Surface(color = DashboardNavigation, modifier = Modifier.width(railWidth).fillMaxHeight()
        .semantics { contentDescription = "Workspace navigation" }
        .drawBehind { drawLine(DashboardBorder, Offset(size.width - 1.dp.toPx(), 0f), Offset(size.width - 1.dp.toPx(), size.height), 1.dp.toPx()) }) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Row(Modifier.height(70.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkspaceBrand(showWordmark = false)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Surface(color = Color.Transparent, shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onToggleExpanded)
                        .semantics {
                            contentDescription = if (expanded) "Collapse sidebar" else "Expand sidebar"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        }) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkspaceIcon(WorkspaceIcon.MENU, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
                        if (expanded) Text("Collapse sidebar", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).clearAndSetSemantics { })
                    }
                }
                Spacer(Modifier.height(7.dp))
                labels.forEachIndexed { index, label ->
                    val active = index == selected
                    Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .semantics { this.selected = active; contentDescription = label }) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WorkspaceIcon(icons[index], if (active) TelemetryInk else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
                            if (expanded) Text(label, style = MaterialTheme.typography.labelLarge,
                                color = if (active) TelemetryInk else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

        }
    }
}

@Composable
internal fun SourceBadge(source: DashboardSource) {
    val label = sourceBadgeLabel(source)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = TelemetryInk, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun WorkspacePanel(modifier: Modifier = Modifier, padding: Int = 15, gap: Int = 8, spread: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = DashboardPanel, border = BorderStroke(1.dp, DashboardBorder)) {
        Column(Modifier.padding(padding.dp), verticalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(gap.dp), content = content)
    }
}

@Composable
internal fun InstrumentDashboard(rows: List<DashboardSignal>, pins: List<String>, workspaceLayout: WorkspaceLayoutPreferences,
    source: DashboardSource, displayState: LiveDisplayState, onSelect: (String) -> Unit, onChoose: () -> Unit, onPin: (String) -> Unit, customOrder: Boolean = false,
    onFuelTrims: (() -> Unit)? = null, fuelTrimMessage: String? = null, shell: WorkspaceShellActions? = null) {
    val plotIds = (displayState.plotSelection ?: regularWorkspacePlotIds(rows, pins, workspaceLayout.raw)).filter { id -> rows.any { it.definition.id == id } }
    val plots = plotIds.mapNotNull { id -> rows.firstOrNull { it.definition.id == id } }
    GraphFocusDashboard(rows, plots, plotIds, source, displayState.frozen, displayState.imperial, displayState,
        onToggle = { displayState.plotSelection = togglePlot(plotIds, it) },
        onPause = { displayState.frozen = if (displayState.frozen == null) rows.toList() else null },
        onUnits = { displayState.imperial = !displayState.imperial }, onChoose = onChoose, onSelect = onSelect,
        onFuelTrims = onFuelTrims, fuelTrimMessage = fuelTrimMessage, pins = pins, onPin = onPin, customOrder = customOrder, workspaceLayout = workspaceLayout, shell = shell)
}

/** The screenshot's gauge/card overview, shared by Both and Values. No chart history in Values. */
@Composable
internal fun InstrumentValuesOverview(rows: List<DashboardSignal>, pins: List<String>, imperial: Boolean,
    customOrder: Boolean, selectedOnly: Boolean, showTraces: Boolean, frozen: List<DashboardSignal>?, range: GraphTimeRange, fitScale: Boolean, onSelect: (String) -> Unit, onPin: (String) -> Unit) {
    val rpm = rows.firstOrNull { it.definition.id == "rpm" }
    val slots = instrumentSlots(rows, pins, customOrder).filter { slot -> !selectedOnly || rows.any { it.definition.id == slot.first } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val layout = dashboardLayout(maxWidth.value, fontScale)
        val desktop = layout == DashboardLayout.DESKTOP
        val columns = minOf(guiValueColumns(maxWidth.value, fontScale), slots.size.coerceAtLeast(1))
        val gauge: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!selectedOnly || rows.any { it.definition.id == "rpm" }) RpmInstrument(rpm,
                    Modifier.fillMaxWidth().heightIn(min = 176.dp), onSelect, onPin, pinned = "rpm" in pins)
                if (!selectedOnly || rows.any { it.definition.id == "fuel-system-status" }) FuelSystemStrip(rows)
            }
        }
        val cards: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                slots.chunked(columns).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { (id, label) -> MetricInstrument(rows.firstOrNull { it.definition.id == id }, label, id in pins,
                            imperial, Modifier.weight(1f).heightIn(min = if (desktop) 129.dp else 150.dp), onSelect, onPin, showTrace = showTraces, showUnpinnedValues = true, frozen = frozen, range = range, fitScale = fitScale) }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (desktop) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(5f)) { gauge() }
            Column(Modifier.weight(16f)) { cards() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { gauge(); cards() }
    }
}

@Composable
internal fun WorkspaceButton(label: String, icon: WorkspaceIcon? = null, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, DashboardBorder),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
        if (icon != null) { WorkspaceIcon(icon, MaterialTheme.colorScheme.onSurface, Modifier.size(14.dp)); Spacer(Modifier.width(7.dp)) }
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RpmInstrument(row: DashboardSignal?, modifier: Modifier, onSelect: (String) -> Unit, onPin: (String) -> Unit, pinned: Boolean = true) {
    WorkspacePanel(modifier.then(if (row == null) Modifier else Modifier.clickable(role = Role.Button,
        onClickLabel = "Inspect engine speed") { onSelect(row.definition.id) }), gap = 10) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Engine speed", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PinButton(row, pinned, onPin)
        }
        Text(groupInstrumentNumber(row?.visibleValue() ?: "—") + " rpm",
            style = TelemetryNumber.copy(fontSize = 32.sp, lineHeight = 40.sp))
        Canvas(Modifier.fillMaxWidth().height(10.dp).semantics {
            contentDescription = "Engine speed scale: zero to 8000 rpm, a display scale, not a vehicle limit"
        }) {
            val count = 24
            val gap = 3.dp.toPx()
            val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0f)
            val fraction = row?.gaugeFraction()
            repeat(count) { index ->
                drawRect(if (fraction != null && index < fraction * count) TelemetryInk else DashboardBorder,
                    Offset(index * (width + gap), 0f), Size(width, size.height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "4,000", "8,000").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = DashboardMuted) }
        }
        Text(row?.let { instrumentStatus(it, true) } ?: "Unavailable · no receipt",
            style = MaterialTheme.typography.bodySmall,
            color = if (row?.availability == SignalAvailability.AVAILABLE) DashboardMuted else DashboardAmber)
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FuelSystemStrip(rows: List<DashboardSignal>) {
    val fuel = rows.firstOrNull { it.definition.id == "fuel-system-status" || it.definition.pid == "0103" }
    Surface(color = DashboardPanel, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text("FUEL SYSTEM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fuel?.visibleValue() ?: "Not supplied", fontSize = 10.sp, color = if (fuel?.value == null) DashboardMuted else DashboardGreen)
        }
    }
}

@Composable
private fun MetricInstrument(row: DashboardSignal?, label: String, pinned: Boolean, imperial: Boolean, modifier: Modifier,
    onSelect: (String) -> Unit, onPin: (String) -> Unit, showTrace: Boolean = true, showUnpinnedValues: Boolean = false, frozen: List<DashboardSignal>? = null,
    range: GraphTimeRange = GraphTimeRange.SIXTY, fitScale: Boolean = false) {
    val visible = pinned || showUnpinnedValues
    WorkspacePanel(modifier.then(if (row == null) Modifier else Modifier.clickable(role = Role.Button) { onSelect(row.definition.id) }), padding = 12, gap = 4, spread = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row?.definition?.label ?: label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PinButton(row, pinned, onPin)
        }
        val ink = TelemetryInk
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (visible) row?.instrumentValue(imperial) ?: "—" else "—", style = TelemetryNumber.copy(fontSize = 28.sp, lineHeight = 32.sp))
            Text(row?.instrumentUnit(imperial).orEmpty(), Modifier.padding(bottom = 2.dp), fontSize = 11.sp,
                color = if (row?.definition?.id == "speed") TelemetryInk else ink)
        }
        if ((row?.definition?.unit == "%" && row.definition.id !in fuelTrimIds) || label in listOf("Engine load", "Throttle")) {
            val fraction = row?.takeIf { visible }?.gaugeFraction()
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                drawRect(DashboardBorder, Offset(0f, size.height / 2), Size(size.width, 3.dp.toPx()))
                fraction?.let { drawRect(ink, Offset(0f, size.height / 2), Size(size.width * it, 3.dp.toPx())) }
            }
        } else if (showTrace) InstrumentTrace(if (visible) row else null, ink, Modifier.fillMaxWidth().height(22.dp),
            presentation = if (row != null && visible) graphFocusTrace(row, frozen, imperial, range, fitScale) else instrumentTracePresentation(null),
            windowMillis = range.millis)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val status = instrumentStatus(row, visible)
            Text(status, fontSize = 12.sp, color = if (row != null && pinned && row.availability != SignalAvailability.AVAILABLE) DashboardAmber else DashboardMuted)
            Text(if (visible) row?.cadenceMillis?.let { SignalValue.Number(1000.0 / it).compactDisplay() + " Hz" } ?: "—" else "—", fontSize = 9.sp, color = DashboardMuted)
        }
    }
}

@Composable
internal fun PinButton(row: DashboardSignal?, pinned: Boolean, onPin: (String) -> Unit) {
    IconButton(onClick = { row?.let { onPin(it.definition.id) } }, enabled = row != null,
        modifier = Modifier.size(48.dp).semantics { contentDescription = (if (pinned) "Unpin " else "Pin ") + (row?.definition?.label ?: "unavailable signal") }) {
        WorkspaceIcon(WorkspaceIcon.PIN, if (pinned) MaterialTheme.colorScheme.onSurfaceVariant else DashboardMuted, Modifier.size(18.dp))
    }
}
