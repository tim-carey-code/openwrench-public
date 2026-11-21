package org.openwrench.frontend.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import org.openwrench.core.*
import org.openwrench.core.SignalAvailability
import org.openwrench.frontend.*
import org.openwrench.nativecore.ObdDecoder
import org.openwrench.frontend.android.transport.UsbConnectionSettings
import org.openwrench.frontend.android.transport.UsbControlLines

enum class AdapterMode(val title: String) { BLUETOOTH("Bluetooth ELM327"), USB("USB / FTDI (ELM-compatible)"), DEMO("Offline demo fixtures") }
data class AdapterOption(val id: String, val label: String, val usbVendorId: Int? = null, val usbProductId: Int? = null)

/** Current dashboard UI, fed only by the in-process Kotlin controller or offline sources. */
@Composable
fun NativeApp(controller: NativeController, mode: AdapterMode, onMode: (AdapterMode) -> Unit,
    options: List<AdapterOption>, selected: String?, onSelect: (String) -> Unit,
    baud: Int, onBaud: (Int) -> Unit, scenario: DemoScenario, onScenario: (DemoScenario) -> Unit,
    onRefresh: () -> Unit, onConnect: () -> Unit, onImport: () -> Unit, onExport: (Boolean, Long?) -> Unit,
    replay: CivicReplayPlayer? = null, pinStore: DashboardPinStore? = null, onExitReplay: () -> Unit = {},
    usbControlLines: UsbControlLines = UsbControlLines.ASSERTED,
    onUsbControlLines: (UsbControlLines) -> Unit = {}, onCopyUsbDiagnostic: () -> Unit = {},
    driveHistory: DriveHistoryCoordinator? = null, caseHistory: DiagnosticCaseCoordinator? = null, scanHistory: ScanHistoryCoordinator? = null, diagnosis: DiagnosisCoordinator? = null,
    onExportDiagnosis: (String) -> Unit = {},
    onAnalyzeDiagnosis: ((String) -> Unit)? = null,
    onReopenAiAnalysis: ((String) -> Unit)? = null,
    savedReplay: SavedDriveReplayPlayer? = null, importedDrives: List<SavedDrive> = emptyList(),
    driveDemoLoading: Boolean = false, driveDemoError: String? = null,
    onLoadDriveDemo: (String) -> Unit = {}, onImportedDriveDemo: (SavedDrive) -> Unit = {},
    onAnalyzeImportedDrive: (SavedDrive) -> Unit = {},
    onImportDriveDemo: () -> Unit = {}, onCancelDriveDemo: () -> Unit = {}, onCloseDriveDemo: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    val view = LocalView.current
    val keepScreenAwake = state.liveHealthEnabled && state.connected && state.origin == ReadingOrigin.HARDWARE
    DisposableEffect(view, keepScreenAwake) {
        val previousKeepScreenOn = view.keepScreenOn
        if (keepScreenAwake) view.keepScreenOn = true
        onDispose { if (keepScreenAwake) view.keepScreenOn = previousKeepScreenOn }
    }
    val context = LocalContext.current.applicationContext
    val driveHistoryState = driveHistory?.state?.collectAsState()?.value
    val history = scanHistory ?: remember(context) { ScanHistoryCoordinator(ScanHistoryStore(context.filesDir), controller.persistenceScope) }
    val scanHistoryState by history.state.collectAsState()
    val savedScans = scanHistoryState.entries
    val historyBusy = scanHistoryState.busy
    val historyLoadFailed = !scanHistoryState.available
    val historyError = scanHistoryState.error
    val savedMessage = scanHistoryState.message
    val replayState = replay?.state?.collectAsState()?.value
    val savedReplayState = savedReplay?.state?.collectAsState()?.value
    var chooseDriveDemo by remember { mutableStateOf(false) }
    LaunchedEffect(savedReplayState?.selectionGeneration) { if (savedReplayState?.recording != null) chooseDriveDemo = false }
    LaunchedEffect(importedDrives) { if (importedDrives.isNotEmpty()) chooseDriveDemo = true }
    val openDriveDemo: () -> Unit = { if (canStartDriveReplay(state)) { savedReplay?.pause(); chooseDriveDemo = true } }
    val aiNotice by diagnosisAiRequests.notice.collectAsState()
    val aiStatus: @Composable () -> Unit = {
        val notice = aiNotice
        if (notice != null && onReopenAiAnalysis != null) {
            AiAnalysisBanner(notice, onOpen = { onReopenAiAnalysis(notice.id) },
                onDismiss = { diagnosisAiRequests.dismissNotice(notice) })
        }
    }
    if (chooseDriveDemo) DiagnosticTheme {
        SavedDriveDemoPicker(driveHistoryState, importedDrives, driveDemoLoading,
            driveDemoError, onLoadDriveDemo, onImportedDriveDemo, onAnalyzeImportedDrive, onImportDriveDemo,
            onCancel = { chooseDriveDemo = false; onCancelDriveDemo() }, aiStatus = aiStatus)
    }
    // This timer only triggers aging when no new state arrives. Each presentation
    // reads the current receipt clock so fresh reads cannot outrun a cached tick.
    var ageTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(250); ageTick++ } }
    val trendHistory = remember(controller) { NativeTrendHistory { android.os.SystemClock.elapsedRealtime() } }
    val nativeRows = remember(state, ageTick) { trendHistory.present(state) }
    val source = when {
        replay != null || savedReplay != null -> DashboardSource.CIVIC_REPLAY
        state.origin == ReadingOrigin.REPLAY -> DashboardSource.SNAPSHOT
        mode == AdapterMode.DEMO -> DashboardSource.DEMO
        else -> DashboardSource.HARDWARE
    }
    val rows = when (source) {
        DashboardSource.CIVIC_REPLAY -> savedReplayState?.dashboardSignals() ?: replayState?.dashboardSignals().orEmpty()
        DashboardSource.DEMO -> mechanicDemoRows(scenario)
        else -> nativeRows
    }
    val watch = state.watchPresentation()
    val actions = DashboardActions(onConnect, controller::disconnect, controller::retryRelease,
        controller::startLive, controller::stopLive,
        select = { id, chosen -> nativeSignalIds.entries.firstOrNull { it.value == id }?.key?.let { pid ->
            controller.watch(pid, if (chosen) liveWatchRate(pid) else null)
        } }, selectAllSupported = {
            val available = state.supported.intersect(ObdDecoder.scalarPids)
            if (watch.canEditSelection() && available.size <= MAX_LIVE_SIGNALS) available.forEach { pid ->
                controller.watch(pid, liveWatchRate(pid))
            }
        }, clearSelection = {
            if (watch.canEditSelection()) state.watched.keys.forEach { controller.watch(it, null) }
        }, prepareFuelTrims = controller::prepareFuelTrims)
    val scan = DiagnosticScanDelivery.present(state)
    val liveHealth = remember(state, ageTick) {
        LiveHealthDelivery.present(state, android.os.SystemClock.elapsedRealtime())
    }
    DashboardApp(watch, rows, source, actions, pinStore, replay,
        actionEnabled = selected != null,
        onSource = openDriveDemo,
        scan = scan, onScan = { controller.requestSnapshot() },
        onWarningScan = { controller.requestSnapshot(includeWarningContext = true) },
        idleHealthReport = state.idleHealthReport, idleHealthPhase = state.idleHealthPhase,
        idleHealthElapsedMillis = state.idleHealthElapsedMillis,
        idleHealthHistorical = state.idleHealthReport?.let {
            !state.connected || it.sessionGeneration != state.sessionGeneration
        } ?: false,
        canStartIdleHealth = source == DashboardSource.HARDWARE && state.connected && !state.busy &&
            !state.liveRunning && !state.stopPending && !state.cleanupUncertain &&
            IdleHealthReportBuilder.targetPids.any { it in state.supported },
        onStartIdleHealth = { controller.startIdleHealth() },
        onCancelIdleHealth = { controller.cancelIdleHealth() },
        liveHealth = liveHealth,
        driveHistory = driveHistory, caseHistory = caseHistory, diagnosis = diagnosis, onExportDiagnosis = onExportDiagnosis, onAnalyzeDiagnosis = onAnalyzeDiagnosis,
        driveSaveStatus = driveHistoryState?.let { it.error ?: it.saveMessage },
        canStartLiveHealth = source == DashboardSource.HARDWARE && state.connected && !state.busy &&
            !state.stopPending && !state.cleanupUncertain && liveHealth.phase != LiveHealthPhase.RUNNING,
        onStartLiveHealth = { controller.startLiveHealth(allSupportedScalars = true) },
        onStopLiveHealth = { controller.stopLiveHealth() },
        vin = state.vin.takeIf { source == DashboardSource.HARDWARE && state.connected },
        savedScanVin = state.scanCaptureVin,
        vinDetail = if (state.connected) state.vinDetail else "Connect to read VIN.",
        canReadVin = source == DashboardSource.HARDWARE && state.connected && !state.busy && !state.liveRunning && !state.stopPending,
        onReadVin = { controller.requestVin() },
        savedScans = savedScans, historyBusy = historyBusy, historyAvailable = !historyLoadFailed,
        historyError = historyError, savedMessage = savedMessage,
        snapshotSimulated = state.origin == ReadingOrigin.REPLAY && state.replaySimulated,
        onSaveScan = { includeVin ->
            if (!historyBusy && !historyLoadFailed && !state.snapshotInProgress && state.scanCapture != null) {
                val report = scan.reportText()
                val capture = checkNotNull(state.scanCapture)
                val vin = state.scanCaptureVin.takeIf { includeVin }
                history.save(report, vin, capture)
            }
        },
        onDeleteScan = history::delete,
        savedReplay = savedReplay, onChooseDriveDemo = openDriveDemo, onCloseDriveDemo = onCloseDriveDemo,
        aiStatus = aiStatus,
        sessionControls = {
            OutlinedButton(onClick = openDriveDemo, enabled = canStartDriveReplay(state)) { Text("Demo a saved drive") }
            if (savedReplay != null) Text("Historical saved-drive replay. No vehicle is connected; playback does not save evidence.")
            NativeSessionControls(state, mode, onMode, options, selected, onSelect, baud, onBaud,
                scenario, onScenario, onRefresh, onConnect, onImport, onExport, controller,
                replay != null || savedReplay != null, if (savedReplay != null) onCloseDriveDemo else onExitReplay, usbControlLines, onUsbControlLines, onCopyUsbDiagnostic)
        })
}

