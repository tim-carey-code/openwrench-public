package org.openwrench.frontend

import androidx.compose.ui.graphics.Color
import org.openwrench.core.*
import kotlin.math.ceil
import kotlin.math.floor

internal val referenceInstruments = listOf(
    "speed" to "Vehicle speed", "coolant" to "Coolant", "load" to "Engine load", "throttle" to "Throttle",
    "intake-air" to "Intake air temp", "maf" to "Mass air flow", "map" to "Manifold pressure",
    "control-module-voltage" to "Module voltage",
)
internal val fuelTrimIds = listOf("stft", "ltft", "stft-bank-2", "ltft-bank-2")
internal fun fuelTrimPins(rows: List<DashboardSignal>, previous: List<String>): List<String> {
    val trims = fuelTrimIds.filter { id -> rows.any { it.definition.id == id && it.definition.support == SignalSupport.SUPPORTED } }
    return (previous.filter { it == "rpm" } + trims + previous).distinct()
}
internal val preferredPlots = listOf("rpm", "speed", "load", "coolant")

/** Source labels stay explicit when native Android displays imported historical data. */
internal fun sourceBadgeLabel(source: DashboardSource): String = when (source) {
    DashboardSource.DEMO, DashboardSource.SCRIPTED -> "SIMULATED"
    DashboardSource.CIVIC_REPLAY -> "RECORDED"
    DashboardSource.SNAPSHOT -> "HISTORICAL"
    DashboardSource.HARDWARE -> "LIVE SOURCE"
}

internal fun dashboardHeading(source: DashboardSource): String = when (source) {
    DashboardSource.DEMO -> "Demo Dashboard"
    DashboardSource.SCRIPTED -> "Scripted Dashboard"
    DashboardSource.CIVIC_REPLAY -> "Recorded Dashboard"
    DashboardSource.SNAPSHOT -> "Saved Snapshot"
    DashboardSource.HARDWARE -> "Live Dashboard"
}

internal fun workspaceSourceDescription(source: DashboardSource): String = when (source) {
    DashboardSource.DEMO -> "Offline demo"
    DashboardSource.SCRIPTED -> "Scripted session"
    DashboardSource.CIVIC_REPLAY -> "Recorded playback"
    DashboardSource.SNAPSHOT -> "Saved snapshot"
    DashboardSource.HARDWARE -> "Read-only session"
}

internal fun instrumentSlots(rows: List<DashboardSignal>, pins: List<String>, customOrder: Boolean): List<Pair<String, String>> {
    val chosen = if (customOrder) pins.filter { it != "rpm" }.mapNotNull { id ->
        rows.firstOrNull { it.definition.id == id }?.let { row -> id to compactSignalLabel(row) }
    } else emptyList()
    return (chosen + referenceInstruments).distinctBy { it.first }.take(8)
}

internal fun channelInk(id: String): Color = when (id) {
    "speed" -> DashboardBlue
    "coolant" -> DashboardAmber
    "load", "control-module-voltage" -> DashboardGreen
    else -> TelemetryInk
}

internal fun compactSignalLabel(row: DashboardSignal): String =
    when (row.definition.id) {
        "stft" -> "Short-term trim · Bank 1"
        "ltft" -> "Long-term trim · Bank 1"
        "stft-bank-2" -> "Short-term trim · Bank 2"
        "ltft-bank-2" -> "Long-term trim · Bank 2"
        else -> referenceInstruments.firstOrNull { it.first == row.definition.id }?.second ?: row.definition.label
    }

internal fun DashboardSignal.numericValue(): Double? = when (val supplied = value) {
    is SignalValue.Number -> supplied.value
    is SignalValue.Integer -> supplied.value.toDouble()
    else -> null
}

internal fun convertDisplayValue(value: Double, unit: String?, imperial: Boolean): Double = when {
    !imperial -> value
    unit == "km/h" -> value * .621371192237334
    unit == "°C" -> value * 1.8 + 32
    unit == "kPa" -> value * .14503773773
    unit == "g/s" -> value * .13227735731
    else -> value
}
internal fun displayUnit(unit: String?, imperial: Boolean): String = when {
    imperial && unit == "km/h" -> "mph"
    imperial && unit == "°C" -> "°F"
    imperial && unit == "kPa" -> "psi"
    imperial && unit == "g/s" -> "lb/min"
    else -> unit.orEmpty()
}
/** Structured fields retain their supplied units; only scalar readings can be converted. */
private fun DashboardSignal.convertsDisplayUnits(imperial: Boolean): Boolean =
    imperial && definition.type in setOf(SignalType.NUMBER, SignalType.INTEGER) &&
        definition.unit in setOf("km/h", "°C", "kPa", "g/s")

