package org.openwrench.frontend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openwrench.core.SignalSupport
import org.openwrench.core.SignalValue
import org.openwrench.nativecore.ObdDecoder
import org.openwrench.frontend.android.*

/** A read-only view of retained chart receipts, not a reconstructed full trip. */
class SavedDrivePlayback private constructor(
    val drive: SavedDrive,
    val firstOffsetMillis: Long,
    val lastOffsetMillis: Long,
) {
    // The labelled end hold makes the final receipt visible before the next pass.
    val durationMillis: Long get() = lastOffsetMillis - firstOffsetMillis + END_HOLD_MILLIS
    val eventOffsets = drive.review.series.flatMap { it.points }.map { it.offsetMillis }.distinct().sorted()
    companion object {
        const val END_HOLD_MILLIS = 1_000L
        fun from(drive: SavedDrive): SavedDrivePlayback {
            // Reuse storage validation without writing or migrating the source file.
            DriveHistoryCodec.encode(drive)
            require(drive.review.series.any { series ->
                series.points.filter { it.value != null }.map { it.offsetMillis }.distinct().size >= 2
            }) { "This drive has no repeated retained numeric receipts to replay. Choose another drive." }
            val points = drive.review.series.flatMap { it.points }
            return SavedDrivePlayback(drive, points.minOf { it.offsetMillis }, points.maxOf { it.offsetMillis })
        }
    }
}

fun SavedDrive.replayOriginLabel(): String = when (origin) {
    DriveEvidenceOrigin.HARDWARE -> "Recorded hardware source"
    DriveEvidenceOrigin.SYNTHETIC -> "Synthetic recording · fictional values"
    DriveEvidenceOrigin.UNKNOWN -> "Original source unknown"
}

/** Must be checked again after asynchronous file IO, not only when opening a picker. */
internal fun canStartDriveReplay(state: NativeUiState): Boolean =
    !state.connected && !state.busy && !state.cleanupUncertain && !state.liveRunning && !state.stopPending

data class SavedDriveReplayState(
    val recording: SavedDrivePlayback? = null,
    val phase: ReplayPhase = ReplayPhase.EMPTY,
    val positionMillis: Long = 0,
    val loop: Long = 0,
    val generation: Long = 0,
    val selectionGeneration: Long = 0,
) {
    val originalOffsetMillis: Long get() = (recording?.firstOffsetMillis ?: 0) + positionMillis
}

/** Presentation clock only. There is no controller, transport or history writer here. */
class SavedDriveReplayPlayer(private val scope: CoroutineScope, private val clock: FrontendClock = platformClock()) {
    private val mutable = MutableStateFlow(SavedDriveReplayState())
    val state = mutable.asStateFlow()
    private var worker: Job? = null
    private var lastTick = 0L
    private var active = true

    fun load(recording: SavedDrivePlayback) {
        pause()
        mutable.value = SavedDriveReplayState(recording, ReplayPhase.PAUSED, generation = state.value.generation + 1, selectionGeneration = state.value.selectionGeneration + 1)
    }
    fun play() {
        if (!active || state.value.recording == null || state.value.phase == ReplayPhase.PLAYING) return
        lastTick = clock.elapsedMillis()
        mutable.value = state.value.copy(phase = ReplayPhase.PLAYING)
        worker = scope.launch {
            while (isActive) {
                val current = state.value
                val recording = current.recording ?: break
                val next = recording.eventOffsets.firstOrNull { it > current.originalOffsetMillis }
                    ?.minus(recording.firstOffsetMillis) ?: recording.durationMillis
                delay(minOf(250L, next - current.positionMillis).coerceAtLeast(1))
                ensureActive()
                advance()
            }
        }
    }
    private fun advance() {
        val current = state.value
        val recording = current.recording ?: return
        val now = clock.elapsedMillis()
        val elapsed = (now - lastTick).coerceAtLeast(0)
        lastTick = now
        val crossed = elapsed / recording.durationMillis +
            (current.positionMillis + elapsed % recording.durationMillis) / recording.durationMillis
        mutable.value = current.copy(
            positionMillis = (current.positionMillis + elapsed % recording.durationMillis) % recording.durationMillis,
            loop = saturatingAdd(current.loop, crossed), generation = saturatingAdd(current.generation, crossed))
    }
    fun pause() {
        if (state.value.phase == ReplayPhase.PLAYING) {
            advance()
            mutable.value = state.value.copy(phase = ReplayPhase.PAUSED)
        }
        worker?.cancel(); worker = null
    }
    fun restart() {
        val playing = state.value.phase == ReplayPhase.PLAYING
        pause()
        if (state.value.recording == null) return
        mutable.value = state.value.copy(positionMillis = 0, loop = 0, generation = state.value.generation + 1, selectionGeneration = state.value.selectionGeneration + 1)
        if (playing) play()
    }
    fun setActive(value: Boolean) { active = value; if (!value) pause() }
    fun close() { pause(); mutable.value = SavedDriveReplayState(generation = state.value.generation + 1, selectionGeneration = state.value.selectionGeneration + 1) }
}

/** Render actual saved receipt offsets. No UTC/source age is inferred from wall-clock metadata. */
fun SavedDriveReplayState.dashboardSignals(): List<DashboardSignal> {
    val playback = recording ?: return emptyList()
    val at = originalOffsetMillis
    return playback.drive.review.series.map { series ->
        val receipts = series.points.takeWhile { it.offsetMillis <= at }
        val latest = receipts.lastOrNull()
        val age = latest?.let { at - it.offsetMillis }
        val availability = when {
            latest == null -> SignalAvailability.NOT_SAMPLED
            latest.value == null -> SignalAvailability.UNAVAILABLE
            age!! >= DASHBOARD_RECEIPT_STALE_MILLIS -> SignalAvailability.STALE
            else -> SignalAvailability.AVAILABLE
        }
        val points = receipts.filter { it.offsetMillis >= at - TREND_WINDOW_MILLIS }.flatMap { receipt ->
            val point = TrendPoint(receipt.offsetMillis, receipt.value, null,
                saturatingAdd(receipt.offsetMillis, minOf(DASHBOARD_RECEIPT_STALE_MILLIS, series.maximumLinkGapMillis)))
            // A separator is presentation metadata, never a numeric sample or inferred receipt.
            if (receipt.breakBefore) listOf(TrendPoint(receipt.offsetMillis, null, null, receipt.offsetMillis), point)
            else listOf(point)
        }
        // SUPPORTED here means a decodable retained channel, not an ECU capability claim.
        DashboardSignal(nativeSignalDefinition(series.pid, SignalSupport.SUPPORTED), availability,
            value = latest?.value?.takeIf { availability == SignalAvailability.AVAILABLE }?.let {
                if (ObdDecoder.isIntegerScalar(series.pid)) SignalValue.Integer(it.toLong()) else SignalValue.Number(it)
            },
            observedAt = null, ageMillis = age,
            ageLabel = "Since saved receipt on replay",
            reason = "${playback.drive.replayOriginLabel()}. Retained archive channel; ECU capability support is unverified. " +
                (latest?.let { "Original receipt offset: +${it.offsetMillis} ms. " } ?: "No retained receipt yet. ") +
                "ECU observation time and source age were not stored. Retained chart receipts only; gaps and omitted receipts are not reconstructed. Not live.",
            trend = SignalTrend(points), trendNowMillis = at, retainedReplay = true)
    }
}