/** Keep the current dashboard's fixed demo examples and its distinct missing-data states. */
internal fun demoRows(scenario: DemoScenario): List<DashboardSignal> {
    if (scenario == DemoScenario.TIMED_WORKSPACE) return syntheticWorkspaceFixture()
    if (scenario == DemoScenario.LOAD_ERROR) return emptyList()
    val rows = demoDashboardSignals()
    if (scenario == DemoScenario.COMPLETE) return rows
    return rows.map { row -> when (row.definition.id) {
        "rpm" -> row.copy(availability = SignalAvailability.STALE, value = null, reason = "Fixture intentionally supplies a stale observation")
        "coolant" -> row.copy(definition = row.definition.copy(support = SignalSupport.UNKNOWN), availability = SignalAvailability.NOT_SAMPLED,
            value = null, reason = "Fixture intentionally leaves support unchecked")
        "speed" -> row.copy(availability = SignalAvailability.UNAVAILABLE, value = null, reason = "Fixture intentionally supplies no value")
        "runtime" -> row.copy(availability = SignalAvailability.ERROR, value = null, reason = "Fixture intentionally reports a read error")
        else -> row
    } }
}

@Composable
private fun NativeSessionControls(state: NativeUiState, mode: AdapterMode, onMode: (AdapterMode) -> Unit,
    options: List<AdapterOption>, selected: String?, onSelect: (String) -> Unit, baud: Int, onBaud: (Int) -> Unit,
    scenario: DemoScenario, onScenario: (DemoScenario) -> Unit, onRefresh: () -> Unit, onConnect: () -> Unit,
    onImport: () -> Unit, onExport: (Boolean, Long?) -> Unit, controller: NativeController,
    replayMode: Boolean, onExitReplay: () -> Unit, usbControlLines: UsbControlLines,
    onUsbControlLines: (UsbControlLines) -> Unit, onCopyUsbDiagnostic: () -> Unit) {
    val editable = !state.busy && !state.connected && !state.cleanupUncertain
    var sourceMenu by remember { mutableStateOf(false) }
    var adapterMenu by remember { mutableStateOf(false) }
    var baudMenu by remember { mutableStateOf(false) }
    var usbLinesMenu by remember { mutableStateOf(false) }
    var demoMenu by remember { mutableStateOf(false) }
    var includeVin by remember(state.snapshotSessionGeneration, state.snapshotCapturedMillis, state.snapshotVin) { mutableStateOf(false) }
    if (replayMode || state.origin == ReadingOrigin.REPLAY) {
        OutlinedButton(onClick = { if (replayMode) onExitReplay() else controller.disconnect() }) {
            Text(if (replayMode) "Close recording" else "Close snapshot")
        }
    } else {
        Box {
            OutlinedButton(onClick = { sourceMenu = true }, enabled = editable) { Text("Source: ${mode.title}") }
            DropdownMenu(sourceMenu, { sourceMenu = false }) {
                AdapterMode.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) },
                    onClick = { sourceMenu = false; onMode(item) }) }
            }
        }
        if (mode == AdapterMode.DEMO) {
            Box {
                OutlinedButton(onClick = { demoMenu = true }, enabled = editable) { Text(scenario.title) }
                DropdownMenu(demoMenu, { demoMenu = false }) {
                    DemoScenario.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) },
                        onClick = { demoMenu = false; onScenario(item) }) }
                }
            }
            if (scenario == DemoScenario.LOAD_ERROR) Text("The selected fixture reports a load error. No values are supplied.", color = MaterialTheme.colorScheme.tertiary)
            Text(if (scenario == DemoScenario.TIMED_WORKSPACE) "Synthetic timed receipts with intentional gaps, solely for UI demonstration. No vehicle is connected; these are not diagnostic evidence."
                else "Static dashboard examples are fictional and have no observation time. No vehicle is connected.")
        } else {
            Box {
                OutlinedButton(onClick = { adapterMenu = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                    Text(options.firstOrNull { it.id == selected }?.label ?: "Select adapter")
                }
                DropdownMenu(adapterMenu, { adapterMenu = false }) {
                    DropdownMenuItem(text = { Text(if (mode == AdapterMode.BLUETOOTH) "Load paired adapters" else "Refresh USB") },
                        onClick = { adapterMenu = false; onRefresh() })
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.label) },
                        onClick = { adapterMenu = false; onSelect(option.id) }) }
                    if (options.isEmpty()) DropdownMenuItem(text = { Text("No available adapters") }, onClick = {}, enabled = false)
                }
            }
            if (mode == AdapterMode.USB) {
                Box {
                    OutlinedButton(onClick = { baudMenu = true }, enabled = editable) {
                        Text(if (baud == UsbConnectionSettings.AUTO_BAUD) "Baud rate: Auto detect" else "Baud rate: $baud")
                    }
                    DropdownMenu(baudMenu, { baudMenu = false }) {
                        DropdownMenuItem(text = { Text("Auto detect") },
                            onClick = { baudMenu = false; onBaud(UsbConnectionSettings.AUTO_BAUD) })
                        UsbConnectionSettings.SUPPORTED_BAUD_RATES.forEach { rate -> DropdownMenuItem(text = { Text("$rate baud") },
                            onClick = { baudMenu = false; onBaud(rate) }) }
                    }
                }
                Box {
                    OutlinedButton(onClick = { usbLinesMenu = true }, enabled = editable) { Text("USB control lines: ${usbControlLines.title}") }
                    DropdownMenu(usbLinesMenu, { usbLinesMenu = false }) {
                        UsbControlLines.entries.forEach { lines -> DropdownMenuItem(text = { Text(lines.title) },
                            onClick = { usbLinesMenu = false; onUsbControlLines(lines) }) }
                    }
                }
                Text("Connect the adapter to the vehicle and USB OTG. OHP's ELM-compatible USB adapter uses FTDI. Auto detect finds the serial baud rate.")
            }
            OutlinedButton(onClick = onCopyUsbDiagnostic, enabled = !state.busy) { Text("Copy session log") }
            Text("Your source, adapter selection and USB settings are remembered on this device. An available adapter is selected automatically when the choice is unambiguous. Tap Connect in the source strip; Android may ask for permission. Select signals, then Start live data explicitly.")
        }
        if (state.connected) {
            Text("Adapter: ${state.identity ?: "Not supplied"}")
            Text(state.vinDetail)
            state.vin?.let { Text("VIN: $it") }
            OutlinedButton(onClick = controller::requestVin,
                enabled = !state.busy && !state.liveRunning && !state.stopPending) { Text("Read VIN") }
            OutlinedButton(onClick = controller::requestSnapshot, enabled = !state.busy) { Text("Capture snapshot") }
        }
    }
    OutlinedButton(onClick = onImport, enabled = editable) { Text("Import snapshot or recording") }
    if (replayMode) Text(state.detail, style = MaterialTheme.typography.bodySmall)
    if (state.snapshot != null && !replayMode) {
        if (state.snapshotVin != null) {
            Text("VIN attached to snapshot: ${state.snapshotVin}", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(checked = includeVin, onCheckedChange = { includeVin = it })
                Text("Include snapshot VIN in export", Modifier.padding(top = 12.dp))
            }
        }
        OutlinedButton(onClick = { onExport(includeVin && state.snapshotVin != null, state.snapshotSessionGeneration) }, enabled = !state.busy) { Text("Export snapshot") }
        Text("${state.snapshot.size} readings · VIN omitted unless explicitly included.", style = MaterialTheme.typography.bodySmall)
    }
    Text("All diagnostic work runs inside this Android app. No server is required. No automatic reconnect or demo fallback.")
    Text("Bluetooth and USB adapter qualification on physical hardware remains pending.", style = MaterialTheme.typography.bodySmall)
}
