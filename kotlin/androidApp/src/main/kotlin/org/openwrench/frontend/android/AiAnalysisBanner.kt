package org.openwrench.frontend.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun AiAnalysisBanner(notice: AiAnalysisNotice, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val (title, action) = when (notice.phase) {
        AiAnalysisPhase.RUNNING -> "AI analysis in progress" to "View progress"
        AiAnalysisPhase.READY -> "AI assessment ready" to "View assessment"
        AiAnalysisPhase.FAILED -> "AI analysis could not finish" to "View details"
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(title, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall)
            FlowRow {
                TextButton(onClick = onOpen) { Text(action) }
                if (notice.phase != AiAnalysisPhase.RUNNING) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}
