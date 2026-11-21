package org.openwrench.frontend.android

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import org.openwrench.frontend.*
import org.openwrench.frontend.android.transport.*

/** Process-wide ownership survives Activity recreation and retains failed-cleanup gates. */
private object NativeRuntime {
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller = NativeController(uiScope)
    val replay = CivicReplayPlayer(uiScope)
    var timedReplay by mutableStateOf(false)
    val driveReplay = SavedDriveReplayPlayer(uiScope)
    var importedDrives by mutableStateOf<List<SavedDrive>>(emptyList())
    var driveDemoLoading by mutableStateOf(false)
    var driveDemoError by mutableStateOf<String?>(null)
    val importRequests = ImportRequestGate()
    var pendingUsbId: Int? = null
    var pendingUsbBaud: Int = UsbConnectionSettings.AUTO_BAUD
    var pendingUsbLines: UsbControlLines = UsbControlLines.ASSERTED
    val usbDiagnostics = UsbDiagnostics()
    var driveHistory: DriveHistoryCoordinator? = null
    var diagnosis: DiagnosisCoordinator? = null
    var diagnosisExportId: String? = null
    var caseHistory: DiagnosticCaseCoordinator? = null
    var scanHistory: ScanHistoryCoordinator? = null
    var scanAutoSaveJob: Job? = null
    var driveStore: DriveHistoryStore? = null
    var scanStore: ScanHistoryStore? = null
    var exportDocument: String? = null
}

