package org.openwrench.frontend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openwrench.core.*
import org.openwrench.frontend.android.*

internal object SyntheticPilotPresentation {
    fun question(contract: ManualOutcomeContract, id: String): String {
        val r = contract.legacySnapshot().journal.revisions.single { it.id == id }
        return "$id · ${r.reason}\n${(r.step as InvestigationStep.ProposedTest).question}"
    }
    fun result(contract: ManualOutcomeContract, r: AttributedManualOutcome): String = buildString {
        appendLine("Question answered: ${question(contract, r.context.questionRevisionId)}")
        appendLine("${r.id} · revision ${r.sequence} · recorded ${r.recordedAtEpochMillis} · by ${r.author}")
        appendLine("${r.declaredOutcome} · ${r.method} · ${r.faultMode} · origin ${r.origin}")
        appendLine("Reason: ${r.reason}\nSource: ${r.provenance}")
        appendLine("Observed at: ${r.observedAtEpochMillis ?: "unknown"}; observer: ${r.observer.text ?: "unknown"}")
        appendLine("External method: ${r.methodDetail.text ?: "unknown"}\nConditions: ${r.conditions.text ?: "unknown"}")
        appendLine("Criteria: ${r.criteriaReference.text ?: "unknown"}; applicability: ${r.applicability}")
        appendLine("Context: case ${r.context.caseId}; starting ${r.context.startingReport.revisionId}; timing ${r.context.timingReport.revisionId}; adaptation ${r.context.adaptationReport.revisionId}")
        appendLine("Manual values: ${r.values.joinToString { "${it.value} ${it.reportedUnit ?: it.unit}" }.ifEmpty { "none recorded" }}; never OBD intervals")
        r.authorEffects.forEach { appendLine("Author assertion ${it.hypothesisId}: ${it.effect} · ${it.rationale}") }
        appendLine("Corrects: ${r.supersedesResultId ?: "no earlier result"}; original records retained")
        appendLine("Use: ${r.scopeAssessment().use}")
        r.scopeAssessment().gaps.forEach { appendLine("Gap: $it") }
        r.scopeAssessment().limitations.forEach { appendLine(it) }
    }
}

private val PilotDraftSaver = listSaver<SyntheticOutcomeDraft, String>(save = { d -> listOf(d.questionId, d.author, d.reason, d.provenance,
    d.method.name, d.outcome.name, d.faultMode.name, d.observer, d.methodDetail, d.conditions, d.criteria, d.applicability.name,
    d.observedAt, d.value, d.unit.name, d.otherUnit, d.vaporEffect.name, d.alternativeEffect.name, d.effectReason, d.corrects.orEmpty()) },
    restore = { a -> SyntheticOutcomeDraft(a[0], a[1], a[2], a[3], enumValueOf(a[4]), enumValueOf(a[5]), enumValueOf(a[6]),
        a[7], a[8], a[9], a[10], enumValueOf(a[11]), a[12], a[13], enumValueOf(a[14]), a[15], enumValueOf(a[16]), enumValueOf(a[17]), a[18], a[19].ifEmpty { null }) })