internal fun DashboardSignal.instrumentUnit(imperial: Boolean): String =
    displayUnit(definition.unit, convertsDisplayUnits(imperial))

internal fun DashboardSignal.instrumentValue(imperial: Boolean): String {
    if (!convertsDisplayUnits(imperial)) return visibleValue()
    val value = numericValue() ?: return "—"
    if (availability != SignalAvailability.AVAILABLE || definition.support != SignalSupport.SUPPORTED) return "—"
    return SignalValue.Number(convertDisplayValue(value, definition.unit, true)).compactDisplay()
}
internal fun DashboardSignal.shortAge(): String = ageMillis?.let {
    if (it < 1000) it.toString() + " ms ago" else (it / 1000).toString() + " s ago"
} ?: "Age unknown"
internal fun DashboardSignal.observedFrequency(): String = cadenceMillis?.let {
    SignalValue.Number(1000.0 / it).compactDisplay() + " Hz observed"
} ?: "Rate unknown"

internal fun instrumentStatus(row: DashboardSignal?, pinned: Boolean): String = when {
    row == null -> "Not supplied"
    row.availability in setOf(SignalAvailability.DISCONNECTED, SignalAvailability.ERROR, SignalAvailability.STALE) -> row.status
    row.definition.support != SignalSupport.SUPPORTED -> row.status
    !pinned -> "Not pinned"
    row.availability != SignalAvailability.AVAILABLE -> row.status
    else -> row.shortAge()
}

internal fun groupInstrumentNumber(value: String): String {
    if (!Regex("-?[0-9]+(\\.[0-9]+)?").matches(value)) return value
    val parts = value.removePrefix("-").split('.')
    return (if (value.startsWith('-')) "-" else "") + parts[0].reversed().chunked(3).joinToString(",").reversed() +
        (if (parts.size > 1) "." + parts[1] else "")
}

/** Display-only plot selection cannot select a PID for acquisition or change its cadence. */
internal fun defaultPlotIds(rows: List<DashboardSignal>, pins: List<String>): List<String> {
    val numeric = rows.filter { it.definition.type in setOf(SignalType.NUMBER, SignalType.INTEGER) && it.definition.id in pins }.map { it.definition.id }
    return (preferredPlots.filter { it in numeric } + numeric).distinct().take(4)
}
internal fun togglePlot(ids: List<String>, id: String): List<String> =
    if (id in ids) ids - id else ids + id

internal fun plotBounds(row: DashboardSignal, values: List<Double>, imperial: Boolean): Pair<Double, Double> {
    val numbers = values + listOfNotNull(row.numericValue())
    val minimum = numbers.minOrNull() ?: 0.0
    val maximum = numbers.maxOrNull() ?: 1.0
    val metric = when {
        row.definition.id == "rpm" -> minOf(0.0, minimum) to maxOf(4000.0, ceil(maximum / 1000) * 1000)
        row.definition.unit == "%" -> minOf(0.0, minimum) to maxOf(100.0, maximum)
        row.definition.unit == "km/h" -> minOf(0.0, minimum) to maxOf(100.0, ceil(maximum / 20) * 20)
        else -> floor(minimum / 10) * 10 - 10 to ceil(maximum / 10) * 10 + 10
    }
    return convertDisplayValue(metric.first, row.definition.unit, imperial) to convertDisplayValue(metric.second, row.definition.unit, imperial)
}

/** Destination navigation is independent of the workspace's Dashboard/Graphs/Values mode. */
internal enum class AppNavigationPlacement { PHONE_DOCK, SIDEBAR, HIDDEN }

internal fun appNavigationPlacement(availableWidth: Float, fontScale: Float, fullScreen: Boolean): AppNavigationPlacement =
    when {
        fullScreen -> AppNavigationPlacement.HIDDEN
        availableWidth / fontScale.coerceAtLeast(1f) >= 700f -> AppNavigationPlacement.SIDEBAR
        else -> AppNavigationPlacement.PHONE_DOCK
    }

/** Icon-only targets stay at least 48dp wide; unusually narrow windows use complete rows. */
internal fun phoneNavigationColumns(availableWidth: Float): Int = when {
    availableWidth >= 48f * 4 -> 4
    availableWidth >= 48f * 2 -> 2
    else -> 1
}
