package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openwrench.nativecore.*
import java.util.Locale

@Composable
internal fun WarningContextPanel(context: DiagnosticContextRead) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Warning evidence", style = MaterialTheme.typography.titleLarge)
            Text("Code status and available ECU-stored readings from this scan. Save the scan to keep this evidence on this device.")
        }
        context.codes.forEach { result -> WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("${result.service.label} trouble codes", style = MaterialTheme.typography.titleMedium)
            if (result.status != DiagnosticReplyStatus.AVAILABLE) Text(result.status.explanation())
            else if (result.codes.isEmpty()) Text("No codes returned by this response")
            else result.codes.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
            Text(when (result.service) {
                DtcService.STORED -> "A code describes a condition reported by the ECU; it does not identify a failed part."
                DtcService.PENDING -> "Pending conditions need follow-up; they are not a confirmed failed-part diagnosis."
                DtcService.PERMANENT -> "A permanent code may remain after a fault is no longer present. It does not alone establish a current fault."
            }, style = MaterialTheme.typography.bodySmall)
            Text(result.sourceEcu?.let { "Response source: $it" } ?: "Response source not established for this reply",
                style = MaterialTheme.typography.bodySmall)
        } }
        val frame = context.freezeFrame
        WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("ECU freeze frame", style = MaterialTheme.typography.titleMedium)
            Text(if (frame.status == DiagnosticReplyStatus.AVAILABLE && frame.triggerCode != null)
                "Stored readings for trigger ${frame.triggerCode}"
                else if (frame.status == DiagnosticReplyStatus.AVAILABLE) "No trigger code returned; no frame readings established"
                else frame.status.explanation())
            frame.readings.forEach { reading ->
                val m = ObdDecoder.catalog.getValue(reading.pid)
                val value = when (val v = reading.value) {
                    null -> (frame.unavailablePids[reading.pid] ?: DiagnosticReplyStatus.NO_DATA).explanation()
                    is DecodedReading.Scalar -> String.format(Locale.US, "%.2f", v.value) + (m.unit?.let { " $it" } ?: "")
                    is DecodedReading.Structured -> v.fields.entries.joinToString("; ") { "${it.key.replace('_', ' ')}: ${it.value}" }
                }
                Text("${m.name}: $value", style = MaterialTheme.typography.bodyMedium)
            }
            Text("These readings were stored by the ECU at an unknown earlier time. They are not live values or an automatic match to this drive, every code, or a warning bookmark.",
                style = MaterialTheme.typography.bodySmall)
            Text("A limited set of supported frame-zero readings is covered. Other stored frames were not read.",
                style = MaterialTheme.typography.bodySmall)
            frame.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun DiagnosticReplyStatus.explanation(): String = when (this) {
    DiagnosticReplyStatus.AVAILABLE -> "Response available"
    DiagnosticReplyStatus.NO_DATA -> "NO DATA · status unknown"
    DiagnosticReplyStatus.UNSUPPORTED -> "ECU reported this request unsupported"
    DiagnosticReplyStatus.UNAVAILABLE -> "Unavailable under current conditions"
}
