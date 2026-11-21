package org.openwrench.frontend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.DiagnosisPhase

/** A minimum readable card width, scaled with text rather than physical device class. */
internal fun guiValueColumns(widthDp: Float, fontScale: Float): Int {
    val scale = fontScale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    return if (!widthDp.isFinite()) 1 else ((widthDp + 12) / (172 * scale + 12)).toInt().coerceIn(1, 4)
}

/** Phase only: the ribbon never claims a scan succeeded or a vehicle is healthy. */
internal fun diagnosisJourneyIndex(phase: DiagnosisPhase, canStart: Boolean): Int = when (phase) {
    DiagnosisPhase.IDLE -> if (canStart) 1 else 0
    DiagnosisPhase.STARTING -> 1
    DiagnosisPhase.RECORDING, DiagnosisPhase.PAUSING, DiagnosisPhase.PAUSED, DiagnosisPhase.INTERRUPTED -> 2
    DiagnosisPhase.FINISHING -> 3
    DiagnosisPhase.COMPLETED -> 4
}

@Composable
internal fun DiagnosisJourney(phase: DiagnosisPhase, canStart: Boolean) {
    val current = diagnosisJourneyIndex(phase, canStart)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Connect", "Initial scan", "Drive", "Final scan", "Review").forEachIndexed { index, label ->
            Column(Modifier.widthIn(min = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(88.dp).height(3.dp).background(if (index == current) DashboardBlue else DashboardBorder))
                Text(label + if (index == current) " · current" else "", style = MaterialTheme.typography.labelMedium,
                    color = if (index == current) MaterialTheme.colorScheme.onSurface else DashboardMuted)
            }
        }
    }
}

@Composable
internal fun StatusChip(label: String, caution: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (caution) DashboardAmber else TelemetryInk, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun WorkspaceNotice(title: String, message: String, caution: Boolean = false) {
    val ink = if (caution) DashboardAmber else TelemetryInk
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(DashboardPanel)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(ink))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
