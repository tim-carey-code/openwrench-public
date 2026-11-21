package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.openwrench.frontend.android.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private enum class DiagnosisResultSection(val title: String) {
    ANALYSIS("Analysis"), CHARTS("Charts"), EVIDENCE("Evidence")
}

/** A single diagnosis is the user-facing parent of automatically saved evidence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiagnosisPage(state: DiagnosisState?, source: DashboardSource, liveHealth: LiveHealthView?,
    canStart: Boolean, hardwareState: WatchUiState, adapterSelected: Boolean,
    onConnect: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onFinish: () -> Unit,
    onAnalyze: ((String) -> Unit)? = null,
    onResume: (String, Boolean) -> Unit, onRetry: () -> Unit, onExport: (String) -> Unit,
    onSettings: () -> Unit,
    loadDrive: suspend (String, String) -> DiagnosisDriveEvidence? = { _, _ -> null }) {
    val record = state?.current
    val phase = state?.phase ?: DiagnosisPhase.IDLE
    val running = phase == DiagnosisPhase.RECORDING
    val transitioning = phase in setOf(DiagnosisPhase.STARTING, DiagnosisPhase.PAUSING, DiagnosisPhase.FINISHING)
    val busy = state == null || state.loading || state.busy || transitioning
    val terminal = record != null && !running && !transitioning
    val home = diagnosisDriveHomePresentation(phase, source, hardwareState, adapterSelected, canStart, busy)
    var sessionExpanded by remember(record?.id) { mutableStateOf(false) }
    var driveMenuExpanded by remember(record?.id) { mutableStateOf(false) }
    var resumeDialog by remember(record?.id) { mutableStateOf(false) }
    var sameVehicle by remember(record?.id, resumeDialog) { mutableStateOf(false) }
    var resultSection by remember(record?.id) { mutableStateOf(DiagnosisResultSection.ANALYSIS) }
    var selectedSegmentId by remember(record?.id) { mutableStateOf<String?>(null) }
    val segment = record?.segments?.firstOrNull { it.id == selectedSegmentId } ?: record?.segments?.lastOrNull()
    val matchingLiveReview = liveHealth?.driveReview?.takeIf {
        running && segment != null && segment.id == record?.segments?.lastOrNull()?.id &&
            segment.endedAtOffsetMillis == null && liveHealth.sessionGeneration == segment.generation &&
            it.startedAtMonotonicMillis == segment.startedAtMonotonicMillis
    }
    var savedDrive by remember(record?.id, segment?.id, segment?.driveId) { mutableStateOf<SavedDrive?>(null) }
    var driveLoading by remember(record?.id, segment?.id, segment?.driveId) { mutableStateOf(false) }
    var driveError by remember(record?.id, segment?.id, segment?.driveId) { mutableStateOf<String?>(null) }
    var reloadDrive by remember(record?.id, segment?.id, segment?.driveId) { mutableIntStateOf(0) }
    val review = matchingLiveReview ?: savedDrive?.historicalReview()
    val currentLiveSegment = record?.segments?.lastOrNull()?.takeIf { part ->
        running && part.endedAtOffsetMillis == null && part.generation == liveHealth?.sessionGeneration &&
            part.startedAtMonotonicMillis == liveHealth?.driveReview?.startedAtMonotonicMillis
    }
    val recordedMillis = record?.segments?.sumOf { part ->
        part.endedAtOffsetMillis ?: liveHealth?.driveReview?.elapsedMillis?.takeIf { part.id == currentLiveSegment?.id } ?: 0L
    } ?: 0L
    val unconfirmedDuration = record?.segments?.any { part ->
        part.endedAtOffsetMillis == null && part.id != currentLiveSegment?.id
    } == true
    var cursor by remember(segment?.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(record?.id, record?.segments?.size) {
        selectedSegmentId = record?.segments?.lastOrNull()?.id
    }
    LaunchedEffect(record?.id, segment?.id, segment?.driveId, phase, reloadDrive) {
        savedDrive = null
        driveError = null
        val diagnosisId = record?.id
        val driveId = segment?.driveId
        if (matchingLiveReview == null && diagnosisId != null && driveId != null) {
            driveLoading = true
            try {
                val loaded = loadDrive(diagnosisId, driveId)
                coroutineContext.ensureActive()
                savedDrive = loaded?.drive
                driveError = loaded?.warning
                if (loaded?.drive == null) driveError = "No saved analysis is available for this drive. Its available scans and recorded readings remain in the diagnosis export."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                driveError = "Saved drive analysis could not be read. Existing evidence was preserved; retry loading it."
            } finally { driveLoading = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            DiagnosisDriveHome(
                presentation = home,
                recordedMillis = recordedMillis,
                readings = record?.segments?.sumOf { it.sampleCount } ?: 0L,
                drives = record?.segments?.size ?: 0,
                onConnect = { performDiagnosisConnectionAction(home.connectionAction, onConnect, onSettings) },
                onStart = onStart, onEnd = onFinish
            )
        }
        if (record != null) item {
            WorkspaceNotice(
                title = when {
                    running -> "Recording locally · Leave the app open"
                    phase == DiagnosisPhase.INTERRUPTED -> "Diagnosis interrupted"
                    phase == DiagnosisPhase.PAUSED -> "Collection paused"
                    terminal -> "Historical evidence · Not current vehicle status"
                    else -> "Start while safely parked"
                },
                message = when {
                    running -> "Park before using controls or finishing. Leaving the app stops collection and releases the adapter."
                    phase == DiagnosisPhase.INTERRUPTED -> "Retained evidence remains available. Missing intervals are not filled in. Confirm the same vehicle before continuing."
                    phase == DiagnosisPhase.PAUSED -> "The saved evidence is retained. Continue only with the same vehicle while safely parked."
                    terminal -> "Review available scans, readings and coverage. A completed capture does not establish vehicle health."
                    else -> "Keep the vehicle stationary for setup and scans. Do not operate OpenWrench while driving."
                }, caution = phase in setOf(DiagnosisPhase.INTERRUPTED, DiagnosisPhase.PAUSED))
        }
        item { WorkspacePanel(Modifier.fillMaxWidth(), padding = 16, gap = 12) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    record == null -> "Selected source · ${source.label}"
                    running -> "Vehicle evidence · live recording"
                    transitioning -> "Vehicle evidence · session updating"
                    else -> "Saved vehicle evidence · not current status"
                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    state?.loading == true -> "Loading saved diagnoses…"
                    phase == DiagnosisPhase.STARTING -> "Preparing your diagnosis"
                    phase == DiagnosisPhase.FINISHING -> "Finishing your diagnosis"
                    phase == DiagnosisPhase.PAUSING -> "Saving this drive"
                    running -> "Diagnosis in progress"
                    phase == DiagnosisPhase.COMPLETED -> "Diagnosis complete"
                    phase == DiagnosisPhase.PAUSED -> "Diagnosis paused"
                    phase == DiagnosisPhase.INTERRUPTED -> "Diagnosis interrupted"
                    else -> "Ready for a new diagnosis"
                }, style = MaterialTheme.typography.titleLarge)
                if (record != null) Text(diagnosisDate(record.startedAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record != null) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosisStat(diagnosisDuration(recordedMillis), "Recorded time")
                    DiagnosisStat(record.segments.size.toString(), "Drives")
                    DiagnosisStat(record.segments.sumOf { it.sampleCount }.toString(), "Readings saved")
                    DiagnosisStat(record.scanReports.size.toString(), "Scans")
                }
                if (unconfirmedDuration) Text("Partial duration unconfirmed", style = MaterialTheme.typography.bodySmall)
                val missed = record.segments.sumOf { it.droppedSamples }
                if (missed > 0) Text("$missed readings were not retained. The export identifies this coverage gap.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state?.message?.isNotBlank() == true) Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (state?.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !busy) { Text(if (record == null) "Retry history" else "Retry saving") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // The three primary drive controls now live in DiagnosisDriveHome.
            if (terminal && record != null) {
                if (onAnalyze != null) {
                    OutlinedButton(onClick = { onAnalyze(record.id) }, enabled = !busy) {
                        Text("Analyze with AI")
                    }
                }
                OutlinedButton(onClick = { onExport(record.id) }, enabled = !busy) {
                    Text("Export diagnosis")
                }
            }

            if (record != null) {
                TextButton(onClick = { sessionExpanded = !sessionExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sessionExpanded) "Hide session options" else "Session options & details")
                }
            }
            if (sessionExpanded && record != null) {
                HorizontalDivider()
                if (running) {
                    OutlinedButton(onClick = onPause, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause for another drive")
                    }
                } else if (!transitioning && terminal) {
                    if (phase in setOf(DiagnosisPhase.PAUSED, DiagnosisPhase.INTERRUPTED)) {
                        OutlinedButton(onClick = { resumeDialog = true }, enabled = home.continueEnabled,
                            modifier = Modifier.fillMaxWidth()) { Text("Continue diagnosis") }
                        OutlinedButton(onClick = onFinish, enabled = home.retainedFinishEnabled,
                            modifier = Modifier.fillMaxWidth()) { Text("End Drive") }
                    } else if (phase == DiagnosisPhase.COMPLETED) {
                        OutlinedButton(onClick = { resumeDialog = true }, enabled = canStart && !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Add another drive") }
                    }
                    OutlinedButton(onClick = onStart, enabled = canStart && !busy,
                        modifier = Modifier.fillMaxWidth()) { Text("Start New Drive") }
                }
            }
        } }
        if (record == null) {
            item { WorkspacePanel(Modifier.fillMaxWidth(), padding = 16) {
                Text("Diagnostic information", style = MaterialTheme.typography.titleLarge)
                Text("Start a diagnosis to see its available analysis, charts and scan evidence here.",
                    style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosisStat("Not started", "Duration")
                    DiagnosisStat("—", "Readings")
                    DiagnosisStat("Not captured", "Scans")
                }
                Text("Findings are offline interpretations. Missing data stays unknown, and no fault codes does not establish that a vehicle is healthy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } else {
            item { Text(if (running) "Diagnostic information" else "Results", style = MaterialTheme.typography.titleLarge) }
            item {
                // Scrollable tabs retain complete labels at large accessibility font sizes.
                ScrollableTabRow(selectedTabIndex = resultSection.ordinal, edgePadding = 0.dp) {
                    DiagnosisResultSection.entries.forEach { destination ->
                        Tab(selected = resultSection == destination, onClick = { resultSection = destination },
                            text = { Text(destination.title) })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (running) "Live diagnosis · findings update as readings arrive" else "Saved diagnosis · historical evidence, not current status",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (record.segments.size > 1) Box {
                        OutlinedButton(onClick = { driveMenuExpanded = true }) {
                            Text("Drive ${record.segments.indexOf(segment) + 1} of ${record.segments.size} · Choose drive")
                        }
                        DropdownMenu(expanded = driveMenuExpanded, onDismissRequest = { driveMenuExpanded = false }) {
                            record.segments.forEachIndexed { index, part ->
                                DropdownMenuItem(text = { Text("Drive ${index + 1} · ${diagnosisDate(part.startedAtEpochMillis)}") },
                                    onClick = { selectedSegmentId = part.id; driveMenuExpanded = false })
                            }
                        }
                    }
                    if (matchingLiveReview == null && review != null) driveError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (resultSection == DiagnosisResultSection.ANALYSIS) {
                        Text("Offline interpretations do not confirm a failed part or whole-vehicle health.", style = MaterialTheme.typography.bodySmall)
                        if (matchingLiveReview == null && segment?.driveId != null && !transitioning) {
                            TextButton(onClick = { reloadDrive++ }, enabled = !driveLoading && !busy) { Text("Reload saved analysis") }
                        }
                    }
                }
            }
            when (resultSection) {
                DiagnosisResultSection.ANALYSIS -> {
                    if (review != null) {
                        item { DriveHealthAssessmentPanel(review.assessment, historical = !running || segment != record.segments.lastOrNull()) }
                        item { DriveRecapPanel(review, running && segment == record.segments.lastOrNull()) }
                    } else item { WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text(if (driveLoading) "Loading saved analysis…" else "Drive analysis is not available yet.")
                        Text(driveError ?: "The diagnosis export retains the available readings and scans. Unavailable evidence does not imply a healthy result.",
                            style = MaterialTheme.typography.bodySmall)
                        if (segment?.driveId != null) OutlinedButton(onClick = { reloadDrive++ }, enabled = !driveLoading) { Text("Reload drive analysis") }
                    } }
                }
                DiagnosisResultSection.CHARTS -> {
                    if (review != null) item { LiveHealthLinkedCharts(review, null, cursor,
                        onCursor = { cursor = it }, onRecentDrive = { cursor = null },
                        collecting = matchingLiveReview != null, reviewKey = listOf(record?.id, segment?.id, source)) }
                    else item { WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text(if (driveLoading) "Loading saved charts…" else "No retained chart evidence is available for this drive yet.")
                        driveError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (segment?.driveId != null) OutlinedButton(onClick = { reloadDrive++ }, enabled = !driveLoading) { Text("Reload drive charts") }
                    } }
                }
                DiagnosisResultSection.EVIDENCE -> {
                    item { WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text("Recorded evidence", style = MaterialTheme.typography.titleLarge)
                        Text("Timestamped received readings are in the CSV export. Chart traces are bounded summaries; they are not the full raw log.")
                        Text("Scans and drives are attached automatically. Vehicle identity is not inferred and VIN is omitted.")
                        record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        record.segments.forEachIndexed { index, part ->
                            Text("Drive ${index + 1}: ${part.sampleCount} readings · ${part.endedAtOffsetMillis?.let { "${it / 1000} s" } ?: "completion unconfirmed"}")
                            part.endReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    } }
                    if (record.scanReports.isEmpty()) item { Text("No completed diagnostic scan is attached. No code-free result is inferred.") }
                    items(record.scanReports.withIndex().toList(), key = { it.index }) { scan ->
                        WorkspacePanel(Modifier.fillMaxWidth()) {
                            Text("Diagnostic scan ${scan.index + 1}", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(scan.value, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
    if (resumeDialog && record != null) AlertDialog(onDismissRequest = { resumeDialog = false },
        title = { Text("Continue this diagnosis?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A new drive will join this diagnosis. OpenWrench cannot identify a vehicle from an adapter or ECU response address.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sameVehicle, onCheckedChange = { sameVehicle = it })
                    Text("I confirm this is the same vehicle", Modifier.weight(1f))
                }
            }
        }, confirmButton = { TextButton(onClick = { onResume(record.id, sameVehicle); resumeDialog = false },
            enabled = sameVehicle && canStart && !busy) { Text("Continue diagnosis") } },
        dismissButton = { TextButton(onClick = { resumeDialog = false }) { Text("Cancel") } })
}


/** Responsive diagnosis landing surface. All actions delegate to existing coordinator callbacks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosisDriveHome(
    presentation: DiagnosisDriveHomePresentation,
    recordedMillis: Long,
    readings: Long,
    drives: Int,
    onConnect: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    val background = androidx.compose.ui.graphics.Color(0xFF10161C)
    val foreground = androidx.compose.ui.graphics.Color(0xFFF2F6F8)
    val muted = androidx.compose.ui.graphics.Color(0xFF9DAFBA)
    val accent = if (presentation.connected) androidx.compose.ui.graphics.Color(0xFF41DBAA)
        else androidx.compose.ui.graphics.Color(0xFFF0B36B)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val landscape = diagnosisDriveHomeWide(maxWidth.value, LocalDensity.current.fontScale)
        val vehicle: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("OPENWRENCH  •  DRIVE", color = foreground, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                Text("Ready for the road.", color = foreground, style = MaterialTheme.typography.headlineMedium)
                Text(
                    presentation.subtitle,
                    color = muted
                )
                Spacer(Modifier.height(12.dp))
                // Editable Compose illustration; not an inferred vehicle identification.
                Box(Modifier.fillMaxWidth().height(165.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(.85f).height(112.dp)
                        .padding(top = 10.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF1D303B),
                            androidx.compose.foundation.shape.RoundedCornerShape(56.dp)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(175.dp).height(40.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFF8EA5B5),
                                androidx.compose.foundation.shape.RoundedCornerShape(topStart = 60.dp, topEnd = 60.dp)))
                        Box(Modifier.width(280.dp).height(65.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFFDCE7ED),
                                androidx.compose.foundation.shape.RoundedCornerShape(30.dp)))
                        Box(Modifier.width(215.dp).height(8.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFF607987),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                    }
                }
                Text("Vehicle diagnostics", color = foreground, style = MaterialTheme.typography.titleLarge)
                Text("OBD-II / ELM327  •  Vehicle identity not inferred", color = muted,
                    style = MaterialTheme.typography.bodySmall)
                Text(
                    presentation.connectionStatus,
                    color = accent, style = MaterialTheme.typography.labelLarge
                )
            }
        }
        val actions: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onConnect, enabled = presentation.connectionAction != DiagnosisConnectionAction.NONE,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(presentation.connectionButton)
                        Text(presentation.connectionHint, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
                Button(
                    onClick = onStart, enabled = presentation.startEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = foreground,
                        contentColor = background
                    )
                ) { Text(if (presentation.recording) "Recording Drive" else "Start Drive", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(
                    onClick = onEnd, enabled = presentation.endEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)
                ) { Text("End Drive", style = MaterialTheme.typography.titleMedium) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosisHomeMetric(diagnosisDuration(recordedMillis), "SESSION")
                    DiagnosisHomeMetric(readings.toString(), "READINGS")
                    DiagnosisHomeMetric(drives.toString(), "DRIVES")
                }
                Text("Start and end only while safely parked. Keep OpenWrench open during recording.",
                    color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            color = background,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ) {
            if (landscape) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.1f)) { vehicle() }
                    Box(Modifier.weight(1f)) { actions() }
                }
            } else {
                Column { vehicle(); actions() }
            }
        }
    }
}

@Composable
private fun DiagnosisHomeMetric(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium,
            color = androidx.compose.ui.graphics.Color(0xFFF2F6F8))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFF9DAFBA))
    }
}

/** Wrapping summary cells avoid squeezing values into equal-width columns at large font sizes. */
@Composable
private fun DiagnosisStat(value: String, label: String) {
    Column(Modifier.widthIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = TelemetryNumber.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DiagnosisHistoryPage(state: DiagnosisState?, onOpen: (String) -> Unit,
    onRetry: () -> Unit, onIndividualRecords: () -> Unit) {
    val active = state?.phase in setOf(DiagnosisPhase.STARTING, DiagnosisPhase.RECORDING, DiagnosisPhase.PAUSING, DiagnosisPhase.FINISHING)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Saved on this device", style = MaterialTheme.typography.headlineMedium)
            Text("Original evidence retained. Reopen a diagnosis to review, continue or export it.")
            if (active) Text("Pause or finish the current diagnosis before opening another saved session.", style = MaterialTheme.typography.bodySmall)
            if (state == null || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onRetry, enabled = state != null && !state.busy && !state.loading) { Text("Refresh history") }
        }
        if (state != null && !state.loading && state.history.isEmpty()) item { WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Your first diagnosis starts here", style = MaterialTheme.typography.titleMedium)
            Text("Start Drive records a session and prepares its evidence automatically. Completed and interrupted sessions appear here.")
        } }
        items(state?.history.orEmpty(), key = { it.id }) { record ->
            val canOpen = !state!!.busy && (!active || state.current?.id == record.id)
            WorkspacePanel(Modifier.fillMaxWidth().clickable(enabled = canOpen, role = Role.Button,
                onClickLabel = "Open diagnosis", onClick = { onOpen(record.id) }), padding = 16, gap = 6) {
                Text(diagnosisDate(record.startedAtEpochMillis), style = MaterialTheme.typography.titleMedium)
                StatusChip(record.status.name.lowercase().replaceFirstChar(Char::uppercase),
                    caution = record.status.name != "COMPLETED")
                Text("${record.segments.size} drive(s) · ${record.segments.sumOf { it.sampleCount }} readings · ${record.scanReports.size} scan(s)")
                record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(if (canOpen) "Open diagnosis →" else "Finish or pause the current session to open",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { WorkspaceNotice("Local evidence", "Capture status describes the saved session, not vehicle health. Records stay on this device unless you export them.") }
        item { OutlinedButton(onClick = onIndividualRecords) { Text("Individual drives and scans") } }
    }
}

@Composable
internal fun AdvancedDiagnosticsPage(onSettings: () -> Unit, onSignals: () -> Unit,
    onScan: () -> Unit, onHealth: () -> Unit, onRecords: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth.value / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 760) 2 else 1
        val tools = listOf(
            Triple("Connection settings", "Offline Bluetooth / USB setup. Choose an adapter, manage sources, or import saved evidence.", onSettings),
            Triple("Signal acquisition", "Choose supported readings requested from the adapter. This is separate from display selection.", onSignals),
            Triple("Diagnostic scan", "Capture fault codes and standard read-only OBD evidence separately.", onScan),
            Triple("Health monitoring", "Manual monitoring and guided stationary observations. No whole-vehicle health clearance is inferred.", onHealth),
            Triple("Local drives and scans", "Open retained records and compare saved drives. No cloud sync or account required.", onRecords),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Connection & local data", style = MaterialTheme.typography.headlineMedium)
                Text("Individual tools and connection settings. Diagnosis handles recording and evidence collection automatically.")
            }
            items(tools.chunked(columns)) { group ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    group.forEach { (title, description, action) ->
                        WorkspacePanel(Modifier.weight(1f).fillMaxHeight(), padding = 16, gap = 12) {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open $title") }
                        }
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { WorkspaceNotice("Read-only by design", "No vehicle-write operations are available. Connect only while safely parked. This app does not establish roadworthiness.") }
        }
    }
}

private fun diagnosisDate(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private fun diagnosisDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}
