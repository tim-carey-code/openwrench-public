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
import org.openwrench.frontend.android.DiagnosticScanView
import org.openwrench.frontend.android.IdleHealthPhase
import org.openwrench.frontend.android.IdleHealthReport
import org.openwrench.frontend.android.IdleHealthSignalSummary
import org.openwrench.frontend.android.IdleHealthStatus
import org.openwrench.frontend.android.LiveHealthView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun VehicleHealthPage(source: DashboardSource, phase: IdleHealthPhase,
    elapsedMillis: Long, report: IdleHealthReport?, historical: Boolean,
    canStart: Boolean, onStart: () -> Unit, onCancel: () -> Unit,
    liveHealth: LiveHealthView? = null, canStartLiveHealth: Boolean = false,
    onStartLiveHealth: () -> Unit = {}, onStopLiveHealth: () -> Unit = {}, driveSaveStatus: String? = null) {
    var idleCheckSelected by remember { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vehicle Health", style = MaterialTheme.typography.headlineMedium)
            SourceBadge(source)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !idleCheckSelected, onClick = { idleCheckSelected = false },
                    modifier = Modifier.weight(1f), label = { Text("Live monitoring") })
                FilterChip(selected = idleCheckSelected, onClick = { idleCheckSelected = true },
                    modifier = Modifier.weight(1f), label = { Text("Guided idle check") })
            }
        }
    }
    if (idleCheckSelected) IdleHealthContent(phase, elapsedMillis, report, historical,
        canStart, onStart, onCancel, header)
    else LiveHealthContent(source, liveHealth, canStartLiveHealth, onStartLiveHealth, onStopLiveHealth, header, driveSaveStatus)
}

