package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openwrench.nativecore.ScanCapture
import org.openwrench.nativecore.ScanOrigin

data class ScanHistoryState(val entries: List<SavedScan> = emptyList(), val busy: Boolean = false,
    val available: Boolean = true, val error: String? = null, val message: String? = null)

/** Process-owned writer. Automatic saves queue behind initialization and earlier mutations;
 * capture IDs make retries and Activity recreation safe without duplicate history rows.
 */
class ScanHistoryCoordinator(private val store: ScanHistoryStore, private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow(ScanHistoryState())
    val state: StateFlow<ScanHistoryState> = mutable.asStateFlow()
    private val mutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()

    init {
        mutable.update { it.copy(busy = true) }
        scope.launch {
            mutex.withLock {
                try { mutable.update { it.copy(entries = withContext(io) { store.load() }) } }
                catch (error: Exception) {
                    mutable.update { it.copy(available = false,
                        error = "${error.message ?: "Scan history could not be read"}. Existing history was preserved.") }
                } finally {
                    mutable.update { it.copy(busy = false) }
                    initialized.complete(Unit)
                }
            }
        }
    }

    private fun operation(message: String? = null, queue: Boolean = false,
        action: suspend () -> List<SavedScan>) {
        if (!state.value.available || (!queue && state.value.busy)) return
        // Reserve admission before dispatch so an immediate UI save/delete sees pending work.
        mutable.update { it.copy(busy = true) }
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (!state.value.available) return@withLock
                mutable.update { it.copy(busy = true, error = null, message = null) }
                try {
                    val entries = action()
                    mutable.update { it.copy(entries = entries, message = message) }
                } catch (error: Exception) {
                    mutable.update { it.copy(error = "${error.message ?: "Scan history operation failed"}. Existing history was preserved.") }
                } finally {
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Ignore transient states before deduplication: a canceled/failed acquisition retains
     * the previous capture, which must not be automatically saved again after deletion.
     */
    internal suspend fun autoSaveCompletedScans(states: Flow<NativeUiState>) {
        states.filter { current ->
            current.scanCapture?.origin == ScanOrigin.HARDWARE &&
                !current.snapshotInProgress && current.snapshot != null
        }.distinctUntilChangedBy { it.scanCapture!!.captureId }.collect { current ->
            autoSave(DiagnosticScanDelivery.present(current).reportText(), current.scanCapture!!)
        }
    }

    /** Persist only complete physical acquisitions, with no VIN unless explicitly opted in later. */
    fun autoSave(report: String, capture: ScanCapture) {
        if (capture.origin != ScanOrigin.HARDWARE) return
        operation("Diagnostic scan automatically saved to History & Reports.", queue = true) {
            withContext(io) { store.saveCapture(report, capture) }
        }
    }

    fun save(report: String, vin: String?, capture: ScanCapture) =
        operation("Scan saved to History & Reports.", queue = true) {
            withContext(io) { store.saveCapture(report, capture, explicitVin = vin) }
        }

    /** Legacy test/synthetic fixture insertion remains explicit. */
    fun save(entry: SavedScan) = operation("Scan saved to History & Reports.") {
        withContext(io) { store.add(entry) }
    }

    fun delete(id: String) = operation {
        withContext(io) { store.delete(id) }
    }
}
