package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** Callbacks retain the existing session/diagnosis owners; the header is presentation only. */
internal data class WorkspaceShellActions(
    val status: String, val detail: String, val recordLabel: String = "Record",
    val onRecord: () -> Unit, val onReplay: () -> Unit, val onNavigate: (DashboardPage) -> Unit,
    val onConnection: () -> Unit, val canConnect: Boolean, val connectionLabel: String, val canReplay: Boolean,
    val showHeaderBrand: Boolean = true,
    val onMode: ((LiveDisplayMode) -> Unit)? = null,
    val diagnosticScan: org.openwrench.frontend.android.DiagnosticScanView? = null,
)

@Composable
private fun WorkspaceHeaderAction(icon: WorkspaceIcon, description: String, onClick: () -> Unit,
    label: String? = null, enabled: Boolean = true) {
    val ink = Color(0xFFC8D2E1).copy(alpha = if (enabled) 1f else .38f)
    // Compact visual surface inside a separate accessible hit target.
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 34.dp).border(1.dp, Color(0xFF2B3A54), RoundedCornerShape(4.dp))
            .background(Color(0xFF101A2D), RoundedCornerShape(4.dp))
            .padding(horizontal = if (label == null) 7.dp else 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIcon(icon, ink, Modifier.size(20.dp))
            if (label != null) { Spacer(Modifier.width(6.dp)); Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FigmaWorkspaceHeader(source: DashboardSource, shell: WorkspaceShellActions?, state: LiveDisplayState,
    rows: List<DashboardSignal>, plotIds: List<String>, onPids: () -> Unit, onPause: () -> Unit,
    onUnits: () -> Unit, onChoose: () -> Unit, onFuelTrims: (() -> Unit)?, onGraphPicker: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.background(Color(0xFF080F1C)).semantics { isTraversalGroup = true }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth.value / fontScale > 640f
            val words = maxWidth.value / fontScale > 900f
            val compactActions = workspaceToolbarCompactActions(maxWidth.value, fontScale)
            val tabs: @Composable () -> Unit = {
                Row {
                    listOf(LiveDisplayMode.BOTH, LiveDisplayMode.GRAPHS, LiveDisplayMode.VALUES).forEach { mode ->
                        // The underline must not take the parent's remaining width and push sibling tabs/actions offscreen.
                        Column(Modifier.width(((if (mode == LiveDisplayMode.BOTH) 108f else 88f) * fontScale).dp)) {
                            TextButton({ shell?.onMode?.invoke(mode) ?: run { state.mode = mode } }, Modifier.heightIn(min = 48.dp).semantics {
                                selected = state.mode == mode
                                contentDescription = if (mode == LiveDisplayMode.BOTH) "Dashboard, Both graphs and values" else mode.label
                            }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text(if (mode == LiveDisplayMode.BOTH) "Dashboard" else mode.label, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, color = if (state.mode == mode) Color.White else DashboardMuted)
                            }
                            HorizontalDivider(thickness = 2.dp, color = if (state.mode == mode) Color(0xFF61A4FF) else Color.Transparent)
                        }
                    }
                }
            }
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (shell?.showHeaderBrand != false) {
                        WorkspaceBrand(showWordmark = words)
                        Spacer(Modifier.width(22.dp))
                    }
                    if (wide) tabs()
                    Spacer(Modifier.weight(1f))
                    WorkspaceHeaderAction(WorkspaceIcon.FILTER, "Configure dashboard PIDs and modules", onPids, if (compactActions) null else "PIDs")
                    if (shell != null) {
                        Spacer(Modifier.width(7.dp))
                        WorkspaceHeaderAction(WorkspaceIcon.RECORD, "Open diagnosis recording controls", shell.onRecord, if (compactActions) null else shell.recordLabel)
                        Spacer(Modifier.width(7.dp))
                        TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text("Import or replay a saved drive") } }, rememberTooltipState()) {
                            WorkspaceHeaderAction(WorkspaceIcon.HISTORY, "Import or replay a saved drive", shell.onReplay, enabled = shell.canReplay)
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    Box {
                        WorkspaceHeaderAction(WorkspaceIcon.MENU, "Workspace menu and chart controls", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            fun close(action: () -> Unit) { menu = false; action() }
                            if (state.mode.showsGraphs) {
                                DropdownMenuItem({ Text(if (state.frozen != null) "Resume chart display only; acquisition unchanged" else "Pause chart display only; acquisition unchanged") }, { close(onPause) })
                                GraphTimeRange.entries.forEach { range -> DropdownMenuItem({ Text("${range.seconds} s window" + if (state.range == range) " ✓" else "") }, { close { state.range = range } }) }
                                DropdownMenuItem({ Text("Fit vertical scales" + if (state.fitScale) " ✓" else "") }, { close { state.fitScale = !state.fitScale } })
                                DropdownMenuItem({ Text(if (state.expanded) "Compact chart height" else "Expand chart height") }, { close { state.expanded = !state.expanded } })
                            } else DropdownMenuItem({ Text(if (state.selectedValuesOnly) "Show all PID values" else "Show selected PID values") }, { close { state.selectedValuesOnly = !state.selectedValuesOnly } })
                            DropdownMenuItem({ Text("Enter full screen") }, { close { state.enterFullScreen() } })
                            DropdownMenuItem({ Text("Choose PID graphs") }, { close(onGraphPicker) })
                            DropdownMenuItem({ Text("Manage modules") }, { close(onPids) })
                            DropdownMenuItem({ Text(if (state.imperial) "Use metric units" else "Use imperial units") }, { close(onUnits) })
                            DropdownMenuItem({ Text("Manage live acquisition") }, { close(onChoose) })
                            if (onFuelTrims != null) DropdownMenuItem({ Text("Show fuel trims") }, { close(onFuelTrims) })
                            shell?.let { actions ->
                                HorizontalDivider(color = WorkspaceLine)
                                DropdownMenuItem({ Text("Source & session details") }, { menu = false; detail = true })
                                listOf(DashboardPage.DIAGNOSIS, DashboardPage.HISTORY, DashboardPage.ADVANCED, DashboardPage.SESSION).forEach { page ->
                                    DropdownMenuItem({ Text(page.title) }, { close { actions.onNavigate(page) } })
                                }
                                DropdownMenuItem({ Text(actions.connectionLabel) }, { close(actions.onConnection) }, enabled = actions.canConnect)
                            }
                        }
                    }
                }
                if (!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = "Scrollable display modes" }, horizontalArrangement = Arrangement.Center) { tabs() }
            }
        }
        HorizontalDivider(color = WorkspaceLine)
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 31.dp).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(shell?.status ?: sourceBadgeLabel(source), fontSize = 11.sp, color = Color(0xFFB9C8DE), fontWeight = FontWeight.SemiBold)
            Text(if (state.frozen != null) "Charts paused · values current" else "${plotIds.size} selected · read only", fontSize = 11.sp, color = DashboardMuted)
        }
        if (detail && shell != null) AlertDialog(onDismissRequest = { detail = false }, title = { Text("Source & session") },
            text = { Text(shell.detail + "\n\n" + graphFocusCaption(source, state.frozen != null)) },
            confirmButton = { TextButton({ detail = false }) { Text("Close") } })
    }
}
