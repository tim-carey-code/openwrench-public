package org.openwrench.frontend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A readable tablet grid for retained playback; other sources keep the existing table. */
internal fun denseReplayValueColumns(widthDp: Float, fontScale: Float, values: List<DashboardSignal>): Int {
    if (!widthDp.isFinite() || !fontScale.isFinite() || widthDp <= 0 || fontScale <= 0 ||
        values.size < 24 || values.any { !it.retainedReplay }) return 0
    val columns = ((widthDp + 6f) / (184f * fontScale.coerceAtLeast(1f) + 6f)).toInt().coerceAtMost(6)
    return columns.takeIf { it >= 4 } ?: 0
}

internal fun denseReplayValueDescription(row: DashboardSignal, imperial: Boolean): String {
    val value = workspaceValuePresentation(row, imperial)
    return "Retained PID ${row.definition.pid} value tile: ${row.definition.label} · ${value.value} ${value.unit} · ${value.status}. Historical replay, not live. Inspect for exact receipt details."
}

@Composable
internal fun DenseReplayValueCell(row: DashboardSignal, imperial: Boolean, onSelect: (String) -> Unit) {
    val value = workspaceValuePresentation(row, imperial)
    val ink = workspaceChannelInk(row.definition.id)
    Surface(Modifier.fillMaxWidth().heightIn(min = 104.dp)
        .clickable(role = Role.Button, onClickLabel = "Inspect ${row.definition.label}") { onSelect(row.definition.id) }
        .semantics(mergeDescendants = true) { contentDescription = denseReplayValueDescription(row, imperial) },
        color = WorkspaceNavy, border = BorderStroke(1.dp, WorkspaceLine), shape = RoundedCornerShape(5.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.heightIn(min = 32.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.definition.label, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.definition.pid.orEmpty(), fontSize = 9.sp, color = DashboardMuted)
            }
            Box(Modifier.heightIn(min = 25.dp)) {
                if (value.scalar) ScalarValue(value.value, value.unit, ink, maximumFontSize = 18)
                else Text(value.value + " " + value.unit, fontSize = 16.sp, color = ink)
            }
            Text(value.status, fontSize = 11.sp, lineHeight = 15.sp,
                color = if (value.available) DashboardMuted else DashboardAmber,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
