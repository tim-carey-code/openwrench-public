package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class DriveHistoryState(
    val drives: List<SavedDriveSummary> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val saveMessage: String = "Drive history loading…",
    val activeId: String? = null, val opened: SavedDrive? = null, val opening: Boolean = false,
    val comparison: DriveComparison? = null, val comparing: Boolean = false,
)

/** Process-owned IO worker. Scheduling a checkpoint never blocks the serial session owner. */
class DriveHistoryCoordinator(
    private val store: DriveHistoryStore, private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val generation: Long, val instance: Long, val started: Long)
    private data class Run(val key: Key, val id: String, val startedEpoch: Long)
    private data class Request(val run: Run, val session: LiveHealthSession, val savedEpoch: Long, val revision: Long)
    private data class Catalog(val revision: Long, val rows: List<SavedDriveSummary>)
    private val mutable = MutableStateFlow(DriveHistoryState())
    val state: StateFlow<DriveHistoryState> = mutable.asStateFlow()
    private val lock = Any()
    private val disk = Mutex()
    private val pending = linkedMapOf<String, Request>()
    private val failed = linkedMapOf<String, Request>()
    private val deleted = linkedSetOf<String>()
    private val deleting = linkedSetOf<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var run: Run? = null
    private var lastCheckpoint = Long.MIN_VALUE
    private var revision = 0L
    private var openRevision = 0L
    private var comparisonRevision = 0L
    private var ended = false
    private var writingId: String? = null
    private var diskRevision = 0L // Accessed only while holding disk.
    private var publishedRevision = 0L // Accessed only while holding lock.
    private fun trimDeleted() {
        if (writingId != null || deleting.isNotEmpty()) return
        deleted.removeAll { it != run?.id && it !in pending && it !in failed }
    }
    private fun publish(result: Catalog, change: (DriveHistoryState) -> DriveHistoryState) = synchronized(lock) {
        if (result.revision >= publishedRevision) {
            publishedRevision = result.revision
            mutable.update { change(it).copy(drives = result.rows.filterNot { row -> row.id in deleted }) }
        }
    }
    private fun failedPublication(attempt: Long) = synchronized(lock) {
        publishedRevision = maxOf(publishedRevision, attempt)
    }

    init {
        scope.launch {
            refresh()
            for (ignored in wake) {
                while (true) {
                    val request = synchronized(lock) { pending.values.firstOrNull()?.also { writingId = it.run.id } } ?: break
                    var attempt = 0L
                    try {
                        val result = withContext(io + NonCancellable) {
                            disk.withLock {
                                if (synchronized(lock) { request.run.id in deleted }) null
                                else {
                                    attempt = ++diskRevision
                                    Catalog(attempt, store.upsert(archive(request)))
                                }
                            }
                        }
                        synchronized(lock) {
                            if (pending[request.run.id]?.revision == request.revision) pending.remove(request.run.id)
                            failed.remove(request.run.id)
                        }
                        val hasFailures = synchronized(lock) { failed.isNotEmpty() }
                        if (result != null) {
                            publish(result) { it.copy(loading = false) }
                            synchronized(lock) {
                                // A later delete/refresh can supersede the catalog while this
                                // write still successfully finishes the current run's save.
                                if (run?.id == request.run.id && request.run.id !in pending && request.run.id !in deleted) mutable.update { it.copy(
                                    error = if (hasFailures) "Some earlier drives are still unsaved. Retry drive history or copy their reports." else null,
                                    saveMessage = if (request.session.stoppedAtMillis == null)
                                        "Drive checkpoint saved automatically" else "Drive saved automatically to History & Reports") }
                            }
                        }
                    } catch (_: Exception) {
                        failedPublication(attempt)
                        synchronized(lock) {
                            if (pending[request.run.id]?.revision == request.revision) {
                                pending.remove(request.run.id)
                                failed[request.run.id] = request
                            }
                        }
                        mutable.update { it.copy(loading = false,
                            error = "Drive history could not be saved. Existing files were kept. Retry or copy the current report.",
                            saveMessage = "A drive could not be saved — live evidence is still available") }
                    } finally {
                        synchronized(lock) { writingId = null; trimDeleted() }
                    }
                }
            }
        }
    }

    /** Called only with hardware monitor snapshots, including its terminal snapshot. */
    fun record(session: LiveHealthSession) {
        try {
            val review = session.driveReview ?: return
            val key = Key(session.generation, review.instanceId, session.startedAtMillis)
            val offset = review.elapsedMillis
            synchronized(lock) {
                if (run?.key != key) {
                    run = Run(key, UUID.randomUUID().toString(), wallClock())
                    lastCheckpoint = Long.MIN_VALUE
                    ended = false
                    trimDeleted()
                }
                if (ended && session.stoppedAtMillis == null) return
                if (session.stoppedAtMillis != null) ended = true
                val current = run!!
                if (current.id in deleted) return
                mutable.update { it.copy(activeId = current.id.takeIf { session.stoppedAtMillis == null }) }
                if (session.stoppedAtMillis == null && lastCheckpoint != Long.MIN_VALUE && offset - lastCheckpoint < 10_000) return
                val unsaved = pending.keys + failed.keys
                if (current.id !in unsaved && unsaved.size >= DriveHistoryStore.MAX_DRIVES) {
                    mutable.update { it.copy(error = "Drive-save queue is full. Copy the current report before starting another drive.", saveMessage = "Automatic save is delayed") }
                    return
                }
                lastCheckpoint = offset
                failed.remove(current.id)
                pending[current.id] = Request(current, session, wallClock(), ++revision)
                mutable.update { it.copy(saveMessage = if (session.stoppedAtMillis == null)
                    "Saving drive checkpoint…" else "Saving finished drive…") }
            }
            wake.trySend(Unit)
        } catch (_: Exception) {
            mutable.update { it.copy(error = "Drive checkpoint could not be scheduled. Copy the current report.", saveMessage = "Automatic save failed") }
        }
    }

    private fun archive(request: Request): SavedDrive {
        val s = request.session
        val now = s.stoppedAtMillis ?: (s.startedAtMillis + s.driveReview!!.elapsedMillis)
        // Generate an explicitly historical presentation at its saved point. No saved clock
        // is ever fed back into a live controller or compared with a new process receipt clock.
        val view = LiveHealthDelivery.present(NativeUiState(sessionGeneration = s.generation,
            liveHealthSession = s), now)
        val report = view.reportText()
        val status = when {
            s.stoppedAtMillis == null -> SavedDriveStatus.CHECKPOINT
            s.endReason in setOf("Health monitoring stopped by user", "Live polling stopped") -> SavedDriveStatus.COMPLETED
            else -> SavedDriveStatus.INTERRUPTED
        }
        return SavedDrive(request.run.id, request.run.startedEpoch, request.savedEpoch, status, s.endReason,
            s.sourceEcu, s.generation, report, s.driveReview!!.present(s.driveReview.elapsedMillis), DriveEvidenceOrigin.HARDWARE)
    }

    fun retry() {
        synchronized(lock) {
            failed.forEach { (id, request) -> if (id !in deleted && id !in pending) pending[id] = request }
            failed.clear()
        }
        scope.launch { refresh(); wake.trySend(Unit) }
    }

    private suspend fun refresh() {
        try {
            val result = withContext(io) { disk.withLock { Catalog(diskRevision, store.list()) } }
            publish(result) { it.copy(loading = false, error = null,
                saveMessage = if (result.rows.isEmpty()) "Drives will save automatically while monitoring" else "Saved drives are available in History & Reports") }
        } catch (_: Exception) {
            mutable.update { it.copy(loading = false, error = "Saved drives could not be read. Existing files were preserved.", saveMessage = "Drive history unavailable") }
        }
    }

    fun open(id: String) {
        closeComparison()
        val token = ++openRevision
        mutable.update { it.copy(opening = true, error = null) }
        scope.launch {
            try {
                val drive = withContext(io) { disk.withLock { store.read(id) } }
                if (token == openRevision) mutable.update { it.copy(opened = drive, opening = false) }
            } catch (_: Exception) { if (token == openRevision) mutable.update { it.copy(opening = false, error = "This saved drive could not be opened. Its file was preserved.") } }
        }
    }
    fun closeReview() { ++openRevision; mutable.update { it.copy(opened = null, opening = false) } }

    fun compare(ids: List<String>, sameVehicleConfirmed: Boolean) {
        if (!sameVehicleConfirmed || ids.size != 2 || ids.distinct().size != 2 || state.value.activeId in ids) {
            mutable.update { it.copy(error = "Select two completed drives and confirm they came from the same vehicle.") }; return
        }
        val token = ++comparisonRevision
        ++openRevision
        mutable.update { it.copy(opened = null, opening = false, comparing = true, comparison = null, error = null) }
        scope.launch {
            try {
                val result = withContext(io) { disk.withLock {
                    val drives = ids.map { store.read(it) }
                    DriveComparisonBuilder.compare(drives[0], drives[1], sameVehicleConfirmed)
                } }
                if (token == comparisonRevision && synchronized(lock) { ids.none { it in deleted || it in deleting } })
                    mutable.update { it.copy(comparing = false, comparison = result) }
            } catch (_: Exception) {
                if (token == comparisonRevision) mutable.update { it.copy(comparing = false,
                    error = "Selected drives could not be compared. Confirm both completed reports are still available; their files were preserved.") }
            }
        }
    }
    fun closeComparison() {
        ++comparisonRevision
        mutable.update { it.copy(comparison = null, comparing = false) }
    }

    fun delete(id: String) {
        closeComparison()
        if (state.value.activeId == id) {
            mutable.update { it.copy(error = "Finish monitoring before deleting its saved checkpoints.") }; return
        }
        synchronized(lock) {
            if (id in deleting) return
            deleting += id
            deleted += id; pending.remove(id); failed.remove(id)
            trimDeleted()
        }
        ++openRevision
        mutable.update { it.copy(opening = false) }
        scope.launch {
            var attempt = 0L
            try {
                val result = withContext(io) { disk.withLock {
                    attempt = ++diskRevision
                    Catalog(attempt, store.delete(id))
                } }
                publish(result) { it.copy(opened = it.opened.takeUnless { d -> d?.id == id }, error = null,
                    saveMessage = if (run?.id == id) "Saved drive deleted" else it.saveMessage) }
            } catch (_: Exception) {
                failedPublication(attempt)
                mutable.update { it.copy(error = "Saved drive could not be deleted. Retry after pending saves finish.") }
            } finally { synchronized(lock) { deleting.remove(id); trimDeleted() } }
        }
    }
}
