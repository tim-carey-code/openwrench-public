package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.*
import java.text.DateFormat
import java.util.Date

internal fun replaySourceSummary(origin: DriveEvidenceOrigin): String = when (origin) {
    DriveEvidenceOrigin.SYNTHETIC -> "Synthetic replay · Not live"
    DriveEvidenceOrigin.HARDWARE -> "Recorded replay · Not live"
    DriveEvidenceOrigin.UNKNOWN -> "Replay · Source unknown · Not live"
}

internal fun replayTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun replayProgress(state: SavedDriveReplayState): String {
    val recording = state.recording ?: return "No replay"
    val span = recording.lastOffsetMillis - recording.firstOffsetMillis
    val phase = if (state.phase == ReplayPhase.PLAYING) "Playing" else "Paused"
    return "$phase · ${replayTime(state.positionMillis.coerceIn(0, span))} / ${replayTime(span)}"
}

/** Keep full source/state text readable at large font sizes without shrinking control targets. */
internal fun replayStatusStacks(widthDp: Float, fontScale: Float): Boolean =
    widthDp < 96f + 160f * fontScale.coerceAtLeast(1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedDriveReplayControls(state: SavedDriveReplayState, player: SavedDriveReplayPlayer,
    onChoose: () -> Unit, onClose: () -> Unit, onRestart: () -> Unit) {
    val recording = state.recording ?: return
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val playing = state.phase == ReplayPhase.PLAYING
    val action = if (playing) "Pause replay" else "Play replay"
    val fontScale = LocalDensity.current.fontScale
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text("Replay details") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Historical drive · Not live · ${recording.drive.replayOriginLabel()}")
                Text("Saved drive: ${DateFormat.getDateTimeInstance().format(Date(recording.drive.startedAtEpochMillis))}")
                Text("Loop ${state.loop + 1} · original offset +${state.originalOffsetMillis} ms")
                Text("Retained segment +${recording.firstOffsetMillis}…+${recording.lastOffsetMillis} ms at original speed. 1 s end hold before repeating. No adapter or new evidence.")
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
    // Bound the area on short windows; scrolling preserves all controls at enlarged text sizes.
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 128.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)
        .semantics { isTraversalGroup = true }) {
        val summary: @Composable () -> Unit = {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(replaySourceSummary(recording.drive.origin), style = MaterialTheme.typography.labelMedium)
                Text(replayProgress(state), style = MaterialTheme.typography.bodySmall)
            }
        }
        val controls: @Composable () -> Unit = {
            Row {
                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(action) } }, state = rememberTooltipState()) {
                    IconButton(onClick = { if (playing) player.pause() else player.play() },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = action }) {
                        ChartControlGlyph(if (playing) ChartControlIcon.PAUSE else ChartControlIcon.PLAY,
                            MaterialTheme.colorScheme.onSurface)
                    }
                }
                Box {
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Replay options") } }, state = rememberTooltipState()) {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)
                            .semantics { contentDescription = "Replay options" }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Restart replay") }, onClick = { menu = false; onRestart() })
                        DropdownMenuItem(text = { Text("Choose drive") }, onClick = { menu = false; onChoose() })
                        DropdownMenuItem(text = { Text("Stop replay") }, onClick = { menu = false; onClose() })
                        DropdownMenuItem(text = { Text("Replay details") }, onClick = { menu = false; details = true })
                    }
                }
            }
        }
        if (replayStatusStacks(maxWidth.value, fontScale)) {
            Column { summary(); controls() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { summary() }
                controls()
            }
        }
    }
}

@Composable
internal fun SavedDriveDemoPicker(history: DriveHistoryState?, imported: List<SavedDrive>, loading: Boolean,
    error: String?, onLocal: (String) -> Unit, onImported: (SavedDrive) -> Unit,
    onAnalyzeImported: (SavedDrive) -> Unit, onImport: () -> Unit, onCancel: () -> Unit,
    aiStatus: @Composable () -> Unit = {}) {
    AlertDialog(onDismissRequest = onCancel,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved drives")
                aiStatus()
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a saved drive to play on the dashboard at its original receipt timing. Only retained readings are replayed; saved history is unchanged.")
                Text("Preview apps have separate history. To use a drive from your regular app, export its diagnosis ZIP there and import it here. A drive-history JSON also works.", style = MaterialTheme.typography.bodySmall)
                if (loading || history?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                history?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (imported.isNotEmpty()) {
                    Text("Imported drives · not added to History", style = MaterialTheme.typography.titleSmall)
                    imported.forEach { drive ->
                        OutlinedButton(onClick = { onImported(drive) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Play ${DateFormat.getDateTimeInstance().format(Date(drive.startedAtEpochMillis))}\n${drive.replayOriginLabel()}")
                        }
                        OutlinedButton(onClick = { onAnalyzeImported(drive) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Analyze with AI")
                        }
                    }
                }
                val drives = history?.drives.orEmpty().filter { it.id != history?.activeId }
                if (drives.isEmpty() && history?.loading != true) Text("No saved drives in this app yet. Import a recording below.")
                drives.forEach { drive ->
                    OutlinedButton(onClick = { onLocal(drive.id) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Play ${DateFormat.getDateTimeInstance().format(Date(drive.startedAtEpochMillis))}\n${drive.status.name.lowercase()} · ${drive.elapsedMillis / 1000} s recorded")
                    }
                }
                Text("Playback never starts a vehicle connection. Backgrounding pauses playback; tap Play to resume.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onImport, enabled = !loading) { Text("Import drive / diagnosis ZIP") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
