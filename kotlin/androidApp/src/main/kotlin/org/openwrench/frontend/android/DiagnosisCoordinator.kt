package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import java.io.OutputStream
import java.util.UUID
import org.openwrench.core.GuidedDiagnosisPolicy
import org.openwrench.core.GuidedDiagnosisProgress
import org.openwrench.core.GuidedDiagnosisRecorder

enum class DiagnosisPhase { IDLE, STARTING, RECORDING, PAUSING, PAUSED, FINISHING, COMPLETED, INTERRUPTED }

data class DiagnosisState(
    val loading: Boolean = true, val busy: Boolean = false, val phase: DiagnosisPhase = DiagnosisPhase.IDLE,
    val current: DiagnosisRecord? = null, val history: List<DiagnosisRecord> = emptyList(),
    val guidance: GuidedDiagnosisProgress? = null,
    val error: String? = null, val message: String = "Loading diagnoses…",
)

/** Process-owned workflow. NativeController remains the only adapter owner and command gate. */
class DiagnosisCoordinator(
    private val controller: NativeController,
    private val driveHistory: DriveHistoryCoordinator,
    private val store: DiagnosticSessionStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(DiagnosisState())
    val state: StateFlow<DiagnosisState> = mutable.asStateFlow()
    private val disk = Mutex()
    private data class Receipt(val segmentId: String, val sample: DiagnosisSample)
    private val pending = ArrayDeque<Receipt>()
    private var recordingSegment: String? = null
    private var boundConnection: Pair<String, Long>? = null
    private var operationRecordId: String? = null
    private var guidanceRecorder: GuidedDiagnosisRecorder? = null
    private var guidanceSegment: String? = null
    private var milestoneAnnounced = false

    init {
        controller.attachDiagnosisRecorder(::record)
        refresh()
        scope.launch {
            controller.state.collect { native ->
                if (!native.connected || native.sessionGeneration != boundConnection?.second) boundConnection = null
                if (state.value.phase == DiagnosisPhase.RECORDING &&
                    (!native.liveHealthEnabled || !native.connected || native.origin != ReadingOrigin.HARDWARE ||
                        native.sessionGeneration != state.value.current?.segments?.lastOrNull()?.generation)) {
                    interrupt(native.liveHealthSession?.endReason ?: "Adapter session interrupted")
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (recordingSegment != null && !state.value.busy) {
                    try {
                        guidanceRecorder?.snapshot(clock())?.let { progress -> mutable.update { it.copy(guidance = progress) } }
                        persist()
                    }
                    catch (_: Exception) { interrupt("Recording could not be saved. Retry saving before continuing.") }
                }
            }
        }
    }

    private fun ready(): Boolean {
        val native = controller.state.value
        return !state.value.loading && !state.value.busy && recordingSegment == null &&
            native.origin == ReadingOrigin.HARDWARE && native.connected && !native.busy &&
            !native.cleanupUncertain && !native.stopPending && !native.liveHealthEnabled
    }

    fun start() {
        if (!ready() || pending.isNotEmpty()) {
            mutable.update { it.copy(error = "Connect an adapter and finish or retry the current operation before starting a diagnosis.") }
            return
        }
        launchOperation(DiagnosisPhase.STARTING, "Collecting initial fault codes and supported sensor evidence…", ownsCurrent = false) {
            val now = wallClock()
            val record = DiagnosisRecord(UUID.randomUUID().toString(), now, now, DiagnosisStatus.PAUSED,
                emptyList(), emptyList(), "Initial scan pending", GuidedDiagnosisPolicy.VERSION)
            disk.withLock { withContext(io) { store.create(record) } }
            operationRecordId = record.id
            guidanceRecorder = null; guidanceSegment = null
            mutable.update { it.copy(current = record, guidance = record.segments.lastOrNull()?.guidance) }
            beginDrive()
        }
    }

    fun resume(id: String, sameVehicleConfirmed: Boolean) {
        if (!sameVehicleConfirmed || !ready() || pending.isNotEmpty()) {
            mutable.update { it.copy(error = "Connect the same vehicle and confirm it before adding a drive.") }; return
        }
        launchOperation(DiagnosisPhase.STARTING, "Collecting a new scan for this drive…", ownsCurrent = false) {
            val record = disk.withLock { withContext(io) { store.read(id) } }
            check(record.segments.size < 24) { "This diagnosis already contains 24 drives. Start a new diagnosis." }
            operationRecordId = record.id
            guidanceRecorder = null; guidanceSegment = null
            mutable.update { it.copy(current = record, guidance = record.segments.lastOrNull()?.guidance) }
            beginDrive()
        }
    }

    private suspend fun beginDrive() {
        val ticket = controller.state.value.sessionGeneration
        stopAndDrain(ticket)
        val scanSucceeded = captureScan(ticket)
        ensureHardware(ticket)
        check(controller.startLiveHealth(allSupportedScalars = true) == ControllerAdmission.ACCEPTED) { "Supported health inputs could not be started." }
        val monitor = checkNotNull(controller.state.value.liveHealthSession)
        val segment = DiagnosisSegment(UUID.randomUUID().toString(), ticket, controller.state.value.sourceEcu,
            wallClock(), monitor.startedAtMillis, driveId = driveHistory.state.value.activeId)
        milestoneAnnounced = state.value.current!!.segments.any { it.guidance?.initialAssessmentAvailable == true }
        guidanceRecorder = GuidedDiagnosisRecorder(segment.startedAtMonotonicMillis, monitor.supported, monitor.admitted, scanSucceeded)
        guidanceSegment = segment.id
        changeRecord { it.copy(status = DiagnosisStatus.RECORDING, segments = it.segments + segment, note = null,
            guidedPolicyVersion = GuidedDiagnosisPolicy.VERSION) }
        mutable.update { it.copy(guidance = guidanceRecorder!!.snapshot()) }
        recordingSegment = segment.id
        boundConnection = state.value.current!!.id to ticket
        persist()
        ensureHardware(ticket)
        check(controller.state.value.liveHealthEnabled) { "Monitoring ended before recording could start." }
        mutable.update { it.copy(busy = false, phase = DiagnosisPhase.RECORDING,
            message = "Recording supported sensors. Keep the app in the foreground; finish when parked.") }
    }

    fun pause() {
        if (state.value.phase != DiagnosisPhase.RECORDING || state.value.busy) return
        recordingSegment = null
        launchOperation(DiagnosisPhase.PAUSING, "Saving this drive…") {
            val ticket = state.value.current!!.segments.last().generation
            controller.stopLiveHealth()
            closeSegment("Drive paused by user")
            stopAndDrain(ticket)
            changeRecord { it.copy(status = DiagnosisStatus.PAUSED, note = "Paused by user; no recording is active.") }
            awaitDriveReport()
            persist()
            completeOperation(DiagnosisPhase.PAUSED, "Drive saved. Add another drive later, or finish this diagnosis.")
        }
    }

    fun finish() {
        if (state.value.busy || state.value.current == null || state.value.phase !in
            setOf(DiagnosisPhase.RECORDING, DiagnosisPhase.PAUSED, DiagnosisPhase.INTERRUPTED)) return
        val wasRecording = recordingSegment != null
        recordingSegment = null
        launchOperation(DiagnosisPhase.FINISHING, "Saving evidence and preparing your diagnosis…") {
            val ticket = state.value.current!!.segments.lastOrNull()?.generation
            if (wasRecording) {
                controller.stopLiveHealth()
                closeSegment("Diagnosis finished by user")
            }
            val native = controller.state.value
            if (ticket != null && boundConnection == (state.value.current!!.id to ticket) && native.connected && native.origin == ReadingOrigin.HARDWARE && native.sessionGeneration == ticket) {
                stopAndDrain(ticket)
                captureScan(ticket)
                changeRecord { it.copy(note = null) }
            } else {
                changeRecord { it.copy(note = "Finished from saved evidence. No final connected scan was collected.") }
            }
            changeRecord { it.copy(status = DiagnosisStatus.COMPLETED) }
            awaitDriveReport()
            persist()
            completeOperation(DiagnosisPhase.COMPLETED, "Diagnosis saved. Review the analysis and charts, or export all evidence.")
        }
    }

    private fun interrupt(reason: String) {
        if (state.value.busy || state.value.phase != DiagnosisPhase.RECORDING) return
        recordingSegment = null
        launchOperation(DiagnosisPhase.PAUSING, "Preserving interrupted evidence…") {
            controller.stopLiveHealth()
            controller.stopLive()
            closeSegment(reason)
            changeRecord { it.copy(status = DiagnosisStatus.INTERRUPTED, note = reason) }
            awaitDriveReport()
            persist()
            completeOperation(DiagnosisPhase.INTERRUPTED, "Evidence saved. Reconnect explicitly to add another drive.")
        }
    }

    private fun closeSegment(reason: String) {
        guidanceRecorder?.stop(clock())
        changeRecord { record -> record.copy(segments = record.segments.mapIndexed { index, segment ->
            if (index == record.segments.lastIndex && segment.endedAtOffsetMillis == null)
                segment.copy(endedAtOffsetMillis = (clock() - segment.startedAtMonotonicMillis).coerceAtLeast(0), endReason = reason)
            else segment
        }) }
    }

    /** The independent drive writer must freeze its assessment before results claim completion. */
    private suspend fun awaitDriveReport() {
        val driveId = state.value.current?.segments?.lastOrNull()?.driveId ?: return
        val saved = withTimeoutOrNull(10_000) {
            while (driveHistory.state.value.drives.none { it.id == driveId && it.status != SavedDriveStatus.CHECKPOINT }) {
                delay(50)
            }
            true
        } == true
        if (!saved) changeRecord { it.copy(note = listOfNotNull(it.note,
            "Drive analysis has not finished saving. Available chart evidence may be a partial checkpoint; reload saved analysis after storage recovers.").joinToString("\n")) }
    }

    private fun record(reading: NativeReading, receiptMillis: Long, ticket: Long) {
        val id = recordingSegment ?: return
        val segment = state.value.current?.segments?.lastOrNull() ?: return
        if (segment.id != id || segment.generation != ticket || receiptMillis < segment.startedAtMonotonicMillis) return
        if (pending.size >= 2048) {
            changeRecord { r -> r.copy(segments = r.segments.map { if (it.id == id) it.copy(droppedSamples = it.droppedSamples + 1) else it }) }
            interrupt("Recording buffer filled. At least one receipt was not saved; recording stopped.")
            return
        }
        guidanceRecorder?.let { recorder ->
            recorder.offer(reading, receiptMillis)
            val progress = recorder.snapshot()
            val announce = progress.initialAssessmentAvailable && !milestoneAnnounced
            if (announce) milestoneAnnounced = true
            mutable.update { it.copy(guidance = progress,
                message = if (announce) GuidedDiagnosisPolicy.MILESTONE else it.message) }
        }
        val value = reading.value
        pending.addLast(Receipt(id, DiagnosisSample(receiptMillis - segment.startedAtMonotonicMillis,
            reading.pid, (value as? DecodedReading.Scalar)?.value,
            (value as? DecodedReading.Structured)?.fields?.toSortedMap()?.entries?.joinToString("; ") { "${it.key}=${it.value}" },
            ObdDecoder.catalog.getValue(reading.pid).unit.orEmpty(), if (value == null) "NO_DATA" else "AVAILABLE")))
    }

    private fun ensureHardware(ticket: Long) {
        val s = controller.state.value
        check(s.connected && s.origin == ReadingOrigin.HARDWARE && s.sessionGeneration == ticket &&
            !s.cleanupUncertain && !s.faulted) { "Adapter connection ended. Saved evidence is retained." }
    }

    private suspend fun stopAndDrain(ticket: Long) {
        ensureHardware(ticket)
        controller.stopLive()
        withTimeout(15_000) {
            while (true) {
                ensureHardware(ticket)
                val s = controller.state.value
                if (!s.liveRunning && !s.stopPending && !s.busy) break
                delay(25)
            }
        }
    }

    private suspend fun captureScan(ticket: Long): Boolean {
        ensureHardware(ticket)
        val previous = controller.state.value.snapshotSequence
        check(controller.requestSnapshot(includeWarningContext = true) == ControllerAdmission.ACCEPTED) {
            "The automatic scan could not start. Saved evidence is retained."
        }
        return withTimeout(120_000) {
            while (true) {
                ensureHardware(ticket)
                val s = controller.state.value
                if (!s.snapshotInProgress && !s.busy && s.snapshotSequence > previous && s.snapshotSessionGeneration == ticket) {
                    val report = DiagnosticScanDelivery.present(s).reportText()
                    changeRecord { it.copy(scanReports = it.scanReports + report) }
                    persist()
                    return@withTimeout s.storedDtcs?.codes != null && DiagnosticScanDelivery.present(s).milOn != null
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    /** Runs under one disk mutex; neither receipt callbacks nor the serial owner perform file IO. */
    private suspend fun persist() = disk.withLock {
        val id = state.value.current?.id ?: return@withLock
        // Publish an open segment before its first CSV batch. A pending terminal flush keeps
        // the disk segment open until its queued receipts have been durably appended.
        val before = state.value.current!!
        val header = if (pending.isNotEmpty()) before.copy(status = DiagnosisStatus.RECORDING,
            segments = before.segments.map { s -> if (pending.any { it.segmentId == s.id })
                s.copy(endedAtOffsetMillis = null, endReason = null) else s }) else before
        withContext(io) { store.save(header) }
        // Only this method removes receipts. New receipts may arrive while IO is suspended.
        val batch = pending.toList()
        batch.groupBy { it.segmentId }.forEach { (segmentId, receipts) ->
            receipts.chunked(512).forEach { chunk ->
                try { withContext(io) { store.appendSamples(id, segmentId, chunk.map { it.sample }) } }
                catch (failure: IllegalArgumentException) {
                    if (failure.message?.contains("recording limit reached") == true) {
                        val lost = pending.groupingBy { it.segmentId }.eachCount()
                        changeRecord { r -> r.copy(segments = r.segments.map { s ->
                            s.copy(droppedSamples = s.droppedSamples + (lost[s.id] ?: 0)) }) }
                        pending.clear()
                        // The recorder consumed the now-dropped batch. Keep only the earlier
                        // durable proof; never publish qualification from receipts absent in CSV.
                        guidanceRecorder = null; guidanceSegment = null
                        mutable.update { it.copy(guidance = it.current?.segments?.lastOrNull()?.guidance) }
                    }
                    throw failure
                }
                repeat(chunk.size) { pending.removeFirst() }
                changeRecord { r -> r.copy(segments = r.segments.map { s ->
                    if (s.id == segmentId) s.copy(sampleCount = s.sampleCount + chunk.size) else s
                }) }
            }
        }
        // Publish policy proof only after all receipts it consumed are durable. Header writes
        // deliberately keep the previous proof, so an interrupted append cannot invent evidence.
        if (pending.isEmpty()) guidanceRecorder?.snapshot()?.let { progress ->
            changeRecord { r -> r.copy(segments = r.segments.map { segment ->
                if (segment.id == guidanceSegment) segment.copy(guidance = progress) else segment
            }) }
        }
        val record = state.value.current ?: return@withLock
        val durable = if (pending.isEmpty()) record else record.copy(status = DiagnosisStatus.RECORDING,
            segments = record.segments.map { s -> if (pending.any { it.segmentId == s.id })
                s.copy(endedAtOffsetMillis = null, endReason = null) else s })
        withContext(io) { store.save(durable) }
        mutable.update { it.copy(history = (listOf(record) + it.history.filterNot { old -> old.id == id })
            .sortedByDescending { row -> row.updatedAtEpochMillis }) }
    }

    private fun changeRecord(change: (DiagnosisRecord) -> DiagnosisRecord) {
        mutable.update { s -> s.copy(current = s.current?.let { change(it).copy(updatedAtEpochMillis = wallClock()) }) }
    }

    private fun launchOperation(phase: DiagnosisPhase, message: String, ownsCurrent: Boolean = true, block: suspend () -> Unit) {
        val previousPhase = state.value.phase
        operationRecordId = state.value.current?.id.takeIf { ownsCurrent }
        mutable.update { it.copy(busy = true, phase = phase, error = null, message = message) }
        scope.launch {
            try { block() }
            catch (e: Exception) {
                if (operationRecordId != null && operationRecordId == state.value.current?.id) {
                    recordingSegment = null
                    // A scan timeout must also revoke the native owner's permission to continue.
                    if (e is TimeoutCancellationException) controller.disconnect()
                    else { controller.stopLiveHealth(); controller.stopLive() }
                    closeSegment("Operation interrupted")
                    changeRecord { it.copy(status = DiagnosisStatus.INTERRUPTED, note = e.message?.take(500) ?: "Operation interrupted") }
                    try { persist() } catch (_: Exception) { /* Bounded pending receipts remain for explicit retry. */ }
                }
                mutable.update { it.copy(busy = false,
                    phase = if (operationRecordId == null) previousPhase else DiagnosisPhase.INTERRUPTED,
                    error = e.message?.take(500) ?: "Diagnosis could not be saved. Existing evidence was kept.",
                    message = "Diagnosis interrupted. Retry saving before starting another drive.") }
            }
        }
    }

    private fun completeOperation(phase: DiagnosisPhase, message: String) {
        mutable.update { it.copy(busy = false, phase = phase, error = null, message = message) }
    }

    fun refresh() {
        if (state.value.busy || recordingSegment != null) return
        mutable.update { it.copy(loading = true, busy = true) }
        scope.launch {
            try {
                if (pending.isNotEmpty() || state.value.current != null) persist()
                val rows = disk.withLock { withContext(io) { store.list() } }
                mutable.update { it.copy(loading = false, busy = false, history = rows, error = null,
                    message = "Diagnoses are saved on this device.") }
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, busy = false,
                    error = "Diagnosis history could not be saved or read. Existing files were preserved. Retry saving.") }
            }
        }
    }

    fun open(id: String) {
        if (state.value.busy || recordingSegment != null || pending.isNotEmpty()) return
        launchOperation(DiagnosisPhase.IDLE, "Opening saved diagnosis…", ownsCurrent = false) {
            val record = disk.withLock { withContext(io) { store.read(id) } }
            boundConnection = null
            guidanceRecorder = null; guidanceSegment = null
            mutable.update { it.copy(current = record, guidance = record.segments.lastOrNull()?.guidance) }
            completeOperation(when (record.status) {
                DiagnosisStatus.COMPLETED -> DiagnosisPhase.COMPLETED
                DiagnosisStatus.PAUSED -> DiagnosisPhase.PAUSED
                else -> DiagnosisPhase.INTERRUPTED
            }, "Saved diagnosis · historical evidence")
        }
    }

    /** Caller owns the document picker. This method streams a frozen, finished archive off-main. */
    suspend fun readDrive(id: String, driveId: String): SavedDrive? = readDriveEvidence(id, driveId).drive

    suspend fun readDriveEvidence(id: String, driveId: String): DiagnosisDriveEvidence =
        disk.withLock { withContext(io) { store.readDriveEvidence(id, driveId) } }

    suspend fun export(id: String, output: OutputStream) {
        check(!state.value.busy && recordingSegment == null) { "Pause or finish diagnosis before exporting." }
        check(pending.isEmpty()) { "Some receipts are not saved. Retry saving before exporting." }
        disk.withLock { withContext(io) {
            val saved = store.read(id)
            val current = state.value.current?.takeIf { it.id == id }
            check(current == null || saved == current) {
                "This diagnosis has unsaved changes. Retry saving or reopen its saved evidence before exporting."
            }
            store.exportZip(id, output)
        } }
    }
}
