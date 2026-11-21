package org.openwrench.frontend

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Cancel/Back discard both selection and layout. Only a successful Apply publishes either. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceModuleEditor(initial: GraphFirstLayout, defaults: GraphFirstLayout,
    rows: List<DashboardSignal>, plotIds: List<String>, preferences: WorkspaceLayoutPreferences,
    onSelection: ((List<String>) -> Unit)? = null,
    resetSelection: List<String> = defaultWorkspacePlotIds(rows, emptyList()), onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var selectedPids by remember { mutableStateOf(plotIds) }
    var kind by remember { mutableStateOf(WorkspaceModuleKind.COMPACT_VALUE) }
    var resetAll by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val submission = remember { WorkspaceLayoutSubmission() }
    val saving = submission.saving
    val scope = rememberCoroutineScope()
    val dismiss = { if (!submission.saving) onDismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(enabled = !saving, onClickLabel = "Cancel module edits", onClick = dismiss)
                .semantics { contentDescription = "Cancel module edits" })
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.92f)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }, color = WorkspaceNavy,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), border = BorderStroke(1.dp, WorkspaceLine)) {
                BoxWithConstraints(Modifier.padding(12.dp)) {
                val headerMax = maxHeight * .30f
                val footerMax = maxHeight * .25f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.heightIn(max = headerMax).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LAYOUT & SIGNALS", fontSize = 10.sp, color = DashboardMuted)
                            Text("Configure dashboard", style = MaterialTheme.typography.titleLarge)
                        }
                        TextButton(dismiss, enabled = !saving, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Close") }
                    }
                    Text("Display only · selection never changes adapter reads", fontSize = 11.sp, color = DashboardMuted)
                    preferences.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    OutlinedTextField(search, { search = it }, label = { Text("Search PID or name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !saving)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Module layout editor" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WorkspaceModuleKind.entries.forEach { choice -> FilterChip(kind == choice, { kind = choice }, enabled = !saving,
                                label = { Text(if (choice == WorkspaceModuleKind.GRAPH) "Graph modules" else "Value cards") }, modifier = Modifier.heightIn(min = 48.dp)) }
                        } }
                        items(draft.ordered(kind).filter { m -> rows.firstOrNull { it.definition.id == m.sensorId }?.let { filterSignals(listOf(it), search, null, null).isNotEmpty() } == true }, key = { it.key }) { module ->
                            val row = rows.first { it.definition.id == module.sensorId }
                            val name = "${row.definition.label} ${kind.label.lowercase()}"
                            Column(Modifier.fillMaxWidth().border(1.dp, WorkspaceLine).padding(5.dp)) {
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                    if (onSelection != null && row.definition.isGraphable()) Checkbox(module.sensorId in selectedPids,
                                        { selectedPids = togglePlot(selectedPids, module.sensorId) }, enabled = !saving,
                                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Display PID ${row.definition.label}" })
                                    Column(Modifier.widthIn(min = 120.dp, max = 220.dp).padding(horizontal = 4.dp)) {
                                        Text(row.definition.label, fontSize = 13.sp)
                                        Text(listOfNotNull(row.definition.pid, row.definition.unit, row.status).joinToString(" · "), fontSize = 10.sp, color = DashboardMuted)
                                    }
                                    TextButton({ draft = draft.update(module.key) { it.copy(pinned = !it.pinned) } }, enabled = !saving,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$name pin first"; selected = module.pinned }) { Text(if (module.pinned) "Pinned" else "Pin", fontSize = 11.sp) }
                                    TextButton({ draft = draft.update(module.key) { it.copy(expanded = !it.expanded) } }, enabled = !saving,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$name expansion"; selected = module.expanded }) { Text(if (module.expanded) "Collapse" else "Expand", fontSize = 11.sp) }
                                    TextButton({ draft = draft.update(module.key) { it.copy(visible = !it.visible) } }, enabled = !saving,
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$name visibility"; selected = module.visible }) { Text(if (module.visible) "Hide" else "Show", fontSize = 11.sp) }
                                    listOf(-1 to "Move up", 1 to "Move down").forEach { (direction, label) ->
                                        IconButton({ draft = draft.move(module.key, direction) }, enabled = !saving && draft.move(module.key, direction) != draft,
                                            modifier = Modifier.size(48.dp).semantics { contentDescription = "$label $name" }) { Text(if (direction == -1) "↑" else "↓") }
                                    }
                                }
                                if (kind == WorkspaceModuleKind.GRAPH) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    WorkspaceGraphHeight.entries.forEach { height -> FilterChip(module.height == height,
                                        { draft = draft.update(module.key) { it.copy(height = height) } }, enabled = !saving,
                                        label = { Text("${height.label} height", fontSize = 11.sp) },
                                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$name ${height.label.lowercase()} height" }) }
                                }
                            }
                        }
                        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WorkspaceCardDensity.entries.forEach { density -> FilterChip(draft.cardDensity == density,
                                { draft = draft.copy(cardDensity = density) }, enabled = !saving, label = { Text(density.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                            FilterChip(draft.hideUnavailable, { draft = draft.copy(hideUnavailable = !draft.hideUnavailable) }, enabled = !saving,
                                label = { Text("Hide unavailable PIDs") }, modifier = Modifier.heightIn(min = 48.dp))
                        } }
                        item { Text("Pins and ordering apply within the selected module kind. Hidden modules remain here for recovery. Unavailable PIDs are never supplied with invented values.", fontSize = 11.sp, color = DashboardMuted) }
                    }
                    FlowRow(Modifier.fillMaxWidth().heightIn(max = footerMax).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ draft = defaults; selectedPids = resetSelection; resetAll = true }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset module layout") }
                        OutlinedButton(dismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        Button({
                            val selected = selectedPids
                            val submitted = draft.withDisplaySelection(rows.map { it.definition.id }, selected)
                            val reset = resetAll
                            submission.submit(scope, { preferences.save(submitted, reset) }, { onSelection?.invoke(selected); onDismiss() })
                        }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (saving) "Saving…" else "Apply layout") }
                    }
                }
                }
            }
        }
    }
}
