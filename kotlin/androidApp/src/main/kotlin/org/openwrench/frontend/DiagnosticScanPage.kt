package org.openwrench.frontend

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.DiagnosticScanStatus
import org.openwrench.frontend.android.DiagnosticScanView
import org.openwrench.frontend.android.SavedScan
import java.text.DateFormat
import java.util.Date
import org.openwrench.nativecore.DecodedReading

@Composable
internal fun DiagnosticScanPage(source: DashboardSource, scan: DiagnosticScanView, onScan: () -> Unit,
    vin: String? = null, vinDetail: String = "VIN has not been requested.", canReadVin: Boolean = false,
    onReadVin: () -> Unit = {}, onSave: (Boolean) -> Unit = {}, historyBusy: Boolean = false,
    historyAvailable: Boolean = true, historyError: String? = null, savedMessage: String? = null,
    onWarningScan: () -> Unit = {}, livePolling: Boolean = false, onStopLive: () -> Unit = {}, savedScanVin: String? = null) {
    val context = LocalContext.current
    var copied by remember(scan) { mutableStateOf(false) }
    var includeVin by remember(scan.capturedMillis, vin) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
            SourceBadge(source)
        }
        item {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Diagnostic snapshot", style = MaterialTheme.typography.titleMedium)
                Text(when (scan.status) {
                    DiagnosticScanStatus.NO_SESSION -> "Connect a vehicle to run a scan."
                    DiagnosticScanStatus.STOP_LIVE -> "Stop live data to run a scan. The scan uses the same serial connection."
                    DiagnosticScanStatus.READY -> "Ready to read every advertised parameter in the supported scan catalog."
                    DiagnosticScanStatus.COLLECTING -> "Collecting supported parameters one at a time…"
                    DiagnosticScanStatus.CAPTURED -> "Scan captured in this connection. Values are a sequential snapshot, not live readings."
                    DiagnosticScanStatus.HISTORICAL -> "Saved hardware scan from an earlier connection. These readings are historical."
                    DiagnosticScanStatus.SIMULATED -> "Offline simulator fixture. These readings did not come from a vehicle connection."
                    DiagnosticScanStatus.IMPORTED_SIMULATED -> "Imported simulator snapshot. These readings are historical and did not come from a vehicle connection."
                    DiagnosticScanStatus.IMPORTED -> "Imported snapshot. Its original source may be unknown; these are not current vehicle readings. Stored codes are not included."
                })
                if (source == DashboardSource.HARDWARE && livePolling) {
                    OutlinedButton(onClick = onStopLive) { Text("Stop live polling") }
                    Text("When parked, stop polling before scanning. This also finishes active health monitoring and saves its drive report.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onScan, enabled = source == DashboardSource.HARDWARE && scan.canRun) {
                    Text(if (scan.rows.isEmpty()) "Run diagnostic scan" else "Run scan again")
                }
                if (source == DashboardSource.HARDWARE) {
                    OutlinedButton(onClick = onWarningScan, enabled = scan.canRun) { Text("Scan with warning context") }
                    Text("When parked with live polling stopped, also read pending/permanent codes and supported freeze-frame data. Unsupported or unavailable replies remain unknown.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onReadVin, enabled = canReadVin) { Text("Read VIN") }
                    Text(vin?.let { "ECU-reported VIN: $it" } ?: vinDetail,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (scan.status in setOf(DiagnosticScanStatus.CAPTURED, DiagnosticScanStatus.HISTORICAL, DiagnosticScanStatus.SIMULATED, DiagnosticScanStatus.IMPORTED, DiagnosticScanStatus.IMPORTED_SIMULATED)) {
                    if (savedScanVin != null) Row {
                        Checkbox(checked = includeVin, onCheckedChange = { includeVin = it })
                        Text("Include acquisition VIN in saved scan", Modifier.padding(top = 12.dp))
                    }
                    OutlinedButton(onClick = { onSave(includeVin && savedScanVin != null) }, enabled = !historyBusy && historyAvailable) {
                        Text(when {
                            historyBusy -> "Saving…"
                            includeVin && savedScanVin != null -> "Save scan with acquisition VIN"
                            else -> "Save scan / retry"
                        })
                    }
                    Text(if (scan.status in setOf(DiagnosticScanStatus.CAPTURED, DiagnosticScanStatus.HISTORICAL))
                        "Completed hardware scans are automatically saved on this device without VIN. Use Save scan / retry if needed, or opt in to include a VIN collected for this scan. Re-saving never creates a duplicate."
                        else "Imported and simulator snapshots require explicit saving. VIN is omitted unless selected.",
                        style = MaterialTheme.typography.bodySmall)
                }
                historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                savedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (scan.status != DiagnosticScanStatus.COLLECTING &&
                    (scan.rows.isNotEmpty() || scan.storedDtcs != null)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("OPENWRENCH diagnostic scan", scan.reportText()))
                        copied = true
                    }) { Text(if (copied) "Report copied" else "Copy scan report") }
                }
                Text("${scan.eligibleCount} ${if (source == DashboardSource.HARDWARE) "advertised parameters eligible" else "parameters in snapshot"} · ${scan.availableCount} values · ${scan.noDataCount} NO DATA",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        scan.warningContext?.let { context -> item { WarningContextPanel(context) } }
        if (scan.storedDtcs != null && scan.warningContext == null) {
            item {
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Stored trouble codes · Mode 03", style = MaterialTheme.typography.titleMedium)
                    Text(scan.storedDtcs?.sourceEcu?.let { "Response ID $it" } ?:
                        "Response source ID unavailable")
                    when (val codes = scan.storedDtcs?.codes) {
                        null -> Text("NO DATA · stored-code status unknown")
                        else -> if (codes.isEmpty()) Text("No stored codes returned in this response")
                            else codes.forEach { code -> Text(code, style = MaterialTheme.typography.titleSmall) }
                    }
                    Text("Identifiers are reported by this response source. They do not identify a failed part or confirm a repair.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (scan.rows.isNotEmpty()) {
            item {
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Monitor fields in snapshot", style = MaterialTheme.typography.titleMedium)
                    Text(if (scan.milOn == null) "MIL status unavailable" else
                        "MIL status in snapshot: ${if (scan.milOn) "ON" else "OFF"}")
                    Text(scan.reportedDtcCount?.let { "DTC count in snapshot: $it" } ?:
                        "DTC count unavailable")
                    scan.monitors.forEach { monitor ->
                        Text("${monitor.name}: " + when {
                            monitor.availability == "false" -> "not supported"
                            monitor.completion == "true" -> "complete"
                            monitor.completion == "false" -> "incomplete"
                            else -> "status unavailable"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(when {
                        scan.warningContext != null -> "MIL and code count come from the current-parameter snapshot. Code-service replies and ECU-stored freeze-frame readings are presented separately above."
                        scan.storedDtcs == null -> "This snapshot contains Mode 01 readings only; stored codes were not captured."
                        else -> "The MIL and count come from Mode 01 PID 01; the identifiers above come from a separate Mode 03 reply."
                    },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            items(scan.rows, key = { it.pid }) { row ->
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("${row.label} · PID 01${row.pid.toString(16).uppercase().padStart(2, '0')}",
                        style = MaterialTheme.typography.titleSmall)
                    when (val value = row.value) {
                        null -> Text("NO DATA")
                        is DecodedReading.Scalar -> Text("${value.value}${row.unit?.let { " $it" } ?: ""}")
                        is DecodedReading.Structured -> value.fields.forEach { (field, answer) ->
                            Text("${field.replace('.', ' ')}: $answer", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Text("Standard scans read supported current parameters and stored codes. The optional warning-context scan also reads pending/permanent codes and supported freeze-frame data. Imported snapshots may contain Mode 01 only. This page does not clear codes, infer a fault from a sensor value, or claim the vehicle is healthy.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ScanHistoryPage(entries: List<SavedScan>, busy: Boolean, available: Boolean, error: String?,
    onDelete: (String) -> Unit) {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("History & Reports", style = MaterialTheme.typography.headlineMedium)
            Text("Saved on this device only · historical, not live · newest first",
                style = MaterialTheme.typography.bodySmall)
            if (available) Text("${entries.size}/${org.openwrench.frontend.android.ScanHistoryCodec.MAX_ENTRIES} scans saved",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (entries.isEmpty() && available) item {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("No saved scans yet")
                Text("Connect a vehicle, run a diagnostic scan, then choose Save scan to history.")
            }
        }
        items(entries, key = { it.id }) { entry ->
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(DateFormat.getDateTimeInstance().format(Date(entry.savedAtEpochMillis)),
                    style = MaterialTheme.typography.titleMedium)
                Text(if (entry.vin == null) "VIN not saved" else "VIN: ${entry.vin}")
                Text(if (entry.capture == null) "Legacy report · structured evidence unavailable; cannot create a case" else "Structured ${entry.capture.origin} evidence · historical", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { selectedId = if (selectedId == entry.id) null else entry.id }) {
                    Text(if (selectedId == entry.id) "Hide report" else "View report")
                }
                if (selected?.id == entry.id) {
                    Text(entry.report, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("OPENWRENCH saved scan", entry.copyText()))
                    }) { Text("Copy saved report") }
                    OutlinedButton(onClick = { deleteId = entry.id }, enabled = !busy && available) { Text("Delete saved scan") }
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("Delete saved scan?") },
        text = { Text("This removes the report and any VIN saved with it from this device.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
}
