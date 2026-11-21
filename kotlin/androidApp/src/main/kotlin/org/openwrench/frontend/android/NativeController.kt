package org.openwrench.frontend.android

import android.annotation.SuppressLint
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.*
import org.openwrench.core.*
import org.openwrench.nativecore.*

enum class DemoScenario(val title: String) { COMPLETE("Complete simulator fixture"), MIXED("Mixed availability fixture"), TIMED_WORKSPACE("Synthetic chart fixture · offline"), LOAD_ERROR("Source error fixture") }

enum class ReadingOrigin { HARDWARE, SIMULATOR, REPLAY }
enum class ControllerAdmission {
    ACCEPTED, DISCONNECTED, BUSY, LIVE_RUNNING, CLEANUP_UNCERTAIN, CONTEXT_DEFERRED,
    NOT_FOREGROUND, STOPPING, NO_INPUTS, SELECTION_FULL,
}
enum class AssessmentOperation { PASSIVE, FOCUSED }
private enum class ExclusiveOperation { VIN, SNAPSHOT, ASSESSMENT, IDLE_HEALTH }

data class TimedReading(val reading: NativeReading, val receivedMillis: Long)
private data class PlannedRead(val pid: Int, val rate: WatchRate, val structured: Boolean)
data class NativeUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val liveRunning: Boolean = false,
    val stopPending: Boolean = false,
    val cleanupUncertain: Boolean = false,
    val faulted: Boolean = false,
    val origin: ReadingOrigin = ReadingOrigin.HARDWARE,
    val replaySimulated: Boolean = false,
    val detail: String = "Choose a paired Bluetooth or USB adapter, or explore the offline simulator.",
    val identity: String? = null,
    val sourceEcu: String? = null,
    val supported: Set<Int> = emptySet(),
    val watched: Map<Int, WatchRate> = emptyMap(),
    val readings: Map<Int, TimedReading> = emptyMap(),
    val demoLabels: Map<Int, String> = emptyMap(),
    val snapshot: List<NativeReading>? = null,
    val scanCapture: ScanCapture? = null,
    val scanCaptureVin: String? = null,
    val storedDtcs: StoredDtcRead? = null,
    val diagnosticContext: DiagnosticContextRead? = null,
    val snapshotSessionGeneration: Long? = null,
    val snapshotCapturedMillis: Long? = null,
    /** Monotonic completion token for consumers that must reject a previous scan. */
    val snapshotSequence: Long = 0,
    val snapshotEligiblePids: Set<Int> = emptySet(),
    val snapshotInProgress: Boolean = false,
    /** VIN belonging to the completed snapshot, independent of the current connection. */
    val snapshotVin: String? = null,
    val vin: String? = null,
    val vinDetail: String = "VIN has not been requested.",
    val nowMillis: Long = 0,
    val sessionGeneration: Long = 0,
    val healthPlan: HealthInputPlan = HealthInputPlan(emptyMap(), emptyMap(), emptyList()),
    /** Current acquisition's bounded receipts, including unavailable and ended receipts. */
    val healthObservations: List<Observation> = emptyList(),
    /** Measured receipt spacing, not a guaranteed ECU sample rate. */
    val healthCadenceMillis: Map<Int, Long> = emptyMap(),
    val assessment: AcquisitionSnapshot? = null,
    val idleHealthPhase: IdleHealthPhase = IdleHealthPhase.IDLE,
    val idleHealthElapsedMillis: Long = 0,
    val idleHealthReport: IdleHealthReport? = null,
    val liveHealthEnabled: Boolean = false,
    /** Temporary diagnosis policy; never changes the user's ordinary dashboard selection. */
    val diagnosisAllScalars: Boolean = false,
    val liveHealthSession: LiveHealthSession? = null,
)