@Composable
internal fun SyntheticInvestigationPilotPage(onExit: () -> Unit) {
    val root = LocalContext.current.applicationContext.filesDir
    val scope = rememberCoroutineScope()
    val coordinator = remember(root, scope) { SyntheticPilotCoordinator(SyntheticPilotRepository(root), scope) }
    val state by coordinator.state.collectAsState()
    var question by rememberSaveable { mutableStateOf<String?>(null) }
    var correction by rememberSaveable { mutableStateOf<String?>(null) }
    var formHead by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedHead by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.storage?.committed
    fun back() {
        if (question != null) { question = null; correction = null; submittedHead = null }
        else if (history) history = false
        else { coordinator.leave(); onExit() }
    }
    BackHandler(onBack = ::back)
    DisposableEffect(coordinator) { onDispose { coordinator.leave() } }
    LaunchedEffect(coordinator) { coordinator.load() }
    LaunchedEffect(snapshot?.digest) {
        if (submittedHead != null && snapshot != null && snapshot.digest != submittedHead) {
            question = null; correction = null; submittedHead = null
        }
    }
    // A newly opened entry/correction must not inherit the scrolled question/history position.
    // Same-view recreation still restores that view's saved scroll and draft normally.
    val scroll = key(question, correction, formHead) { rememberLazyListState() }
    LazyColumn(Modifier.fillMaxSize().imePadding(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Synthetic investigation pilot", style = MaterialTheme.typography.headlineMedium)
            Text("Fictional example only. No vehicle test is performed. Entries remain SYNTHETIC; this pilot does not read or change saved vehicle histories.")
            OutlinedButton(onClick = ::back) { Text(if (question != null) "Cancel entry" else if (history) "Back to pilot" else "Back to cases") }
            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("A save already in progress may finish. Reopen to check it.") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = coordinator::load, enabled = !state.busy) { Text("Reload pilot") }
        }
        if (state.storage != null && snapshot == null && state.storage?.pending == null && state.error == null) item {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Start a separate fictional investigation", style = MaterialTheme.typography.titleMedium)
                Text("Fixed complaint, synthetic Bank 1 interval, unknown Bank 2, competing hypotheses and two predefined occasions. No case import, arbitrary editing, migration or deletion.")
                Button(onClick = coordinator::start, enabled = !state.busy) { Text("Start fictional pilot") }
            }
        }
        state.storage?.pending?.let { pending -> item {
            WorkspacePanel(Modifier.fillMaxWidth()) {
                Text("Unfinished save preserved", style = MaterialTheme.typography.titleMedium)
                Text("${pending.sizeBytes} staged bytes. Recording is blocked. Recovery validates this save against the complete prior history; invalid or partial data stays preserved.")
                Button(onClick = { coordinator.recover(snapshot?.digest, checkNotNull(pending.digest)) }, enabled = !state.busy && pending.digest != null) { Text("Finish validated pending save") }
            }
        } }
        if (snapshot != null) {
            val contract = snapshot.contract
            if (question != null) item {
                if (snapshot.digest != formHead) Text("Pilot changed. Cancel this entry and reopen it against the current history.", color = MaterialTheme.colorScheme.error)
                else key(formHead, question, correction) {
                    SyntheticOutcomeForm(contract, checkNotNull(question), correction, state.busy || state.storage?.pending != null, state.error) { draft ->
                        submittedHead = snapshot.digest
                        coordinator.append(snapshot.digest, draft)
                    }
                }
            } else {
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Fictional complaint and competing explanations", style = MaterialTheme.typography.titleMedium)
                    val saved = contract.legacySnapshot()
                    saved.journal.revisions.filter { it.step is InvestigationStep.Complaint || it.step is InvestigationStep.Hypothesis || it.step is InvestigationStep.Observation }.forEach { r ->
                        Text("${r.id} · ${InvestigationPresentation.title(r.step)}\n${InvestigationPresentation.detail(r.step)}")
                    }
                    OutlinedButton(onClick = { history = !history }) { Text(if (history) "Hide full history" else "Full history") }
                } }
                if (history) {
                    contract.legacySnapshot().journal.revisions.forEach { r -> item(key = "seed-${r.id}") {
                        WorkspacePanel(Modifier.fillMaxWidth()) { Text("${r.id} · ${r.author} · ${r.recordedAtEpochMillis}\n${r.reason}\n${InvestigationPresentation.detail(r.step)}") }
                    } }
                }
                val active = contract.activeResultIds().values.toSet()
                contract.outcomes.filter { history || it.id in active }.forEach { r -> item(key = "result-${r.id}") {
                    WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text(if (r.id in active) "Active attributed result" else "Earlier result · corrected", style = MaterialTheme.typography.titleMedium)
                        Text(SyntheticPilotPresentation.result(contract, r))
                        if (r.id in active) OutlinedButton(onClick = { question = r.context.questionRevisionId; correction = r.id; formHead = snapshot.digest },
                            enabled = !state.busy && state.error == null && state.storage?.pending == null) { Text("Record explicit correction") }
                    }
                } }
                SyntheticInvestigationSeed.questions.filter { it !in contract.activeResultIds() }.forEach { id -> item(key = "question-$id") {
                    WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text(SyntheticPilotPresentation.question(contract, id), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { question = id; correction = null; formHead = snapshot.digest },
                            enabled = !state.busy && state.error == null && state.storage?.pending == null) { Text("Record fictional outcome") }
                    }
                } }
                state.preview?.let { preview -> item {
                    WorkspacePanel(Modifier.fillMaxWidth()) {
                        Text("Suggested next question · synthetic preview", style = MaterialTheme.typography.titleLarge)
                        Text(preview.nextQuestion.question); Text(preview.why)
                        preview.gaps.forEach { Text("Missing: $it") }
                        preview.nextQuestion.prerequisites.forEach { Text("Prerequisite: $it") }
                        preview.nextQuestion.limitations.forEach { Text(it) }
                        Text("Retained fictional evidence: ${preview.basis.bankRelationship}")
                        preview.basis.correctionEvidence.forEach { Text(it.description) }
                        Text("Further distinguishing questions:")
                        preview.basis.recommendations.forEach { r -> Text("${r.question.question}\n${r.why}"); r.missingEvidence.forEach { Text("Missing: $it") } }
                        preview.basis.limitations.forEach { Text(it) }
                    }
                } }
            }
        }
    }
}

