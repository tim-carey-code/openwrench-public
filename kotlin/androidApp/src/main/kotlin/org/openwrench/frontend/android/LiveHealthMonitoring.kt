package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder

/** Scheduling policy for the optional foreground monitor, independent of dashboard selection. */
internal object LiveHealthInputs {
    val scalarPids = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x42, 0x04, 0x11, 0x08, 0x09, 0x10, 0x0B)
    val structuredPids = listOf(0x03, 0x01)
    val intervalOverrides = mapOf(0x01 to 30_000L)
    // Best-effort protection for context used to label live evidence, not a
    // freshness guarantee. Slow/absent replies still become stale normally.
    val freshnessGuards = mapOf(0x0D to 5_000L, 0x03 to 5_000L, 0x01 to 35_000L)
    const val CONTEXT_AGE_MILLIS = 10_000L
    const val SIGNAL_AGE_MILLIS = 15_000L
    const val ECU_AGE_MILLIS = 45_000L
    const val WINDOW_MILLIS = 120_000L
    const val SAMPLES_PER_PID = 64

    fun plan(dashboard: Map<Int, WatchRate>, supported: Set<Int>, allSupportedScalars: Boolean = false): HealthInputPlan {
        val acquisition = (if (allSupportedScalars) defaultLiveWatch(supported) else dashboard).toMutableMap()
        for (pid in scalarPids) if (pid in supported && pid !in acquisition && acquisition.size < MAX_LIVE_SIGNALS)
            acquisition[pid] = when (pid) {
                0x0C -> WatchRate.HIGH
                0x0D -> WatchRate.NORMAL
                else -> WatchRate.LOW
            }
        return HealthInputPlan(dashboard.toMap(), acquisition,
            emptyList(), structuredPids.filter { it in supported }.associateWith { WatchRate.LOW })
    }
}

enum class LiveHealthPhase { NOT_STARTED, RUNNING, PAUSED, HISTORICAL }

