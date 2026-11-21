package org.openwrench.frontend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.MAX_LIVE_SIGNALS

/** Dense inventory presentation only. Opening details or pinning never acquires a signal. */
@Composable
internal fun SignalBrowserRow(
    row: DashboardSignal,
    pinned: Boolean,
    acquisition: WatchUiState?,
    onPin: () -> Unit,
    onAcquisition: (Boolean) -> Unit,
    onSelect: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            // Reserve full-size actions; wrap below the text on narrow screens / large fonts.
            val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp
            val summary: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open ${row.definition.label} details", onClick = onSelect)
                    .padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(row.definition.label, style = MaterialTheme.typography.titleSmall)
                    Text("${row.value?.compactDisplay() ?: "—"}${row.definition.unit?.let { " $it" } ?: ""} · ${row.status}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(row.definition.id, row.definition.pid).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val actions: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPin, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "${if (pinned) "Unpin" else "Pin"} ${row.definition.label} ${if (pinned) "from" else "to"} dashboard" }) {
                        Text(if (pinned) "Unpin" else "Pin")
                    }
                    if (acquisition != null) {
                        val chosen = row.definition.id in acquisition.selection
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(checked = chosen, onCheckedChange = onAcquisition,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = "Acquire ${row.definition.label}" },
                                enabled = acquisition.canEditSelection() && row.definition.isAcquirable() &&
                                    (chosen || acquisition.selection.size < MAX_LIVE_SIGNALS))
                            Text("Read", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (stacked) {
                Column {
                    summary(Modifier.fillMaxWidth())
                    actions()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    summary(Modifier.weight(1f))
                    actions()
                }
            }
        }
    }
}