@Composable
private fun PilotField(label: String, value: String, busy: Boolean, error: String? = null, update: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 4000) update(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        isError = error != null, supportingText = error?.let { message -> { Text(message) } })
}
@Composable
private fun <T : Enum<T>> PilotChoice(label: String, value: T, values: List<T>, busy: Boolean, update: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    values.forEach { option -> FilterChip(selected = value == option, onClick = { update(option) }, enabled = !busy,
        label = { Text(option.name.lowercase().replace('_', ' ')) }, modifier = Modifier.fillMaxWidth()) }
}
@Composable
private fun SyntheticOutcomeForm(contract: ManualOutcomeContract, question: String, corrects: String?, busy: Boolean, saveError: String?, submit: (SyntheticOutcomeDraft) -> Unit) {
    var draft by rememberSaveable(stateSaver = PilotDraftSaver) { mutableStateOf(SyntheticOutcomeDraft(questionId = question, corrects = corrects)) }
    var attemptedSave by rememberSaveable { mutableStateOf(false) }
    val numericError = if (attemptedSave) draft.manualValueError() else null
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(if (corrects == null) "Record a fictional attributed outcome" else "Correct $corrects · original kept", style = MaterialTheme.typography.titleLarge)
        Text(SyntheticPilotPresentation.question(contract, question))
        Text("Blank optional details mean unknown. Describe a fictional external account only; no procedure, command or test is executed.")
        PilotField("Recorded by (required)", draft.author, busy) { draft = draft.copy(author = it) }
        PilotField("Reason for this record / correction (required)", draft.reason, busy) { draft = draft.copy(reason = it) }
        PilotField("Fictional source / provenance (required)", draft.provenance, busy) { draft = draft.copy(provenance = it) }
        PilotChoice("Tested fault mode", draft.faultMode, ManualFaultMode.entries, busy) { draft = draft.copy(faultMode = it) }
        PilotChoice("Actual reported method", draft.method, ManualOutcomeMethod.entries, busy) { draft = draft.copy(method = it) }
        PilotChoice("Author's declared outcome", draft.outcome, DeclaredManualOutcome.entries, busy) { draft = draft.copy(outcome = it) }
        PilotField("Observer (blank = unknown)", draft.observer, busy) { draft = draft.copy(observer = it) }
        PilotField("External method details", draft.methodDetail, busy) { draft = draft.copy(methodDetail = it) }
        PilotField("Conditions / occasion", draft.conditions, busy) { draft = draft.copy(conditions = it) }
        PilotField("Criteria document / edition / location", draft.criteria, busy) { draft = draft.copy(criteria = it) }
        PilotChoice("Reported applicability", draft.applicability, ReportedApplicability.entries, busy) { draft = draft.copy(applicability = it) }
        PilotField("Observed at UTC (2026-01-01T12:00:00Z, blank = unknown)", draft.observedAt, busy) { draft = draft.copy(observedAt = it) }
        PilotField("Optional manual value (blank = absent)", draft.value, busy, numericError) { draft = draft.copy(value = it) }
        PilotChoice("Manual unit · never OBD qualification", draft.unit, ManualValueUnit.entries, busy) { draft = draft.copy(unit = it) }
        if (draft.unit == ManualValueUnit.OTHER_REPORTED) PilotField("Other reported unit", draft.otherUnit, busy) { draft = draft.copy(otherUnit = it) }
        Text("Effects are author assertions only. Unknown, inconclusive, unavailable and inapplicable outcomes require unchanged effects.")
        PilotChoice("Vapor-flow hypothesis", draft.vaporEffect, HypothesisEffect.entries, busy) { draft = draft.copy(vaporEffect = it) }
        PilotChoice("Competing input/delivery hypothesis", draft.alternativeEffect, HypothesisEffect.entries, busy) { draft = draft.copy(alternativeEffect = it) }
        PilotField("Effect rationale (required for support / weakening)", draft.effectReason, busy) { draft = draft.copy(effectReason = it) }
        // Save is at the end of a long form: errors must also be visible at the action.
        (numericError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            attemptedSave = true
            if (draft.manualValueError() == null) submit(draft)
        }, enabled = !busy && draft.author.isNotBlank() && draft.reason.isNotBlank() && draft.provenance.isNotBlank()) { Text("Save fictional outcome") }
    }
}