data class LiveHealthSignalView(
    val pid: Int, val name: String, val unit: String?, val supported: Boolean, val admitted: Boolean,
    val availability: String, val latestValue: Double?, val ageMillis: Long?, val sampleCount: Int,
    val minimum: Double?, val maximum: Double?,
)
data class LiveHealthContextView(
    val condition: String, val fuelSystemStatus: String, val sampleCount: Long, val spanMillis: Long,
    val signals: List<LiveHealthSignalView>,
)
/** Facts frozen at the run's end/last receipt. They never qualify as current observations. */
data class LiveHealthRecordedSummary(
    val frozenAtOffsetMillis: Long,
    val condition: String, val fuelSystemStatus: String, val contextNote: String,
    val ecuStatus: String, val ecuReceiptOffsetMillis: Long?, val ecuAgeAtEndMillis: Long?,
    val flags: List<String>, val monitors: List<DiagnosticMonitorView>,
)
data class LiveHealthView(
    val phase: LiveHealthPhase,
    val sessionGeneration: Long?, val sourceEcu: String?, val elapsedMillis: Long,
    val currentCondition: String, val fuelSystemStatus: String, val receiptCount: Long,
    val signals: List<LiveHealthSignalView>, val contexts: List<LiveHealthContextView>,
    val coverage: List<String>, val ecuStatus: String, val monitors: List<DiagnosticMonitorView>,
    val flags: List<String>,
    val driveReview: LiveHealthDriveReview? = null,
    val recordedSummary: LiveHealthRecordedSummary? = null,
) {
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH live health monitoring")
        appendLine("Report format: live-health/2")
        appendLine("Status: ${phase.name.lowercase()}; session: ${sessionGeneration ?: "none"}; source: ${sourceEcu ?: "unknown"}")
        appendLine("Monitored duration: $elapsedMillis ms; receipts: $receiptCount")
        driveReview?.let { appendLine("Recorded scalar evidence: ${it.signals.size} signals; ${it.signals.sumOf { signal -> signal.sampleCount }} valid scalar receipts") }
        if (phase == LiveHealthPhase.RUNNING) {
            appendLine("Current observed condition: $currentCondition")
            appendLine("Fuel control: $fuelSystemStatus")
            appendLine("ECU status: $ecuStatus")
            flags.forEach { appendLine("ECU attention: $it") }
            monitors.forEach { appendLine("Monitor ${it.name}: available=${it.availability}, complete=${it.completion}") }
        } else if (recordedSummary != null) {
            appendLine("The following observations are historical at the saved point, not current vehicle status.")
            appendLine("Recorded condition at saved point: ${recordedSummary.condition}")
            appendLine("Recorded fuel control at saved point: ${recordedSummary.fuelSystemStatus}")
            appendLine(recordedSummary.contextNote)
            appendLine("Recorded ECU status: ${recordedSummary.ecuStatus}")
            recordedSummary.flags.forEach { appendLine("Recorded ECU attention: $it") }
            recordedSummary.monitors.forEach { appendLine("Recorded monitor ${it.name}: available=${it.availability}, complete=${it.completion}") }
        } else if (phase == LiveHealthPhase.NOT_STARTED) appendLine("No monitoring run has started; no live or recorded vehicle status is available.")
        else appendLine("A recorded summary is unavailable; retained session evidence follows.")
        coverage.forEach { appendLine("Coverage: $it") }
        if (phase == LiveHealthPhase.RUNNING) {
            appendLine("Current condition window (up to 120 seconds, at most 64 receipts per signal):")
            signals.forEach { signal -> appendLine(signal.reportLine()) }
        }
        appendLine("Earlier/session context aggregates (not current findings; spans may include gaps):")
        contexts.forEach { bucket ->
            appendLine("${bucket.condition}; ${bucket.fuelSystemStatus}: ${bucket.sampleCount} receipts; first-to-last receipt span ${bucket.spanMillis} ms (may include gaps; not time spent in this condition)")
            bucket.signals.forEach { appendLine(it.reportLine()) }
        }
        driveReview?.let { appendLine(it.reportText()) }
        appendLine("Receipt times describe adapter observations, not simultaneous ECU samples. Missing or stale context grants no operating-condition conclusion.")
        appendLine("Stored-code identifiers require the separate diagnostic scan; no current absence of stored codes is inferred here. VIN omitted.")
        appendLine("Observed trends and ECU flags do not establish a failed part or whole-vehicle health.")
    }
}

private fun LiveHealthSignalView.reportLine() =
    "01${pid.toString(16).uppercase().padStart(2, '0')} $name: $availability; latest=${latestValue ?: "unknown"}${unit?.let { " $it" } ?: ""}; age=${ageMillis ?: "unknown"}ms; samples=$sampleCount; range=${minimum ?: "unknown"}..${maximum ?: "unknown"}"

data class LiveHealthReceipt(val reading: NativeReading, val receivedAtMillis: Long)
data class LiveHealthContextKey(val condition: String, val fuel: String)
data class LiveHealthAggregate(
    val pid: Int, val count: Long, val firstAtMillis: Long, val lastAtMillis: Long,
    val latest: Double, val minimum: Double, val maximum: Double,
)
data class LiveHealthContextBucket(
    val key: LiveHealthContextKey, val receipts: Long, val firstAtMillis: Long, val lastAtMillis: Long,
    val signals: Map<Int, LiveHealthAggregate>,
)
/** Immutable, bounded state. No VIN, identity guesses, raw frames or unbounded trip recording. */
data class LiveHealthSession(
    val generation: Long, val sourceEcu: String?, val startedAtMillis: Long, val stoppedAtMillis: Long?,
    val supported: Set<Int>, val admitted: Set<Int>, val receiptCount: Long,
    val latest: Map<Int, LiveHealthReceipt>, val segmentKey: LiveHealthContextKey,
    val segment: List<LiveHealthReceipt>, val contexts: List<LiveHealthContextBucket>,
    val driveReview: LiveHealthDriveReview? = null,
    val endReason: String? = null,
)