class MainActivity : ComponentActivity() {
    private val uiScope get() = NativeRuntime.uiScope
    private val controller get() = NativeRuntime.controller
    private var mode by mutableStateOf(AdapterMode.DEMO)
    private var options by mutableStateOf<List<AdapterOption>>(emptyList())
    private var selected by mutableStateOf<String?>(null)
    private var baud by mutableIntStateOf(UsbConnectionSettings.AUTO_BAUD)
    private var usbControlLines by mutableStateOf(UsbControlLines.ASSERTED)
    private var scenario by mutableStateOf(DemoScenario.COMPLETE)
    private var pendingUsbId: Int?
        get() = NativeRuntime.pendingUsbId
        set(value) { NativeRuntime.pendingUsbId = value }
    private var started = false
    private var adapterRefreshJob: Job? = null
    private var adapterMemory = AdapterSelectionMemory()
    private fun saveAdapterMemory(value: AdapterSelectionMemory) {
        adapterMemory = value
        getSharedPreferences("adapter-selection-v1", MODE_PRIVATE).edit()
            .putString("selection", AdapterSelectionMemoryCodec.encode(value)).apply()
    }
    private var exportDocument: String?
        get() = NativeRuntime.exportDocument
        set(value) { NativeRuntime.exportDocument = value }
    private val usbAction get() = "$packageName.USB_PERMISSION"

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refresh() else controller.showMessage("Nearby devices permission was denied. Grant it in Android Settings or use USB/demo.")
    }
    private val saveSnapshot = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val document = exportDocument
        exportDocument = null
        if (uri != null && document == null) controller.showMessage("Export was interrupted. Export the snapshot again.")
        if (uri != null && document != null) uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(document) }
                }
                controller.showMessage("Snapshot exported.")
            } catch (_: Exception) { controller.showMessage("Snapshot export failed. The destination may contain an incomplete file.") }
        }
    }
    private val saveDiagnosis = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = NativeRuntime.diagnosisExportId
        NativeRuntime.diagnosisExportId = null
        if (uri != null && id == null) controller.showMessage("Export was interrupted. Export the diagnosis again.")
        if (uri != null && id != null) uiScope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                }
                output.use { checkNotNull(NativeRuntime.diagnosis).export(id, it) }
                Toast.makeText(this@MainActivity, "Diagnosis archive exported. Available evidence and coverage notes included; VIN omitted.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Diagnosis export failed. The destination may contain an incomplete file; export again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Some Android builds have no working ACTION_CREATE_DOCUMENT handler. */
    private fun saveDiagnosisToDownloads(id: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "No document picker is available. Enable a Files app to export this diagnosis.", Toast.LENGTH_LONG).show()
            return
        }
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val name = "openwrench-diagnosis-$id.zip"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Downloads is unavailable")
                    try {
                        val output = contentResolver.openOutputStream(uri, "w") ?: error("Cannot open Downloads file")
                        output.use { checkNotNull(NativeRuntime.diagnosis).export(id, it) }
                        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        check(contentResolver.update(uri, published, null, null) == 1) { "Could not publish Downloads file" }
                    } catch (failure: Exception) {
                        runCatching { contentResolver.delete(uri, null, null) }
                        throw failure
                    }
                }
                Toast.makeText(this@MainActivity, "Diagnosis ZIP saved to Downloads. VIN omitted.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Diagnosis export failed. Check free storage and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun cancelImportRequest() {
        NativeRuntime.importRequests.cancel()
        NativeRuntime.driveDemoLoading = false
    }

    private fun beginImportRequest(): Long {
        NativeRuntime.driveDemoLoading = false
        return NativeRuntime.importRequests.begin()
    }

    private fun clearDriveDemoImport() {
        NativeRuntime.driveDemoLoading = false
        NativeRuntime.driveDemoError = null
        NativeRuntime.importedDrives = emptyList()
    }

    private fun cancelDriveDemoRequest() {
        cancelImportRequest()
        clearDriveDemoImport()
    }

    private fun startDriveDemo(read: () -> SavedDrive) {
        if (!canStartDriveReplay(controller.state.value)) return
        val request = beginImportRequest()
        NativeRuntime.driveDemoLoading = true
        NativeRuntime.driveDemoError = null
        uiScope.launch {
            try {
                val recording = withContext(Dispatchers.IO) { SavedDrivePlayback.from(read()) }
                NativeRuntime.importRequests.complete(request) {
                    check(canStartDriveReplay(controller.state.value)) { "Disconnect and finish pending operations before replay." }
                    NativeRuntime.replay.close(); NativeRuntime.timedReplay = false
                    controller.disconnect()
                    NativeRuntime.driveReplay.load(recording)
                    NativeRuntime.importedDrives = emptyList()
                    NativeRuntime.driveReplay.play()
                }
            } catch (_: Exception) {
                if (NativeRuntime.importRequests.isCurrent(request)) NativeRuntime.driveDemoError =
                    "This drive cannot be replayed. It needs repeated retained numeric receipts. Check that the file is valid and no adapter is connected. Saved history was not changed."
            } finally {
                if (NativeRuntime.importRequests.isCurrent(request)) NativeRuntime.driveDemoLoading = false
            }
        }
    }

    private val loadDriveDemo = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::importDriveDemo)

    private fun importDriveDemo(uri: Uri?) {
        if (uri == null || !canStartDriveReplay(controller.state.value)) return
        val request = beginImportRequest()
        NativeRuntime.driveDemoLoading = true
        NativeRuntime.driveDemoError = null
        uiScope.launch {
            try {
                val drives = withContext(Dispatchers.IO) {
                    ImportFileReader.drives { contentResolver.openInputStream(uri) }
                }
                NativeRuntime.importRequests.complete(request) {
                    check(canStartDriveReplay(controller.state.value))
                    NativeRuntime.importedDrives = drives
                }
            } catch (_: Exception) {
                if (NativeRuntime.importRequests.isCurrent(request)) NativeRuntime.driveDemoError =
                    "Could not import this recording. Choose an OpenWrench drive-history JSON (up to 2 MB) or diagnosis ZIP (up to 25 MB). No saved history was changed."
            } finally {
                if (NativeRuntime.importRequests.isCurrent(request)) NativeRuntime.driveDemoLoading = false
            }
        }
    }

    private val loadSnapshot = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::importSnapshot)

    private fun importSnapshot(uri: Uri?) {
        if (uri == null) return
        val request = beginImportRequest()
        uiScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    ImportFileReader.snapshotText { contentResolver.openInputStream(uri) }
                }
                // Both strict codecs bound nesting before constructing a JSON tree.
                val imported = withContext(Dispatchers.IO) {
                    val snapshot = runCatching { SnapshotCodec.decode(document) }.getOrNull()
                    if (snapshot != null) snapshot to null
                    else {
                        val recording = readCivicRecordingText(document)
                        require(recording is ReplayLoadResult.Loaded)
                        null to recording
                    }
                }
                NativeRuntime.importRequests.complete(request) {
                    val current = controller.state.value
                    check(!current.busy && !current.connected && !current.cleanupUncertain)
                    clearDriveDemoImport()
                    NativeRuntime.driveReplay.close()
                    val snapshot = imported.first
                    if (snapshot != null) {
                        NativeRuntime.replay.close(); NativeRuntime.timedReplay = false
                        controller.replay(snapshot.readings, snapshot.identity, snapshot.simulated, snapshot.vin)
                    } else {
                        controller.disconnect()
                        NativeRuntime.replay.load(checkNotNull(imported.second))
                        NativeRuntime.timedReplay = true
                    }
                }
            } catch (_: Exception) {
                if (NativeRuntime.importRequests.isCurrent(request)) {
                    controller.showMessage("Recording is unreadable, malformed, or incompatible. No data was loaded.")
                }
            }
        }
    }

    // Keep the original registrations in order and append unconditional fallback registrations.
    // Both routes use the same readers and null/admission/request checks after recreation.
    private val loadDriveDemoFallback = registerForActivityResult(OpenableContent(), ::importDriveDemo)
    private val loadSnapshotFallback = registerForActivityResult(OpenableContent(), ::importSnapshot)

    private fun openImportPicker(
        primary: ActivityResultLauncher<Array<String>>,
        fallback: ActivityResultLauncher<Array<String>>,
        mimeTypes: Array<String>,
    ): Boolean = launchImportPicker(
        launch = { route ->
            when (route) {
                ImportPickerRoute.DOCUMENT -> primary.launch(mimeTypes)
                ImportPickerRoute.CONTENT -> fallback.launch(mimeTypes)
            }
        },
        onFailure = { route, failure ->
            // No exception message/stack, URI, filename, VIN or telemetry in this diagnostic.
            Log.w("OpenWrenchImport", "Picker launch failed: $route (${failure.javaClass.simpleName})")
        },
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                if (started) refresh(requestPermission = false)
                return
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // A disappearing selected device invalidates the owner, never triggers a reconnect.
                val usb = getSystemService(UsbManager::class.java)
                val attached = usb.deviceList.values.map { it.deviceId.toString() }.toSet()
                if (pendingUsbId?.toString()?.let { it !in attached } == true) pendingUsbId = null
                if (mode == AdapterMode.USB && selected != null && selected !in attached) {
                    controller.disconnect()
                    options = options.filter { it.id in attached }
                    selected = null
                }
                if (started) refresh(requestPermission = false)
                return
            }
            if (intent.action != usbAction) return
            val id = pendingUsbId ?: return
            pendingUsbId = null
            val usb = getSystemService(UsbManager::class.java)
            val device = usb.deviceList.values.firstOrNull { it.deviceId == id }
            if (device != null && usb.hasPermission(device)) {
                if (started && mode == AdapterMode.USB && selected == id.toString() &&
                    baud == NativeRuntime.pendingUsbBaud && usbControlLines == NativeRuntime.pendingUsbLines
                ) connectUsb(id, NativeRuntime.pendingUsbBaud, NativeRuntime.pendingUsbLines)
                else controller.showMessage("USB permission granted. Tap Connect to open the adapter.")
            } else controller.showMessage("USB access was denied or the adapter was unplugged.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapterMemory = AdapterSelectionMemoryCodec.decode(
            getSharedPreferences("adapter-selection-v1", MODE_PRIVATE).getString("selection", null))
        mode = adapterMemory.lastMode
        baud = adapterMemory.baud
        usbControlLines = adapterMemory.usbControlLines
        savedInstanceState?.let {
            mode = runCatching { AdapterMode.valueOf(it.getString("mode") ?: mode.name) }.getOrDefault(mode)
            // USB device IDs can change or be reused after unplugging. Resolve the saved
            // VID/PID against the new inventory instead of restoring a stale ID.
            // An active process-owned session needs no selection to disconnect;
            // listing stays deferred until release, without reopening its transport.
            selected = if (mode == AdapterMode.USB) pendingUsbId?.toString() else it.getString("adapter")
            baud = it.getInt("baud", baud).takeIf { rate -> rate == UsbConnectionSettings.AUTO_BAUD || rate in UsbConnectionSettings.SUPPORTED_BAUD_RATES } ?: baud
            usbControlLines = runCatching { UsbControlLines.valueOf(it.getString("usbControlLines") ?: UsbControlLines.ASSERTED.name) }
                .getOrDefault(UsbControlLines.ASSERTED)
            scenario = DemoScenario.valueOf(it.getString("scenario") ?: DemoScenario.COMPLETE.name)
        }
        val filter = IntentFilter(usbAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, filter)
        val preferences = getSharedPreferences("dashboard-pins-v1", MODE_PRIVATE)
        val driveStore = NativeRuntime.driveStore ?: DriveHistoryStore(filesDir).also { NativeRuntime.driveStore = it }
        val scanStore = NativeRuntime.scanStore ?: ScanHistoryStore(filesDir).also { NativeRuntime.scanStore = it }
        val scanHistory = NativeRuntime.scanHistory ?: ScanHistoryCoordinator(scanStore, uiScope).also { NativeRuntime.scanHistory = it }
        // Monitor successful physical scan publication independently of Activity and tab lifetime.
        // The process-owned coordinator handles durable writes and capture-ID deduplication.
        if (NativeRuntime.scanAutoSaveJob == null) {
            NativeRuntime.scanAutoSaveJob = uiScope.launch {
                // Bypass the Activity getter so this process-lived coroutine cannot capture it.
                scanHistory.autoSaveCompletedScans(NativeRuntime.controller.state)
            }
        }
        val driveHistory = NativeRuntime.driveHistory ?: DriveHistoryCoordinator(driveStore, uiScope)
            .also { NativeRuntime.driveHistory = it }
        controller.attachDriveHistory(driveHistory)
        val diagnosis = NativeRuntime.diagnosis ?: DiagnosisCoordinator(controller, driveHistory,
            DiagnosticSessionStore(filesDir, driveProvider = { driveStore.read(it) }), uiScope)
            .also { NativeRuntime.diagnosis = it }
        val caseHistory = NativeRuntime.caseHistory ?: DiagnosticCaseCoordinator(DiagnosticCaseStore(filesDir),
            scanStore, driveStore, uiScope).also { NativeRuntime.caseHistory = it }
        val pins = StoredDashboardPinStore({ preferences.getString(it, null) }) { key, value ->
            preferences.edit().putString(key, value).commit()
        }
        setContent {
            NativeApp(controller, mode, onMode = {
                cancelDriveDemoRequest(); NativeRuntime.driveReplay.close()
                NativeRuntime.replay.close(); NativeRuntime.timedReplay = false
                controller.disconnect(); mode = it; selected = null; options = emptyList()
                saveAdapterMemory(adapterMemory.copy(lastMode = it))
                if (it != AdapterMode.DEMO) refresh(requestPermission = false)
            }, options, selected, onSelect = { id ->
                options.singleOrNull { it.id == id }?.let { option ->
                    selected = id
                    saveAdapterMemory(adapterMemory.remember(mode, option))
                }
            }, baud, onBaud = { baud = it; saveAdapterMemory(adapterMemory.copy(baud = it)) },
                scenario = scenario, onScenario = { cancelImportRequest(); controller.disconnect(); scenario = it }, onRefresh = { refresh() }, onConnect = { connectSelected() },
                onImport = {
                    if (!openImportPicker(loadSnapshot, loadSnapshotFallback, arrayOf("application/json", "text/plain"))) {
                        controller.showMessage(IMPORT_PICKER_UNAVAILABLE)
                    }
                },
                onExport = { includeVin, expectedGeneration ->
                    try {
                        exportDocument = SnapshotCodec.encode(controller.state.value, includeVin, expectedGeneration)
                        saveSnapshot.launch("openwrench-snapshot.json")
                    } catch (error: Exception) { controller.showMessage(error.message ?: "Snapshot could not be exported.") }
                }, replay = NativeRuntime.replay.takeIf { NativeRuntime.timedReplay }, pinStore = pins,
                onExitReplay = { cancelImportRequest(); NativeRuntime.replay.close(); NativeRuntime.timedReplay = false; controller.disconnect() },
                usbControlLines = usbControlLines, onUsbControlLines = {
                    usbControlLines = it; saveAdapterMemory(adapterMemory.copy(usbControlLines = it))
                },
                driveHistory = driveHistory, caseHistory = caseHistory, scanHistory = scanHistory,
                diagnosis = diagnosis,
                savedReplay = NativeRuntime.driveReplay.takeIf { it.state.collectAsState().value.recording != null },
                importedDrives = NativeRuntime.importedDrives, driveDemoLoading = NativeRuntime.driveDemoLoading,
                driveDemoError = NativeRuntime.driveDemoError,
                onLoadDriveDemo = { id -> startDriveDemo { driveStore.read(id) } },
                onImportedDriveDemo = { drive -> startDriveDemo { drive } },
                onAnalyzeImportedDrive = { drive -> showImportedDriveAiDialog(drive) },
                onImportDriveDemo = {
                    NativeRuntime.driveDemoError = null
                    if (!openImportPicker(loadDriveDemo, loadDriveDemoFallback,
                            arrayOf("application/json", "application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream"))) {
                        NativeRuntime.driveDemoError = IMPORT_PICKER_UNAVAILABLE
                    }
                },
                onCancelDriveDemo = ::cancelDriveDemoRequest,
                onCloseDriveDemo = { cancelDriveDemoRequest(); NativeRuntime.driveReplay.close() },
                onAnalyzeDiagnosis = { id -> showDiagnosisAiDialog(diagnosis, id) },
                onReopenAiAnalysis = { id -> reopenAiAnalysis(id) },
                onExportDiagnosis = { id ->
                    try {
                        NativeRuntime.diagnosisExportId = id
                        saveDiagnosis.launch("openwrench-diagnosis-$id.zip")
                    } catch (_: RuntimeException) {
                        NativeRuntime.diagnosisExportId = null
                        saveDiagnosisToDownloads(id)
                    }
                },
                onCopyUsbDiagnostic = {
                    val report = controller.liveDiagnosticSnapshot() +
                        (if (mode == AdapterMode.USB) "\n\n" + NativeRuntime.usbDiagnostics.snapshot() else "")
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("OPENWRENCH session diagnostic", report))
                    // Copy feedback must not replace the connection failure shown in Session detail.
                    Toast.makeText(this, "Session log copied with decoded live values and timing.", Toast.LENGTH_SHORT).show()
                })
        }
        refresh(requestPermission = false)
    }

    private fun refresh(requestPermission: Boolean = true) {
        val state = controller.state.value
        if (state.connected || state.busy || state.cleanupUncertain || pendingUsbId != null) return
        if (mode == AdapterMode.BLUETOOTH && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            options = emptyList(); selected = null
            if (requestPermission) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        try {
            options = when (mode) {
                AdapterMode.BLUETOOTH -> listPairedBluetooth(this).map { AdapterOption(it.address, "${it.name} · ${it.address}") }
                AdapterMode.USB -> listUsbDevices(this).map { AdapterOption(it.deviceId.toString(), it.name, it.vendorId, it.productId) }
                AdapterMode.DEMO -> emptyList()
            }
            selected = adapterMemory.select(mode, options, selected)
            if (options.isEmpty() && mode != AdapterMode.DEMO) controller.showMessage(if (mode == AdapterMode.BLUETOOTH)
                "No paired SPP adapters. Pair your ELM327 in Android Bluetooth Settings, then refresh." else "No supported USB serial adapter. Attach an OTG adapter, then refresh.")
        } catch (error: Exception) {
            options = emptyList(); selected = null
            controller.showMessage(error.message ?: "Cannot list adapters.")
        }
    }

    private fun connectSelected() {
        // The process-owned pending permission survives rotation. Do not open a second prompt.
        if (pendingUsbId != null) return
        cancelImportRequest()
        // Save the explicit Connect choice, including an automatically preselected sole candidate.
        options.singleOrNull { it.id == selected }?.let { saveAdapterMemory(adapterMemory.remember(mode, it)) }
        when (mode) {
            AdapterMode.DEMO -> controller.demo(scenario)
            AdapterMode.BLUETOOTH -> selected?.let { address -> controller.connect { BluetoothElmTransport(applicationContext, address) } }
            AdapterMode.USB -> {
                val id = selected?.toIntOrNull() ?: return
                val manager = getSystemService(UsbManager::class.java)
                val device = manager.deviceList.values.firstOrNull { it.deviceId == id }
                if (device == null) { controller.showMessage("The selected USB adapter was unplugged."); return }
                if (manager.hasPermission(device)) connectUsb(id, baud, usbControlLines)
                else {
                    pendingUsbId = id
                    NativeRuntime.pendingUsbBaud = baud
                    NativeRuntime.pendingUsbLines = usbControlLines
                    val intent = Intent(usbAction).setPackage(packageName)
                    val permission = PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    manager.requestPermission(device, permission)
                }
            }
        }
    }

    private fun connectUsb(id: Int, selectedBaud: Int, selectedLines: UsbControlLines) {
        // Capture settings at admission rather than reading mutable Activity state on the I/O worker.
        controller.connect { UsbElmTransport(applicationContext, id, selectedBaud, selectedLines, NativeRuntime.usbDiagnostics) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode.name)
        outState.putString("adapter", selected)
        outState.putInt("baud", baud)
        outState.putString("usbControlLines", usbControlLines.name)
        outState.putString("scenario", scenario.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart(); started = true; controller.setActive(true); NativeRuntime.replay.setActive(true); NativeRuntime.driveReplay.setActive(true)
        // Listing only: a lifecycle transition never opens an adapter or requests permission.
        adapterRefreshJob = uiScope.launch {
            controller.state.map { !it.connected && !it.busy && !it.cleanupUncertain }
                .distinctUntilChanged().collect { if (it) refresh(requestPermission = false) }
        }
    }
    override fun onStop() {
        started = false; adapterRefreshJob?.cancel(); adapterRefreshJob = null
        // A rotation replaces this Activity but keeps the process-owned foreground
        // session. A real background transition still releases the adapter.
        if (!isChangingConfigurations) {
            controller.setActive(false)
            NativeRuntime.replay.setActive(false)
            NativeRuntime.driveReplay.setActive(false)
        }
        super.onStop()
    }
    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        // The process owner retains the session across rotation and any pending
        // cleanup across subsequent Activity launches.
        super.onDestroy()
    }
}
