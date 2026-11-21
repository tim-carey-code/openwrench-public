package org.openwrench.frontend

import org.openwrench.core.SignalValue

const val TREND_WINDOW_MILLIS = 60_000L
private const val MAX_TREND_POINTS = 240

/** A received observation, or an explicit gap. Times are receipt/playback offsets, not ECU time. */
data class TrendPoint(val atMillis: Long, val value: Double?, val observedAt: String?, val expiresAtMillis: Long)

data class SignalTrend(
    val points: List<TrendPoint> = emptyList(),
    val lastIdentity: String? = null,
    val lastAvailability: SignalAvailability? = null,
) {
    fun receive(at: Long, availability: SignalAvailability, value: SignalValue?, identity: String?, age: Long?): SignalTrend {
        val numeric = when (value) {
            is SignalValue.Number -> value.value
            is SignalValue.Integer -> value.value.toDouble()
            else -> null
        }
        val available = availability == SignalAvailability.AVAILABLE && numeric != null && age != null && age < DASHBOARD_RECEIPT_STALE_MILLIS
        // Cache polling/clock ticks must never create observations or extend their lifetime.
        if (available && (identity == null || identity == lastIdentity)) return this
        if (!available && lastAvailability == availability) return this
        val point = TrendPoint(at, numeric.takeIf { available }, identity,
            if (available) saturatingAdd(at, DASHBOARD_RECEIPT_STALE_MILLIS - checkNotNull(age)) else at)
        return SignalTrend((points + point).filter { it.atMillis >= at - TREND_WINDOW_MILLIS }.takeLast(MAX_TREND_POINTS),
            identity ?: lastIdentity, availability)
    }
}

/** Segment boundaries represent missing/expired data. No interpolated or carried-forward points. */
fun SignalTrend.segments(now: Long): List<List<TrendPoint>> {
    val segments = mutableListOf<MutableList<TrendPoint>>()
    var current: MutableList<TrendPoint>? = null
    var previous: TrendPoint? = null
    points.filter { it.atMillis in (now - TREND_WINDOW_MILLIS)..now }.forEach { point ->
        if (point.value == null) { current = null; previous = null }
        else {
            if (current == null || previous?.let { point.atMillis >= it.expiresAtMillis } == true) {
                current = mutableListOf<TrendPoint>().also { segments += it }
            }
            current!!.add(point)
            previous = point
        }
    }
    return segments
}
