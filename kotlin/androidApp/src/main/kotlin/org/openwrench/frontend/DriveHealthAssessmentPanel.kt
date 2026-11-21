package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openwrench.frontend.android.DriveHealthAssessment
import org.openwrench.frontend.android.DriveHealthDisposition
import org.openwrench.frontend.android.DriveHealthFinding
import org.openwrench.frontend.android.evidenceText

/** Assessment remains separate from raw measurements and never becomes a whole-vehicle grade. */
@Composable
internal fun DriveHealthAssessmentPanel(assessment: DriveHealthAssessment?, historical: Boolean,
    showSummary: Boolean = true, showFindings: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showSummary) WorkspacePanel(Modifier.fillMaxWidth()) {
            Text(if (historical) "Recorded health assessment" else "Health assessment",
                style = MaterialTheme.typography.titleLarge)
            if (assessment == null) {
                Text(if (historical) "This drive was saved before health assessments were available."
                    else "Gathering evidence for the first checks.")
                Text(if (historical) "Its measurements, charts, and warning bookmarks remain available. Start a new monitoring run to collect an assessment."
                    else "Checks explain what the available evidence supports and what still needs more data.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                val review = assessment.findings.count { it.disposition == DriveHealthDisposition.REVIEW }
                val reassuring = assessment.findings.count { it.disposition == DriveHealthDisposition.REASSURING }
                val observed = assessment.findings.count { it.disposition == DriveHealthDisposition.OBSERVED }
                val insufficient = assessment.findings.count { it.disposition == DriveHealthDisposition.INSUFFICIENT }
                Text(when {
                    review > 0 -> "$review check(s) to review"
                    reassuring > 0 -> "Reassuring evidence in $reassuring check(s)"
                    observed > 0 -> "Checks updated"
                    else -> "More evidence needed"
                }, style = MaterialTheme.typography.headlineSmall,
                    color = if (review > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                Text(buildList {
                    if (observed > 0) add("$observed observed")
                    if (reassuring > 0) add("$reassuring reassuring")
                    if (review > 0) add("$review to review")
                    if (insufficient > 0) add("$insufficient need more data")
                }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                Text(if (historical) "These findings describe the saved drive, not the vehicle's current condition."
                    else "Findings update from this monitored drive. A recorded concern remains visible if later readings change.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Fuel-correction screening is not an RPM/load-specific pass or fail test. Standard OBD checks cover part of the vehicle; no whole-vehicle grade is assigned.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!showFindings) return@Column
        assessment?.findings?.sortedBy { when (it.disposition) {
            DriveHealthDisposition.REVIEW -> 0
            DriveHealthDisposition.REASSURING -> 1
            DriveHealthDisposition.OBSERVED -> 2
            DriveHealthDisposition.INSUFFICIENT -> 3
            DriveHealthDisposition.NOT_ASSESSED -> 4
        } }?.forEach { finding -> AssessmentFindingPanel(finding) }
        if (assessment != null) WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Assessment scope", style = MaterialTheme.typography.titleSmall)
            assessment.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Assessment rules ${assessment.packVersion} · recorded through +${assessment.evaluatedAtOffsetMillis / 1_000} s",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AssessmentFindingPanel(finding: DriveHealthFinding) {
    var expanded by remember(finding.checkId) { mutableStateOf(false) }
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(finding.title, style = MaterialTheme.typography.titleMedium)
        Text(when (finding.disposition) {
            DriveHealthDisposition.REASSURING -> "Reassuring evidence"
            DriveHealthDisposition.OBSERVED -> "Observed"
            DriveHealthDisposition.REVIEW -> "Review recommended"
            DriveHealthDisposition.INSUFFICIENT -> "Not enough data"
            DriveHealthDisposition.NOT_ASSESSED -> "Not assessed"
        }, style = MaterialTheme.typography.labelLarge,
            color = when (finding.disposition) {
                DriveHealthDisposition.REVIEW -> MaterialTheme.colorScheme.tertiary
                DriveHealthDisposition.REASSURING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        Text(finding.summary)
        finding.nextSteps.firstOrNull()?.let { Text("Next: $it", style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide evidence" else "Evidence and next checks")
        }
        if (expanded) {
            if (finding.context.isNotBlank()) Text(finding.context, style = MaterialTheme.typography.bodySmall)
            if (finding.sampleCount > 0) Text(buildString {
                append("${finding.sampleCount} qualifying ${if (finding.fuelIntervals.isNotEmpty()) "independent pair(s)" else "receipt(s)"}")
                finding.firstOffsetMillis?.let { append(" · +${it / 1_000} s") }
                finding.lastOffsetMillis?.let { append(" to +${it / 1_000} s") }
            }, style = MaterialTheme.typography.bodySmall)
            finding.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            finding.comparisonWindow?.let { Text(it.reportText(), style = MaterialTheme.typography.bodySmall) }
            finding.fuelIntervals.forEachIndexed { index, interval ->
                HorizontalDivider()
                Text("Qualified interval ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(interval.evidenceText(), style = MaterialTheme.typography.bodySmall)
                var receiptsExpanded by remember(finding.checkId, interval.firstOffsetMillis) { mutableStateOf(false) }
                TextButton(onClick = { receiptsExpanded = !receiptsExpanded }) { Text(if (receiptsExpanded) "Hide paired receipts" else "Show paired receipts") }
                if (receiptsExpanded) interval.pairs.forEach { pair ->
                    Text("STFT ${pair.short.value}% at +${pair.short.offsetMillis} ms · LTFT ${pair.long.value}% at +${pair.long.offsetMillis} ms · separation ${pair.separationMillis} ms", style = MaterialTheme.typography.bodySmall)
                }
            }
            finding.nextSteps.drop(1).forEach { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
            finding.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
