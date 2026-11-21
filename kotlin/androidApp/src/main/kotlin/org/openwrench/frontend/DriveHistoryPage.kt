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
internal fun HistoryWorkspace(history: DriveHistoryCoordinator?, cases: DiagnosticCaseCoordinator? = null,
    selected: IndividualRecordsTab, onSelect: (IndividualRecordsTab) -> Unit, scans: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndividualRecordsTab.entries.forEach { tab ->
                FilterChip(selected = selected == tab, onClick = { onSelect(tab) },
                    label = { Text(tab.name.lowercase().replaceFirstChar(Char::uppercase)) }, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.weight(1f)) {
            if (selected == IndividualRecordsTab.CASES) {
                if (cases != null) DiagnosticCasePage(cases) else Text("Case history unavailable.", Modifier.padding(20.dp))
            }
            else if (selected == IndividualRecordsTab.SCANS) scans()
            else if (history == null) Text("Drive history is unavailable.", Modifier.padding(20.dp))
            else {
                val state by history.state.collectAsState()
                DriveHistoryPage(state, history::open, history::closeReview, history::delete, history::retry, history::compare, history::closeComparison)
            }
        }
    }
}

private enum class SavedDriveSection { RECAP, CHARTS, EVENTS }

@Composable
internal fun DriveHistoryPage(state: DriveHistoryState, onOpen: (String) -> Unit,
    onBack: () -> Unit, onDelete: (String) -> Unit, onRetry: () -> Unit,
    onCompare: (List<String>, Boolean) -> Unit = { _, _ -> }, onCloseComparison: () -> Unit = {}) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    // These flags belong to this saved navigation visit, not to the retained coordinator.
    var openedHere by rememberSaveable { mutableStateOf(false) }
    var comparedHere by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var comparisonIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var sameVehicle by remember(comparisonIds) { mutableStateOf(false) }
    LaunchedEffect(state.drives) {
        comparisonIds = comparisonIds.filter { id -> state.drives.any { it.id == id && it.status == SavedDriveStatus.COMPLETED } }
    }
    BackHandler(enabled = comparedHere && (state.comparison != null || state.comparing) ||
        openedHere && (state.opened != null || state.opening)) {
        if (comparedHere && (state.comparison != null || state.comparing)) {
            comparedHere = false; onCloseComparison()
        } else { openedHere = false; onBack() }
    }
    val drive = state.opened
    var section by rememberSaveable(drive?.id) { mutableStateOf(SavedDriveSection.RECAP) }
    var cursor by remember(drive?.id) { mutableStateOf<Long?>(null) }
    var selectedEvent by remember(drive?.id) { mutableStateOf<Long?>(null) }
    var chartEvent by remember(drive?.id) { mutableStateOf<Long?>(null) }
    var copied by remember(drive?.id) { mutableStateOf(false) }
    val review = remember(drive) { drive?.historicalReview() }
    val scroll = rememberLazyListState()
    LaunchedEffect(drive?.id, section) { scroll.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (drive == null) "Drive history" else "Saved drive · historical", style = MaterialTheme.typography.headlineMedium)
            Text("Saved on this device · latest 20 drives within 25 MB · oldest drives rotate automatically",
                style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.error != null) OutlinedButton(onClick = onRetry) { Text("Retry drive history") }
            if (state.loading || state.opening || state.comparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.comparison != null) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Drive comparison", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onCloseComparison) { Text("Back to saved drives") }
                Text("Same vehicle confirmed by you. These are historical observations from the two selected drives.", style = MaterialTheme.typography.bodySmall)
                state.comparison.startedEpochMillis.forEach { Text(DateFormat.getDateTimeInstance().format(Date(it))) }
                state.comparison.observations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Overlapping ranges do not prove identical operating conditions. This comparison does not confirm a failed part or a successful repair.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("OPENWRENCH drive comparison", state.comparison.reportText()))
                }) { Text("Copy comparison") }
            } }
        } else if (drive == null) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Compare recorded patterns", style = MaterialTheme.typography.titleMedium)
                Text("Select two completed drives below. Confirm they came from the same vehicle; OpenWrench cannot identify a vehicle from its ECU response address.")
                Row {
                    Checkbox(checked = sameVehicle, onCheckedChange = { sameVehicle = it }, enabled = comparisonIds.size == 2)
                    Text("I confirm these two drives came from the same vehicle", Modifier.padding(top = 12.dp))
                }
                OutlinedButton(onClick = { comparedHere = true; onCompare(comparisonIds, sameVehicle) },
                    enabled = comparisonIds.size == 2 && sameVehicle && !state.comparing) { Text("Compare selected drives (${comparisonIds.size}/2)") }
            } }
            item { Text(state.saveMessage, style = MaterialTheme.typography.bodySmall) }
            if (state.drives.isEmpty() && !state.loading) item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("No saved drives yet", style = MaterialTheme.typography.titleMedium)
                Text("Start Vehicle Health monitoring. Checkpoints save automatically during the drive, and finishing saves the assessment, report, charts, and warning bookmarks.")
            } }
            items(state.drives, key = { it.id }) { entry ->
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text(DateFormat.getDateTimeInstance().format(Date(entry.startedAtEpochMillis)), style = MaterialTheme.typography.titleMedium)
                    Text(statusLabel(entry.status, entry.id == state.activeId))
                    Text("${entry.elapsedMillis / 1_000} seconds recorded · ${entry.bookmarks} warning bookmark(s)", style = MaterialTheme.typography.bodySmall)
                    Text("Saved ${DateFormat.getDateTimeInstance().format(Date(entry.savedAtEpochMillis))}", style = MaterialTheme.typography.bodySmall)
                    if (entry.status == SavedDriveStatus.COMPLETED && entry.id != state.activeId) Row {
                        Checkbox(checked = entry.id in comparisonIds, onCheckedChange = { selected ->
                            comparisonIds = if (!selected) comparisonIds - entry.id else (comparisonIds + entry.id).takeLast(2)
                        })
                        Text("Select for comparison", Modifier.padding(top = 12.dp))
                    }
                    OutlinedButton(onClick = { openedHere = true; onOpen(entry.id) }, enabled = !state.opening) { Text("Open saved drive") }
                    OutlinedButton(onClick = { deleteId = entry.id }, enabled = entry.id != state.activeId) { Text("Delete saved drive") }
                }
            }
        } else if (review != null) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBack) { Text("Back to saved drives") }
                Text(DateFormat.getDateTimeInstance().format(Date(drive.startedAtEpochMillis)), style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(drive.status, drive.id == state.activeId))
                Text("${drive.endReason ?: "Completion was not confirmed; this record ends at its last saved checkpoint."}")
                Text("Response source: ${drive.sourceEcu ?: "unknown"}. This is stored evidence; it does not describe the current vehicle state.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("OPENWRENCH saved drive", drive.copyText()))
                    copied = true
                }) { Text(if (copied) "Report copied" else "Copy saved drive report") }
                // Full-width controls remain usable with large system text.
                SavedDriveSection.entries.forEach { destination ->
                    FilterChip(selected = section == destination, onClick = { section = destination },
                        modifier = Modifier.fillMaxWidth(), label = { Text(destination.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
                OutlinedButton(onClick = { deleteId = drive.id }, enabled = drive.id != state.activeId) { Text("Delete saved drive") }
            } }
            when (section) {
                SavedDriveSection.RECAP -> {
                    item { WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text("Offline review of saved evidence", style = MaterialTheme.typography.titleLarge)
                        Text("Uses this drive's retained readings and original findings. No adapter or new drive is needed.")
                        val offline = remember(drive) { SavedEvidenceReview.build(drive) }
                        offline.observations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        offline.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { DriveHealthAssessmentPanel(review.assessment, historical = true) }
                    item { DriveRecapPanel(review, false) }
                }
                SavedDriveSection.CHARTS -> item { LiveHealthLinkedCharts(review, review.bookmarks.firstOrNull { it.id == chartEvent }, cursor,
                    onCursor = { cursor = it }, onRecentDrive = { chartEvent = null; cursor = null }, reviewKey = drive.id) }
                SavedDriveSection.EVENTS -> item { LiveHealthWarningBookmarks(review, selectedEvent,
                    onSelect = { selectedEvent = it }, onCharts = { bookmark ->
                        selectedEvent = bookmark.id; chartEvent = bookmark.id; cursor = bookmark.offsetMillis; section = SavedDriveSection.CHARTS
                    }) }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete saved drive?") },
        text = { Text("This removes the saved recap, chart trace, and warning bookmarks from this device.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
}

private fun statusLabel(status: SavedDriveStatus, active: Boolean): String = when (status) {
    SavedDriveStatus.CHECKPOINT -> if (active) "Recording · saved checkpoint" else "Recovered interrupted drive · last saved checkpoint"
    SavedDriveStatus.COMPLETED -> "Completed monitoring · saved"
    SavedDriveStatus.INTERRUPTED -> "Interrupted monitoring · partial drive saved"
}
