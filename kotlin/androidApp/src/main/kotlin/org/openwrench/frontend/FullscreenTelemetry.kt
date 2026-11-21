package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openwrench.core.SignalSupport
import org.openwrench.frontend.android.DiagnosisPhase

internal data class TelemetryStatus(val sourceAndConnection: String, val freshness: String)

/** Current supplied receipts only, never paused chart rows or inferred ECU sampling times. */
internal fun telemetryStatus(source: DashboardSource, state: WatchUiState, rows: List<DashboardSignal>, snapshotSimulated: Boolean = false): TelemetryStatus {
    if (source != DashboardSource.HARDWARE) return when (source) {
        DashboardSource.DEMO, DashboardSource.SCRIPTED -> TelemetryStatus("Simulator · no adapter", "Fictional values · no live receipts")
        DashboardSource.SNAPSHOT -> if (snapshotSimulated)
            TelemetryStatus("Simulator snapshot · no adapter", "Fictional historical values · capture age unknown")
            else TelemetryStatus("Snapshot · no adapter", "Historical readings · capture age unknown")
        DashboardSource.CIVIC_REPLAY -> TelemetryStatus("Recording · no adapter", "Recorded readings · not live")
        else -> error("Hardware handled below")
    }
    val connection = when {
        state.cleanupUncertain -> "Cleanup unconfirmed"
        state.connection == HardwareConnection.CONNECTED -> "Connected"
        state.connection == HardwareConnection.CONNECTING -> "Connecting"
        state.connection == HardwareConnection.DISCOVERING -> "Discovering"
        state.connection == HardwareConnection.FAULTED -> "Connection error"
        else -> "Disconnected"
    }
    val acquisition = when (state.acquisition) {
        Acquisition.RUNNING -> "reading"
        Acquisition.STARTING -> "starting reads"
        Acquisition.STOPPING -> "stopping reads"
        Acquisition.STOP_UNCONFIRMED -> "stop unconfirmed"
        Acquisition.FAULTED -> "read error"
        Acquisition.EXPIRED -> "reads expired"
        Acquisition.STOPPED -> "reads stopped"
    }
    val active = state.connection == HardwareConnection.CONNECTED && !state.cleanupUncertain && state.acquisition == Acquisition.RUNNING
    val current = if (active) rows.filter {
        it.availability == SignalAvailability.AVAILABLE && it.definition.support == SignalSupport.SUPPORTED &&
            it.value != null && it.ageMillis != null && it.ageMillis in 0 until DASHBOARD_RECEIPT_STALE_MILLIS
    } else emptyList()
    val stale = rows.count { it.definition.support == SignalSupport.SUPPORTED && it.availability == SignalAvailability.STALE }
    val oldest = current.maxOfOrNull { checkNotNull(it.ageMillis) }
    val freshness = if (oldest == null) "No current receipts" else
        "${current.size} current · oldest receipt ${if (oldest < 1000) "<1 s" else "${oldest / 1000} s"}"
    return TelemetryStatus("Hardware · $connection · $acquisition", freshness + if (stale > 0) " · $stale stale" else "")
}

/** Separate collection status from visual pause. This component never changes a session. */
internal fun telemetryActivity(phase: DiagnosisPhase?, chartsPaused: Boolean): String? {
    val diagnosis = when (phase) {
        DiagnosisPhase.STARTING -> "Diagnosis starting"
        DiagnosisPhase.RECORDING -> "Diagnosis recording"
        DiagnosisPhase.PAUSING -> "Diagnosis pausing"
        DiagnosisPhase.PAUSED -> "Diagnosis paused"
        DiagnosisPhase.FINISHING -> "Diagnosis finishing"
        else -> null
    }
    return listOfNotNull(diagnosis, if (chartsPaused) "Charts paused" else null).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Status stays visible and scrolls for large text; exit lives in the pinned telemetry bar. */
@Composable
internal fun FullscreenTelemetryStatus(source: DashboardSource, state: WatchUiState, rows: List<DashboardSignal>,
    phase: DiagnosisPhase?, chartsPaused: Boolean, snapshotSimulated: Boolean, maximumHeight: Dp) {
    val status = telemetryStatus(source, state, rows, snapshotSimulated)
    Surface(color = DashboardPanel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight.coerceAtLeast(48.dp))
                .verticalScroll(rememberScrollState()).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(status.sourceAndConnection, style = MaterialTheme.typography.labelMedium)
                Text(status.freshness, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                telemetryActivity(phase, chartsPaused)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = DashboardAmber)
                }
            }

        }
    }
}
