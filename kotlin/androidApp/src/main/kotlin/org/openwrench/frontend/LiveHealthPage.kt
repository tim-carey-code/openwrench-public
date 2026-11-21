package org.openwrench.frontend

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.LiveHealthContextView
import org.openwrench.frontend.android.LiveHealthPhase
import org.openwrench.frontend.android.LiveHealthSignalView
import org.openwrench.frontend.android.LiveHealthView
import java.util.Locale

private enum class HealthReviewSection { OVERVIEW, CHARTS, EVENTS }

@Composable
internal fun LiveHealthContent(source: DashboardSource, view: LiveHealthView?,
    canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit,
    header: @Composable () -> Unit = {}, driveSaveStatus: String? = null) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    val monitorIdentity = view?.driveReview?.instanceId
    var copied by remember(view?.sessionGeneration, monitorIdentity) { mutableStateOf(false) }
    var section by remember(view?.sessionGeneration, monitorIdentity) { mutableStateOf(HealthReviewSection.OVERVIEW) }
    var selectedBookmarkId by remember(view?.sessionGeneration, monitorIdentity) { mutableStateOf<Long?>(null) }
    var chartBookmarkId by remember(view?.sessionGeneration, monitorIdentity) { mutableStateOf<Long?>(null) }
    var cursorOffsetMillis by remember(view?.sessionGeneration, monitorIdentity) { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val running = source == DashboardSource.HARDWARE && view?.phase == LiveHealthPhase.RUNNING
    val review = view?.driveReview
    LaunchedEffect(view?.phase, monitorIdentity) {
        section = HealthReviewSection.OVERVIEW
        listState.scrollToItem(0)
        if (running) { cursorOffsetMillis = null; chartBookmarkId = null }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { header() }
        if (review != null && !running) item {
            DriveHealthAssessmentPanel(review.assessment, historical = true, showFindings = false)
        }
        item { WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Live health monitoring", style = MaterialTheme.typography.titleLarge)
            Text(if (review != null && !running) "Review the health findings, drive recap, linked charts, and warning bookmarks, then copy the drive report."
                else "Start before driving. Health checks, stationary and moving evidence, warning bookmarks, and linked charts update while this app stays in the foreground. Finish the drive report when parked.")
            Text(when {
                running -> "Monitoring · ${view!!.elapsedMillis.duration()} elapsed · ${view.receiptCount} receipts"
                view?.phase == LiveHealthPhase.PAUSED -> "Drive report ready · monitoring stopped"
                view?.phase == LiveHealthPhase.HISTORICAL -> "Historical session · connect to start new evidence"
                else -> "Ready to collect evidence during this connection"
            }, style = MaterialTheme.typography.titleSmall)
            if (running) OutlinedButton(onClick = onStop) { Text("Finish drive report") }
            else Button(onClick = onStart, enabled = canStart) {
                Text(if (review == null) "Start health monitoring" else "Start new drive monitoring")
            }
            if (!running && !canStart) Text(
                if (source != DashboardSource.HARDWARE) "Choose a hardware source and connect to monitor the vehicle."
                else "Connect an adapter and finish any active operation before starting.",
                style = MaterialTheme.typography.bodySmall)
            Text("The screen stays awake while monitoring. Finishing the report leaves ordinary live dashboard data running. Leaving the app releases the adapter.",
                style = MaterialTheme.typography.bodySmall)
            driveSaveStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (review != null && !running) Text(if (driveSaveStatus == null) "Copy this report before closing the app process."
                else "Saved drives can be reopened from History & Reports, including charts and bookmarks. Check the save status above before leaving.",
                style = MaterialTheme.typography.bodySmall)
        } }
        if (view == null || view.phase == LiveHealthPhase.NOT_STARTED) {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("No live health evidence yet", style = MaterialTheme.typography.titleMedium)
                Text("Supported inputs are gathered automatically alongside the dashboard. Moving and stationary observations stay separate; missing inputs remain visible as coverage gaps.")
            } }
        } else {
            item { WorkspacePanel(Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth.value / fontScale.coerceAtLeast(1f) < 300) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HealthReviewSection.entries.forEach { destination ->
                                ReviewSectionChoice(destination, section, running, review?.bookmarks?.size ?: 0,
                                    Modifier.fillMaxWidth()) { section = it }
                            }
                        }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HealthReviewSection.entries.forEach { destination ->
                            ReviewSectionChoice(destination, section, running, review?.bookmarks?.size ?: 0,
                                Modifier.weight(1f)) { section = it }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val report = view.reportText() + (driveSaveStatus?.let { "\nLocal drive storage: $it\n" } ?: "")
                    clipboard.setPrimaryClip(ClipData.newPlainText("OPENWRENCH live health report", report))
                    copied = true
                }) { Text(if (copied) "Report copied" else "Copy drive report") }
            } }
            if (section == HealthReviewSection.CHARTS) item {
                if (review == null) Text("Chart evidence is still collecting.")
                else LiveHealthLinkedCharts(review, review.bookmarks.firstOrNull { it.id == chartBookmarkId },
                    cursorOffsetMillis, onCursor = { cursorOffsetMillis = it },
                    onRecentDrive = { chartBookmarkId = null; cursorOffsetMillis = null },
                    collecting = running, reviewKey = listOf(source, view?.sessionGeneration, monitorIdentity))
            }
            if (section == HealthReviewSection.EVENTS) item {
                if (review == null) Text("Warning bookmark evidence is still collecting.")
                else LiveHealthWarningBookmarks(review, selectedBookmarkId,
                    onSelect = { selectedBookmarkId = it }, onCharts = { bookmark ->
                        selectedBookmarkId = bookmark.id; chartBookmarkId = bookmark.id
                        cursorOffsetMillis = bookmark.offsetMillis; section = HealthReviewSection.CHARTS
                    })
            }
            if (section == HealthReviewSection.OVERVIEW) {
                item { DriveHealthAssessmentPanel(review?.assessment, historical = !running, showSummary = running) }
                if (review != null) item { DriveRecapPanel(review, running) }
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    val recorded = view.recordedSummary
                    Text(if (running) "Current operating observations" else "Last recorded operating observations",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (running) view.currentCondition else recorded?.condition ?: "Recorded context was not retained by this report version",
                        style = MaterialTheme.typography.headlineSmall)
                    Text(if (running) view.fuelSystemStatus else recorded?.fuelSystemStatus ?: "No recorded fuel-control summary")
                    if (!running && recorded != null) {
                        Text("Recorded at +${recorded.frozenAtOffsetMillis.duration()} · historical observations", style = MaterialTheme.typography.bodySmall)
                        Text(recorded.contextNote, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Response source: ${view.sourceEcu ?: "unknown"} · session ${view.sessionGeneration ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Stationary readings do not establish Park or idle. Temperature trends do not establish that the engine is fully warm.",
                        style = MaterialTheme.typography.bodySmall)
                } }
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    val recorded = view.recordedSummary
                    Text(if (running) "ECU status" else "Last recorded ECU status", style = MaterialTheme.typography.titleMedium)
                    Text(if (running) view.ecuStatus else recorded?.ecuStatus ?: "No recorded ECU status was retained by this report version")
                    (if (running) view.flags else recorded?.flags.orEmpty()).forEach {
                        Text(if (running) it else "Recorded: $it", color = MaterialTheme.colorScheme.error)
                    }
                    (if (running) view.monitors else recorded?.monitors.orEmpty()).forEach { monitor ->
                        Text("${monitor.name}: " + when {
                            monitor.availability == "false" -> "not supported"
                            monitor.completion == "true" -> "complete"
                            monitor.completion == "false" -> "incomplete"
                            else -> "unknown"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Stored-code identifiers remain available through a separate diagnostic scan. An incomplete readiness monitor does not identify a failed part.",
                        style = MaterialTheme.typography.bodySmall)
                } }
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Evidence coverage", style = MaterialTheme.typography.titleMedium)
                    if (view.coverage.isEmpty()) Text("All planned inputs are admitted. Receipt freshness is shown below.")
                    else view.coverage.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Actual refresh rates depend on the adapter. Samples and ages describe received data; unavailable or stale data cannot support a current finding.",
                        style = MaterialTheme.typography.bodySmall)
                } }
                if (running) {
                    item {
                        Text("Current context window", style = MaterialTheme.typography.titleMedium)
                        Text("Rolling measurements for the current motion and fuel-control context.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(view.signals, key = { it.pid }) { LiveSignalEvidence(it) }
                }
                item {
                    Text("Session context history", style = MaterialTheme.typography.titleMedium)
                    Text("Earlier observed contexts are session evidence. They do not describe the vehicle's current condition.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (view.contexts.isEmpty()) item { Text("Collecting context evidence…") }
                items(view.contexts) { ContextEvidence(it) }
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Assessment coverage", style = MaterialTheme.typography.titleMedium)
                    Text("Health checks use the available standard OBD evidence. ABS, airbags, body modules, and dedicated transmission-module health need separate coverage; no whole-vehicle grade is assigned.",
                        style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}

@Composable
private fun ReviewSectionChoice(destination: HealthReviewSection, selected: HealthReviewSection,
    running: Boolean, bookmarkCount: Int, modifier: Modifier, onSelect: (HealthReviewSection) -> Unit) {
    FilterChip(selected = selected == destination, onClick = { onSelect(destination) }, modifier = modifier,
        label = { Text(when (destination) {
            HealthReviewSection.OVERVIEW -> if (running) "Overview" else "Recap"
            HealthReviewSection.CHARTS -> "Charts"
            HealthReviewSection.EVENTS -> "Events ($bookmarkCount)"
        }) })
}

@Composable
private fun LiveSignalEvidence(signal: LiveHealthSignalView) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(signal.name, style = MaterialTheme.typography.titleSmall)
        Text(signal.availability, style = MaterialTheme.typography.bodySmall)
        Text(signal.latestValue.value(signal.unit), style = MaterialTheme.typography.titleLarge)
        signal.ageMillis?.let { Text("Last receipt ${it.receiptAge()} ago", style = MaterialTheme.typography.bodySmall) }
        if (signal.sampleCount > 0) Text("${signal.sampleCount} receipts · observed ${signal.minimum.value(signal.unit)} to ${signal.maximum.value(signal.unit)}",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContextEvidence(context: LiveHealthContextView) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(context.condition, style = MaterialTheme.typography.titleMedium)
        Text(context.fuelSystemStatus, style = MaterialTheme.typography.bodySmall)
        Text("${context.sampleCount} receipts · first-to-last receipt span ${context.spanMillis.duration()} (may include gaps)",
            style = MaterialTheme.typography.bodySmall)
        context.signals.filter { it.sampleCount > 0 }.forEach { signal ->
            Text("${signal.name}: ${signal.minimum.value(signal.unit)} to ${signal.maximum.value(signal.unit)} (${signal.sampleCount} receipts)",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Double?.value(unit: String?): String = if (this == null) "Unavailable"
    else String.format(Locale.US, "%.2f", this) + (unit?.let { " $it" } ?: "")

private fun Long.duration(): String {
    val seconds = coerceAtLeast(0) / 1_000
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}

private fun Long.receiptAge(): String = String.format(Locale.US, "%.1f s", coerceAtLeast(0) / 1_000.0)