/** One foreground owner. All adapter operations, including cleanup, run in order on IO. */
class NativeController internal constructor(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
) {
    constructor(scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) :
        this(scope, io, clock, System::currentTimeMillis)

    private val mutable = MutableStateFlow(NativeUiState())
    val state: StateFlow<NativeUiState> = mutable.asStateFlow()
    internal val persistenceScope get() = scope
    private val liveDiagnosticLog = LiveDiagnosticLog(clock)
    fun liveDiagnosticSnapshot(): String = liveDiagnosticLog.snapshot()
    private var worker: Job? = null
    private var session: NativeSession? = null
    private var generation = 0L
    private var durableSessionId: String? = null
    private var snapshotRequested = false
    private var snapshotWithContext = false
    private var vinRequested = false
    private var idleHealthRequested = false
    private var idleHealthPermit: AtomicBoolean? = null
    private var exclusive: ExclusiveOperation? = null
    private var collector: AssessmentCollector? = null
    private var assessmentOperation: AssessmentOperation? = null
    private var assessmentPermit: AtomicBoolean? = null
    private var pendingRead = false
    private var lastExchangeCompletion: Long? = null
    private var foreground = true
    private var watchGeneration = 0L
    private var releaseFailure: String? = null
    private var healthRequirements = emptyList<InputRequirement>()
    private var liveHealthAccumulator: LiveHealthAccumulator? = null
    private var liveHealthInstanceSequence = 0L
    private var driveHistory: DriveHistoryCoordinator? = null
    private var diagnosisReceipt: ((NativeReading, Long, Long) -> Unit)? = null

    /** Receives each accepted live receipt once; consumers must enqueue, never perform IO here. */
    fun attachDiagnosisRecorder(recorder: (NativeReading, Long, Long) -> Unit) {
        check(diagnosisReceipt == null) { "A diagnosis recorder is already attached" }
        diagnosisReceipt = recorder
    }

    fun attachDriveHistory(history: DriveHistoryCoordinator) {
        if (driveHistory === history) return
        driveHistory = history
        if (state.value.origin == ReadingOrigin.HARDWARE) state.value.liveHealthSession?.let(history::record)
    }

    private fun plan(dashboard: Map<Int, WatchRate>, advertised: Set<Int>) =
        if (state.value.liveHealthEnabled) LiveHealthInputs.plan(dashboard, advertised, state.value.diagnosisAllScalars)
        else HealthInputPlanner.plan(dashboard, healthRequirements, advertised)

    private fun record(observation: Observation) {
        val previous = state.value.healthObservations.lastOrNull { it.definition.id == observation.definition.id &&
            it.sessionGeneration == observation.sessionGeneration }
        val pid = observation.definition.pid?.substring(2)?.toInt(16)
        val cadence = if (pid != null && observation.availability == SignalAvailability.AVAILABLE &&
            previous?.availability == SignalAvailability.AVAILABLE &&
            observation.receivedAtMonotonicMillis > previous.receivedAtMonotonicMillis)
            state.value.healthCadenceMillis + (pid to observation.receivedAtMonotonicMillis - previous.receivedAtMonotonicMillis)
        else if (pid != null && observation.availability != SignalAvailability.AVAILABLE) state.value.healthCadenceMillis - pid
        else state.value.healthCadenceMillis
        val others = state.value.healthObservations.filter { it.definition.id != observation.definition.id }
        val same = if (observation.availability == SignalAvailability.AVAILABLE &&
            previous?.availability == SignalAvailability.AVAILABLE)
            state.value.healthObservations.filter { it.definition.id == observation.definition.id }.takeLast(63)
        else emptyList()
        // An unavailable receipt supersedes the old window; recovery begins a fresh window.
        val retained = others + same + observation
        mutable.value = state.value.copy(healthObservations = retained, healthCadenceMillis = cadence)
    }

    private fun endReceipts(availability: SignalAvailability, reason: ObservationQualityReason, ticket: Long) {
        val current = state.value
        (current.healthPlan.acquisition.keys + current.healthPlan.structured.keys).forEach { pid ->
            record(HealthObservationBridge.unavailable(pid, SignalSupport.SUPPORTED, ObservationSource.HARDWARE,
                ticket, clock(), availability, reason))
        }
    }

    fun showMessage(message: String) { mutable.value = state.value.copy(detail = message) }

    fun connect(factory: () -> NativeTransport) {
        if (!foreground || state.value.busy || state.value.cleanupUncertain || worker?.isActive == true) return
        val ticket = ++generation
        durableSessionId = java.util.UUID.randomUUID().toString()
        liveDiagnosticLog.opening(ticket)
        snapshotRequested = false; snapshotWithContext = false; vinRequested = false; idleHealthRequested = false
        idleHealthPermit?.set(false); idleHealthPermit = null
        exclusive = null; collector = null; lastExchangeCompletion = null
        liveHealthAccumulator = null
        val previous = state.value
        val hardwareSnapshot = previous.origin == ReadingOrigin.HARDWARE
        mutable.value = NativeUiState(busy = true, sessionGeneration = ticket,
            snapshot = previous.snapshot.takeIf { hardwareSnapshot },
            scanCapture = previous.scanCapture.takeIf { hardwareSnapshot },
            scanCaptureVin = previous.scanCaptureVin.takeIf { hardwareSnapshot },
            storedDtcs = previous.storedDtcs.takeIf { hardwareSnapshot && previous.snapshot != null },
            diagnosticContext = previous.diagnosticContext.takeIf { hardwareSnapshot && previous.snapshot != null },
            snapshotSessionGeneration = previous.snapshotSessionGeneration.takeIf { hardwareSnapshot },
            snapshotCapturedMillis = previous.snapshotCapturedMillis.takeIf { hardwareSnapshot },
            snapshotSequence = previous.snapshotSequence,
            snapshotEligiblePids = previous.snapshotEligiblePids.takeIf { hardwareSnapshot } ?: emptySet(),
            snapshotVin = previous.snapshotVin.takeIf { hardwareSnapshot },
            idleHealthReport = previous.idleHealthReport.takeIf { hardwareSnapshot },
            liveHealthSession = previous.liveHealthSession.takeIf { hardwareSnapshot },
            detail = "Opening adapter and discovering supported PIDs…")
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failed = false
            try {
                val opened = NativeSession(factory()).also { session = it }
                val ownerJob = currentCoroutineContext().job
                val capabilities = withContext(io + NonCancellable) { opened.connect { ownerJob.ensureActive() } }
                ensureActive()
                if (ticket != generation) return@launch
                val dashboard = defaultLiveWatch(capabilities.pids)
                liveDiagnosticLog.connected(ticket, capabilities.sourceEcu)
                mutable.value = state.value.copy(connected = true, busy = false, identity = opened.identity,
                    sourceEcu = capabilities.sourceEcu,
                    supported = capabilities.pids, nowMillis = clock(), watched = dashboard,
                    healthPlan = plan(dashboard, capabilities.pids),
                    detail = "Connected." + (capabilities.sourceEcu?.let { " ECU response ID $it selected (RPM support, then lowest ID); readings use only this source." } ?: "") +
                        " All ${dashboard.size} supported numeric sensors selected. Start live data explicitly. More sensors can mean slower updates; pinning changes display only.")
                lastExchangeCompletion = clock()
                var schedule = LivePollSchedule(clock())
                var observedWatch = watchGeneration
                while (isActive && ticket == generation) {
                    mutable.value = state.value.copy(nowMillis = clock())
                    if (state.value.stopPending) mutable.value = state.value.copy(stopPending = false, busy = false,
                        detail = "Acquisition stopped. No new reads will begin; adapter remains connected.")
                    if (observedWatch != watchGeneration) {
                        schedule = LivePollSchedule(clock()); observedWatch = watchGeneration
                    }
                    processAssessment(opened, ticket)
                    if (idleHealthRequested) {
                        idleHealthRequested = false
                        processIdleHealth(opened, ticket, ownerJob)
                        continue
                    }
                    if (vinRequested) {
                        vinRequested = false
                        mutable.value = state.value.copy(busy = true)
                        val vin = withContext(io + NonCancellable) { opened.identifyVin() }
                        ensureActive()
                        if (ticket != generation) break
                        lastExchangeCompletion = clock(); exclusive = null
                        mutable.value = state.value.copy(busy = false, vin = vin,
                            snapshotVin = if (state.value.snapshotSessionGeneration == ticket) vin else state.value.snapshotVin,
                            vinDetail = if (vin == null) "VIN unavailable: adapter returned NO DATA." else "VIN reported by ECU; make/model/year remain unknown.")
                    }
                    if (snapshotRequested) {
                        snapshotRequested = false
                        val includeContext = snapshotWithContext
                        snapshotWithContext = false
                        mutable.value = state.value.copy(busy = true, detail = "Collecting supported snapshot PIDs…")
                        val acquisitionStart = wallClock()
                        val acquisitionClock = clock()
                        val acquisitionState = state.value
                        val scan = withContext(io + NonCancellable) {
                            val readings = opened.snapshot { ownerJob.ensureActive() }
                            ownerJob.ensureActive()
                            val context = if (includeContext) opened.readDiagnosticContext { ownerJob.ensureActive() } else null
                            val stored = context?.codes?.first { it.service == DtcService.STORED }?.let {
                                StoredDtcRead(it.codes.takeIf { _ -> it.status == DiagnosticReplyStatus.AVAILABLE }, it.sourceEcu)
                            } ?: opened.readStoredDtcs { ownerJob.ensureActive() }
                            Triple(readings, stored, context)
                        }
                        ensureActive()
                        if (ticket != generation) break
                        lastExchangeCompletion = clock(); exclusive = null
                        mutable.value = state.value.copy(busy = false, snapshot = scan.first,
                            storedDtcs = scan.second, diagnosticContext = scan.third,
                            scanCapture = capture(acquisitionState, scan.first, scan.second, scan.third, acquisitionStart, acquisitionClock),
                            snapshotSessionGeneration = ticket, snapshotCapturedMillis = clock(),
                            snapshotSequence = state.value.snapshotSequence + 1,
                            snapshotVin = acquisitionState.vin, scanCaptureVin = acquisitionState.vin,
                            snapshotEligiblePids = state.value.supported.intersect(ObdDecoder.snapshotPids)
                                .intersect(ObdDecoder.catalog.keys).minus(ObdDecoder.rangeBases),
                            snapshotInProgress = false,
                            detail = "Scan captured · ${scan.first.size} readings" + (if (includeContext) " with warning context." else ".") + " Export omits VIN unless selected.")
                    }
                    val now = clock()
                    val planned = state.value.healthPlan.acquisition.map { (pid, rate) -> PlannedRead(pid, rate, false) } +
                        state.value.healthPlan.structured.map { (pid, rate) -> PlannedRead(pid, rate, true) }
                    val eligible = planned.filter { (pid, _, structured) ->
                        state.value.liveRunning &&
                            (state.value.liveHealthEnabled || ((!structured || state.value.watched.isEmpty()) &&
                            (pid in state.value.watched || state.value.watched.isEmpty())))
                    }
                    val rates = eligible.associate { it.pid to it.rate }
                    val overrides = if (state.value.liveHealthEnabled) LiveHealthInputs.intervalOverrides else emptyMap()
                    val guards = if (state.value.liveHealthEnabled) LiveHealthInputs.freshnessGuards else emptyMap()
                    val duePid = schedule.next(rates, now, overrides, guards)
                    val due = eligible.firstOrNull { it.pid == duePid }
                    if (due != null) {
                        pendingRead = true
                        var started = now
                        val completed = withContext(io + NonCancellable) {
                            started = clock()
                            val reading = if (due.structured) opened.readStructured(due.pid) else opened.read(due.pid)
                            TimedReading(reading, clock())
                        }
                        val reading = completed.reading
                        ensureActive()
                        if (ticket != generation) break
                        val receipt = completed.receivedMillis
                        lastExchangeCompletion = receipt
                        pendingRead = false
                        if (exclusive == null && !state.value.stopPending) mutable.value = state.value.copy(busy = false)
                        schedule.completed(due.pid, started, receipt, reading.value != null)
                        if (state.value.liveRunning && observedWatch == watchGeneration &&
                            (if (due.structured) due.pid in state.value.healthPlan.structured
                            else due.pid in state.value.healthPlan.acquisition)) {
                            liveDiagnosticLog.completed(ticket, reading, started, receipt)
                            diagnosisReceipt?.invoke(reading, receipt, ticket)
                            if (state.value.liveHealthEnabled) {
                                liveHealthAccumulator?.offer(reading, receipt)
                                mutable.value = state.value.copy(liveHealthSession = liveHealthAccumulator?.snapshot)
                                liveHealthAccumulator?.snapshot?.let { driveHistory?.record(it) }
                            }
                            record(HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
                                ObservationSource.HARDWARE, ticket, receipt))
                            if (!due.structured && due.pid in state.value.watched && assessmentOperation == AssessmentOperation.PASSIVE) {
                                collector?.offerPassive(collector!!.instanceId, HealthObservationBridge.normalize(reading,
                                    SignalSupport.SUPPORTED, ObservationSource.HARDWARE, ticket, receipt))
                                publishAssessment(collector)
                            }
                            // Share actual monitoring receipts with display; pins and selected watch stay independent.
                            if (!due.structured && (due.pid in state.value.watched || state.value.liveHealthEnabled)) {
                                mutable.value = state.value.copy(
                                    readings = state.value.readings + (due.pid to TimedReading(reading, receipt)), nowMillis = receipt)
                            }
                        }
                        // This overlaps the transport's completion-to-write gap; it also yields
                        // the owner so Stop/lifecycle events can run between every exchange.
                        delay(org.openwrench.frontend.android.transport.LIVE_READ_GAP_MILLIS)
                    } else delay(schedule.waitMillis(rates, clock(), overrides))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation) {
                    failed = true
                    liveDiagnosticLog.failure(ticket, (error as? NativeDiagnosticException)?.kind?.name ?: error.javaClass.simpleName)
                    collector?.fail(error.message ?: "Session read failed")
                    publishAssessment(collector)
                    endReceipts(SignalAvailability.ERROR, ObservationQualityReason.READ_FAILURE, ticket)
                    mutable.value = state.value.copy(connected = false, faulted = true, detail = error.message ?: "Adapter operation failed.")
                }
            } finally { withContext(NonCancellable) {
                cancelAssessment()
                endLiveHealth(if (failed) "Session read failed" else "Adapter session ended")
                pendingRead = false; exclusive = null
                idleHealthPermit?.set(false); idleHealthPermit = null; idleHealthRequested = false
                if (!failed && ticket == generation) endReceipts(SignalAvailability.DISCONNECTED, ObservationQualityReason.SESSION_ENDED, ticket)
                mutable.value = state.value.copy(connected = false, busy = true, liveRunning = false, stopPending = false,
                    snapshotInProgress = false, idleHealthPhase = IdleHealthPhase.IDLE,
                    supported = emptySet(), watched = emptyMap(), readings = emptyMap(),
                    healthPlan = plan(emptyMap(), emptySet()))
                val released = withContext(io + NonCancellable) { release() }
                mutable.value = state.value.copy(busy = false, cleanupUncertain = !released, faulted = failed || !released,
                    detail = if (!released) releaseFailure ?: "Adapter release is unconfirmed. Retry release before reconnecting."
                    else if (failed) state.value.detail + " Reconnect explicitly to retry."
                    else "Adapter released. Connect explicitly to resume.")
            } }
        }
    }

    private fun release(): Boolean = try { session?.disconnect(); session = null; releaseFailure = null; true }
        catch (error: Exception) { releaseFailure = error.message; false }

    fun disconnect() {
        if (state.value.origin != ReadingOrigin.HARDWARE && worker?.isActive != true) {
            mutable.value = NativeUiState(sessionGeneration = ++generation); return
        }
        cancelAssessment()
        endLiveHealth("Adapter disconnected")
        liveDiagnosticLog.stop(generation, "disconnect")
        ++generation
        mutable.value = state.value.copy(sessionGeneration = generation, healthObservations = emptyList(),
            healthCadenceMillis = emptyMap())
        endReceipts(SignalAvailability.DISCONNECTED, ObservationQualityReason.SESSION_ENDED, generation)
        snapshotRequested = false; snapshotWithContext = false; vinRequested = false; idleHealthRequested = false
        idleHealthPermit?.set(false); idleHealthPermit = null; exclusive = null
        val running = worker?.isCompleted == false
        mutable.value = state.value.copy(connected = false, supported = emptySet(), readings = emptyMap(),
            watched = emptyMap(), healthPlan = plan(emptyMap(), emptySet()), busy = running, liveRunning = false, stopPending = false,
            idleHealthPhase = IdleHealthPhase.IDLE, faulted = state.value.cleanupUncertain,
            detail = if (running) "Releasing adapter…" else "Disconnected.")
        worker?.cancel()
    }

    fun retryRelease() {
        if (state.value.busy || !state.value.cleanupUncertain) return
        mutable.value = state.value.copy(busy = true)
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                val released = withContext(io) { release() }
                mutable.value = state.value.copy(busy = false, cleanupUncertain = !released, faulted = !released,
                    detail = if (released) "Adapter released." else releaseFailure ?: "Adapter release remains unconfirmed.")
            }
        }
    }

    fun setActive(active: Boolean) {
        foreground = active
        if (!active && state.value.origin == ReadingOrigin.HARDWARE) {
            endLiveHealth("App left the foreground")
            disconnect()
        }
    }
    suspend fun shutdown() { disconnect(); worker?.join() }

    fun startLive(): ControllerAdmission {
        admission()?.let { return it }
        if (state.value.healthPlan.acquisition.isEmpty() && state.value.healthPlan.structured.isEmpty()) return ControllerAdmission.NO_INPUTS
        ++watchGeneration
        liveDiagnosticLog.start(generation, state.value.watched)
        mutable.value = state.value.copy(liveRunning = true, readings = emptyMap(), healthObservations = emptyList(),
            healthCadenceMillis = emptyMap(), healthPlan = plan(state.value.watched, state.value.supported),
            detail = "Live acquisition running. Adapter response time determines the actual cadence.")
        return ControllerAdmission.ACCEPTED
    }

    /** Joins the existing reader; no second adapter session or diagnostic sweep is started. */
    fun startLiveHealth(allSupportedScalars: Boolean = false): ControllerAdmission {
        if (state.value.origin != ReadingOrigin.HARDWARE) return ControllerAdmission.DISCONNECTED
        admission(allowLive = true)?.let { return it }
        if (collector != null || state.value.liveHealthEnabled) return ControllerAdmission.BUSY
        val combined = LiveHealthInputs.plan(state.value.watched, state.value.supported, allSupportedScalars)
        val admitted = (combined.acquisition.keys + combined.structured.keys).intersect(
            (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet())
        if (admitted.isEmpty() && (!allSupportedScalars || combined.acquisition.isEmpty())) return ControllerAdmission.NO_INPUTS
        liveHealthAccumulator = LiveHealthAccumulator(generation, state.value.sourceEcu, clock(),
            state.value.supported, admitted, ++liveHealthInstanceSequence)
        mutable.value = state.value.copy(liveHealthEnabled = true, diagnosisAllScalars = allSupportedScalars,
            liveHealthSession = liveHealthAccumulator?.snapshot, healthPlan = combined)
        liveHealthAccumulator?.snapshot?.let { driveHistory?.record(it) }
        ++watchGeneration
        if (!state.value.liveRunning) {
            // startLive uses the same monitor-aware plan and keeps the user's selection unchanged.
            val result = startLive()
            if (result != ControllerAdmission.ACCEPTED) {
                stopLiveHealth()
                return result
            }
        }
        mutable.value = state.value.copy(detail = "Live health monitoring is collecting supported context and ECU status alongside live readings. Keep the app in the foreground.")
        return ControllerAdmission.ACCEPTED
    }

    /** Stop additional monitoring reads; ordinary dashboard acquisition remains as selected. */
    fun stopLiveHealth() = endLiveHealth("Health monitoring stopped by user")

    private fun endLiveHealth(reason: String) {
        if (!state.value.liveHealthEnabled) return
        liveHealthAccumulator?.stop(clock(), reason)
        mutable.value = state.value.copy(liveHealthEnabled = false, diagnosisAllScalars = false,
            readings = state.value.readings.filterKeys { it in state.value.watched },
            liveHealthSession = liveHealthAccumulator?.snapshot,
            healthPlan = HealthInputPlanner.plan(state.value.watched, healthRequirements, state.value.supported))
        liveHealthAccumulator?.snapshot?.let { driveHistory?.record(it) }
        ++watchGeneration
    }

    fun stopLive() {
        if (!state.value.connected || !state.value.liveRunning || (state.value.busy && assessmentOperation != AssessmentOperation.PASSIVE)) return
        cancelAssessment()
        endLiveHealth("Live polling stopped")
        liveDiagnosticLog.stop(generation, "user")
        ++watchGeneration
        endReceipts(SignalAvailability.STALE, ObservationQualityReason.SESSION_ENDED, generation)
        mutable.value = state.value.copy(liveRunning = false, stopPending = true, busy = true, readings = emptyMap(),
            detail = "Stopping acquisition; waiting for the current bounded read to finish…")
    }

    /** Explicit live preset: select, display and read advertised trims using the current owner. */
    fun prepareFuelTrims(): ControllerAdmission {
        if (state.value.origin != ReadingOrigin.HARDWARE) return ControllerAdmission.DISCONNECTED
        admission(allowLive = true)?.let { return it }
        val pids = listOf(0x06, 0x07, 0x08, 0x09).filter { it in state.value.supported }
        if (pids.isEmpty()) return ControllerAdmission.NO_INPUTS
        val missing = pids.filter { it !in state.value.watched }
        if (state.value.watched.size + missing.size > MAX_LIVE_SIGNALS) return ControllerAdmission.SELECTION_FULL
        val current = state.value
        // Existing health-only trims keep their rate. New trims use the ordinary live policy.
        if (current.healthPlan.acquisition.size + pids.count { it !in current.healthPlan.acquisition } > MAX_LIVE_SIGNALS)
            return ControllerAdmission.SELECTION_FULL
        val acquisition = current.healthPlan.acquisition + pids.filter { it !in current.healthPlan.acquisition }
            .associateWith(::liveWatchRate)
        val next = current.watched + missing.associateWith { acquisition.getValue(it) }
        mutable.value = current.copy(watched = next,
            healthPlan = current.healthPlan.copy(dashboard = next, acquisition = acquisition))
        if (!current.liveRunning) return startLive()
        // The owner takes the updated plan on its next iteration. Keep receipt ages, NO DATA
        // backoff, in-flight receipts and the current monitoring instance intact.
        liveDiagnosticLog.selectionChanged(generation, next)
        return ControllerAdmission.ACCEPTED
    }

    fun watch(pid: Int, rate: WatchRate?) {
        if (!state.value.connected || state.value.busy || state.value.liveRunning || pid !in state.value.supported || pid !in ObdDecoder.scalarPids) return
        if (rate != null && pid !in state.value.watched && state.value.watched.size >= MAX_LIVE_SIGNALS) {
            showMessage("Choose at most $MAX_LIVE_SIGNALS live signals."); return
        }
        val dashboard = if (rate == null) state.value.watched - pid else state.value.watched + (pid to rate)
        mutable.value = state.value.copy(watched = dashboard, healthPlan = plan(dashboard, state.value.supported),
            readings = if (rate == null) state.value.readings - pid else state.value.readings)
    }

    /** Future health owners can declare inputs while stopped; this never starts acquisition. */
    fun setHealthRequirements(requirements: List<InputRequirement>): HealthPlanUpdate {
        val currentHardware = state.value.connected && state.value.origin == ReadingOrigin.HARDWARE
        val result = HealthInputPlanner.plan(state.value.watched, requirements,
            if (currentHardware) state.value.supported else emptySet())
        val rejection = when {
            state.value.cleanupUncertain -> HealthPlanUpdateStatus.CLEANUP_UNCERTAIN
            state.value.liveHealthEnabled -> HealthPlanUpdateStatus.BUSY
            state.value.liveRunning -> HealthPlanUpdateStatus.RUNNING
            state.value.busy -> HealthPlanUpdateStatus.BUSY
            else -> null
        }
        if (rejection != null) return HealthPlanUpdate(result, rejection)
        healthRequirements = requirements.toList()
        mutable.value = state.value.copy(healthPlan = result)
        return HealthPlanUpdate(result, if (currentHardware) HealthPlanUpdateStatus.APPLIED
            else HealthPlanUpdateStatus.DEFERRED_DISCONNECTED)
    }

    private fun admission(allowLive: Boolean = false): ControllerAdmission? = when {
        state.value.cleanupUncertain -> ControllerAdmission.CLEANUP_UNCERTAIN
        !foreground -> ControllerAdmission.NOT_FOREGROUND
        !state.value.connected -> ControllerAdmission.DISCONNECTED
        state.value.stopPending -> ControllerAdmission.STOPPING
        exclusive != null || state.value.busy -> ControllerAdmission.BUSY
        !allowLive && state.value.liveRunning -> ControllerAdmission.LIVE_RUNNING
        pendingRead && !allowLive -> ControllerAdmission.BUSY
        else -> null
    }

    fun requestVin(): ControllerAdmission = reserve(ExclusiveOperation.VIN).also {
        if (it == ControllerAdmission.ACCEPTED) vinRequested = true
    }
    fun requestSnapshot(includeWarningContext: Boolean = false): ControllerAdmission = reserve(ExclusiveOperation.SNAPSHOT).also {
        if (it == ControllerAdmission.ACCEPTED) {
            snapshotRequested = true
            snapshotWithContext = includeWarningContext
            mutable.value = state.value.copy(snapshotInProgress = true)
        }
    }
    /** One explicit read-only owner operation. Its idle condition is user-reported, not inferred. */
    fun startIdleHealth(): ControllerAdmission {
        if (state.value.origin != ReadingOrigin.HARDWARE) return ControllerAdmission.DISCONNECTED
        admission()?.let { return it }
        if (IdleHealthReportBuilder.targetPids.none { it in state.value.supported }) return ControllerAdmission.NO_INPUTS
        exclusive = ExclusiveOperation.IDLE_HEALTH
        idleHealthPermit = AtomicBoolean(true)
        idleHealthRequested = true
        mutable.value = state.value.copy(busy = true, idleHealthPhase = IdleHealthPhase.CAPTURING,
            idleHealthElapsedMillis = 0, idleHealthReport = null,
            detail = "Guided idle observation starting. Keep the parked engine idling for 60 seconds.")
        return ControllerAdmission.ACCEPTED
    }

    /** Capture cancel keeps the session; scan cancel releases it at a native guard. */
    fun cancelIdleHealth() {
        if (state.value.idleHealthPhase == IdleHealthPhase.IDLE) return
        idleHealthPermit?.set(false)
        if (state.value.idleHealthPhase == IdleHealthPhase.SCANNING) {
            mutable.value = state.value.copy(detail = "Cancelling scan and releasing adapter after the current read…")
            worker?.cancel()
        } else mutable.value = state.value.copy(detail = "Cancelling guided idle observation after the current read…")
    }
    private fun reserve(operation: ExclusiveOperation): ControllerAdmission {
        admission()?.let { return it }
        exclusive = operation
        mutable.value = state.value.copy(busy = true)
        return ControllerAdmission.ACCEPTED
    }

    private suspend fun processIdleHealth(opened: NativeSession, ticket: Long, ownerJob: Job) {
        val permit = idleHealthPermit ?: return
        val startedAt = clock()
        val startedAtWall = wallClock()
        val supported = state.value.supported.toSet()
        val sourceEcu = state.value.sourceEcu
        val pids = IdleHealthReportBuilder.targetPids.filter { it in supported }
        val otherPids = pids.filter { it != 0x0C }
        val samples = mutableListOf<IdleHealthSample>()
        val deadline = startedAt + IdleHealthReportBuilder.DURATION_MILLIS
        fun report(status: IdleHealthStatus, scan: DiagnosticScanView?, message: String?) =
            IdleHealthReportBuilder.build(ticket, sourceEcu, startedAtWall,
                wallClock().coerceAtLeast(startedAtWall),
                (clock() - startedAt).coerceAtLeast(0).coerceAtMost(IdleHealthReportBuilder.DURATION_MILLIS),
                status, supported, samples, scan, message)
        try {
            var cursor = 0
            while (ticket == generation && permit.get() && clock() < deadline &&
                samples.size < IdleHealthReportBuilder.MAX_SAMPLES) {
                // Give RPM an every-other-read slot while still cycling every other input.
                val pid = if (0x0C in pids && (otherPids.isEmpty() || cursor % 2 == 0)) 0x0C
                    else if (0x0C in pids) otherPids[(cursor / 2) % otherPids.size]
                    else pids[cursor % pids.size]
                cursor++
                pendingRead = true
                val completed = withContext(io + NonCancellable) {
                    val reading = opened.read(pid)
                    IdleHealthSample(reading, ticket, clock())
                }
                currentCoroutineContext().ensureActive()
                if (ticket != generation) return
                pendingRead = false
                lastExchangeCompletion = completed.receivedAtMonotonicMillis
                samples += completed
                mutable.value = state.value.copy(
                    idleHealthElapsedMillis = (clock() - startedAt).coerceIn(0, IdleHealthReportBuilder.DURATION_MILLIS),
                    nowMillis = clock())
                if (permit.get() && clock() < deadline)
                    delay(minOf(IdleHealthReportBuilder.READ_SPACING_MILLIS,
                        (deadline - clock()).coerceAtLeast(0)))
            }
            if (ticket != generation) return
            if (!permit.get()) {
                mutable.value = state.value.copy(idleHealthReport = report(IdleHealthStatus.CANCELLED,
                    null, "Cancelled before the scan. Captured measurements are partial."),
                    idleHealthPhase = IdleHealthPhase.IDLE, busy = false,
                    detail = "Guided idle observation cancelled; partial evidence retained.")
                return
            }
            // A capture that exhausts the bounded sample cap is not called a full 60-second run.
            if (clock() < deadline) {
                mutable.value = state.value.copy(idleHealthReport = report(IdleHealthStatus.PARTIAL,
                    null, "Sample limit reached before 60 seconds; scan not started."),
                    idleHealthPhase = IdleHealthPhase.IDLE, busy = false,
                    detail = "Guided idle observation stopped at its sample limit.")
                return
            }
            mutable.value = state.value.copy(idleHealthPhase = IdleHealthPhase.SCANNING,
                idleHealthElapsedMillis = IdleHealthReportBuilder.DURATION_MILLIS,
                detail = "Idle observation complete. Reading standard codes and readiness…")
            val acquisitionStart = wallClock()
            val acquisitionClock = clock()
            val acquisitionState = state.value
            val scan = withContext(io + NonCancellable) {
                val readings = opened.snapshot { ownerJob.ensureActive() }
                ownerJob.ensureActive()
                readings to opened.readStoredDtcs { ownerJob.ensureActive() }
            }
            currentCoroutineContext().ensureActive()
            if (ticket != generation) return
            lastExchangeCompletion = clock()
            if (!permit.get()) {
                mutable.value = state.value.copy(idleHealthReport = report(IdleHealthStatus.CANCELLED,
                    null, "Cancelled during the scan. Captured measurements are partial."),
                    idleHealthPhase = IdleHealthPhase.IDLE, busy = false,
                    detail = "Guided idle observation cancelled; scan facts omitted.")
                return
            }
            val scanState = state.value.copy(snapshot = scan.first, storedDtcs = scan.second, diagnosticContext = null,
                scanCapture = capture(acquisitionState, scan.first, scan.second, null, acquisitionStart, acquisitionClock),
                snapshotSessionGeneration = ticket, snapshotCapturedMillis = clock(),
                snapshotVin = acquisitionState.vin, scanCaptureVin = acquisitionState.vin,
                snapshotEligiblePids = supported.intersect(ObdDecoder.snapshotPids)
                    .intersect(ObdDecoder.catalog.keys).minus(ObdDecoder.rangeBases),
                snapshotInProgress = false)
            val scanView = DiagnosticScanDelivery.present(scanState)
            mutable.value = scanState.copy(idleHealthReport = report(IdleHealthStatus.COMPLETE,
                scanView, "Idle condition was supplied by the user and not verified by the app."),
                idleHealthPhase = IdleHealthPhase.IDLE, busy = false,
                detail = "Guided idle observation and read-only scan complete. Review Vehicle Health.")
        } catch (error: Exception) {
            if (ticket == generation) {
                when {
                    error is CancellationException && !permit.get() ->
                        mutable.value = state.value.copy(idleHealthReport = report(IdleHealthStatus.CANCELLED,
                            null, "Cancelled during the final scan. Adapter release requested; reconnect after cleanup is confirmed."),
                            idleHealthPhase = IdleHealthPhase.IDLE)
                    error !is CancellationException ->
                        mutable.value = state.value.copy(idleHealthReport = report(IdleHealthStatus.PARTIAL,
                            null, "A read or scan failed. No scan conclusion is available."),
                            idleHealthPhase = IdleHealthPhase.IDLE)
                }
            }
            throw error
        } finally {
            pendingRead = false
            if (ticket == generation) {
                idleHealthPermit = null
                if (exclusive == ExclusiveOperation.IDLE_HEALTH) exclusive = null
            }
        }
    }

    /** Synthetic, unregistered API. No operating context is inferred from caller assertions. */
    fun startAssessment(profile: AssessmentProfile, instanceId: String,
        operation: AssessmentOperation): ControllerAdmission {
        admission(allowLive = operation == AssessmentOperation.PASSIVE)?.let { return it }
        if (state.value.liveHealthEnabled) return ControllerAdmission.BUSY
        if (profile.inputs.any { it.contextKeys.isNotEmpty() }) return ControllerAdmission.CONTEXT_DEFERRED
        val next = AssessmentCollector(profile, instanceId, generation, ObservationSource.HARDWARE,
            state.value.supported, emptyMap(), AssessmentClock(clock),
            if (operation == AssessmentOperation.PASSIVE) AssessmentCollectionMode.PASSIVE else AssessmentCollectionMode.FOCUSED)
        collector = next
        assessmentPermit = AtomicBoolean(true)
        assessmentOperation = operation
        exclusive = ExclusiveOperation.ASSESSMENT
        mutable.value = state.value.copy(busy = operation == AssessmentOperation.FOCUSED)
        publishAssessment(next)
        return ControllerAdmission.ACCEPTED
    }

    // Android Lint treats the previous method's closing brace as a continued expression.
    @SuppressLint("SuspiciousIndentation")
    fun cancelAssessment() {
        val current = collector ?: return
        assessmentPermit?.set(false)
        current.cancel()
        publishAssessment(current)
    }

    /** Cancel the old instance first; a pending exchange blocks replacement without queuing it. */
    fun replaceAssessment(profile: AssessmentProfile, instanceId: String,
        operation: AssessmentOperation): ControllerAdmission {
        cancelAssessment()
        return startAssessment(profile, instanceId, operation)
    }

    private fun publishAssessment(current: AssessmentCollector?) {
        if (current == null || current !== collector) return
        val result = current.snapshot()
        mutable.value = state.value.copy(assessment = result)
        if (result.state != AcquisitionState.COLLECTING) {
            assessmentPermit?.set(false); assessmentPermit = null
            collector = null; assessmentOperation = null
            if (exclusive == ExclusiveOperation.ASSESSMENT) {
                exclusive = null
                // A cancelled native exchange still owns the worker until it returns.
                mutable.value = state.value.copy(busy = pendingRead)
            }
        }
    }

    // Android Lint misreads the first statement inside finally as a continuation.
    @SuppressLint("SuspiciousIndentation")
    private suspend fun processAssessment(opened: NativeSession, ticket: Long) {
        val current = collector ?: return
        val permit = assessmentPermit ?: return
        if (assessmentOperation == AssessmentOperation.FOCUSED) {
            val remainingPacing = lastExchangeCompletion?.let {
                (HealthInputPlanner.INTER_READ_MILLIS - (clock() - it)).coerceAtLeast(0)
            } ?: 0L
            val action = current.nextAction(remainingPacing + NATIVE_STANDARD_READ_TIMEOUT_MILLIS)
            if (action != null) {
                pendingRead = true
                try {
                    val previousCompletion = lastExchangeCompletion
                    val completed = withContext(io + NonCancellable) {
                        // IO scheduling can consume the budget after owner-side admission.
                        // Recheck at the wire boundary without mutating the collector off its owner context.
                        if (!permit.get()) return@withContext null
                        val now = clock()
                        val pacing = previousCompletion?.let {
                            (HealthInputPlanner.INTER_READ_MILLIS - (now - it)).coerceAtLeast(0)
                        } ?: 0L
                        if (current.deadlineMonotonicMillis - now <= pacing + NATIVE_STANDARD_READ_TIMEOUT_MILLIS)
                            return@withContext null
                        val reading = when (action) {
                            is AssessmentReadAction.Scalar -> opened.read(action.pid)
                            is AssessmentReadAction.FuelSystemStatus -> opened.readStructured(0x03)
                        }
                        TimedReading(reading, clock())
                    }
                    currentCoroutineContext().ensureActive()
                    if (completed != null) lastExchangeCompletion = completed.receivedMillis
                    if (ticket == generation && current === collector) {
                        if (completed == null) current.declineDispatchForTimeBudget(action)
                        else current.complete(action, HealthObservationBridge.normalize(completed.reading,
                            SignalSupport.SUPPORTED, ObservationSource.HARDWARE, ticket, completed.receivedMillis))
                    }
                } catch (error: Exception) {
                    if (error !is CancellationException && ticket == generation && current === collector) {
                        current.fail(action, error.message ?: "Assessment read failed")
                        publishAssessment(current)
                    }
                    throw error
                } finally {
                    pendingRead = false
                    if (collector == null && exclusive == null && !state.value.stopPending) mutable.value = state.value.copy(busy = false)
                }
            }
        }
        if (ticket == generation) publishAssessment(current)
    }

    fun demo(scenario: DemoScenario = DemoScenario.COMPLETE) {
        if (state.value.busy || state.value.cleanupUncertain || worker?.isActive == true) return
        if (scenario == DemoScenario.LOAD_ERROR) {
            mutable.value = NativeUiState(origin = ReadingOrigin.SIMULATOR,
                detail = "The selected demo fixture reports a load error. No data was loaded.")
            return
        }
        val simulator = SimulatorClient()
        simulator.connect()
        val readings = LiveParameter.entries.map { parameter ->
            val measurement = (simulator.liveParameter(parameter.identifier) as DiagnosticResult.Success).value
            val scalar = when (val value = measurement.value) {
                is DiagnosticValue.DecimalValue -> DecodedReading.Scalar(value.value)
                is DiagnosticValue.IntegerValue -> DecodedReading.Scalar(value.value.toDouble(), true)
            }
            NativeReading(parameter.pid, scalar)
        }
        simulator.disconnect()
        val labels = if (scenario == DemoScenario.MIXED) mapOf(0x0C to "Stale", 0x05 to "Unknown", 0x0B to "Unavailable", 0x11 to "Read error") else emptyMap()
        val supported = readings.map { it.pid }.toSet() - if (scenario == DemoScenario.MIXED) setOf(0x10) else emptySet()
        val receipt = clock()
        val ticket = ++generation
        val observations = readings.filter { it.pid in supported }.map { reading ->
            val label = labels[reading.pid]
            val availability = when (label) {
                "Stale" -> SignalAvailability.STALE
                "Unknown" -> SignalAvailability.NOT_SAMPLED
                "Unavailable" -> SignalAvailability.UNAVAILABLE
                "Read error" -> SignalAvailability.ERROR
                else -> SignalAvailability.AVAILABLE
            }
            val reason = when (availability) {
                SignalAvailability.STALE -> ObservationQualityReason.EXPIRED
                SignalAvailability.UNAVAILABLE -> ObservationQualityReason.NO_DATA
                SignalAvailability.ERROR -> ObservationQualityReason.READ_FAILURE
                SignalAvailability.NOT_SAMPLED -> ObservationQualityReason.SOURCE_UNVERIFIED
                else -> null
            }
            HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED, ObservationSource.SIMULATOR,
                ticket, receipt, availability, qualityReasons = reason?.let { setOf(it) } ?: emptySet())
        }
        mutable.value = NativeUiState(origin = ReadingOrigin.SIMULATOR, identity = "OpenWrench offline simulator",
            supported = supported, snapshot = if (scenario == DemoScenario.COMPLETE) readings else null, nowMillis = clock(),
            scanCapture = if (scenario == DemoScenario.COMPLETE) offlineCapture(readings, ScanOrigin.SIMULATOR) else null,
            snapshotSessionGeneration = ticket.takeIf { scenario == DemoScenario.COMPLETE },
            sessionGeneration = generation, healthObservations = observations,
            demoLabels = labels,
            readings = readings.filter { it.pid in supported && it.pid !in labels }.associate { it.pid to TimedReading(it, clock()) },
            detail = "SIMULATED · fixed reference samples. No adapter is connected.")
    }

    private fun capture(operation: NativeUiState, readings: List<NativeReading>, stored: StoredDtcRead?,
        context: DiagnosticContextRead?, started: Long, monotonicStart: Long): ScanCapture {
        val completed = wallClock()
        return ScanCapture(java.util.UUID.randomUUID().toString(), ScanOrigin.HARDWARE,
            checkNotNull(durableSessionId), operation.sourceEcu, started, completed,
            (clock() - monotonicStart).coerceAtLeast(0), completed,
            readings.map { it.pid }.toSet(), readings.map(::freezeReading), context?.codes?.map { it.copy(codes = it.codes.toList()) } ?: listOfNotNull(stored?.let {
                DiagnosticCodeRead(DtcService.STORED, if (it.codes == null) DiagnosticReplyStatus.NO_DATA else DiagnosticReplyStatus.AVAILABLE,
                    it.codes.orEmpty().toList(), it.sourceEcu)
            }), context?.freezeFrame?.let { it.copy(readings = it.readings.map(::freezeReading),
                advertisedPids = it.advertisedPids.toSet(), unavailablePids = it.unavailablePids.toMap()) })
    }

    private fun freezeReading(r: NativeReading) = r.copy(value = when (val v = r.value) {
        is DecodedReading.Structured -> v.copy(fields = v.fields.toMap())
        else -> v
    })

    private fun offlineCapture(readings: List<NativeReading>, origin: ScanOrigin) = ScanCapture(
        java.util.UUID.randomUUID().toString(), origin, null, null, null, null, null, wallClock(),
        readings.map { it.pid }.toSet(), readings.map { freezeReading(it).copy(sourceEcu = null) }, emptyList(), null)

    fun replay(readings: List<NativeReading>, identity: String?, simulated: Boolean = false, vin: String? = null) {
        if (state.value.busy || state.value.cleanupUncertain || worker?.isActive == true) return
        val ticket = ++generation
        val receipt = clock()
        val observations = readings.filter { it.pid in ObdDecoder.catalog }.map { reading ->
            HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED, ObservationSource.REPLAY,
                ticket, receipt, qualityReasons = if (reading.value == null)
                    setOf(ObservationQualityReason.NO_DATA) else emptySet())
        }
        mutable.value = NativeUiState(origin = ReadingOrigin.REPLAY, identity = identity, snapshot = readings, replaySimulated = simulated, vin = vin,
            scanCapture = offlineCapture(readings, if (simulated) ScanOrigin.IMPORTED_SIMULATOR else ScanOrigin.IMPORTED),
            snapshotSessionGeneration = ticket, snapshotVin = vin, scanCaptureVin = vin,
            sessionGeneration = ticket, healthObservations = observations,
            supported = readings.map { it.pid }.toSet(), detail = "REPLAY · saved snapshot, not current vehicle data.")
    }
}

fun valueText(value: DecodedReading?): String = when (value) {
    null -> "Unavailable · NO DATA"
    is DecodedReading.Scalar -> if (value.integer) value.value.toLong().toString() else value.value.toString()
    is DecodedReading.Structured -> value.fields.entries.joinToString("\n") { "${it.key.replace('_', ' ')}: ${it.value}" }
}
