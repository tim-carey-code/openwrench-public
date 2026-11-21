package org.openwrench.frontend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openwrench.core.SignalSupport
import org.openwrench.core.SignalType

internal enum class LiveDisplayMode(val label: String) { GRAPHS("Graphs"), VALUES("Values"), BOTH("Both") }

/** Owned above navigation/details. These are display choices, never a vehicle read plan. */
internal class LiveDisplayState {
    var fullScreen by mutableStateOf(false)
    private var modeBeforeFullScreen: LiveDisplayMode? = null
    fun enterFullScreen() {
        if (fullScreen) return
        modeBeforeFullScreen = mode
        mode = LiveDisplayMode.GRAPHS
        fullScreen = true
    }
    fun exitFullScreen() {
        if (!fullScreen) return
        fullScreen = false
        modeBeforeFullScreen?.let { mode = it }
        modeBeforeFullScreen = null
    }
    var mode by mutableStateOf(LiveDisplayMode.BOTH)
    var imperial by mutableStateOf(false)
    var frozen by mutableStateOf<List<DashboardSignal>?>(null)
    var plotSelection by mutableStateOf<List<String>?>(null)
    var range by mutableStateOf(GraphTimeRange.SIXTY)
    var fitScale by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var selectedValuesOnly by mutableStateOf(true)
}

/** Stable catalog order: receipt arrival/expiry must never reshuffle values under a finger. */
internal fun liveValueRows(rows: List<DashboardSignal>, selected: List<String>, selectedOnly: Boolean): List<DashboardSignal> =
    if (selectedOnly) selected.mapNotNull { id -> rows.firstOrNull { it.definition.id == id } } else rows

/** A small phone shows two compact cells; enlarged fonts trade density for complete labels. */
internal fun liveValueColumns(widthDp: Float, fontScale: Float): Int = guiValueColumns(widthDp, fontScale)

internal data class LiveValuePresentation(val value: String, val unit: String, val status: String,
    val available: Boolean, val scalar: Boolean)

internal fun liveValuePresentation(row: DashboardSignal, imperial: Boolean): LiveValuePresentation {
    val available = row.availability == SignalAvailability.AVAILABLE && row.definition.support == SignalSupport.SUPPORTED
    return LiveValuePresentation(
        value = row.instrumentValue(imperial), unit = row.instrumentUnit(imperial),
        status = if (available) row.shortAge() else row.status,
        available = available, scalar = row.definition.type in setOf(SignalType.NUMBER, SignalType.INTEGER),
    )
}

internal fun liveValuesCaption(source: DashboardSource, chartsPaused: Boolean): String {
    val provenance = when (source) {
        DashboardSource.DEMO, DashboardSource.SCRIPTED -> "Simulated values"
        DashboardSource.SNAPSHOT -> "Historical snapshot · not live"
        DashboardSource.CIVIC_REPLAY -> "Recorded values · not live"
        DashboardSource.HARDWARE -> "Latest received values · acquisition unchanged"
    }
    return if (chartsPaused) "$provenance · charts remain paused" else provenance
}

internal val LiveDisplayMode.showsValues: Boolean get() = this != LiveDisplayMode.GRAPHS
internal val LiveDisplayMode.showsGraphs: Boolean get() = this != LiveDisplayMode.VALUES

/** Values-only selection preference must not silently filter the Both home. */
internal fun LiveDisplayState.filtersValues(): Boolean = mode == LiveDisplayMode.VALUES && selectedValuesOnly
