package org.openwrench.frontend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.openwrench.core.*

/** On-demand graph choices. Toggling is presentation-only, never an acquisition request. */
@Composable
internal fun GraphPickerMenu(rows: List<DashboardSignal>, selected: List<String>, onToggle: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.semantics {
        contentDescription = "Choose PID graphs"
        stateDescription = "${selected.size} graphs selected"
    }) {
        WorkspaceIcon(WorkspaceIcon.MENU, MaterialTheme.colorScheme.onSurface, Modifier.size(24.dp))
    }
    if (expanded) GraphPickerDialog(rows, selected, onToggle) { expanded = false }
}

@Composable
internal fun GraphPickerDialog(rows: List<DashboardSignal>, selected: List<String>, onToggle: (String) -> Unit, archived: Boolean = false, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.CenterEnd) {
            // The dialog window fills the screen: its internal scrim needs its own dismissal target.
            Box(Modifier.matchParentSize().clickable(onClickLabel = "Close display signals", onClick = onDismiss)
                .semantics { contentDescription = "Close display signals" })
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight()
                // Consume blank panel taps; child controls and scrolling handle their own gestures.
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, DashboardBorder)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Display signals", style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) { GraphPicker(rows, selected, onToggle, archived) }
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Done · Keep ${selected.size} selected")
                    }
                }
            }
        }
    }
}

@Composable
internal fun GraphPicker(rows: List<DashboardSignal>, selected: List<String>, onToggle: (String) -> Unit, archived: Boolean = false) {
    var search by remember { mutableStateOf("") }
    val filtered = graphPickerRows(rows, search, supportedOnly = false)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
            label = { Text("Search signal name or PID") }, singleLine = true,
            trailingIcon = { if (search.isNotEmpty()) TextButton({ search = "" }) { Text("Clear") } })
        Text("${selected.size} selected · ${filtered.size} matches · display only",
            style = MaterialTheme.typography.bodySmall)
        if (rows.any { it.retainedReplay }) Text(retainedReplayCatalogSummary(rows), style = MaterialTheme.typography.bodySmall)
        // Extra row of headroom keeps five standard rows visible between scroll positions.
        // Allow wrapping and system font scaling rather than clipping PID names.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)
            .semantics { contentDescription = "Scrollable PID graph choices" },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.definition.id }) { row ->
                val checked = row.definition.id in selected
                Surface(shape = MaterialTheme.shapes.small,
                    color = if (checked) MaterialTheme.colorScheme.secondaryContainer else DashboardCanvas,
                    border = BorderStroke(1.dp, if (checked) TelemetryInk else DashboardBorder)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(row.definition.id) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(row.definition.graphLabel(), style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(row.definition.pid, workspaceSupportLabel(row, archived), row.status).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (checked) "On" else "Off", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("No matching numeric PIDs. Clear search to see all.",
                style = MaterialTheme.typography.bodySmall) }
        }
        Text("Selection changes display only. Configure adapter reads in Advanced. Unsupported PIDs stay unavailable.",
            style = MaterialTheme.typography.bodySmall)
    }
}