internal class LiveHealthAccumulator(
    generation: Long, sourceEcu: String?, startedAtMillis: Long,
    supported: Set<Int>, admitted: Set<Int>, instanceId: Long = startedAtMillis,
) {
    private val driveRecorder = LiveHealthDriveRecorder(generation, instanceId, startedAtMillis, retainCharts = false)
    private val telemetryRecorder = SavedDriveTelemetryRecorder(startedAtMillis, supported)
    private var lastReceiptAt = startedAtMillis
    private val receiptTimes = mutableMapOf<Int, Long>()
    private val assessmentRecorder = DriveHealthAssessmentRecorder(startedAtMillis, supported.toSet(), admitted.toSet())
    private fun driveSnapshot() = telemetryRecorder.applyTo(driveRecorder.snapshot()).copy(assessment = assessmentRecorder.snapshot())
    var snapshot = LiveHealthSession(generation, sourceEcu, startedAtMillis, null, supported.toSet(),
        admitted.toSet(), 0, emptyMap(), LiveHealthContextKey("Unknown", "Unknown"), emptyList(), emptyList(), driveSnapshot())
        private set

    fun stop(now: Long, reason: String = "Monitoring stopped") {
        driveRecorder.stop(now, reason)
        telemetryRecorder.stop(now)
        assessmentRecorder.stop(now)
        snapshot = snapshot.copy(stoppedAtMillis = maxOf(now, snapshot.startedAtMillis), driveReview = driveSnapshot(), endReason = reason)
    }

    fun offer(reading: NativeReading, at: Long) {
        if (snapshot.stoppedAtMillis != null || at < snapshot.startedAtMillis || at < lastReceiptAt ||
            receiptTimes[reading.pid] == at ||
            (reading.pid !in snapshot.admitted && reading.pid !in telemetryRecorder.pids)) return
        lastReceiptAt = at
        receiptTimes[reading.pid] = at
        telemetryRecorder.offer(reading, at)
        if (reading.pid !in snapshot.admitted) {
            snapshot = snapshot.copy(driveReview = driveSnapshot())
            return
        }
        val receipt = LiveHealthReceipt(reading, at)
        val latest = snapshot.latest + (reading.pid to receipt)
        val key = liveContext(latest, at)
        driveRecorder.offer(reading, at, key)
        assessmentRecorder.offer(reading, at)
        val retained = (if (key == snapshot.segmentKey && liveContext(snapshot.latest, at) == snapshot.segmentKey)
            snapshot.segment else emptyList())
            .filter { at >= it.receivedAtMillis && at - it.receivedAtMillis <= LiveHealthInputs.WINDOW_MILLIS }
        // A NO DATA reply invalidates that input's old run. Recovery starts a fresh window.
        val same = if (reading.value == null || snapshot.latest[reading.pid]?.reading?.value == null) emptyList()
            else retained.filter { it.reading.pid == reading.pid }.takeLast(LiveHealthInputs.SAMPLES_PER_PID - 1)
        val segment = retained.filter { it.reading.pid != reading.pid } + same + receipt
        val contexts = snapshot.contexts.toMutableList()
        val index = contexts.indexOfFirst { it.key == key }
        val prior = contexts.getOrNull(index)
        val signals = prior?.signals.orEmpty().toMutableMap()
        val value = (reading.value as? DecodedReading.Scalar)?.value?.takeIf { it.isFinite() }
        if (value != null) {
            val old = signals[reading.pid]
            signals[reading.pid] = LiveHealthAggregate(reading.pid, (old?.count ?: 0) + 1,
                old?.firstAtMillis ?: at, at, value, minOf(old?.minimum ?: value, value), maxOf(old?.maximum ?: value, value))
        }
        val bucket = LiveHealthContextBucket(key, (prior?.receipts ?: 0) + 1,
            prior?.firstAtMillis ?: at, at, signals)
        if (index >= 0) contexts.removeAt(index)
        contexts += bucket
        snapshot = snapshot.copy(latest = latest, segmentKey = key, segment = segment,
            contexts = contexts.takeLast(16), receiptCount = snapshot.receiptCount + 1, driveReview = driveSnapshot())
    }
}

