package org.openwrench.frontend

import org.openwrench.core.*

/** Presentation categories only; decoding and support remain native-session-owned. */
enum class SignalCategory { ENGINE, TEMPERATURE, FUEL, AIR, EMISSIONS, OTHER }
fun SignalDefinition.category(): SignalCategory {
    val text = "$id $label".lowercase()
    return when {
        listOf("temperature", "coolant").any { it in text } -> SignalCategory.TEMPERATURE
        listOf("fuel", "trim", "equivalence", "ethanol").any { it in text } -> SignalCategory.FUEL
        listOf("oxygen", "catalyst", "egr", "evap", "emission").any { it in text } -> SignalCategory.EMISSIONS
        listOf("air", "intake", "maf", "map", "throttle", "boost").any { it in text } -> SignalCategory.AIR
        listOf("rpm", "speed", "runtime", "load", "timing", "torque").any { it in text } -> SignalCategory.ENGINE
        else -> SignalCategory.OTHER
    }
}
fun SignalDefinition.isAcquirable() = support == SignalSupport.SUPPORTED &&
    type in setOf(SignalType.INTEGER, SignalType.NUMBER)
fun WatchUiState.canEditSelection() = connection == HardwareConnection.CONNECTED && !busy && !cleanupUncertain &&
    acquisition in setOf(Acquisition.STOPPED, Acquisition.EXPIRED)
/** Defaults follow the connected session; saved/user-edited pins are handled by the view. */
fun WatchUiState.defaultLivePins(): List<String> = selection.filter { id ->
    catalog.any { it.id == id && it.isAcquirable() }
}.take(4)

/** Retained catalog metadata cannot substantiate current capability counts after release/fault. */
fun WatchUiState.availableSignalCount(): Int? =
    catalog.takeIf { connection == HardwareConnection.CONNECTED }?.count { it.isAcquirable() }

/** Keep the prior catalog size, but never present retained support as current. */
fun WatchUiState.sessionCatalogSummary(): String = when {
    catalog.isEmpty() -> "Support not checked"
    connection != HardwareConnection.CONNECTED -> "${catalog.size} signals · support unknown until connected · ${selection.size} selected"
    else -> "${catalog.size} signals · ${availableSignalCount()} supported · ${selection.size} selected"
}

/** Display eligibility does not grant permission to acquire a reading. */
internal fun SignalDefinition.isGraphable() = type in setOf(SignalType.INTEGER, SignalType.NUMBER)
internal fun graphPickerRows(rows: List<DashboardSignal>, search: String, supportedOnly: Boolean): List<DashboardSignal> =
    rows.filter { row ->
        (!supportedOnly || row.capabilitySupport == SignalSupport.SUPPORTED) &&
            (filterSignals(listOf(row), search, null, null).isNotEmpty() ||
                row.definition.graphLabel().contains(search.trim(), ignoreCase = true))
    }
        .filter { it.definition.isGraphable() }
        .sortedWith(compareBy<DashboardSignal> { it.definition.support != SignalSupport.SUPPORTED }
            .thenBy { it.definition.label.lowercase() })

/** Familiar names in graph controls; protocol metadata stays unchanged. */
internal fun SignalDefinition.graphLabel(): String = when (id) {
    "rpm" -> "RPM (engine speed)"
    "speed" -> "Vehicle speed"
    "map" -> "MAP pressure (intake manifold)"
    else -> label
}

/** Counts archival display channels, not advertised vehicle capabilities or available values. */
internal fun retainedReplayCatalogSummary(rows: List<DashboardSignal>): String =
    "${rows.count { it.retainedReplay }} retained replay channels · ECU capability unverified"
