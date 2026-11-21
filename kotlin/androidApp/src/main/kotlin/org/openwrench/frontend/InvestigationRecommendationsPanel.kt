package org.openwrench.frontend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openwrench.core.*
import org.openwrench.frontend.android.*

/** Read-only previews plus deliberate ordinary ledger mutations; no session/controller dependency. */
@Composable
internal fun InvestigationRecommendationsPanel(saved: SavedInvestigation, preview: InvestigationRecommendationPreview?,
    busy: Boolean, author: String, onAuthor: (String) -> Unit,
    onPreview: (String, String?, String?) -> Unit,
    onReport: (String, InvestigationStep.Observation) -> Unit,
    onAdopt: (InvestigationRecommendationPreview, InvestigationQuestionId, List<String>, String) -> Unit,
    onAddHypothesis: () -> Unit, onRecordResult: () -> Unit) {
    var caseId by rememberSaveable(saved.journal.id) { mutableStateOf<String?>(null) }
    var reportForm by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val current = preview?.takeIf { it.headRevisionId == saved.journal.revisions.last().id && it.report.caseId == caseId }
    val head = saved.journal.revisions.last().id
    var seenHead by rememberSaveable { mutableStateOf(head) }
    LaunchedEffect(head) {
        if (seenHead != head) { reportForm = null; expanded = null; seenHead = head }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel(Modifier.fillMaxWidth()) {
            Text("Suggested next questions", style = MaterialTheme.typography.titleLarge)
            Text("Offline evidence review · questions, not diagnoses. Previewing does not save or execute anything. Order reflects missing prerequisites, not cause likelihood.")
            Text("Select the retained case explicitly")
            saved.cases.forEach { case ->
                FilterChip(selected = caseId == case.id, enabled = !busy, onClick = { caseId = case.id; expanded = null }, label = {
                    Text("Case ${case.id.take(8)} · historical ${case.scan.capture?.origin} scan / ${case.drive.origin} drive")
                })
            }
            Button(enabled = !busy && caseId != null, onClick = { onPreview(checkNotNull(caseId), null, null) }) { Text("Preview next questions") }
            OutlinedButton(onClick = { reportForm = "starting" }, enabled = !busy) { Text("Record reported starting behavior") }
            OutlinedButton(onClick = { reportForm = "timing" }, enabled = !busy) { Text("Record reported symptom timing") }
            OutlinedButton(onClick = onAddHypothesis, enabled = !busy) { Text("Add a competing hypothesis") }
            OutlinedButton(onClick = onRecordResult, enabled = !busy) { Text("Record an attributed result") }
        }
        reportForm?.let { kind ->
            ReportedInvestigationAnswerForm(kind, author, onAuthor, busy, onCancel = { reportForm = null }, submit = onReport)
        }
        if (current == null) {
            Text("Choose a case and preview. After a saved revision, preview again to use the latest evidence. These questions never run vehicle tests.")
        } else {
            val report = current.report
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Evidence basis and uncertainty", style = MaterialTheme.typography.titleMedium)
                Text("Bank evidence: ${report.bankRelationship.name.lowercase().replace('_', ' ')}")
                Text("Reported starting category: ${report.selectedStartingReport?.value?.label ?: "unknown or conflicting"}")
                Text("Reported timing: ${report.selectedTimingReport?.value?.label ?: "unknown or conflicting"}")
                listOfNotNull(current.requestedStartingReportId ?: report.selectedStartingReport?.revisionId,
                    current.requestedTimingReportId ?: report.selectedTimingReport?.revisionId).distinct().forEach { id ->
                    val revision = saved.journal.revisions.single { it.id == id }
                    val observation = revision.step as InvestigationStep.Observation
                    Text("Owner report #${revision.sequence} by ${revision.author}: ${observation.provenance}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Preview of saved revision ${saved.journal.revisions.last().sequence}. No new measurements or verified symptom outcome are inferred.", style = MaterialTheme.typography.bodySmall)
                if (report.correctionEvidence.isEmpty()) Text("No qualified paired correction evidence retained. Reported percentages and scan/chart points cannot substitute.")
                report.correctionEvidence.forEach { basis -> Text(basis.description, style = MaterialTheme.typography.bodySmall) }
                report.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (report.startingReports.size > 1) {
                    Text("Select an existing owner report for this preview only; conflicting history remains retained.")
                    report.startingReports.forEach { answer ->
                        val revision = saved.journal.revisions.single { it.id == answer.revisionId }
                        OutlinedButton(enabled = !busy, onClick = { onPreview(report.caseId, answer.revisionId, current.requestedTimingReportId ?: report.selectedTimingReport?.revisionId) }) {
                            Text("Use starting report #${revision.sequence}: ${answer.value.label}")
                        }
                        Text(answer.provenance, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (report.timingReports.size > 1) report.timingReports.forEach { answer ->
                    val revision = saved.journal.revisions.single { it.id == answer.revisionId }
                    OutlinedButton(enabled = !busy, onClick = { onPreview(report.caseId, current.requestedStartingReportId ?: report.selectedStartingReport?.revisionId, answer.revisionId) }) {
                        Text("Use timing report #${revision.sequence}: ${answer.value.label}")
                    }
                    Text(answer.provenance, style = MaterialTheme.typography.bodySmall)
                }
            }
            report.recommendations.forEachIndexed { index, recommendation ->
                val question = recommendation.question
                WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text(if (index == 0) "Next useful question" else "Further distinguishing question", style = MaterialTheme.typography.titleMedium)
                    Text(question.question, style = MaterialTheme.typography.titleMedium)
                    Text(recommendation.why)
                    val showing = expanded == question.id.name || (expanded == null && index == 0)
                    OutlinedButton(onClick = { expanded = if (showing) "none" else question.id.name }) { Text(if (showing) "Hide question detail" else "Review question detail") }
                    if (showing) {
                        Text("Competing explanations / outcomes")
                        question.alternatives.forEach { Text("• $it") }
                        Text(question.distinguishingOutcomes)
                        if (recommendation.missingEvidence.isNotEmpty()) {
                            Text("Missing evidence / prerequisites", color = MaterialTheme.colorScheme.tertiary)
                            recommendation.missingEvidence.forEach { Text("• $it") }
                        }
                        question.prerequisites.forEach { Text("Prerequisite: $it", style = MaterialTheme.typography.bodySmall) }
                        question.limitations.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        question.sourceIds.forEach { id ->
                            val source = InvestigationQuestionCatalog.sources.single { it.id == id }
                            Text("Reference: ${source.title}\n${source.scope}\n${source.limitation}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Record this descriptive question only. No procedure, command or vehicle action will run.")
                        AdoptInvestigationQuestionForm(saved, current, question, author, onAuthor, busy, onAdopt)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportedInvestigationAnswerForm(kind: String, author: String, onAuthor: (String) -> Unit,
    busy: Boolean, onCancel: () -> Unit, submit: (String, InvestigationStep.Observation) -> Unit) {
    var selected by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var provenance by rememberSaveable(kind) { mutableStateOf("") }
    var reason by rememberSaveable(kind) { mutableStateOf("") }
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text("Owner-reported ${if (kind == "starting") "starting behavior" else "timing"}", style = MaterialTheme.typography.titleMedium)
        Text("This is an attributed report, not a measured cranking speed, cold-start record or verified event match.")
        RecommendationTextField("Recorded by", author, busy, onAuthor)
        RecommendationTextField("Provenance / occasion / time (or unknown)", provenance, busy) { provenance = it }
        RecommendationTextField("Revision reason", reason, busy) { reason = it }
        val options = if (kind == "starting") StartingBehavior.entries.map { it.name to it.label } else SymptomTiming.entries.map { it.name to it.label }
        options.forEach { (key, label) ->
            FilterChip(selected = selected == key, enabled = !busy, onClick = { selected = key }, label = { Text(label) })
        }
        Button(enabled = !busy && selected != null && provenance.isNotBlank() && author.isNotBlank() && reason.isNotBlank(), onClick = {
            val step = if (kind == "starting") InvestigationQuestionCatalog.startingReport(enumValueOf(checkNotNull(selected)), provenance)
                else InvestigationQuestionCatalog.timingReport(enumValueOf(checkNotNull(selected)), provenance)
            submit(reason, step)
        }) { Text("Save reported answer") }
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel reported answer") }
    }
}

@Composable
private fun AdoptInvestigationQuestionForm(saved: SavedInvestigation, preview: InvestigationRecommendationPreview,
    question: InvestigationQuestion, author: String, onAuthor: (String) -> Unit, busy: Boolean,
    submit: (InvestigationRecommendationPreview, InvestigationQuestionId, List<String>, String) -> Unit) {
    var selected by rememberSaveable(preview.headRevisionId, preview.report.caseId, question.id.name, preview.requestedStartingReportId, preview.requestedTimingReportId) { mutableStateOf(listOf<String>()) }
    var reason by rememberSaveable(preview.headRevisionId, preview.report.caseId, question.id.name, preview.requestedStartingReportId, preview.requestedTimingReportId) { mutableStateOf("") }
    val hypotheses = saved.journal.revisions.filter { it.step is InvestigationStep.Hypothesis }
    RecommendationTextField("Recorded by", author, busy, onAuthor)
    RecommendationTextField("Why record this question?", reason, busy) { reason = it }
    Text("Link at least two existing competing hypotheses. Selection does not establish either explanation.")
    hypotheses.forEach { revision ->
        val step = revision.step as InvestigationStep.Hypothesis
        FilterChip(selected = revision.id in selected, enabled = !busy,
            onClick = { selected = if (revision.id in selected) selected - revision.id else selected + revision.id },
            label = { Text("#${revision.sequence} · ${step.explanation}") })
    }
    if (hypotheses.size < 2) Text("Add observations and at least two hypotheses before recording a question.")
    if (reason.length > 3_500) Text("Use at most 3,500 characters for the reason; the saved audit also includes case and preview references.")
    Button(enabled = !busy && selected.size in 2..32 && author.isNotBlank() && reason.isNotBlank() && reason.length <= 3_500,
        onClick = { submit(preview, question.id, selected, reason) }) { Text("Record this question") }
}

@Composable
private fun RecommendationTextField(label: String, text: String, busy: Boolean, change: (String) -> Unit) {
    OutlinedTextField(text, { if (it.length <= 4_000 && '\u0000' !in it) change(it) }, enabled = !busy,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
}