private fun fresh(latest: Map<Int, LiveHealthReceipt>, pid: Int, now: Long, maximumAge: Long): LiveHealthReceipt? =
    latest[pid]?.takeIf { now >= it.receivedAtMillis && now - it.receivedAtMillis <= maximumAge && it.reading.value != null }
private fun scalar(latest: Map<Int, LiveHealthReceipt>, pid: Int, now: Long): Double? =
    (fresh(latest, pid, now, LiveHealthInputs.CONTEXT_AGE_MILLIS)?.reading?.value as? DecodedReading.Scalar)?.value?.takeIf { it.isFinite() }
private fun liveContext(latest: Map<Int, LiveHealthReceipt>, now: Long): LiveHealthContextKey {
    val speed = scalar(latest, 0x0D, now)
    val rpm = scalar(latest, 0x0C, now)
    val condition = when {
        speed == null -> "Unknown"
        speed > 0 -> "Moving"
        speed == 0.0 && rpm != null && rpm > 0 -> "Stationary with engine running"
        speed == 0.0 && rpm == 0.0 -> "Engine stopped"
        else -> "Unknown"
    }
    val fuel = (fresh(latest, 0x03, now, LiveHealthInputs.CONTEXT_AGE_MILLIS)?.reading?.value as? DecodedReading.Structured)
        ?.fields?.filterKeys { it.startsWith("system_") }?.entries?.sortedBy { it.key }
        ?.joinToString("; ") { "${it.key.replace('_', ' ')}: ${it.value}" }?.takeIf { it.isNotBlank() } ?: "Unknown"
    return LiveHealthContextKey(condition, fuel)
}