@Composable
private fun IdleHealthContent(phase: IdleHealthPhase, elapsedMillis: Long,
    report: IdleHealthReport?, historical: Boolean, canStart: Boolean,
    onStart: () -> Unit, onCancel: () -> Unit, header: @Composable () -> Unit) {
    val context = LocalContext.current
    var copied by remember(report) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        item {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("60-second idle check", style = MaterialTheme.typography.titleMedium)
                Text("With the vehicle parked and engine idling, start a timed read-only capture. Keep the ignition and adapter connected until the final diagnostic scan finishes. The app samples supported signals, then reads a diagnostic snapshot and stored-code status on the same connection.")
                Text("The idle condition is supplied by you; the app cannot verify that the vehicle is parked or operating normally.",
                    style = MaterialTheme.typography.bodySmall)
                when (phase) {
                    IdleHealthPhase.IDLE -> {
                        Button(onClick = onStart, enabled = canStart) { Text("Start idle check") }
                        if (!canStart) Text("Connect a hardware adapter and stop live data before starting.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IdleHealthPhase.CAPTURING -> {
                        val seconds = (elapsedMillis.coerceIn(0, 60_000) / 1_000).toInt()
                        Text("Capturing timed readings · $seconds / 60 seconds")
                        LinearProgressIndicator(progress = { (elapsedMillis / 60_000f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = onCancel) { Text("Cancel idle check") }
                    }
                    IdleHealthPhase.SCANNING -> {
                        Text("Idle capture finished. Keep the ignition and adapter connected while supported diagnostic parameters and stored codes are read…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = onCancel) { Text("Cancel idle check") }
                        Text("Cancel stops between bounded scan reads and starts adapter release. Reconnect after cleanup to retry.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (report == null) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("No idle report yet", style = MaterialTheme.typography.titleMedium)
                Text("A completed check will show what the ECU reported, the timed measurement ranges, and what remains unknown.")
            } }
        } else {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(if (historical) "Historical idle report" else "Idle report",
                    style = MaterialTheme.typography.titleMedium)
                Text("${report.status.label()} · started ${DateFormat.getDateTimeInstance().format(Date(report.startedAtWallMillis))}")
                Text("Completed ${DateFormat.getDateTimeInstance().format(Date(report.completedAtWallMillis))}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Response source: ${report.sourceEcu ?: "unavailable"} · capture ${report.elapsedMillis / 1_000} seconds",
                    style = MaterialTheme.typography.bodySmall)
                report.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Readings belong to one connection. This report is a captured record, not live data.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("OPENWRENCH idle health report", report.reportText()))
                    copied = true
                }) { Text(if (copied) "Report copied" else "Copy idle report") }
            } }
            item { ScanFacts(report.scan) }
            item { EvidenceCoverage(report) }
            item { Text("Timed receipt measurements", style = MaterialTheme.typography.titleMedium) }
            items(report.signals, key = { it.pid }) { SignalEvidence(it) }
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("What this report can say", style = MaterialTheme.typography.titleMedium)
                Text("The scan reports ECU flags and readiness. Repeated adapter receipts show their observed range during this idle capture; they are not ECU sample timestamps. A single idle check cannot establish that the vehicle is healthy, identify a failed part, or assess driving conditions.",
                    style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
private fun EvidenceCoverage(report: IdleHealthReport) {
    val advertised = report.signals.count { it.supported }
    val measured = report.signals.count { it.sampleCount > 0 }
    val receipts = report.signals.sumOf { it.sampleCount }
    val noData = report.signals.sumOf { it.noDataCount }
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text("Evidence coverage", style = MaterialTheme.typography.titleMedium)
        Text("$measured of $advertised advertised target signals returned values across $receipts timed receipts.")
        Text("${report.signals.size - advertised} targets unsupported · $noData NO DATA replies")
        Text(if (report.scan == null) "Final scan unavailable: MIL, codes, and readiness remain unknown. Run a new scan; reconnect first if the adapter was released."
            else "Final scan captured on this connection: ${report.scan.availableCount} of ${report.scan.eligibleCount} eligible Mode 01 values.")
        Text("This is factual ECU status and observed ranges, not a whole-vehicle health score or a fault diagnosis.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScanFacts(scan: DiagnosticScanView?) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text("ECU flags and readiness", style = MaterialTheme.typography.titleMedium)
        if (scan == null) {
            Text("Diagnostic scan unavailable. MIL, stored codes, and readiness are unknown for this idle check.")
        } else {
            val storedCodes = scan.storedDtcs?.codes
            val attention = buildList {
                if (scan.milOn == true) add("MIL is ON")
                if ((scan.reportedDtcCount ?: 0) > 0) add("ECU reports ${scan.reportedDtcCount} DTCs")
                if (!storedCodes.isNullOrEmpty()) add("stored codes returned: ${storedCodes.joinToString(", ")}")
            }
            Text(when {
                attention.isNotEmpty() -> "ECU attention flags: ${attention.joinToString("; ")}."
                scan.milOn == false && scan.reportedDtcCount == 0 && storedCodes != null && storedCodes.isEmpty() ->
                    "No MIL or stored codes reported in this scan. This is not a whole-vehicle health verdict."
                else -> "ECU attention flags cannot be fully assessed from this scan."
            }, color = if (attention.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Mode 01: ${scan.availableCount}/${scan.eligibleCount} values · ${scan.noDataCount} NO DATA")
            Text("MIL: ${scan.milOn?.let { if (it) "ON" else "OFF" } ?: "unknown"} · ECU-reported DTC count: ${scan.reportedDtcCount ?: "unknown"}")
            Text("Mode 03 response source: ${scan.storedDtcs?.sourceEcu ?: "unavailable"}")
            Text("Stored codes: " + when {
                scan.storedDtcs == null -> "not captured"
                storedCodes == null -> "NO DATA; status unknown"
                storedCodes.isEmpty() -> "none returned by this response source"
                else -> storedCodes.joinToString(", ")
            })
            if (scan.monitors.isEmpty()) Text("Readiness monitors: unavailable")
            else scan.monitors.forEach { monitor ->
                Text("${monitor.name}: " + when {
                    monitor.availability == "false" -> "not supported"
                    monitor.completion == "true" -> "complete"
                    monitor.completion == "false" -> "incomplete"
                    else -> "status unknown"
                }, style = MaterialTheme.typography.bodySmall)
            }
            val incomplete = scan.monitors.filter { it.availability == "true" && it.completion == "false" }
            if (incomplete.isNotEmpty()) Text("Readiness incomplete: ${incomplete.joinToString(", ") { it.name }}. This is a status, not a fault finding.",
                style = MaterialTheme.typography.bodySmall)
            Text("An incomplete monitor is an ECU-reported readiness state; it does not by itself identify a fault.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SignalEvidence(signal: IdleHealthSignalSummary) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text("${signal.name} · PID 01${signal.pid.toString(16).uppercase().padStart(2, '0')}",
            style = MaterialTheme.typography.titleSmall)
        when {
            !signal.supported -> Text("Unsupported by this ECU")
            signal.sampleCount == 0 -> Text("No valid samples · ${signal.noDataCount} NO DATA replies")
            else -> {
                Text("${signal.sampleCount} timed receipts · ${signal.noDataCount} NO DATA replies")
                Text("First ${signal.firstValue.fmt(signal.unit)} · last ${signal.lastValue.fmt(signal.unit)}")
                Text("Observed range ${signal.minimum.fmt(signal.unit)} to ${signal.maximum.fmt(signal.unit)}")
            }
        }
    }
}

private fun IdleHealthStatus.label() = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Double?.fmt(unit: String?): String = if (this == null) "unknown"
    else String.format(Locale.US, "%.2f", this) + (unit?.let { " $it" } ?: "")
