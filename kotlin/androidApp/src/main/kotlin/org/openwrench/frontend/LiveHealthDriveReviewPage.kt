package org.openwrench.frontend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import android.os.SystemClock
import org.openwrench.frontend.android.LiveHealthChartPoint
import org.openwrench.frontend.android.LiveHealthChartSeries
import org.openwrench.frontend.android.LiveHealthDriveReview
import org.openwrench.frontend.android.LiveHealthEventSample
import org.openwrench.frontend.android.LiveHealthWarningBookmark
import java.util.Locale

private val linkedChartPids = listOf(0x0C, 0x04, 0x10, 0x06, 0x07, 0x08, 0x09, 0x05, 0x0D)

@Composable
internal fun DriveRecapPanel(review: LiveHealthDriveReview, running: Boolean, compact: Boolean = false) {
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(if (running) "Drive recap · collecting" else if (compact) "Drive recap" else "Drive measurements",
            style = MaterialTheme.typography.titleLarge)
        Text("${review.elapsedMillis.reviewTime()} monitored · ${review.bookmarks.size} retained warning bookmark(s)")
        (if (compact) review.highlights.take(3) else review.highlights).forEach { Text(it) }
        Text("Observed operating conditions", style = MaterialTheme.typography.titleSmall)
        if (review.conditions.isEmpty()) Text("Waiting for supported context readings.")
        review.conditions.forEach { condition ->
            Text("${condition.condition}: ${condition.sampleCount} valid scalar receipts",
                style = MaterialTheme.typography.bodySmall)
        }
        if (!compact && review.signals.isNotEmpty()) {
            Text("Changes across this monitored session", style = MaterialTheme.typography.titleSmall)
            review.signals.forEach { signal ->
                Text("${signal.name}: ${signal.firstValue.reviewValue(signal.unit)} → ${signal.lastValue.reviewValue(signal.unit)}; observed ${signal.minimum.reviewValue(signal.unit)} to ${signal.maximum.reviewValue(signal.unit)} (${signal.sampleCount} receipts)",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Receipt counts describe sampled conditions, not continuously measured time spent driving. Use context history and charts to review how conditions differed.",
            style = MaterialTheme.typography.bodySmall)
        if (!compact) review.retentionNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun LiveHealthLinkedCharts(review: LiveHealthDriveReview,
    bookmark: LiveHealthWarningBookmark?, selectedOffsetMillis: Long?,
    onCursor: (Long) -> Unit, onRecentDrive: () -> Unit,
    collecting: Boolean = false, reviewKey: Any = review.instanceId) {
    val sourceKey = listOf(reviewKey, review.instanceId, review.startedAtMonotonicMillis, bookmark?.id, collecting)
    var frozenReview by remember(sourceKey) { mutableStateOf<LiveHealthDriveReview?>(null) }
    val displayedReview = frozenReview ?: review
    val displayedBookmark = bookmark?.let { requested ->
        displayedReview.bookmarks.firstOrNull { it.id == requested.id } ?: requested
    }
    val series = if (displayedBookmark == null) displayedReview.series.filter { it.pid in linkedChartPids }
        .sortedBy { linkedChartPids.indexOf(it.pid) } else displayedBookmark.chartSeries()
    val points = series.flatMap { it.points }
    var rangeMillis by remember(sourceKey) { mutableStateOf<Long?>(null) }
    var chartHelp by remember { mutableStateOf(false) }
    var fitScale by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    val retainedStart = points.minOfOrNull { it.offsetMillis }
    val end = points.maxOfOrNull { it.offsetMillis }
    val start = chartWindowStart(retainedStart, end, rangeMillis)
    var playing by remember(sourceKey) { mutableStateOf(false) }
    val latestOnCursor by rememberUpdatedState(onCursor)
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(lifecycleOwner, sourceKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            playing = false
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }
    LaunchedEffect(playing, sourceKey, rangeMillis, start, end) {
        if (playing && !collecting && start != null && end != null) {
            runSavedChartPlayback(
                initial = SavedChartPlayback(selectedOffsetMillis ?: bookmark?.offsetMillis ?: end).play(start, end),
                end = end,
                shouldContinue = { playing },
                clockMillis = SystemClock::elapsedRealtime,
                onCursor = { latestOnCursor(it) },
            )
            playing = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel(Modifier.fillMaxWidth()) {
            Text(if (bookmark == null) "Linked driving charts" else "Bookmark window · ${bookmark.title}",
                style = MaterialTheme.typography.titleLarge)
            ChartToolbar(
                transportLabel = if (collecting) { if (frozenReview == null) "Pause" else "Resume" }
                    else if (playing) "Pause" else "Play",
                transportDescription = if (collecting) "Freeze or resume charts only; recording continues"
                    else "Play or pause the saved time cursor at 1× speed",
                transportRunning = if (collecting) frozenReview == null else playing,
                transportEnabled = points.isNotEmpty() && (collecting || (start != null && end != null && end > start)),
                onTransport = {
                    if (collecting) {
                        if (frozenReview == null) frozenReview = freezeChartReview(frozenReview, review)
                        else frozenReview = null
                    } else playing = !playing
                },
                rangeOptions = listOf(ChartRangeOption(15_000L, "15 s"), ChartRangeOption(30_000L, "30 s"),
                    ChartRangeOption(60_000L, "60 s"), ChartRangeOption(300_000L, "5 min"), ChartRangeOption(null, "All")),
                selectedRangeMillis = rangeMillis,
                onRange = { playing = false; rangeMillis = it },
                fitScale = fitScale, onFitScale = { fitScale = !fitScale },
                expanded = expanded, onExpand = { expanded = !expanded },
                caption = if (collecting) {
                    if (frozenReview == null) "Collecting · Pause freezes charts only; recording continues"
                    else "Charts paused · recording continues"
                } else if (playing) "Saved receipts · playing cursor at 1× · gaps remain gaps"
                    else "Saved receipts · playback moves only the cursor"
            )
            TextButton(onClick = { chartHelp = !chartHelp }) { Text(if (chartHelp) "Hide chart guide" else "Chart guide") }
            if (chartHelp || bookmark != null) {
            Text("All plots share elapsed time from the start of monitoring. Each plot uses its own observed value scale. Fit scale uses visible receipts; turn it off for a stable scale across all retained receipts.")
            if (bookmark != null) {
                Text("Observed at ${bookmark.offsetMillis.reviewTime()} · ${displayedBookmark?.collectionStatus ?: bookmark.collectionStatus}")
                Text("The vertical warning marker shows this ECU observation time.", style = MaterialTheme.typography.bodySmall)
                Text("Retained points are shown; lines are omitted where missing data or sample limits prevent continuity checks.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRecentDrive) { Text("Return to recent drive charts") }
            } else Text("This is the retained recent trace. The recap keeps whole-session aggregates; each bookmark keeps its own saved window.",
                style = MaterialTheme.typography.bodySmall)
            Text("Move one cursor to compare nearby actual receipts. Values show their own receipt time; missing periods remain gaps.",
                style = MaterialTheme.typography.bodySmall)
            }
        }
        if (start == null || end == null || points.isEmpty()) {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(if (collecting) "No chart receipts yet" else "No retained chart receipts", style = MaterialTheme.typography.titleMedium)
                Text(if (collecting) "Supported RPM, load, MAF, fuel-trim, coolant, and speed readings will appear as they arrive. A missing chart is a coverage gap."
                    else "This saved window contains no supported scalar chart receipts. A missing chart is a coverage gap.")
            }
        } else {
            val safeEnd = maxOf(start, end)
            val cursor = (selectedOffsetMillis ?: bookmark?.offsetMillis ?: safeEnd).coerceIn(start, safeEnd)
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Shared cursor · ${cursor.reviewTime()}", style = MaterialTheme.typography.titleMedium)
                Slider(value = if (safeEnd == start) 0f else (cursor - start).toFloat() / (safeEnd - start).toFloat(),
                    onValueChange = { playing = false; onCursor(start + ((safeEnd - start) * it).toLong()) },
                    enabled = safeEnd > start,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Linked chart shared time cursor" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(start.reviewTime(), style = MaterialTheme.typography.bodySmall)
                    Text(safeEnd.reviewTime(), style = MaterialTheme.typography.bodySmall)
                }
                Text("Cursor readouts use the nearest actual receipt within 2 seconds. Lines connect retained adjacent receipts only; points mark actual readings.",
                    style = MaterialTheme.typography.bodySmall)
            }
            series.forEachIndexed { index, signal ->
                LinkedSignalChart(signal, start, safeEnd, cursor, bookmark?.offsetMillis, fitScale, expanded,
                    when (index % 3) {
                        0 -> MaterialTheme.colorScheme.primary
                        1 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    })
            }
            if (bookmark == null) WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Chart retention", style = MaterialTheme.typography.titleSmall)
                displayedReview.retentionNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("The recap keeps whole-session scalar aggregates. Charts are a bounded recent trace; bookmarks retain their own pre/post receipt windows.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LinkedSignalChart(series: LiveHealthChartSeries, start: Long, end: Long,
    cursor: Long, bookmarkOffset: Long?, fitScale: Boolean, expanded: Boolean, lineColor: Color) {
    val fontScale = LocalDensity.current.fontScale
    val points = series.points.filter { it.offsetMillis in start..end }.sortedBy { it.offsetMillis }
    val valid = points.mapNotNull { it.value?.takeIf(Double::isFinite) }
    val minimum = valid.minOrNull()
    val maximum = valid.maxOrNull()
    val nearest = savedChartReceipt(points, cursor)
    val bounds = savedChartBounds(series, start, end, fitScale)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bookmarkColor = MaterialTheme.colorScheme.tertiary
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(series.name, style = MaterialTheme.typography.titleMedium)
        when {
            nearest == null -> Text("No receipt within 2 s of cursor", style = MaterialTheme.typography.bodySmall)
            nearest.value == null || !nearest.value.isFinite() -> Text("Unavailable receipt at ${nearest.offsetMillis.reviewTime()} · ${nearest.offsetMillis.cursorDistance(cursor)}",
                style = MaterialTheme.typography.bodySmall)
            else -> Text("${nearest.value.reviewValue(series.unit)} at ${nearest.offsetMillis.reviewTime()} · ${nearest.offsetMillis.cursorDistance(cursor)}",
                style = MaterialTheme.typography.bodySmall)
        }
        if (minimum == null || maximum == null) {
            Text("No valid retained readings for this signal.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Observed range ${minimum.reviewValue(series.unit)} to ${maximum.reviewValue(series.unit)}",
                style = MaterialTheme.typography.bodySmall)
            val (lower, upper) = bounds ?: (minimum to maximum)
            Text("${upper.reviewValue(series.unit)}", style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(graphFocusPlotHeightDp(expanded, fontScale).dp).semantics {
                contentDescription = "${series.name} linked plot; ${points.size} actual retained receipts; cursor ${cursor.reviewTime()}; gaps stay empty"
            }) {
                fun x(offset: Long) = if (end == start) size.width / 2 else
                    ((offset - start).toDouble() / (end - start) * size.width).toFloat()
                fun y(value: Double) = (size.height - (value - lower) / (upper - lower) * size.height).toFloat()
                for (step in 0..4) {
                    val height = size.height * step / 4
                    drawLine(gridColor, Offset(0f, height), Offset(size.width, height), 1.dp.toPx())
                }
                var previous: LiveHealthChartPoint? = null
                points.forEach { point ->
                    val value = point.value?.takeIf(Double::isFinite)
                    val prior = previous
                    val priorValue = prior?.value?.takeIf(Double::isFinite)
                    if (value != null) {
                        if (prior != null && priorValue != null && !point.breakBefore &&
                            point.offsetMillis >= prior.offsetMillis &&
                            point.offsetMillis - prior.offsetMillis <= series.maximumLinkGapMillis) {
                            drawLine(lineColor, Offset(x(prior.offsetMillis), y(priorValue)),
                                Offset(x(point.offsetMillis), y(value)), 1.5.dp.toPx())
                        }
                        drawCircle(lineColor, 2.dp.toPx(), Offset(x(point.offsetMillis), y(value)))
                    }
                    previous = point.takeIf { value != null }
                }
                bookmarkOffset?.takeIf { it in start..end }?.let { at ->
                    drawLine(bookmarkColor, Offset(x(at), 0f), Offset(x(at), size.height), 2.dp.toPx())
                }
                drawLine(cursorColor, Offset(x(cursor), 0f), Offset(x(cursor), size.height), 1.dp.toPx())
            }
            Text("${lower.reviewValue(series.unit)}", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(start.reviewTime(), style = MaterialTheme.typography.labelSmall)
                Text(end.reviewTime(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun LiveHealthWarningBookmarks(review: LiveHealthDriveReview, selectedId: Long?,
    onSelect: (Long) -> Unit, onCharts: (LiveHealthWarningBookmark) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Warning bookmarks", style = MaterialTheme.typography.titleLarge)
            Text("ECU warning observations retain nearby receipts automatically. Review these after parking; no interaction is needed during driving.")
            if (review.bookmarks.isEmpty()) Text("No warning bookmarks were captured. This does not establish that every vehicle system is healthy.")
            if (review.droppedBookmarks > 0) Text("${review.droppedBookmarks} earlier bookmarks were removed by the retention limit.",
                style = MaterialTheme.typography.bodySmall)
        }
        review.bookmarks.forEach { bookmark ->
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text(bookmark.title, style = MaterialTheme.typography.titleMedium)
                Text("Observed at ${bookmark.offsetMillis.reviewTime()} · ${bookmark.collectionStatus}")
                Text(if (bookmark.initialObservation) "First observed status in this monitoring session"
                    else "Status change observed during monitoring", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onSelect(bookmark.id) }) {
                    Text(if (selectedId == bookmark.id) "Bookmark selected" else "Review bookmark")
                }
                if (selectedId == bookmark.id) {
                    Text(bookmark.observation)
                    Text("Saved receipt span: ${bookmark.preWindowMillis.reviewTime()} before / ${bookmark.postWindowMillis.reviewTime()} after the observation.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("${bookmark.samples.size} saved actual receipts · ${bookmark.samplesDropped} omitted by sample limits",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onCharts(bookmark) }) { Text("Review bookmark charts") }
                    if (bookmark.samples.isEmpty()) Text("No nearby receipts were retained.")
                    else {
                        val preview = bookmark.samples.receiptPreview(80)
                        Text("Receipt preview · ${preview.size} of ${bookmark.samples.size} actual receipts, spread across the saved window. Charts use all saved scalar receipts.",
                            style = MaterialTheme.typography.bodySmall)
                        preview.forEach { sample ->
                            Text("${sample.offsetMillis.eventDistance(bookmark.offsetMillis)} · ${sample.name}: ${sample.displayValue}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun List<LiveHealthEventSample>.receiptPreview(limit: Int): List<LiveHealthEventSample> {
    val ordered = sortedBy { it.offsetMillis }
    if (ordered.size <= limit) return ordered
    return (0 until limit).map { index -> ordered[index * (ordered.lastIndex) / (limit - 1)] }
}

private fun Long.reviewTime(): String {
    val millis = coerceAtLeast(0)
    return if (millis < 60_000) String.format(Locale.US, "%.1f s", millis / 1_000.0)
    else String.format(Locale.US, "%d min %.1f s", millis / 60_000, (millis % 60_000) / 1_000.0)
}

private fun Long.cursorDistance(cursor: Long): String {
    val delta = this - cursor
    return when {
        delta == 0L -> "at cursor"
        delta > 0 -> String.format(Locale.US, "+%.1f s after cursor", delta / 1_000.0)
        else -> String.format(Locale.US, "%.1f s before cursor", -delta / 1_000.0)
    }
}

private fun Long.eventDistance(observation: Long): String {
    val delta = this - observation
    return when {
        delta == 0L -> "At warning observation"
        delta > 0 -> String.format(Locale.US, "%.1f s after observation", delta / 1_000.0)
        else -> String.format(Locale.US, "%.1f s before observation", -delta / 1_000.0)
    }
}

private fun Double?.reviewValue(unit: String?): String = if (this == null) "Unavailable"
    else String.format(Locale.US, "%.2f", this) + (unit?.let { " $it" } ?: "")
