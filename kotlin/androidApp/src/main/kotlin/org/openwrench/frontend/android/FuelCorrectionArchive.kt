package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*

/** Strict receipt-level archive evidence; no reconstruction from report prose or chart points. */
internal object FuelCorrectionArchive {
    private fun row(vararg values: Number) = JsonArray(values.map { JsonPrimitive(it) })
    fun encode(interval: FuelCorrectionInterval): JsonElement = buildJsonObject {
        put("policy", interval.policyVersion); put("bank", interval.bank); put("condition", interval.condition)
        put("system_1", interval.system1); put("system_2", interval.system2); put("pattern", interval.pattern.name)
        put("context_bounds", JsonArray(listOf(interval.contextBounds.rpm, interval.contextBounds.load, interval.contextBounds.coolant).flatMap {
            listOf(JsonPrimitive(it.minimum.offsetMillis), JsonPrimitive(it.minimum.value), JsonPrimitive(it.maximum.offsetMillis), JsonPrimitive(it.maximum.value))
        }))
        put("pairs", JsonArray(interval.pairs.map { p -> row(p.short.offsetMillis, p.short.value, p.long.offsetMillis, p.long.value,
            p.rpm.offsetMillis, p.rpm.value, p.load.offsetMillis, p.load.value, p.coolant.offsetMillis, p.coolant.value,
            p.speed.offsetMillis, p.speed.value, p.fuelOffsetMillis) }))
    }
    fun decode(value: JsonElement): FuelCorrectionInterval {
        val o = value.jsonObject
        require(o.keys == setOf("policy", "bank", "condition", "system_1", "system_2", "pattern", "pairs", "context_bounds"))
        fun text(key: String) = o.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        fun integer(key: String) = o.getValue(key).jsonPrimitive.also { require(!it.isString) }.int
        val pairs = o.getValue("pairs").jsonArray.also { require(it.size in 1..FuelCorrectionPolicy.MAX_PAIRS) }.map { element ->
            val p = element.jsonArray.also { require(it.size == 13) }
            fun at(i: Int) = p[i].jsonPrimitive.also { require(!it.isString) }.long
            fun obs(i: Int) = FuelObservation(at(i), p[i + 1].jsonPrimitive.also { require(!it.isString) }.double)
            FuelCorrectionPair(obs(0), obs(2), obs(4), obs(6), obs(8), obs(10), at(12))
        }
        val bounds = o.getValue("context_bounds").jsonArray.also { require(it.size == 12) }
        fun bound(index: Int): FuelCorrectionBounds {
            fun at(i: Int) = bounds[i].jsonPrimitive.also { require(!it.isString) }.long
            fun value(i: Int) = bounds[i].jsonPrimitive.also { require(!it.isString) }.double
            return FuelCorrectionBounds(FuelObservation(at(index), value(index + 1)), FuelObservation(at(index + 2), value(index + 3)))
        }
        return FuelCorrectionInterval(integer("policy"), integer("bank"), text("condition"), text("system_1"), text("system_2"),
            FuelCorrectionPattern.valueOf(text("pattern")), pairs, FuelCorrectionContextBounds(bound(0), bound(4), bound(8)))
    }
    fun validate(intervals: List<FuelCorrectionInterval>, bank: Int, evaluatedAt: Long) {
        val policy = FuelCorrectionPolicy
        require(intervals.size <= policy.MAX_INTERVALS)
        require(intervals.zipWithNext().all { (a, b) ->
            a.lastOffsetMillis <= b.firstOffsetMillis &&
                a.pairs.last().short.offsetMillis < b.pairs.first().short.offsetMillis &&
                a.pairs.last().long.offsetMillis < b.pairs.first().long.offsetMillis
        })
        // A reused context receipt must retain the same value even across interval boundaries.
        val allPairs = intervals.flatMap { it.pairs }
        fun extremes(select: (FuelCorrectionContextBounds) -> FuelCorrectionBounds) = intervals.flatMap {
            val b = select(it.contextBounds); listOf(b.minimum, b.maximum)
        }
        for (observations in listOf(allPairs.map { it.rpm } + extremes { it.rpm },
            allPairs.map { it.load } + extremes { it.load }, allPairs.map { it.coolant } + extremes { it.coolant }, allPairs.map { it.speed })) {
            require(observations.groupBy { it.offsetMillis }.all { (_, values) -> values.map { it.value }.distinct().size == 1 })
        }
        val fuelStates = intervals.flatMap { i -> i.pairs.map { it.fuelOffsetMillis to (i.system1 to i.system2) } }
        require(fuelStates.groupBy { it.first }.all { (_, rows) -> rows.map { it.second }.distinct().size == 1 })
        intervals.forEach { i ->
            require(i.policyVersion == policy.VERSION && i.bank == bank)
            require(i.pairs.size in policy.MIN_PAIRS..policy.MAX_PAIRS)
            require(i.firstOffsetMillis >= 0 && i.lastOffsetMillis <= evaluatedAt && i.lastOffsetMillis - i.firstOffsetMillis <= policy.MAX_SPAN_MS)
            require(i.system1 == FuelCorrectionRecorder.CLOSED && (i.system2 == FuelCorrectionRecorder.CLOSED || (bank == 1 && i.system2 == "engine off")))
            require(i.condition in setOf("Moving", "Stationary with engine running"))
            require(FuelCorrectionRecorder.qualificationIssue(i.pairs) == null)
            require(i.pairs.zipWithNext().all { (a, b) ->
                b.short.offsetMillis > a.short.offsetMillis && b.long.offsetMillis > a.long.offsetMillis &&
                    b.firstOffsetMillis >= a.lastOffsetMillis && b.lastOffsetMillis - a.lastOffsetMillis <= policy.GAP_MS
            })
            i.pairs.forEach { p ->
                require(p.separationMillis <= policy.PAIR_SEPARATION_MS)
                require(listOf(p.short, p.long).all { it.offsetMillis in 0..evaluatedAt && it.value.isFinite() && it.value in -100.0..policy.TRIM_MAX })
                require(FuelCorrectionRecorder.classify(p.short.value, p.long.value) == i.pattern)
                require(FuelCorrectionRecorder.screen(p.long.value) == FuelCorrectionRecorder.screen(i.pairs.first().long.value))
                require(FuelCorrectionRecorder.direction(p.long.value) == FuelCorrectionRecorder.direction(i.pairs.first().long.value))
                require(listOf(p.rpm, p.load, p.coolant, p.speed).all { it.value.isFinite() && it.offsetMillis >= 0 && p.lastOffsetMillis - it.offsetMillis in 0..policy.CONTEXT_AGE_MS })
                require(p.rpm.value > 0 && p.rpm.value <= policy.RPM_MAX && p.speed.value in 0.0..policy.SPEED_MAX && p.load.value in 0.0..100.0 && p.coolant.value in -40.0..215.0)
                require(if (i.condition == "Moving") p.speed.value > 0 else p.speed.value == 0.0)
                require(p.fuelOffsetMillis >= 0 && p.lastOffsetMillis - p.fuelOffsetMillis in 0..policy.CONTEXT_AGE_MS)
            }
            for ((bound, observations, domain) in listOf(
                Triple(i.contextBounds.rpm, i.pairs.map { it.rpm }, 0.0..policy.RPM_MAX),
                Triple(i.contextBounds.load, i.pairs.map { it.load }, 0.0..100.0),
                Triple(i.contextBounds.coolant, i.pairs.map { it.coolant }, -40.0..215.0))) {
                require(bound.minimum.value.isFinite() && bound.maximum.value.isFinite())
                require(bound.minimum.value in domain && bound.maximum.value in domain && bound.minimum.value <= bound.maximum.value)
                // Initial support may precede the first trim by the context freshness limit;
                // extrema during its pending pair can precede the completion-time support.
                val earliest = (i.firstOffsetMillis - policy.CONTEXT_AGE_MS).coerceAtLeast(0)
                require(listOf(bound.minimum, bound.maximum).all { it.offsetMillis in earliest..i.lastOffsetMillis })
                require(observations.all { it.value in bound.minimum.value..bound.maximum.value })
            }
            require(i.contextBounds.rpm.minimum.value > 0 && i.contextBounds.rpm.range <= policy.RPM_RANGE)
            require(i.contextBounds.load.range <= policy.LOAD_RANGE && i.contextBounds.coolant.range <= policy.COOLANT_RANGE)
            for ((observations, range) in listOf(i.pairs.map { it.rpm } to policy.RPM_RANGE,
                i.pairs.map { it.load } to policy.LOAD_RANGE, i.pairs.map { it.coolant } to policy.COOLANT_RANGE,
                i.pairs.map { it.speed } to Double.MAX_VALUE)) {
                require(observations.maxOf { it.value } - observations.minOf { it.value } <= range)
                require(observations.zipWithNext().all { (a, b) -> a.offsetMillis <= b.offsetMillis })
                require(observations.groupBy { it.offsetMillis }.all { (_, values) -> values.map { it.value }.distinct().size == 1 })
            }
            require(i.pairs.map { it.fuelOffsetMillis }.zipWithNext().all { (a, b) -> a <= b })
        }
    }
}
