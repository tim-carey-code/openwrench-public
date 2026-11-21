package org.openwrench.frontend

import org.openwrench.core.*

internal const val SYNTHETIC_TRACE_REASON = "Synthetic UI trace fixture; not a vehicle recording"

/** Fixed, bounded, explicitly fictional receipts for exercising plots and intentional gaps. */
internal fun syntheticWorkspaceFixture(): List<DashboardSignal> {
    data class Example(val id: String, val label: String, val unit: String, val pid: String, val start: Double, val change: Double)
    val examples = listOf(
        Example("rpm", "Engine speed", "rpm", "010C", 720.0, 1600.0),
        Example("coolant", "Coolant temperature", "°C", "0105", 88.0, 3.0),
        Example("control-module-voltage", "Module voltage", "V", "0142", 13.8, .3),
        Example("speed", "Vehicle speed", "km/h", "010D", 0.0, 50.0),
        Example("load", "Engine load", "%", "0104", 22.0, 27.0),
        Example("maf", "Mass air flow", "g/s", "0110", 4.4, 24.0),
        Example("throttle", "Throttle", "%", "0111", 12.0, 14.0),
        Example("intake-air", "Intake air temperature", "°C", "010F", 28.0, 4.0),
        Example("ambient-air-temperature", "Ambient air temperature", "°C", "0146", 22.0, 1.0),
    )
    return examples.map { example ->
        val points = (0..60).map { second ->
            val phase = when { second < 10 -> 0.0; second < 30 -> (second - 10) / 20.0; else -> 1.0 }
            val ripple = if (second >= 10) ((second * 7 % 9) - 4) * .008 else 0.0
            val number = example.start + example.change * (phase + ripple)
            TrendPoint(second * 1000L, number.takeUnless { second in 35..38 },
                "Synthetic receipt +${second}s", second * 1000L + 3000)
        }
        DashboardSignal(SignalDefinition(example.id, example.label, example.unit, SignalType.NUMBER, SignalSupport.SUPPORTED, example.pid),
            SignalAvailability.AVAILABLE, SignalValue.Number(checkNotNull(points.last().value)), observedAt = "Synthetic receipt +60s",
            ageMillis = 0, reason = SYNTHETIC_TRACE_REASON, trend = SignalTrend(points), trendNowMillis = 60_000)
    }
}
