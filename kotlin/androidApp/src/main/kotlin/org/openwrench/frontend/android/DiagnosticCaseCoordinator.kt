package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DiagnosticCaseState(val cases: List<DiagnosticCase> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val preview: DiagnosticCase? = null, val opened: DiagnosticCase? = null,
    val scans: List<SavedScan> = emptyList(), val drives: List<SavedDriveSummary> = emptyList(),
    val selecting: Boolean = false)

/** Main-thread, process-owned command admission; one IO operation at a time.
 * UI cancellation invalidates deliveries but never cancels an admitted atomic mutation.
 * Saving consumes its exact preview once; there is no queued upsert that could resurrect a deletion.
 */
class DiagnosticCaseCoordinator(private val store: DiagnosticCaseStore,
    private val scans: ScanHistoryStore, private val drives: DriveHistoryStore,
    private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(DiagnosticCaseState())
    val state: StateFlow<DiagnosticCaseState> = mutable.asStateFlow()
    private var revision = 0L
    init { refresh() }
    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { action() }
            catch (error: Exception) {
                mutable.update { it.copy(error = "${error.message ?: "Case operation failed"}. Existing history was preserved.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() = work {
        val entries = withContext(io) { store.load() }
        mutable.update { it.copy(cases = entries) }
    }
    fun begin() {
        if (state.value.busy) return
        val token = ++revision
        work {
            val choices = withContext(io) { scans.load() to drives.list().filter { it.status == SavedDriveStatus.COMPLETED } }
            if (token == revision) mutable.update { it.copy(selecting = true, preview = null, opened = null, scans = choices.first, drives = choices.second) }
        }
    }
    fun prepare(scanId: String, driveId: String, confirmed: Boolean) {
        if (state.value.busy || !state.value.selecting) return
        val token = ++revision
        // Capture IDs and confirmation at admission; never read a later UI selection from IO.
        work {
            require(confirmed) { "Explicit same-vehicle confirmation is required" }
            val preview = withContext(io) {
                val scan = scans.load().single { it.id == scanId }
                val drive = drives.read(driveId)
                DiagnosticCaseBuilder.create(scan, drive, confirmed, wallClock())
            }
            if (token == revision) mutable.update { it.copy(preview = preview, selecting = false) }
        }
    }
    fun cancel() {
        ++revision
        mutable.update { it.copy(preview = null, opened = null, selecting = false, scans = emptyList(), drives = emptyList()) }
    }
    fun save(previewId: String) {
        if (state.value.busy) return
        val selected = state.value.preview?.takeIf { it.id == previewId } ?: return
        val token = ++revision
        mutable.update { it.copy(preview = null) }
        work {
            // Save metadata is separate from the captured source metadata and review time.
            val saved = selected.copy(savedAtEpochMillis = wallClock())
            val entries = withContext(io) { store.add(saved) }
            mutable.update { it.copy(cases = entries, opened = if (token == revision) saved else it.opened) }
        }
    }
    fun open(id: String) {
        if (state.value.busy) return
        val token = ++revision
        work {
            val selected = withContext(io) { store.load().single { it.id == id } }
            if (token == revision) mutable.update { it.copy(opened = selected, preview = null, selecting = false) }
        }
    }
    fun delete(id: String) {
        if (state.value.busy) return
        ++revision
        work {
            val entries = withContext(io) { store.delete(id) }
            mutable.update { it.copy(cases = entries, opened = it.opened.takeUnless { c -> c?.id == id }) }
        }
    }
}
