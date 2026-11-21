package org.openwrench.core

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import kotlin.math.abs

/** Evidence policy 1: engineering tolerances, never manufacturer diagnostic limits. */
object FuelCorrectionPolicy {
    const val VERSION = 1
    const val PAIR_SEPARATION_MS = 2_000L
    const val CONTEXT_AGE_MS = 10_000L
    const val GAP_MS = 6_000L
    const val MIN_SPAN_MS = 30_000L
    const val MAX_SPAN_MS = 60_000L
    const val MIN_PAIRS = 10
    const val MAX_PAIRS = 256
    const val MAX_INTERVALS = 8
    const val MIN_CONTEXT_RECEIPTS = 4
    const val MIN_CONTEXT_SPAN_MS = 20_000L
    const val RPM_RANGE = 300.0
    const val LOAD_RANGE = 10.0
    const val COOLANT_RANGE = 3.0
    const val SMALL_TRIM = 3.0
    const val LTFT_SCREEN = 10.0
    // Mode 01 decoder domains, not mechanical operating limits.
    const val TRIM_MAX = 99.21875
    const val RPM_MAX = 16_383.75
    const val SPEED_MAX = 255.0
}

enum class FuelCorrectionPattern { REINFORCING, OPPOSING, LEARNED_WITH_SMALL_SHORT, SMALL_LEARNED }

data class FuelObservation(val offsetMillis: Long, val value: Double)

/** Actual extremes include context receipts between pairs; ties preserve the earlier receipt. */
data class FuelCorrectionBounds(val minimum: FuelObservation, val maximum: FuelObservation) {
    fun include(value: FuelObservation) = FuelCorrectionBounds(
        if (value.value < minimum.value) value else minimum,
        if (value.value > maximum.value) value else maximum)
    val range get() = maximum.value - minimum.value
}

data class FuelCorrectionContextBounds(
    val rpm: FuelCorrectionBounds, val load: FuelCorrectionBounds, val coolant: FuelCorrectionBounds,
)

/** Every pair consumes two actual receipts. Supporting receipts may recur, with their original times. */
data class FuelCorrectionPair(
    val short: FuelObservation, val long: FuelObservation,
    val rpm: FuelObservation, val load: FuelObservation, val coolant: FuelObservation,
    val speed: FuelObservation, val fuelOffsetMillis: Long,
) {
    val separationMillis get() = abs(short.offsetMillis - long.offsetMillis)
    val firstOffsetMillis get() = minOf(short.offsetMillis, long.offsetMillis)
    val lastOffsetMillis get() = maxOf(short.offsetMillis, long.offsetMillis)
}

data class FuelCorrectionInterval(
    val policyVersion: Int, val bank: Int, val condition: String,
    val system1: String, val system2: String, val pattern: FuelCorrectionPattern,
    val pairs: List<FuelCorrectionPair>,
    val contextBounds: FuelCorrectionContextBounds,
) {
    val firstOffsetMillis get() = pairs.first().firstOffsetMillis
    val lastOffsetMillis get() = pairs.last().lastOffsetMillis
    val screened get() = pairs.all { it.long.value > FuelCorrectionPolicy.LTFT_SCREEN } ||
        pairs.all { it.long.value < -FuelCorrectionPolicy.LTFT_SCREEN }
}