object LiveHealthDelivery {
    fun present(state: NativeUiState, nowMonotonicMillis: Long = state.nowMillis): LiveHealthView {
        val session = state.liveHealthSession
        val active = session != null && state.liveHealthEnabled && state.connected && state.liveRunning &&
            state.origin == ReadingOrigin.HARDWARE && session.generation == state.sessionGeneration &&
            !state.faulted && !state.cleanupUncertain
        val phase = when {
            session == null -> LiveHealthPhase.NOT_STARTED
            !state.connected || state.origin != ReadingOrigin.HARDWARE || session.generation != state.sessionGeneration -> LiveHealthPhase.HISTORICAL
            active -> LiveHealthPhase.RUNNING
            else -> LiveHealthPhase.PAUSED
        }
        val now = nowMonotonicMillis.coerceAtLeast(0)
        val frozenAt = session?.let { s -> s.stoppedAtMillis ?: (s.startedAtMillis +
            (s.driveReview?.elapsedMillis ?: ((s.latest.values.filter { it.receivedAtMillis in s.startedAtMillis..now }
                .maxOfOrNull { it.receivedAtMillis } ?: s.startedAtMillis) - s.startedAtMillis).coerceAtLeast(0))) }
        val recorded = if (!active && session != null && frozenAt != null) recordedSummary(session, frozenAt) else null
        val key = if (active) liveContext(session!!.latest, now) else LiveHealthContextKey("Unknown", "Unknown")
        val segment = session?.segment.orEmpty().takeIf { active && key == session?.segmentKey }.orEmpty()
            .filter { now >= it.receivedAtMillis && now - it.receivedAtMillis <= LiveHealthInputs.WINDOW_MILLIS }
        val signals = LiveHealthInputs.scalarPids.map { pid ->
            signal(pid, session?.supported.orEmpty(), session?.admitted.orEmpty(), segment, now, active)
        }
        val statusReceipt = session?.latest?.let { if (active) fresh(it, 0x01, now, LiveHealthInputs.ECU_AGE_MILLIS) else null }
        val status = (statusReceipt?.reading?.value as? DecodedReading.Structured)?.fields.orEmpty()
        val mil = status["mil_on"]?.toBooleanStrictOrNull()
        val count = status["dtc_count"]?.toIntOrNull()
        val flags = buildList {
            if (mil == true) add("MIL is ON")
            if (count != null && count > 0) add("ECU reports $count diagnostic trouble code(s)")
        }
        val monitors = monitorViews(status)
        val ecu = if (statusReceipt == null) "Unknown: monitor-status reading missing, stale, or monitoring stopped"
            else "MIL ${mil?.let { if (it) "ON" else "OFF" } ?: "unknown"}; ECU-reported DTC count ${count ?: "unknown"}; receipt age ${now - statusReceipt.receivedAtMillis} ms"
        val contexts = session?.contexts.orEmpty().map { bucket ->
            LiveHealthContextView(bucket.key.condition, bucket.key.fuel, bucket.receipts,
                (bucket.lastAtMillis - bucket.firstAtMillis).coerceAtLeast(0),
                LiveHealthInputs.scalarPids.mapNotNull { pid -> bucket.signals[pid]?.let { a ->
                    val metadata = ObdDecoder.catalog.getValue(pid)
                    LiveHealthSignalView(pid, metadata.name, metadata.unit, true, true, "Session aggregate",
                        a.latest, null, a.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), a.minimum, a.maximum)
                } })
        }
        val coverage = buildList {
            add("Standard OBD evidence only. ABS, airbags, body modules, and dedicated transmission-module health are not assessed.")
            if (session == null) add("Start live health to collect supported inputs automatically alongside the dashboard.")
            else {
                val unsupported = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).filter { it !in session.supported }
                val capacity = LiveHealthInputs.scalarPids.filter { it in session.supported && it !in session.admitted }
                if (unsupported.isNotEmpty()) add("Unsupported: " + unsupported.joinToString { ObdDecoder.catalog.getValue(it).name })
                if (capacity.isNotEmpty()) add("Not admitted: $MAX_LIVE_SIGNALS scalar acquisition slots are occupied. " + capacity.joinToString { ObdDecoder.catalog.getValue(it).name })
                if (active && key.condition == "Unknown") add("Motion/engine context unavailable: fresh speed and, when stationary, RPM are needed.")
                if (active && key.fuel == "Unknown") add("Fuel-loop context unavailable: a fresh fuel-system status reading is needed.")
                if (active && key.condition != "Unknown" && key.fuel != "Unknown") add("Evidence is separated by observed motion and fuel-loop state; no Park or warmed-engine condition is inferred.")
                if (!active) {
                    add("Current live status is not presented here; recorded context and ECU facts describe the saved point only.")
                    if (recorded?.condition == "Unknown") add("Recorded motion context at the frozen point was unavailable: speed or required RPM was missing, NO DATA, or stale at that point.")
                    if (recorded?.fuelSystemStatus == "Unknown") add("Recorded fuel-loop context at the frozen point was unavailable: fuel-system status was missing, NO DATA, or stale at that point.")
                }
                add(if (active) "Latest condition window: up to 120 seconds / 64 receipts per signal; ECU status refresh requested every 30 seconds."
                    else "During monitoring, rolling windows retain up to 120 seconds / 64 receipts per signal; ECU status refresh was requested every 30 seconds.")
                add("Stored-code identifiers are available through a separate stopped diagnostic scan.")
            }
        }
        return LiveHealthView(phase, session?.generation, session?.sourceEcu,
            session?.let { ((if (active) now else frozenAt!!) - it.startedAtMillis).coerceAtLeast(0) } ?: 0,
            key.condition, key.fuel, session?.receiptCount ?: 0, signals, contexts, coverage, ecu, monitors, flags,
            session?.driveReview?.present(((if (active) now else frozenAt!!) - session.startedAtMillis).coerceAtLeast(0)), recorded)
    }

    private fun recordedSummary(session: LiveHealthSession, frozenAt: Long): LiveHealthRecordedSummary {
        val latest = session.latest.filterValues { it.receivedAtMillis in session.startedAtMillis..frozenAt }
        val key = liveContext(latest, frozenAt)
        fun offset(pid: Int) = latest[pid]?.let { "+${it.receivedAtMillis - session.startedAtMillis} ms" } ?: "not captured"
        val endOffset = (frozenAt - session.startedAtMillis).coerceAtLeast(0)
        val receipt = latest[0x01]
        val receiptOffset = receipt?.let { it.receivedAtMillis - session.startedAtMillis }
        val age = receipt?.let { frozenAt - it.receivedAtMillis }
        val fields = (receipt?.reading?.value as? DecodedReading.Structured)?.fields.orEmpty()
        val mil = fields["mil_on"]?.toBooleanStrictOrNull()
        val count = fields["dtc_count"]?.toIntOrNull()
        val timing = receiptOffset?.let { "at +$it ms; $age ms before the frozen point" }
        val status = when {
            receipt == null -> "No monitor-status receipt was captured."
            receipt.reading.value == null -> "Last monitor-status attempt $timing returned NO DATA; status unknown."
            fields.isEmpty() -> "Last attempt $timing did not contain usable monitor status."
            else -> "Last monitor-status observation $timing: MIL ${mil?.let { if (it) "ON" else "OFF" } ?: "unknown"}; ECU-reported DTC count ${count ?: "unknown"}. " +
                if (age!! > LiveHealthInputs.ECU_AGE_MILLIS) "Stale by the frozen point; no status at that point is inferred."
                else "Historical observation; no current status is inferred."
        }
        val flags = buildList {
            if (mil == true) add("MIL ON was recorded at +$receiptOffset ms")
            if (count != null && count > 0) add("ECU-reported DTC count $count was recorded at +$receiptOffset ms")
        }
        return LiveHealthRecordedSummary(endOffset, key.condition, key.fuel,
            "Historical context frozen at +$endOffset ms; receipt offsets: speed ${offset(0x0D)}, RPM ${offset(0x0C)}, fuel status ${offset(0x03)}. Missing or stale prerequisites at that point remain unknown.",
            status, receiptOffset, age, flags, monitorViews(fields))
    }

    private fun monitorViews(status: Map<String, String>) = (0 until 16).mapNotNull { index ->
        val prefix = "monitors.$index."
        status[prefix + "name"]?.let { DiagnosticMonitorView(it, status[prefix + "available"] ?: "unknown", status[prefix + "complete"] ?: "unknown") }
    }

    private fun signal(pid: Int, supported: Set<Int>, admitted: Set<Int>, segment: List<LiveHealthReceipt>, now: Long, active: Boolean): LiveHealthSignalView {
        val rows = segment.filter { it.reading.pid == pid }
        val last = rows.lastOrNull()
        val age = last?.receivedAtMillis?.takeIf { it <= now }?.let { now - it }
        val latest = (last?.reading?.value as? DecodedReading.Scalar)?.value?.takeIf { it.isFinite() }
        val usable = active && age != null && age <= LiveHealthInputs.SIGNAL_AGE_MILLIS && latest != null
        val values = rows.mapNotNull { (it.reading.value as? DecodedReading.Scalar)?.value?.takeIf(Double::isFinite) }
        val metadata = ObdDecoder.catalog.getValue(pid)
        val availability = when {
            pid !in supported -> "Unsupported"
            pid !in admitted -> "No acquisition capacity"
            !active -> "Monitoring stopped"
            last == null -> "Collecting in this condition"
            age == null || age > LiveHealthInputs.SIGNAL_AGE_MILLIS -> "Stale"
            latest == null -> "NO DATA"
            else -> "Current receipt"
        }
        return LiveHealthSignalView(pid, metadata.name, metadata.unit, pid in supported, pid in admitted,
            availability, latest.takeIf { usable }, age, values.size, values.minOrNull(), values.maxOrNull())
    }
}
