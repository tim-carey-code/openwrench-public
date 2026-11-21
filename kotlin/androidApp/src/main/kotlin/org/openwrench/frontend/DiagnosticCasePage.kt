package org.openwrench.frontend

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DiagnosticCasePage(history: DiagnosticCaseCoordinator) {
    var syntheticPilot by rememberSaveable { mutableStateOf(false) }
    if (syntheticPilot) {
        SyntheticInvestigationPilotPage(onExit = { syntheticPilot = false })
        return
    }
    var investigationWorkspace by rememberSaveable { mutableStateOf(false) }
    if (investigationWorkspace) {
        InvestigationWorkspace(onExit = { investigationWorkspace = false })
        return
    }
    val state by history.state.collectAsState()
    val activity = LocalContext.current.dashboardActivity()
    var openedHere by rememberSaveable { mutableStateOf(false) }
    // Invalidate late presentation delivery when navigation leaves during work. Atomic disk
    // saves/deletes continue; configuration recreation keeps the same retained view/operation.
    DisposableEffect(history, activity) {
        onDispose { if (activity?.isChangingConfigurations != true && history.state.value.busy) history.cancel() }
    }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    var scanId by remember(state.selecting) { mutableStateOf<String?>(null) }
    var driveId by remember(state.selecting) { mutableStateOf<String?>(null) }
    var confirmed by remember(scanId, driveId) { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = openedHere && (state.selecting || state.preview != null || state.opened != null)) {
        openedHere = false; history.cancel()
    }
    val case = state.preview ?: state.opened
    var showReceipts by remember(case?.id) { mutableStateOf(false) }
    var expandedSections by remember(case?.id) { mutableStateOf(setOf(3)) }
    val scroll = rememberLazyListState()
    LaunchedEffect(case?.id, state.selecting) { scroll.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Diagnostic cases", style = MaterialTheme.typography.headlineMedium)
            if (state.preview == null && state.opened == null && !state.selecting) {
                OutlinedButton(onClick = { investigationWorkspace = true }, enabled = !state.busy) { Text("Investigation workspace") }
                OutlinedButton(onClick = { syntheticPilot = true }) { Text("Synthetic investigation pilot") }
            }
            Text("Historical evidence saved on this device · up to 10 cases within 25 MB", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (case != null) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(if (state.preview != null) "Review selected evidence" else "Saved case · historical", style = MaterialTheme.typography.titleLarge)
                Text("Scan ${case.scan.id}")
                Text("Drive ${case.drive.id}")
                Text("You confirmed these records came from the same vehicle. This case preserves copies of the selected evidence; deleting the original records or drive retention will not change it. VIN is omitted.")
                if (state.preview != null) Button(onClick = { openedHere = true; history.save(case.id) }, enabled = !state.busy) { Text("Save this case") }
                else {
                    OutlinedButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("OPENWRENCH diagnostic case", case.reportText())) }) { Text("Copy case report") }
                    OutlinedButton(onClick = { deleteId = case.id }, enabled = !state.busy) { Text("Delete case") }
                }
                OutlinedButton(onClick = history::cancel) { Text(if (state.preview != null) "Cancel case" else "Back to cases") }
            } }
            items(case.sections.indices.toList(), key = { it }) { index ->
                val expanded = index in expandedSections
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text(DiagnosticCaseBuilder.headings[index], style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { expandedSections = if (expanded) expandedSections - index else expandedSections + index }) { Text(if (expanded) "Hide evidence" else "Show evidence") }
                    if (expanded) Text(case.sections[index].substringAfter('\n'), style = MaterialTheme.typography.bodySmall)
                }
            }
            val intervals = case.drive.review.assessment?.findings?.flatMap { it.fuelIntervals }.orEmpty()
            if (intervals.isNotEmpty()) item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Retained paired receipts", style = MaterialTheme.typography.titleMedium)
                Text("The report includes bounded receipt excerpts. All ${intervals.sumOf { it.pairs.size }} retained pairs are preserved in this case.")
                OutlinedButton(onClick = { showReceipts = !showReceipts }) { Text(if (showReceipts) "Hide all paired receipts" else "Inspect all paired receipts") }
            } }
            if (showReceipts) intervals.forEachIndexed { intervalIndex, interval ->
                item(key = "interval-$intervalIndex") { Text("Bank ${interval.bank} · policy ${interval.policyVersion} · ${interval.condition}", style = MaterialTheme.typography.titleMedium) }
                items(interval.pairs, key = { "pair-$intervalIndex-${it.short.offsetMillis}-${it.long.offsetMillis}" }) { pair ->
                    WorkspacePanel(Modifier.fillMaxWidth()) { Text(pair.caseReceiptText(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else if (state.selecting) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Choose one scan and one completed drive", style = MaterialTheme.typography.titleMedium)
                Text("Selection is deliberate. ECU addresses and session numbers cannot identify a vehicle. Legacy reports remain readable in Scans, but cannot supply structured case evidence.")
                OutlinedButton(onClick = history::cancel) { Text("Cancel selection") }
            } }
            item { Text("Saved diagnostic scans", style = MaterialTheme.typography.titleMedium) }
            if (state.scans.isEmpty()) item { Text("No saved scans. Run and save an explicit diagnostic scan first.") }
            items(state.scans, key = { "scan-${it.id}" }) { scan -> WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(DateFormat.getDateTimeInstance().format(Date(scan.savedAtEpochMillis)))
                Text(scan.capture?.let { "${it.origin} · capture ${it.captureId}" } ?: "Legacy report · structured evidence unavailable")
                FilterChip(selected = scanId == scan.id, enabled = scan.capture != null && !state.busy,
                    onClick = { scanId = scan.id }, label = { Text("Select scan ${scan.id.take(8)}") })
            } }
            item { Text("Completed saved drives", style = MaterialTheme.typography.titleMedium) }
            if (state.drives.isEmpty()) item { Text("No completed saved drives. Finish Vehicle Health monitoring to save one.") }
            items(state.drives, key = { "drive-${it.id}" }) { drive -> WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(DateFormat.getDateTimeInstance().format(Date(drive.startedAtEpochMillis)))
                Text("${drive.elapsedMillis / 1_000} seconds · completed")
                FilterChip(selected = driveId == drive.id, enabled = !state.busy, onClick = { driveId = drive.id }, label = { Text("Select drive ${drive.id.take(8)}") })
            } }
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Row {
                    Checkbox(checked = confirmed, enabled = scanId != null && driveId != null && !state.busy, onCheckedChange = { confirmed = it })
                    Text("I confirm this scan and drive came from the same vehicle", Modifier.weight(1f).padding(top = 12.dp))
                }
                Button(onClick = { openedHere = true; history.prepare(checkNotNull(scanId), checkNotNull(driveId), confirmed) },
                    enabled = confirmed && scanId != null && driveId != null && !state.busy) { Text("Review case") }
            } }
        } else {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Keep a scan and drive together", style = MaterialTheme.typography.titleMedium)
                Text("Select saved evidence, confirm the same vehicle, then review the report before saving a durable case. These records do not establish the same fault event or a failed part.")
                Button(onClick = { openedHere = true; history.begin() }, enabled = !state.busy) { Text("Create diagnostic case") }
                OutlinedButton(onClick = history::refresh, enabled = !state.busy) { Text("Reload cases") }
            } }
            if (state.cases.isEmpty() && !state.busy) item { Text("No diagnostic cases saved yet.") }
            items(state.cases, key = { it.id }) { saved -> WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(DateFormat.getDateTimeInstance().format(Date(saved.savedAtEpochMillis)), style = MaterialTheme.typography.titleMedium)
                Text("Scan ${saved.scan.id.take(8)} + completed drive ${saved.drive.id.take(8)}")
                Text("${saved.scan.capture?.origin} scan · ${saved.drive.origin} drive", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { openedHere = true; history.open(saved.id) }, enabled = !state.busy) { Text("Open case") }
                OutlinedButton(onClick = { deleteId = saved.id }, enabled = !state.busy) { Text("Delete case") }
            } }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete diagnostic case?") },
        text = { Text("Removes this case and its evidence copies from this device. The original scan and drive are unchanged.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(history::delete); deleteId = null }, enabled = !state.busy) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
}