/** One recorder per monitor/session/source; no acquisition, wall clock, vehicle matching or interpolation. */
class FuelCorrectionRecorder(private val startedAt: Long, private val supported: Set<Int>, private val admitted: Set<Int>,
    private val minimumCoolantCelsius: Double? = null,
) {
    private data class Receipt(val reading: DecodedReading?, val at: Long)
    private data class Context(val condition: String, val first: String, val second: String,
        val rpm: FuelObservation, val load: FuelObservation, val coolant: FuelObservation,
        val speed: FuelObservation, val fuelAt: Long)
    private data class PendingTrim(val observation: FuelObservation, val bounds: FuelCorrectionContextBounds)
    private data class Run(val id: Long, val context: Context, val pattern: FuelCorrectionPattern,
        val screen: Int, val pairs: List<FuelCorrectionPair>, val bounds: FuelCorrectionContextBounds)
    private val latest = mutableMapOf<Int, Receipt>()
    private val pending = mutableMapOf<Int, PendingTrim>()
    private val runs = mutableMapOf<Int, Run>()
    private val contexts = mutableMapOf<Int, Context>()
    private val retained = mutableMapOf<Int, List<Pair<Long, FuelCorrectionInterval>>>()
    private val reasons = mutableMapOf<Int, String>()
    private var nextId = 0L
    private var lastAt = startedAt
    private var stopped = false

    fun intervals(bank: Int): List<FuelCorrectionInterval> = retained[bank].orEmpty().map { it.second }
    fun missingRequirement(bank: Int, at: Long = lastAt): String {
        val required = listOf(shortPid(bank), longPid(bank), 0x03, 0x04, 0x05, 0x0C, 0x0D)
        val unsupported = required.filter { it !in supported }
        if (unsupported.isNotEmpty()) return "Required inputs not advertised: ${unsupported.joinToString { label(it) }}."
        val deferred = required.filter { it !in admitted }
        if (deferred.isNotEmpty()) return "Required inputs not admitted to acquisition: ${deferred.joinToString { label(it) }}."
        if (minimumCoolantCelsius != null && context(bank, at) == null) return contextReason(bank, at)
        return reasons[bank] ?: "Needs 10 independent same-bank STFT/LTFT pairs over 30 continuous seconds, with repeated fresh RPM, load, coolant, speed and closed-loop status."
    }

    fun offer(reading: NativeReading, at: Long) {
        if (stopped || reading.pid !in admitted || reading.pid !in supported || at < lastAt || at < startedAt || latest[reading.pid]?.at == at) return
        // Evaluate expiration BEFORE refreshing a context input: recovery cannot bridge a stale interval.
        for (bank in 1..2) {
            val ctx = context(bank, at)
            if (ctx == null) breakRun(bank, contextReason(bank, at))
            val expired = listOf(shortPid(bank), longPid(bank)).filter { pid ->
                pending[pid]?.let { at - startedAt - it.observation.offsetMillis > FuelCorrectionPolicy.PAIR_SEPARATION_MS } == true
            }
            if (expired.isNotEmpty()) {
                expired.forEach { pending.remove(it) }
                reasons[bank] = "Unmatched ${expired.joinToString { label(it) }} receipt exceeded the 2000 ms pairing limit; a fresh same-bank counterpart is required."
            }
            runs[bank]?.let { run -> if (at - startedAt - run.pairs.last().lastOffsetMillis > FuelCorrectionPolicy.GAP_MS)
                breakRun(bank, "Independent trim-pair gap exceeded 6000 ms; a new continuous interval is required.") }
        }
        lastAt = at
        latest[reading.pid] = Receipt(reading.value, at)
        for (bank in 1..2) {
            val ctx = context(bank, at)
            if (ctx == null) { breakRun(bank, contextReason(bank, at)); contexts.remove(bank); continue }
            contexts[bank]?.let { if (!sameContext(it, ctx)) breakRun(bank, "Motion or fuel-control state changed; a new interval is required.") }
            contexts[bank] = ctx
            runs[bank]?.let { run ->
                val bounds = include(run.bounds, ctx)
                if (!sameContext(run.context, ctx) || !stable(bounds))
                    breakRun(bank, "Operating context changed or exceeded policy variation bounds; a new 30-second interval is required.")
                else runs[bank] = run.copy(bounds = bounds)
            }
            // Pending receipts need their own bounds, even before a run exists or when the
            // completed pair will start a different pattern/screen/direction/cap candidate.
            for (pid in listOf(shortPid(bank), longPid(bank))) pending[pid]?.let { trim ->
                val bounds = include(trim.bounds, ctx)
                if (!stable(bounds)) breakRun(bank, "Operating context exceeded policy variation bounds while a trim receipt was pending; a new interval is required.")
                else pending[pid] = trim.copy(bounds = bounds)
            }
            if (reading.pid !in listOf(shortPid(bank), longPid(bank))) continue
            val value = (reading.value as? DecodedReading.Scalar)?.value?.takeIf { it.isFinite() && it in -100.0..FuelCorrectionPolicy.TRIM_MAX }
            if (value == null) { breakRun(bank, "Latest ${label(reading.pid)} attempt was unavailable/invalid (including NO DATA); older trim receipts cannot replace it."); continue }
            // A same-PID replacement starts fresh evidence at that actual receipt. When a
            // counterpart exists, its bounds already include every intervening context update.
            val counterpart = pending[if (reading.pid == shortPid(bank)) longPid(bank) else shortPid(bank)]
            val pairBounds = counterpart?.bounds ?: initialBounds(ctx)
            pending[reading.pid] = PendingTrim(FuelObservation(at - startedAt, value), initialBounds(ctx))
            val short = pending[shortPid(bank)]?.observation ?: continue
            val long = pending[longPid(bank)]?.observation ?: continue
            pending.remove(shortPid(bank)); pending.remove(longPid(bank))
            if (abs(short.offsetMillis - long.offsetMillis) > FuelCorrectionPolicy.PAIR_SEPARATION_MS) {
                breakRun(bank, "Same-bank trim receipts were more than 2000 ms apart."); continue
            }
            val pair = FuelCorrectionPair(short, long, ctx.rpm, ctx.load, ctx.coolant, ctx.speed, ctx.fuelAt)
            // A pair cannot straddle the start of a new context/run.
            val pattern = classify(short.value, long.value)
            val screen = screen(long.value)
            var prior = runs[bank]
            if (prior != null && (prior.pattern != pattern || prior.screen != screen ||
                    direction(prior.pairs.last().long.value) != direction(long.value) ||
                    pair.lastOffsetMillis - prior.pairs.first().firstOffsetMillis > FuelCorrectionPolicy.MAX_SPAN_MS ||
                    prior.pairs.size >= FuelCorrectionPolicy.MAX_PAIRS)) {
                runs.remove(bank); prior = null
            }
            val run = Run(prior?.id ?: ++nextId, ctx, pattern, screen, prior?.pairs.orEmpty() + pair, prior?.bounds ?: pairBounds)
            runs[bank] = run
            val issue = qualificationIssue(run.pairs)
            reasons[bank] = issue ?: "Qualified intervals are retained; additional conditions require their own independent 30-second interval."
            if (issue == null) retain(run.id, FuelCorrectionInterval(FuelCorrectionPolicy.VERSION, bank, ctx.condition, ctx.first, ctx.second, pattern, run.pairs, run.bounds))
        }
    }

    fun stop(at: Long) { lastAt = maxOf(lastAt, at); stopped = true }

    private fun retain(id: Long, interval: FuelCorrectionInterval) {
        val all = (retained[interval.bank].orEmpty().filterNot { it.first == id } + (id to interval)).sortedBy { it.first }
        val firstConcern = all.firstOrNull { it.second.screened }
        val keep = all.takeLast(FuelCorrectionPolicy.MAX_INTERVALS).toMutableList()
        if (firstConcern != null && firstConcern !in keep) { keep.removeAt(0); keep.add(0, firstConcern) }
        retained[interval.bank] = keep
    }
    private fun breakRun(bank: Int, reason: String) {
        runs.remove(bank); pending.remove(shortPid(bank)); pending.remove(longPid(bank)); reasons[bank] = reason
    }
    private fun scalar(pid: Int, at: Long): FuelObservation? = latest[pid]?.takeIf {
        pid in supported && pid in admitted && at - it.at in 0..FuelCorrectionPolicy.CONTEXT_AGE_MS
    }?.let { r -> (r.reading as? DecodedReading.Scalar)?.value?.takeIf(Double::isFinite)?.let { FuelObservation(r.at - startedAt, it) } }
    private fun context(bank: Int, at: Long): Context? {
        if (listOf(shortPid(bank), longPid(bank), 0x03, 0x04, 0x05, 0x0C, 0x0D).any { it !in supported || it !in admitted }) return null
        val rpm = scalar(0x0C, at)?.takeIf { it.value > 0 && it.value <= FuelCorrectionPolicy.RPM_MAX } ?: return null
        val speed = scalar(0x0D, at)?.takeIf { it.value in 0.0..FuelCorrectionPolicy.SPEED_MAX } ?: return null
        val load = scalar(0x04, at)?.takeIf { it.value in 0.0..100.0 } ?: return null
        val coolant = scalar(0x05, at)?.takeIf { it.value in -40.0..215.0 } ?: return null
        if (minimumCoolantCelsius != null && coolant.value < minimumCoolantCelsius) return null
        val fuel = latest[0x03]?.takeIf { at - it.at in 0..FuelCorrectionPolicy.CONTEXT_AGE_MS } ?: return null
        val fields = (fuel.reading as? DecodedReading.Structured)?.fields ?: return null
        val first = fields["system_1"] ?: return null; val second = fields["system_2"] ?: return null
        if (first != CLOSED || (bank == 2 && second != CLOSED) || (bank == 1 && second !in setOf(CLOSED, "engine off"))) return null
        return Context(if (speed.value == 0.0) "Stationary with engine running" else "Moving", first, second, rpm, load, coolant, speed, fuel.at - startedAt)
    }
    private fun contextReason(bank: Int, at: Long): String {
        for (pid in listOf(0x0C, 0x0D, 0x04, 0x05)) if (scalar(pid, at) == null)
            return "${label(pid)} context is missing, unavailable/NO DATA, or older than 10000 ms; recovery starts a new interval."
        val rpm = scalar(0x0C, at)!!.value
        if (rpm <= 0 || rpm > FuelCorrectionPolicy.RPM_MAX) return "RPM must be positive and within the decoder domain to establish engine-running observations."
        if (scalar(0x0D, at)!!.value !in 0.0..FuelCorrectionPolicy.SPEED_MAX) return "Speed is outside the decoder domain; valid stationary/moving context is required."
        if (scalar(0x04, at)!!.value !in 0.0..100.0) return "Calculated load is outside the decoder domain; a valid load observation is required."
        if (scalar(0x05, at)!!.value !in -40.0..215.0) return "Coolant temperature is outside the decoder domain; a valid coolant observation is required."
        if (minimumCoolantCelsius != null && scalar(0x05, at)!!.value < minimumCoolantCelsius)
            return "Coolant must remain at least $minimumCoolantCelsius °C throughout a new qualified interval under the provisional app screening policy."
        return "Fuel status is missing, unavailable/NO DATA, older than 10000 ms, or incompatible with exact closed-loop feedback${if (bank == 2) " in both fields for Bank 2" else " in system 1 and closed-loop/engine-off system 2"}; system fields are not assumed to map to banks."
    }
    private fun sameContext(a: Context, b: Context) = a.condition == b.condition && a.first == b.first && a.second == b.second
    private fun initialBounds(ctx: Context) = FuelCorrectionContextBounds(FuelCorrectionBounds(ctx.rpm, ctx.rpm),
        FuelCorrectionBounds(ctx.load, ctx.load), FuelCorrectionBounds(ctx.coolant, ctx.coolant))
    private fun include(bounds: FuelCorrectionContextBounds, ctx: Context) = FuelCorrectionContextBounds(
        bounds.rpm.include(ctx.rpm), bounds.load.include(ctx.load), bounds.coolant.include(ctx.coolant))
    private fun stable(bounds: FuelCorrectionContextBounds): Boolean =
        bounds.rpm.range <= FuelCorrectionPolicy.RPM_RANGE && bounds.load.range <= FuelCorrectionPolicy.LOAD_RANGE &&
            bounds.coolant.range <= FuelCorrectionPolicy.COOLANT_RANGE

    companion object {
        const val CLOSED = "closed loop: oxygen-sensor feedback"
        fun classify(short: Double, long: Double) = when {
            abs(long) <= FuelCorrectionPolicy.SMALL_TRIM -> FuelCorrectionPattern.SMALL_LEARNED
            abs(short) <= FuelCorrectionPolicy.SMALL_TRIM -> FuelCorrectionPattern.LEARNED_WITH_SMALL_SHORT
            short * long > 0 -> FuelCorrectionPattern.REINFORCING
            else -> FuelCorrectionPattern.OPPOSING
        }
        fun direction(long: Double) = when { long > 0 -> 1; long < 0 -> -1; else -> 0 }
        fun screen(long: Double) = when { long > FuelCorrectionPolicy.LTFT_SCREEN -> 1; long < -FuelCorrectionPolicy.LTFT_SCREEN -> -1; else -> 0 }
        fun qualificationIssue(pairs: List<FuelCorrectionPair>): String? {
            if (pairs.size < FuelCorrectionPolicy.MIN_PAIRS) return "Needs at least 10 independent same-bank trim pairs; ${pairs.size} in the current interval."
            if (pairs.last().lastOffsetMillis - pairs.first().lastOffsetMillis < FuelCorrectionPolicy.MIN_SPAN_MS)
                return "Independent trim pairs must span 30 continuous seconds; shorter intervals are not joined."
            for ((name, observations) in listOf("RPM" to pairs.map { it.rpm }, "load" to pairs.map { it.load }, "coolant" to pairs.map { it.coolant }, "speed" to pairs.map { it.speed })) {
                val distinct = observations.distinctBy { it.offsetMillis }
                if (distinct.size < FuelCorrectionPolicy.MIN_CONTEXT_RECEIPTS || distinct.last().offsetMillis - distinct.first().offsetMillis < FuelCorrectionPolicy.MIN_CONTEXT_SPAN_MS)
                    return "Needs at least 4 distinct $name context receipts spanning 20 seconds; cached context does not establish stability."
            }
            val fuel = pairs.map { it.fuelOffsetMillis }.distinct()
            if (fuel.size < FuelCorrectionPolicy.MIN_CONTEXT_RECEIPTS || fuel.last() - fuel.first() < FuelCorrectionPolicy.MIN_CONTEXT_SPAN_MS)
                return "Needs at least 4 distinct closed-loop status receipts spanning 20 seconds."
            return null
        }
        private fun shortPid(bank: Int) = if (bank == 1) 0x06 else 0x08
        private fun longPid(bank: Int) = if (bank == 1) 0x07 else 0x09
        private fun label(pid: Int) = "01" + pid.toString(16).uppercase().padStart(2, '0') +
            " (${ObdDecoder.catalog[pid]?.name ?: "unknown input"})"
    }
}
