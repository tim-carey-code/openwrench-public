package org.openwrench.frontend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openwrench.core.*
import org.openwrench.frontend.android.DiagnosticScanView
import org.openwrench.frontend.android.IdleHealthPhase
import org.openwrench.frontend.android.IdleHealthReport
import org.openwrench.frontend.android.LiveHealthView
import org.openwrench.frontend.android.DriveHistoryCoordinator
import org.openwrench.frontend.android.SavedScan
import org.openwrench.frontend.android.MAX_LIVE_SIGNALS
import org.openwrench.frontend.android.ControllerAdmission
import org.openwrench.frontend.android.DiagnosisCoordinator
import org.openwrench.frontend.android.DiagnosisPhase

internal enum class DashboardPage(val title: String) {
    DIAGNOSIS("Diagnosis"), DASHBOARD("Live Sensors"), HISTORY("History"), ADVANCED("Advanced"),
    SIGNALS("All signals"), SESSION("Connection settings"), DIAGNOSTICS("Diagnostic scan"),
    HEALTH("Health monitoring"), LEGACY_HISTORY("Individual records")
}

/** A single controller owns acquisition. Cards only render supplied presentation state. */
@Composable
fun DashboardApp(hardwareState: WatchUiState, rows: List<DashboardSignal>, source: DashboardSource,
    hardware: DashboardActions, pinStore: DashboardPinStore? = null, replay: CivicReplayPlayer? = null,
    actionEnabled: Boolean, onSource: () -> Unit, scan: DiagnosticScanView? = null,
    onScan: () -> Unit = {}, onWarningScan: () -> Unit = {}, vin: String? = null, vinDetail: String = "VIN has not been requested.",
    savedScanVin: String? = null, canReadVin: Boolean = false, onReadVin: () -> Unit = {}, onSaveScan: (Boolean) -> Unit = {},
    savedScans: List<SavedScan> = emptyList(), historyBusy: Boolean = false, historyAvailable: Boolean = true,
    historyError: String? = null,
    savedMessage: String? = null, onDeleteScan: (String) -> Unit = {},
    snapshotSimulated: Boolean = false,
    idleHealthReport: IdleHealthReport? = null, idleHealthPhase: IdleHealthPhase = IdleHealthPhase.IDLE,
    idleHealthElapsedMillis: Long = 0, idleHealthHistorical: Boolean = false,
    canStartIdleHealth: Boolean = false, onStartIdleHealth: () -> Unit = {},
    onCancelIdleHealth: () -> Unit = {},
    liveHealth: LiveHealthView? = null, canStartLiveHealth: Boolean = false,
    onStartLiveHealth: () -> Unit = {}, onStopLiveHealth: () -> Unit = {},
    driveHistory: DriveHistoryCoordinator? = null, caseHistory: org.openwrench.frontend.android.DiagnosticCaseCoordinator? = null, driveSaveStatus: String? = null,
    diagnosis: DiagnosisCoordinator? = null, onExportDiagnosis: (String) -> Unit = {},
    onAnalyzeDiagnosis: ((String) -> Unit)? = null,
    savedReplay: SavedDriveReplayPlayer? = null, onChooseDriveDemo: () -> Unit = {},
    onCloseDriveDemo: () -> Unit = {},
    aiStatus: @Composable () -> Unit = {},
    sessionControls: @Composable () -> Unit) {
    val replayState = replay?.state?.collectAsState()?.value
    val savedReplayState = savedReplay?.state?.collectAsState()?.value
    val replayMode = replay != null || savedReplay != null
    val demo = source == DashboardSource.DEMO
    val hardwareSource = source == DashboardSource.HARDWARE
    var navigation by rememberSaveable(stateSaver = DashboardBackStack.Saver) { mutableStateOf(DashboardBackStack()) }
    // A display preference, independent of destination and navigation history.
    var sidebarExpanded by rememberSaveable { mutableStateOf(false) }
    val page = navigation.current.page
    val pageState = rememberSaveableStateHolder()
    var retainedVisits by remember { mutableStateOf(emptySet<Long>()) }
    val visitIds = navigation.visits.map { it.id }.toSet()
    LaunchedEffect(visitIds) {
        (retainedVisits - visitIds).forEach(pageState::removeState)
        retainedVisits = visitIds
    }
    val diagnosisState = diagnosis?.state?.collectAsState()?.value
    val diagnosisActive = diagnosisState?.phase in setOf(DiagnosisPhase.STARTING, DiagnosisPhase.RECORDING,
        DiagnosisPhase.PAUSING, DiagnosisPhase.FINISHING)
    val primaryPages = listOf(DashboardPage.DIAGNOSIS, DashboardPage.DASHBOARD, DashboardPage.HISTORY, DashboardPage.ADVANCED)
    val primaryPage = if (page in primaryPages) page else DashboardPage.ADVANCED
    var selectedId by rememberSaveable(source, replayState?.generation, savedReplayState?.recording?.drive?.id) { mutableStateOf<String?>(null) }
    val inspectorCatalog = rows.map { it.definition.id }
    LaunchedEffect(source, inspectorCatalog) {
        if (selectedId != null && selectedId !in inspectorCatalog) selectedId = null
    }
    val preferences = pinStore ?: remember { MemoryDashboardPinStore() }
    val loadedPins = remember(source, preferences) { runCatching { preferences.load(source) } }
    var pins by remember(source, preferences, replayState?.recording, savedReplayState?.recording) { mutableStateOf(if (savedReplayState?.recording != null) {
        val ids = rows.map { it.definition.id }
        loadedPins.getOrNull().orEmpty().filter { it in ids }.ifEmpty { ids.take(4) }
    } else loadedPins.getOrNull() ?: if (replayMode) replayState?.recording?.catalog.orEmpty().map { it.id }.take(4) else if (demo) listOf("rpm", "coolant", "speed", "runtime") else if (source == DashboardSource.SNAPSHOT) rows.map { it.definition.id }.take(4) else emptyList()) }
    var pinError by remember(source, preferences) { mutableStateOf(if (loadedPins.isFailure) "Could not load saved pins. Displaying defaults; acquisition remains stopped." else null) }
    var pinsEdited by remember(source, preferences) { mutableStateOf(false) }
    LaunchedEffect(source, hardwareState.catalog) {
        if (hardwareSource && !replayMode && loadedPins.getOrNull() == null && !pinsEdited)
            pins = hardwareState.defaultLivePins()
    }
    val snapshotCatalogIds = if (source == DashboardSource.SNAPSHOT) rows.map { it.definition.id } else emptyList()
    LaunchedEffect(source, snapshotCatalogIds) {
        if (source == DashboardSource.SNAPSHOT)
            pins = snapshotPinsForCatalog(snapshotCatalogIds, pins, loadedPins.getOrNull(), pinsEdited)
    }
    var fuelTrimMessage by remember(source, hardwareState.connection) { mutableStateOf<String?>(null) }
    var fuelTrimCapacityDialog by remember(source, hardwareState.connection) { mutableStateOf(false) }
    val savePins: (List<String>) -> Unit = { next ->
        pinsEdited = true
        pins = next
        pinError = if (runCatching { preferences.save(source, next) }.isFailure) "Could not save pins. Changes apply only to this window." else null
    }
    val moveSelectedFirst: (() -> Unit)? = if (selectedId in pins && selectedId != pins.firstOrNull()) {
        { selectedId?.let { savePins(listOf(it) + (pins - it)) } }
    } else null
    val contentKey: Any? = when (source) {
        DashboardSource.CIVIC_REPLAY -> savedReplayState?.recording ?: replayState?.recording
        DashboardSource.SNAPSHOT -> rows
        else -> hardwareState.connection
    }
    val workspaceLayout = rememberWorkspaceLayoutPreferences()
    val liveDisplayState = rememberDashboardDisplay(source, contentKey, navigation.current.mode)
    val fullScreen = page == DashboardPage.DASHBOARD && liveDisplayState.fullScreen
    val exitFullScreen: () -> Unit = { liveDisplayState.exitFullScreen() }
    val navigate: (DashboardPage) -> Unit = { destination ->
        exitFullScreen()
        navigation = navigation.visit(destination, liveDisplayState.mode)
        selectedId = null
    }
    val navigateMode: (LiveDisplayMode) -> Unit = { mode ->
        liveDisplayState.mode = mode
        navigation = navigation.visit(DashboardPage.DASHBOARD, mode)
    }
    val replaySelection = savedReplayState?.takeIf { it.recording != null }?.let {
        "${it.recording!!.drive.id}:${it.selectionGeneration}"
    }
    LaunchedEffect(replaySelection) {
        val next = navigation.consumeReplaySelection(replaySelection)
        if (next.current != navigation.current) {
            exitFullScreen(); selectedId = null; liveDisplayState.mode = next.current.mode
        }
        navigation = next
    }
    // Child handlers/dialog windows take precedence. At the actual root Android owns Back.
    BackHandler(enabled = fullScreen || selectedId != null || navigation.canGoBack) {
        when {
            selectedId != null -> selectedId = null
            fullScreen -> exitFullScreen()
            else -> {
                navigation = navigation.back()
                liveDisplayState.mode = navigation.current.mode
            }
        }
    }
    // Navigation away ends the presentation, but does not alter display choices or acquisition.
    LaunchedEffect(page) { if (page != DashboardPage.DASHBOARD) exitFullScreen() }
    val canChangeSource = !hardwareState.busy && !hardwareState.cleanupUncertain && hardwareState.connection != HardwareConnection.CONNECTED
    val connectionAction: () -> Unit = {
        if (hardwareSource) when {
            hardwareState.cleanupUncertain && !hardwareState.busy -> hardware.retryCleanup()
            hardwareState.connection in setOf(HardwareConnection.CONNECTED, HardwareConnection.CONNECTING, HardwareConnection.DISCOVERING) -> hardware.disconnect()
            actionEnabled && !hardwareState.busy -> hardware.connect()
        }
    }
    val dashboardFocus = remember { FocusRequester() }
    // Inspector/layout transitions can dispose the focused card; retain workspace shortcuts.
    LaunchedEffect(page, selectedId, pins, fullScreen) { dashboardFocus.requestFocus() }
    DiagnosticTheme {
        if (fuelTrimCapacityDialog) AlertDialog(onDismissRequest = { fuelTrimCapacityDialog = false },
            title = { Text("Make room for fuel trims") },
            text = { Text("The live read plan, including Vehicle Health inputs, is limited to $MAX_LIVE_SIGNALS signals. Stop polling in All signals and unselect a few signals, then tap Show fuel trims again. Your current selection has been kept.") },
            confirmButton = { TextButton(onClick = { fuelTrimCapacityDialog = false; navigate(DashboardPage.SIGNALS); selectedId = null }) { Text("Open All signals") } },
            dismissButton = { TextButton(onClick = { fuelTrimCapacityDialog = false }) { Text("Cancel") } })
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when {
                event.key == Key.Escape && selectedId != null -> { selectedId = null; true }
                event.key == Key.Escape && fullScreen -> { exitFullScreen(); true }
                event.isAltPressed && event.key == Key.C && hardwareSource && !replayMode -> {
                    connectionAction(); true
                }
                event.isAltPressed && event.key == Key.S && hardwareSource && !replayMode && !diagnosisActive -> {
                    if (hardwareState.acquisition in setOf(Acquisition.RUNNING, Acquisition.STOP_UNCONFIRMED)) hardware.stopLive()
                    else hardware.startLive()
                    true
                }
                event.isAltPressed && event.key == Key.X && hardwareSource && !replayMode -> { hardware.disconnect(); true }
                event.isAltPressed && event.key in listOf(Key.One, Key.Two, Key.Three) -> {
                    navigate(primaryPages[listOf(Key.One, Key.Two, Key.Three).indexOf(event.key)])
                    selectedId = null; true
                }
                else -> false
            }
        }.focusRequester(dashboardFocus).focusable(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val fontScale = LocalDensity.current.fontScale
                val statusMaximumHeight = maxHeight * .45f
                val wide = maxWidth.value / fontScale.coerceAtLeast(1f) >= 1000
                val navigationPlacement = appNavigationPlacement(maxWidth.value, fontScale, fullScreen)
                val rail = navigationPlacement == AppNavigationPlacement.SIDEBAR
                Row(Modifier.fillMaxSize()) {
                    if (rail && !fullScreen) WorkspaceNavigation(primaryPages.indexOf(primaryPage),
                        expanded = sidebarExpanded, onToggleExpanded = { sidebarExpanded = !sidebarExpanded },
                        onSelect = { index ->
                            navigate(primaryPages[index]); selectedId = null
                        })
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        aiStatus()
                        if (fullScreen) FullscreenTelemetryStatus(source, hardwareState, rows,
                            diagnosisState?.phase, liveDisplayState.frozen != null, snapshotSimulated, statusMaximumHeight)
                        if (savedReplay != null && savedReplayState != null) SavedDriveReplayControls(savedReplayState, savedReplay,
                            onChooseDriveDemo, onCloseDriveDemo,
                            onRestart = { liveDisplayState.frozen = null; selectedId = null; savedReplay.restart() })
                        else if (!fullScreen && replay != null && replayState != null) ReplayControls(replayState, replay)
                        else if (!fullScreen && page != DashboardPage.DASHBOARD) SourceStrip(source, hardwareState, true, canChangeSource,
                            pageTitle = page.title, demoTimed = rows.any { it.reason == SYNTHETIC_TRACE_REASON },
                            snapshotSimulated = snapshotSimulated,
                            onSource = onSource, compact = !rail, scripted = false,
                            actionEnabled = actionEnabled, onAction = connectionAction,
                            showLiveControls = page in setOf(DashboardPage.DASHBOARD, DashboardPage.SIGNALS) && !diagnosisActive, onSettings = { navigate(DashboardPage.SESSION); selectedId = null }, onLiveAction = {
                                if (hardwareState.acquisition in setOf(Acquisition.RUNNING, Acquisition.STOP_UNCONFIRMED)) hardware.stopLive()
                                else hardware.startLive()
                            })
                        pinError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.tertiary) }
                        HorizontalDivider()
                        Row(Modifier.weight(1f)) {
                            val selected = rows.firstOrNull { it.definition.id == selectedId }
                            if (selected != null && !wide) {
                                SignalDetails(selected, source, selectedId in pins,
                                    onBack = { selectedId = null }, onPin = {
                                        savePins(if (selectedId in pins) pins - selectedId!! else pins + selectedId!!)
                                    }, onMoveFirst = moveSelectedFirst, modifier = Modifier.weight(1f))
                            } else {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    pageState.SaveableStateProvider(navigation.current.id) {
                                    when (page) {
                                        DashboardPage.DIAGNOSIS -> DiagnosisPage(diagnosisState, source, liveHealth,
                                            canStart = hardwareSource && !replayMode && hardwareState.connection == HardwareConnection.CONNECTED &&
                                                !hardwareState.busy && !hardwareState.cleanupUncertain && hardwareState.acquisition != Acquisition.STOP_UNCONFIRMED,
                                            hardwareState = hardwareState, adapterSelected = actionEnabled,
                                            onConnect = hardware.connect,
                                            onStart = { diagnosis?.start() }, onPause = { diagnosis?.pause() }, onFinish = { diagnosis?.finish() },
                                            onResume = { id, confirmed -> diagnosis?.resume(id, confirmed) }, onRetry = { diagnosis?.refresh() },
                                            onExport = onExportDiagnosis, onAnalyze = onAnalyzeDiagnosis, onSettings = { navigate(DashboardPage.SESSION) },
                                            loadDrive = { id, driveId -> diagnosis?.readDriveEvidence(id, driveId) })
                                        DashboardPage.HISTORY -> DiagnosisHistoryPage(diagnosisState,
                                            onOpen = { diagnosis?.open(it); navigate(DashboardPage.DIAGNOSIS) }, onRetry = { diagnosis?.refresh() },
                                            onIndividualRecords = { navigate(DashboardPage.LEGACY_HISTORY) })
                                        DashboardPage.ADVANCED -> AdvancedDiagnosticsPage(
                                            onSettings = { navigate(DashboardPage.SESSION) }, onSignals = { navigate(DashboardPage.SIGNALS) },
                                            onScan = { navigate(DashboardPage.DIAGNOSTICS) }, onHealth = { navigate(DashboardPage.HEALTH) },
                                            onRecords = { navigate(DashboardPage.LEGACY_HISTORY) })
                                        DashboardPage.DASHBOARD -> InstrumentDashboard(rows, pins, workspaceLayout = workspaceLayout, onSelect = { selectedId = it },
                                            source = source, displayState = liveDisplayState, onChoose = { navigate(DashboardPage.SIGNALS) }, customOrder = loadedPins.getOrNull() != null || pinsEdited,
                                            onPin = { id -> savePins(if (id in pins) pins - id else pins + id) },
                                            onFuelTrims = if (hardwareSource && !replayMode && hardware.prepareFuelTrims != null) ({
                                                val result = hardware.prepareFuelTrims.invoke()
                                                if (result == ControllerAdmission.ACCEPTED) savePins(fuelTrimPins(rows, pins))
                                                fuelTrimMessage = when (result) {
                                                    ControllerAdmission.ACCEPTED -> "Live fuel trims selected and pinned. Waiting for actual adapter readings; Vehicle Health continues if running."
                                                    ControllerAdmission.SELECTION_FULL -> {
                                                        fuelTrimCapacityDialog = true
                                                        "All supported trims need free read slots. Open All signals to adjust the $MAX_LIVE_SIGNALS-signal read plan."
                                                    }
                                                    ControllerAdmission.NO_INPUTS -> "This vehicle does not advertise supported fuel-trim inputs."
                                                    else -> "Connect and finish any active operation before showing fuel trims."
                                                }
                                            }) else null, fuelTrimMessage = fuelTrimMessage,
                                            shell = WorkspaceShellActions(
                                                status = when {
                                                    savedReplayState?.recording != null -> "REPLAY · " + savedReplayState.recording!!.drive.replayOriginLabel() + " · not live"
                                                    source == DashboardSource.HARDWARE -> (if (hardwareState.cleanupUncertain) "RELEASE UNCONFIRMED · " else if (hardwareState.busy) "BUSY · " else "") + hardwareState.connection.name + " · " + hardwareState.acquisition.name.replace('_', ' ')
                                                    snapshotSimulated -> "SYNTHETIC SNAPSHOT · not live"
                                                    else -> sourceBadgeLabel(source)
                                                }, detail = source.label + "\n" + hardwareState.detail,
                                                showHeaderBrand = !rail,
                                                recordLabel = if (diagnosisActive) "Diagnosis" else "Record",
                                                onRecord = { navigate(DashboardPage.DIAGNOSIS); selectedId = null },
                                                onReplay = onSource, onNavigate = navigate, onMode = navigateMode, diagnosticScan = scan,
                                                onConnection = connectionAction,
                                                canConnect = hardwareSource && hardwareState.canUseConnectionAction(actionEnabled),
                                                connectionLabel = if (hardwareState.connection == HardwareConnection.CONNECTED) "Disconnect" else "Connection / retry", canReplay = canChangeSource))
                                        DashboardPage.SIGNALS -> SignalBrowser(rows, if (!hardwareSource || replayMode) null else hardwareState,
                                            pins = pins, onPin = { id -> savePins(if (id in pins) pins - id else pins + id) },
                                            onAcquisition = { id, enabled -> hardware.select(id, enabled) },
                                            onPinSelection = { savePins((pins + hardwareState.selection).distinct()) },
                                            onSelectAll = { hardware.selectAllSupported() }, onClear = { hardware.clearSelection() }, onSelect = { selectedId = it })
                                        DashboardPage.SESSION -> SessionDetails(source, hardwareState, replayState, sessionControls)
                                        DashboardPage.DIAGNOSTICS -> if (scan != null) DiagnosticScanPage(source, scan, onScan,
                                            vin, vinDetail, canReadVin, onReadVin, onSaveScan, historyBusy,
                                            historyAvailable, historyError, savedMessage, onWarningScan,
                                            hardwareState.acquisition == Acquisition.RUNNING, hardware.stopLive, savedScanVin)
                                            else Text("Diagnostic scan is unavailable from this source.")
                                        DashboardPage.HEALTH -> VehicleHealthPage(source, idleHealthPhase,
                                            idleHealthElapsedMillis, idleHealthReport, idleHealthHistorical,
                                            canStartIdleHealth, onStartIdleHealth, onCancelIdleHealth,
                                            liveHealth, canStartLiveHealth, onStartLiveHealth, onStopLiveHealth, driveSaveStatus)
                                        DashboardPage.LEGACY_HISTORY -> HistoryWorkspace(driveHistory, caseHistory,
                                            selected = navigation.current.recordsTab,
                                            onSelect = { navigation = navigation.visit(DashboardPage.LEGACY_HISTORY, recordsTab = it) }) {
                                            ScanHistoryPage(savedScans, historyBusy, historyAvailable, historyError, onDeleteScan)
                                        }
                                    }
                                    }
                                }
                                if (wide && selected != null) SignalDetails(selected, source, selectedId in pins,
                                    onBack = { selectedId = null }, onPin = {
                                        savePins(if (selectedId in pins) pins - selectedId!! else pins + selectedId!!)
                                    }, onMoveFirst = moveSelectedFirst, modifier = Modifier.width(420.dp))
                            }
                        }
                        if (navigationPlacement == AppNavigationPlacement.PHONE_DOCK) PhoneNavigation(primaryPages, primaryPage) {
                            navigate(it); selectedId = null
                        }
                    }
                }
            }
        }
    }
}

