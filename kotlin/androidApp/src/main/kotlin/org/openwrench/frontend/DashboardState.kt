package org.openwrench.frontend

import org.openwrench.core.*
import kotlin.math.abs
import kotlin.math.round

/** Display rounding only; details retain the original supplied numeric value. */
fun SignalValue.compactDisplay(): String = when (this) {
    is SignalValue.Number -> if (abs(value) in 0.001..1_000_000_000.0)
        SignalValue.Number(round(value * 1000) / 1000).display() else display()
    else -> display()
}

typealias SignalAvailability = org.openwrench.core.SignalAvailability
enum class DashboardSource(val label: String) {
    CIVIC_REPLAY("Historical recording replay — not live"),
    SNAPSHOT("Saved snapshot — historical data, not live"),
    DEMO("OFFLINE DEMO · fictional values"),
    HARDWARE("LIVE · ELM327 · selected readings"),
    SCRIPTED("SCRIPTED TRANSPORT · fictional values · not a vehicle"),
}

data class DashboardSignal(
    val definition: SignalDefinition,
    val availability: SignalAvailability = SignalAvailability.NOT_SAMPLED,
    val value: SignalValue? = null,
    val observedAt: String? = null,
    val ageMillis: Long? = null,
    val reason: String = when (definition.support) {
        SignalSupport.SUPPORTED -> "Not sampled by this dashboard"
        SignalSupport.UNSUPPORTED -> "Source reports this signal unsupported"
        SignalSupport.UNKNOWN -> "Support has not been checked"
    },
    val cadenceMillis: Long? = null,
    val ageLabel: String = "Observation age",
    val trend: SignalTrend = SignalTrend(),
    val trendNowMillis: Long? = null,
    // Archive selectability is not an ECU-advertised capability, including null-only channels.
    val retainedReplay: Boolean = false,
) {
    init {
        require(ageMillis == null || ageMillis >= 0)
        require(cadenceMillis == null || cadenceMillis > 0)
        require(availability != SignalAvailability.AVAILABLE || value != null)
        require(value == null || (availability == SignalAvailability.AVAILABLE && definition.support == SignalSupport.SUPPORTED))
        require(value == null || when (definition.type) {
            SignalType.INTEGER -> value is SignalValue.Integer
            SignalType.NUMBER -> value is SignalValue.Number
            SignalType.BOOLEAN -> value is SignalValue.Boolean
            SignalType.CATEGORY -> value is SignalValue.Category
            SignalType.TEXT -> value is SignalValue.Text
            SignalType.STRUCTURED -> value is SignalValue.Structured
            SignalType.TROUBLE_CODES -> value is SignalValue.TroubleCodes
            SignalType.UNKNOWN -> false
        })
    }
    val capabilitySupport: SignalSupport get() = if (retainedReplay) SignalSupport.UNKNOWN else definition.support
    val supportDescription: String get() = if (retainedReplay)
        "Retained replay channel · ECU capability unverified" else definition.support.name.lowercase()
    val status: String get() = when {
        availability == SignalAvailability.DISCONNECTED -> "Disconnected"
        availability == SignalAvailability.ERROR -> "Read error"
        else -> supportStatus()
    }
    private fun supportStatus(): String = when (definition.support) {
        SignalSupport.UNSUPPORTED -> "Unsupported"
        SignalSupport.UNKNOWN -> "Support not checked"
        SignalSupport.SUPPORTED -> when (availability) {
            SignalAvailability.AVAILABLE -> "Value supplied"
            SignalAvailability.NOT_SAMPLED -> "Not sampled"
            SignalAvailability.UNAVAILABLE -> "Unavailable"
            SignalAvailability.STALE -> "Stale"
            SignalAvailability.ERROR -> "Read error"
            SignalAvailability.DISCONNECTED -> "Disconnected"
        }
    }
}

val uncheckedRpm = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.UNKNOWN, "010C")

/** Fixed UI-only examples. No ECU time, source module, or reading age is invented. */
fun demoDashboardSignals(): List<DashboardSignal> {
    fun sample(id: String, label: String, unit: String?, type: SignalType, value: SignalValue) = DashboardSignal(
        SignalDefinition(id, label, unit, type, SignalSupport.SUPPORTED), SignalAvailability.AVAILABLE,
        value, reason = "Fictional static fixture; observation time and age are not supplied")
    return listOf(
        sample("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalValue.Number(715.0)),
        sample("coolant", "Coolant temperature", "°C", SignalType.NUMBER, SignalValue.Number(82.0)),
        sample("speed", "Vehicle speed", "km/h", SignalType.NUMBER, SignalValue.Number(0.0)),
        sample("runtime", "Engine runtime", "s", SignalType.INTEGER, SignalValue.Integer(184)),
        sample("example_boolean", "Boolean example", null, SignalType.BOOLEAN, SignalValue.Boolean(false)),
        sample("example_category", "Categorical example", null, SignalType.CATEGORY, SignalValue.Category("Example category")),
        sample("example_text", "Text example", null, SignalType.TEXT, SignalValue.Text("Fictional description")),
        sample("example_structure", "Structured example", null, SignalType.STRUCTURED, SignalValue.Structured(mapOf("Field" to "Example"))),
        sample("example_codes", "Trouble-code display example", null, SignalType.TROUBLE_CODES, SignalValue.TroubleCodes(listOf("EXAMPLE"))),
        DashboardSignal(SignalDefinition("example_missing", "Unavailable example", "kPa", SignalType.NUMBER, SignalSupport.SUPPORTED),
            SignalAvailability.UNAVAILABLE, reason = "Fixture intentionally supplies no value"),
        DashboardSignal(SignalDefinition("example_unsupported", "Unsupported example", null, SignalType.UNKNOWN, SignalSupport.UNSUPPORTED)),
        DashboardSignal(SignalDefinition("example_unknown", "Unchecked example", null, SignalType.UNKNOWN, SignalSupport.UNKNOWN)),
    )
}

fun filterSignals(signals: List<DashboardSignal>, search: String, support: SignalSupport?, availability: SignalAvailability?) =
    signals.filter { row ->
        (support == null || row.capabilitySupport == support) &&
            (availability == null || row.availability == availability) &&
            listOfNotNull(row.definition.id, row.definition.label, row.definition.pid, row.definition.unit)
                .any { it.contains(search.trim(), ignoreCase = true) }
    }

/** Width is evaluated in scaled logical units, leaving room for enlarged system fonts. */
fun dashboardColumns(widthDp: Float, fontScale: Float): Int =
    ((widthDp + 12f) / (260f * fontScale.coerceAtLeast(1f) + 12f)).toInt().coerceIn(1, 4)
