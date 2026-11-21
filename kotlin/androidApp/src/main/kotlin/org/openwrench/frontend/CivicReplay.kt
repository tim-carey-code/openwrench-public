package org.openwrench.frontend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openwrench.core.*

/** Original capture facts. Playback never rewrites these as current observations. */
data class CivicCaptureMetadata(
    val vehicleLabel: String,
    val capturedAtUtc: String?,
    val clockBasis: String,
    val provenance: String,
)

/**
 * atMillis locates the event on the declared capture clock. For client_receipt it
 * is first receipt, never receipt minus source age. ageAtCaptureMillis is the age
 * at that event, so playback adds only time elapsed since that offset.
 * Unavailable/stale events are explicit transitions, not inferred missing reads.
 */
data class RecordedObservation(
    val atMillis: Long,
    val signal: String,
    val availability: SignalAvailability,
    val value: SignalValue?,
    val observedAtUtc: String?,
    val ageAtCaptureMillis: Long?,
    val reason: String?,
)

data class CivicRecording(
    val capture: CivicCaptureMetadata,
    val durationMillis: Long,
    val catalog: List<SignalDefinition>,
    val observations: List<RecordedObservation>,
) {
    init {
        require(durationMillis in 1..300_000)
        require(catalog.size in 1..12 && catalog.map { it.id }.distinct().size == catalog.size)
        require(catalog.all { it.support == SignalSupport.SUPPORTED && it.type in setOf(SignalType.NUMBER, SignalType.INTEGER) })
        require(observations.size in 2..10_000)
        require(observations.map { it.atMillis }.distinct().size >= 2) { "Single values are not a timed recording" }
        require(observations.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis })
        require(observations.map { it.signal to it.atMillis }.distinct().size == observations.size)
        require(observations.groupBy { it.signal }.values.any { it.size >= 2 }) { "A signal must have repeated observations" }
        observations.forEach { event ->
            require(event.atMillis in 0 until durationMillis)
            val definition = requireNotNull(catalog.singleOrNull { it.id == event.signal })
            require(event.ageAtCaptureMillis == null || event.ageAtCaptureMillis >= 0)
            require(event.availability in setOf(SignalAvailability.AVAILABLE, SignalAvailability.UNAVAILABLE, SignalAvailability.STALE))
            require((event.availability == SignalAvailability.AVAILABLE) == (event.value != null))
            // An unknown source age must never be treated as zero, which would look fresh.
            require(event.availability != SignalAvailability.AVAILABLE ||
                (event.ageAtCaptureMillis != null && event.observedAtUtc != null)) { "Available observation needs source time and age" }
            // The existing presentation contract validates support and exact numeric type.
            DashboardSignal(definition, event.availability, event.value)
        }
    }
}

sealed interface ReplayLoadResult {
    data class Loaded(val recording: CivicRecording) : ReplayLoadResult
    data class Invalid(val message: String) : ReplayLoadResult
}

enum class ReplayPhase { EMPTY, PAUSED, PLAYING }

data class CivicReplayState(
    val recording: CivicRecording? = null,
    val phase: ReplayPhase = ReplayPhase.EMPTY,
    val positionMillis: Long = 0,
    val loop: Long = 0,
    val generation: Long = 0,
    /** History contains only the current loop, never observations from the prior pass. */
    val observations: List<RecordedObservation> = emptyList(),
    val detail: String = "No usable timed Civic recording is bundled. Supply a reviewed local capture; single recorded values are insufficient.",
)

/** A small clock-driven file source. It has no adapter, HTTP client or live factory. */
class CivicReplayPlayer(private val scope: CoroutineScope, private val clock: FrontendClock = platformClock()) {
    private val mutable = MutableStateFlow(CivicReplayState())
    val state: StateFlow<CivicReplayState> = mutable.asStateFlow()
    private var worker: Job? = null
    private var lastTick = 0L
    private var active = true