/** Icon-only 56dp dock; the enclosing safeDrawingPadding owns system insets. */
@Composable
private fun PhoneNavigation(pages: List<DashboardPage>, selected: DashboardPage, onSelect: (DashboardPage) -> Unit) {
    fun icon(page: DashboardPage) = when (page) {
        DashboardPage.DIAGNOSIS -> WorkspaceIcon.CAR
        DashboardPage.DASHBOARD -> WorkspaceIcon.WAVE
        DashboardPage.HISTORY -> WorkspaceIcon.HISTORY
        else -> WorkspaceIcon.SETTINGS
    }
    Surface(color = DashboardNavigation, modifier = Modifier.fillMaxWidth()
        .semantics { contentDescription = "App navigation" }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            val columns = phoneNavigationColumns(maxWidth.value)
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                pages.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { destination ->
                            val active = selected == destination
                            Box(Modifier.weight(1f).height(48.dp)
                                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(destination) })
                                .semantics { contentDescription = destination.title },
                                contentAlignment = Alignment.Center) {
                                Box(Modifier.size(48.dp, 40.dp).background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center) {
                                    WorkspaceIcon(icon(destination),
                                        if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplayControls(state: CivicReplayState, player: CivicReplayPlayer) {
    Column(Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(state.recording?.capture?.vehicleLabel ?: "Recorded playback", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Original UTC: " + (state.recording?.capture?.capturedAtUtc ?: "not supplied"), fontSize = 9.sp, color = DashboardMuted)
            }
            Column {
                Text("• RECORDED · NOT LIVE", fontSize = 12.sp, color = TelemetryInk)
                Text(state.phase.name.lowercase() + " · " + state.positionMillis + " / " + (state.recording?.durationMillis ?: 0) + " ms · loop " + (state.loop + 1), fontSize = 9.sp, color = DashboardMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.play() }, enabled = state.phase == ReplayPhase.PAUSED,
                    modifier = Modifier.height((32 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) { Text("Play", fontSize = 11.sp) }
                OutlinedButton(onClick = { player.pause() }, enabled = state.phase == ReplayPhase.PLAYING,
                    modifier = Modifier.height((32 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) { Text("Pause", fontSize = 11.sp) }
                OutlinedButton(onClick = { player.restart() }, enabled = state.recording != null,
                    modifier = Modifier.height((32 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) { Text("Restart", fontSize = 11.sp) }
            }
        }
        if (state.recording == null) Text(state.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceStrip(source: DashboardSource, state: WatchUiState, hasHardware: Boolean,
    canChange: Boolean, compact: Boolean, scripted: Boolean, actionEnabled: Boolean, pageTitle: String,
    snapshotSimulated: Boolean = false, demoTimed: Boolean = false, onSource: () -> Unit, onAction: () -> Unit,
    onLiveAction: () -> Unit, onSettings: () -> Unit, showLiveControls: Boolean = true) {
    val demo = source == DashboardSource.DEMO
    val snapshot = source == DashboardSource.SNAPSHOT
    val hardwareSource = source == DashboardSource.HARDWARE
    val opening = state.connection in setOf(HardwareConnection.CONNECTING, HardwareConnection.DISCOVERING)
    val action = when {
        state.cleanupUncertain -> "Retry release"
        state.connection == HardwareConnection.CONNECTED -> "Disconnect"
        opening -> "Cancel connection"
        state.busy -> "Releasing…"
        state.connection == HardwareConnection.FAULTED -> "Reconnect"
        else -> "Connect"
    }
    var expanded by remember(source) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(DashboardNavigation)) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(max = if (compact) 240.dp else 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pageTitle, style = MaterialTheme.typography.titleLarge)
                Text(when {
                    demo -> "Demo · fictional values"
                    snapshot && snapshotSimulated -> "Simulator snapshot · historical"
                    snapshot -> "Saved snapshot · historical"
                    else -> "${state.connection.name.lowercase().replaceFirstChar(Char::uppercase)} · read only"
                }, style = MaterialTheme.typography.labelMedium, color = TelemetryInk)
                if (hardwareSource) Text(state.acquisition.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow {
                TextButton(onClick = onSource, enabled = canChange) { Text("Demo a saved drive") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide connection" else "Connection") }
            }
        }
        if (!expanded && hardwareSource && (state.busy || state.cleanupUncertain || state.connection == HardwareConnection.FAULTED || state.acquisition == Acquisition.STOP_UNCONFIRMED)) {
            Text(state.detail, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.width(if (compact) 220.dp else 232.dp)) {
                            Text(when { demo -> "Vehicle not identified / Demo"; snapshot && snapshotSimulated -> "Imported simulator snapshot"; snapshot -> "Saved snapshot / Historical"; else -> "Vehicle not identified" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(when { demo -> "VIN not supplied · fictional demo profile"; snapshot && snapshotSimulated -> "Simulated historical readings · no vehicle connected"; snapshot -> "Original source preserved · no vehicle connected"; else -> "Identity details in Session · read-only session" }, fontSize = 9.sp, color = DashboardMuted)
                        }
                        Column(Modifier.widthIn(max = if (compact) 260.dp else 350.dp)) {
                            Text("• " + when { demo -> "Demo adapter / offline examples"; snapshot && snapshotSimulated -> "Simulated / historical / not live"; snapshot -> "Historical / not live"; else -> state.connection.name.lowercase() + " / " + state.acquisition.name.lowercase().replace('_', ' ') },
                                fontSize = 12.sp, color = if (demo || state.connection == HardwareConnection.CONNECTED) MaterialTheme.colorScheme.onSurface else DashboardAmber)
                            Text(when { demo && demoTimed -> "Synthetic timed receipts · intentional gaps · no vehicle"; demo -> "Fictional static values · observation timing unavailable"; snapshot -> "Saved readings · no live acquisition"; else -> state.selection.size.toString() + "/$MAX_LIVE_SIGNALS selected · no automatic reconnect" },
                                fontSize = 9.sp, color = DashboardMuted)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (hardwareSource) {
                            Button(onClick = onAction, shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                enabled = state.canUseConnectionAction(adapterSelected = actionEnabled)) { Text(action, fontSize = 11.sp) }
                            if (showLiveControls && state.connection == HardwareConnection.CONNECTED) OutlinedButton(onClick = onLiveAction,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                enabled = !state.busy && !state.cleanupUncertain &&
                                    (state.acquisition in setOf(Acquisition.RUNNING, Acquisition.STOP_UNCONFIRMED) || state.selection.isNotEmpty())) {
                                Text(when (state.acquisition) {
                                    Acquisition.RUNNING -> "Stop live data"
                                    Acquisition.STOP_UNCONFIRMED -> "Retry Stop"
                                    Acquisition.STARTING -> "Starting…"
                                    Acquisition.STOPPING -> "Stopping…"
                                    else -> "Start live data"
                                }, fontSize = 11.sp)
                            }
                        } else {
                            SourceBadge(source)
                            if (demo) Text("• RECORDER UNAVAILABLE", Modifier.border(1.dp, DashboardBorder, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hasHardware) TextButton(onClick = onSource, enabled = canChange) { Text(if (scripted) "Use demo" else "Demo a saved drive", fontSize = 10.sp) }
                        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Session settings" }) {
                            WorkspaceIcon(WorkspaceIcon.SETTINGS, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(17.dp))
                        }
                    }
                }
                if (hardwareSource && (state.busy || state.cleanupUncertain || state.connection == HardwareConnection.FAULTED || state.acquisition == Acquisition.STOP_UNCONFIRMED)) {
                    Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalBrowser(rows: List<DashboardSignal>, acquisition: WatchUiState?, pins: List<String>, onPin: (String) -> Unit, onAcquisition: (String, Boolean) -> Unit,
    onPinSelection: () -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onSelect: (String) -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var support by remember { mutableStateOf<SignalSupport?>(null) }
    var availability by remember { mutableStateOf<SignalAvailability?>(null) }
    var category by remember { mutableStateOf<SignalCategory?>(null) }
    val filtered = filterSignals(rows, search, support, availability).filter { category == null || it.definition.category() == category }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text("All signals", style = MaterialTheme.typography.headlineMedium) }
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search name, identifier, PID or unit") }, singleLine = true) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu("Category", category?.name ?: "All", listOf(null) + SignalCategory.entries, { it?.name ?: "All" }) { category = it }
                ChoiceMenu("Support", support?.name ?: "All", listOf(null) + SignalSupport.entries, { it?.name ?: "All" }) { support = it }
                ChoiceMenu("Availability", availability?.name ?: "All", listOf(null) + SignalAvailability.entries, { it?.name ?: "All" }) { availability = it }
            }
        }
        item { Text("${filtered.size} of ${rows.size} signals · opening details never requests a vehicle read", style = MaterialTheme.typography.bodySmall) }
        if (rows.any { it.retainedReplay }) item { Text(retainedReplayCatalogSummary(rows), style = MaterialTheme.typography.bodySmall) }
        item {
            TextButton(onClick = { search = ""; support = null; availability = null; category = null }) { Text("Reset filters / show all PIDs") }
        }
        if (acquisition != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide acquisition help" else "How to display live PIDs") }
                Text(if (acquisition.connection != HardwareConnection.CONNECTED) "Support unknown: Connect to discover the current session catalog. Any listed definitions are from the previous session." else "Support comes from this session's standard-parameter catalog. Unsupported entries are never requested.")
                if (showHelp) {
                    Text("All supported numeric sensors are selected on connection. Stop before changing selection, then Start explicitly. RPM keeps priority while every selected sensor gets a turn. More sensors can mean slower updates or stale readings; receipt ages show actual timing. Structured status data remain in scans and Vehicle Health.")
                    Text("Pinning changes the dashboard, not acquisition. Diagnosis temporarily reads all supported numeric sensors regardless of this selection; ordinary health monitoring adds its supported inputs.")
                    Text("Catalog coverage: standard Mode 01 scalar parameters decoded on this Android device. Structured readings are available in snapshots.", style = MaterialTheme.typography.bodySmall)
                }
                val count = acquisition.availableSignalCount()
                if (showHelp) Text((count?.let { "$it supported scalar signals. " } ?: "Supported scalar count unknown until connected. ") + "Fast signals receive more polling time, while every selected signal gets a turn. Open a signal to see its measured update interval. Values expire after 5 seconds; signals returning NO DATA are checked less often so they do not hold up the others.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelectAll, enabled = acquisition.canEditSelection() && count != null && count in 1..MAX_LIVE_SIGNALS) { Text(count?.let { "Select all ($it)" } ?: "Select all available") }
                    OutlinedButton(onClick = onClear, enabled = acquisition.canEditSelection() && acquisition.selection.isNotEmpty()) { Text("Clear selection") }
                    OutlinedButton(onClick = onPinSelection, enabled = acquisition.selection.isNotEmpty()) { Text("Pin selected") }
                }
                if (count != null && count > MAX_LIVE_SIGNALS) Text("Select All unavailable: limit is $MAX_LIVE_SIGNALS signals per watch. Choose up to $MAX_LIVE_SIGNALS manually; no silent rotation or truncation.")
                Text("${acquisition.selection.size}/$MAX_LIVE_SIGNALS selected · Read selects acquisition; Pin changes the dashboard.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(filtered, key = { it.definition.id }) { row ->
            SignalBrowserRow(row, row.definition.id in pins, acquisition,
                onPin = { onPin(row.definition.id) },
                onAcquisition = { onAcquisition(row.definition.id, it) },
                onSelect = { onSelect(row.definition.id) })
        }
        if (filtered.isEmpty()) item { Text(if (acquisition != null && acquisition.catalog.isEmpty()) "No capability catalog loaded. Connect explicitly to discover signals; no vehicle-specific list is assumed." else "No signals match these filters.") }
    }
}

@Composable
private fun <T> ChoiceMenu(label: String, selected: String, choices: List<T>, title: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${selected.lowercase().replace('_', ' ')}") }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(title(choice).lowercase().replace('_', ' ')) }, onClick = { onSelect(choice); expanded = false }) }
        }
    }
}

@Composable
private fun SignalDetails(row: DashboardSignal, source: DashboardSource, pinned: Boolean, onBack: () -> Unit, onPin: () -> Unit,
    onMoveFirst: (() -> Unit)?, modifier: Modifier) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Close details") }
        Text(row.definition.label, style = MaterialTheme.typography.headlineSmall)
        ReadingSummary(row)
        Detail("Observed update interval", row.cadenceMillis?.let { "${it / 1000}.${(it % 1000) / 100} s" } ?: "Unknown")
        Detail("Original observation (UTC)", row.observedAt ?: "Not supplied")
        Text("Observation history", style = MaterialTheme.typography.titleMedium)
        SignalGraph(row, expanded = true)
        OutlinedButton(onClick = onPin) { Text(if (pinned) "Unpin reading" else "Pin reading") }
        if (onMoveFirst != null) OutlinedButton(onClick = onMoveFirst) { Text("Move to first") }
        Detail("Source", source.label)
        Detail("Identifier", row.definition.id)
        Detail("PID", row.definition.pid ?: "Not supplied")
        Detail("Module", row.definition.module ?: "Not supplied")
        Detail("Value type", row.definition.type.name.lowercase())
        Detail("Exact supplied value", row.value?.display() ?: "Not available")
        Detail("Support", workspaceSupportLabel(row, source == DashboardSource.CIVIC_REPLAY))
        Detail("Reason", row.reason)
        Text("Keyboard: Tab to move, Enter/Space to activate, Esc to close details. Alt+1/2/3: Diagnosis/Live sensors/History. Alt+C: connection action; Alt+S: Start/Stop; Alt+X: Disconnect. No diagnostic conclusion is inferred from this value.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun SessionDetails(source: DashboardSource, state: WatchUiState, replay: CivicReplayState?, controls: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Session", style = MaterialTheme.typography.headlineMedium)
        Detail("Selected source", source.label)
        if (replay != null) {
            Detail("Original vehicle label", replay.recording?.capture?.vehicleLabel ?: "No recording loaded")
            Detail("Original capture UTC", replay.recording?.capture?.capturedAtUtc ?: "Not supplied")
            Detail("Capture clock basis", replay.recording?.capture?.clockBasis ?: "Not supplied")
            Detail("Capture provenance", replay.recording?.capture?.provenance ?: "Not supplied")
            Detail("Playback position", "${replay.positionMillis} ms; loop ${replay.loop + 1}")
            Text("Playback timing is separate from original capture metadata. No adapter is opened; playback is not live or physical qualification.")
        } else {
            Detail("Hardware session", state.connection.name.lowercase())
            Detail("Session detail", state.detail)
            Detail("Last session catalog", state.sessionCatalogSummary())
            Detail("Acquisition", state.acquisition.name.lowercase())
        }
        controls()
    }
}