    fun load(result: ReplayLoadResult) {
        pause()
        val generation = state.value.generation + 1
        mutable.value = when (result) {
            is ReplayLoadResult.Invalid -> CivicReplayState(generation = generation, detail = result.message)
            is ReplayLoadResult.Loaded -> CivicReplayState(result.recording, ReplayPhase.PAUSED,
                generation = generation, observations = result.recording.observations.takeWhile { it.atMillis == 0L },
                detail = "Recorded Civic replay loaded. Play explicitly; no vehicle is connected.")
        }
    }

    fun play() {
        if (!active || state.value.recording == null || state.value.phase == ReplayPhase.PLAYING) return
        lastTick = clock.elapsedMillis()
        mutable.value = state.value.copy(phase = ReplayPhase.PLAYING)
        worker = scope.launch {
            while (isActive) {
                val current = state.value
                val recording = current.recording ?: break
                val next = recording.observations.firstOrNull { it.atMillis > current.positionMillis }?.atMillis ?: recording.durationMillis
                // Wake at the next original event/loop boundary; 250 ms only refreshes the playhead/age display.
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
        val position = (current.positionMillis + elapsed % recording.durationMillis) % recording.durationMillis
        mutable.value = current.copy(positionMillis = position, loop = saturatingAdd(current.loop, crossed),
            generation = saturatingAdd(current.generation, crossed),
            observations = recording.observations.takeWhile { it.atMillis <= position })
    }

    fun pause() {
        if (state.value.phase == ReplayPhase.PLAYING) {
            advance()
            mutable.value = state.value.copy(phase = ReplayPhase.PAUSED)
        }
        worker?.cancel(); worker = null
    }

    fun restart() {
        val wasPlaying = state.value.phase == ReplayPhase.PLAYING
        pause()
        val current = state.value
        val recording = current.recording ?: return
        mutable.value = current.copy(positionMillis = 0, loop = 0, generation = current.generation + 1,
            observations = recording.observations.takeWhile { it.atMillis == 0L })
        if (wasPlaying) play()
    }

    fun setActive(value: Boolean) { active = value; if (!value) pause() }
    fun close() { pause(); mutable.value = CivicReplayState(generation = state.value.generation + 1) }
}

/** Same dashboard rows/cards/details as live acquisition; no new presentation implementation. */
fun CivicReplayState.dashboardSignals(): List<DashboardSignal> {
    val latest = observations.associateBy { it.signal }
    val traces = mutableMapOf<String, SignalTrend>()
    observations.forEach { event ->
        val availability = if (event.availability == SignalAvailability.AVAILABLE &&
            (event.ageAtCaptureMillis == null || event.observedAtUtc == null)) SignalAvailability.STALE else event.availability
        traces[event.signal] = (traces[event.signal] ?: SignalTrend()).receive(event.atMillis,
            availability, event.value, event.observedAtUtc ?: "recorded:${event.atMillis}", event.ageAtCaptureMillis)
    }
    return recording?.catalog.orEmpty().map { definition ->
        val event = latest[definition.id]
        val elapsed = event?.let { (positionMillis - it.atMillis).coerceAtLeast(0) }
        val age = elapsed?.let { gap -> event?.ageAtCaptureMillis?.let { saturatingAdd(gap, it) } }
        val availability = when {
            event == null -> SignalAvailability.NOT_SAMPLED
            // Directly constructed state must still have both original freshness facts.
            event.availability == SignalAvailability.AVAILABLE &&
                (event.observedAtUtc == null || age == null || age >= 5000) -> SignalAvailability.STALE
            else -> event.availability
        }
        DashboardSignal(definition, availability, event?.value?.takeIf { availability == SignalAvailability.AVAILABLE },
            observedAt = event?.observedAtUtc, ageMillis = age,
            reason = event?.let {
                "Recorded offset (${recording?.capture?.clockBasis}): ${it.atMillis} ms. Original capture age: ${it.ageAtCaptureMillis?.let { age -> "$age ms" } ?: "not supplied"}. " +
                    (it.reason ?: "Recorded observation; never a current live reading.")
            } ?: "No observation yet at this position in the recording.",
            ageLabel = "Age on recording timeline", trend = traces[definition.id] ?: SignalTrend(), trendNowMillis = positionMillis)
    }
}
