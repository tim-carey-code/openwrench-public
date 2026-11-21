package org.openwrench.frontend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import java.text.DateFormat
import java.util.Date

/** Offline nested page: no session/controller/transport dependency. */
@Composable
internal fun InvestigationWorkspace(onExit: () -> Unit) {
    val root = LocalContext.current.applicationContext.filesDir
    val scope = rememberCoroutineScope()
    val coordinator = remember(root, scope) { InvestigationCoordinator(InvestigationStore(root), DiagnosticCaseStore(root), scope) }
    val state by coordinator.state.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("list") }
    var author by rememberSaveable { mutableStateOf("") }
    var creationId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var formHead by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = state.opened
    val visibleRevisions = remember(saved, mode) {
        saved?.let { InvestigationPresentation.revisions(it, fullHistory = mode == "history") }.orEmpty()
    }
    fun back() {
        when {
            mode !in setOf("list", "detail") -> mode = if (saved != null) "detail" else "list"
            saved != null -> { openId = null; mode = "list"; coordinator.reload() }
            else -> onExit()
        }
    }
    BackHandler { if (!state.busy) back() }
    LaunchedEffect(coordinator) { coordinator.reload(openId) }
    LaunchedEffect(saved?.journal?.id, saved?.journal?.revisions?.lastOrNull()?.id) {
        if (saved != null) {
            openId = saved.journal.id
            // A successful durable create/append closes its form. Failed writes keep the draft.
            if (mode != "recommendations" && (mode == "create" || formHead != saved.journal.revisions.last().id)) mode = "detail"
        }
    }
    fun form(next: String) { formHead = saved?.journal?.revisions?.last()?.id; mode = next }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Investigation workspace", style = MaterialTheme.typography.headlineMedium)
            Text("Offline · evidence and author judgments · no vehicle tests executed", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = ::back, enabled = !state.busy) { Text(if (mode == "list") "Back to cases" else "Back") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        when {
            mode == "create" -> item {
                InvestigationCreateForm(state.cases, author, { author = it }, state.busy) { caseId, complaint ->
                    coordinator.create(caseId, complaint, author, creationId)
                }
            }
            saved != null && mode == "recommendations" -> item {
                InvestigationRecommendationsPanel(saved, state.recommendations, state.busy, author, { author = it },
                    onPreview = { caseId, starting, timing -> coordinator.previewRecommendations(saved.journal.revisions.last().id, caseId, starting, timing) },
                    onReport = { reason, step -> coordinator.append(saved.journal.revisions.last().id, author, reason, step) },
                    onAdopt = { preview, id, hypotheses, reason -> coordinator.adoptRecommendedQuestion(preview.headRevisionId,
                        preview.report.caseId, id, hypotheses, author, reason) },
                    onAddHypothesis = { form("hypothesis") }, onRecordResult = { form("result") })
            }
            saved != null && mode !in setOf("detail", "sources", "history") -> item {
                InvestigationStepForm(mode, saved, author, { author = it }, state.busy) { reason, step ->
                    coordinator.append(checkNotNull(formHead), author, reason, step)
                }
            }
            saved != null && mode == "sources" -> {
                saved.cases.forEach { case ->
                    item { Text("Frozen case ${case.id.take(8)} · historical", style = MaterialTheme.typography.titleLarge) }
                    items(case.sections.indices.toList(), key = { "${case.id}-$it" }) { index ->
                        var expanded by rememberSaveable(case.id, index) { mutableStateOf(false) }
                        WorkspacePanel(Modifier.fillMaxWidth()) {
                            Text(DiagnosticCaseBuilder.headings[index], style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide source section" else "Show source section") }
                            if (expanded) Text(case.sections[index].substringAfter('\n'), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            saved != null -> {
                val revisions = saved.journal.revisions
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text((revisions.first().step as InvestigationStep.Complaint).reported, style = MaterialTheme.typography.titleMedium)
                    Text("${revisions.size} saved revisions · ${saved.cases.size} frozen case(s). Hypotheses remain unproven; supporting evidence is not cause certainty.")
                    if (mode == "detail") {
                        OutlinedButton(onClick = { form("recommendations") }, enabled = !state.busy) { Text("Suggested next questions") }
                        OutlinedButton(onClick = { form("observation") }, enabled = !state.busy) { Text("Add observation") }
                        OutlinedButton(onClick = { form("hypothesis") }, enabled = !state.busy) { Text("Add hypothesis") }
                        OutlinedButton(onClick = { form("question") }, enabled = !state.busy) { Text("Add distinguishing question") }
                        OutlinedButton(onClick = { form("result") }, enabled = !state.busy) { Text("Record test result") }
                        OutlinedButton(onClick = { mode = "sources" }) { Text("Inspect frozen case evidence") }
                        OutlinedButton(onClick = { mode = "history" }) { Text("Full revision history") }
                        OutlinedButton(onClick = { coordinator.reload(saved.journal.id) }, enabled = !state.busy) { Text("Reload saved investigation") }
                    } else Text("Every revision is retained, including corrected results. Recording time is not measurement time.")
                } }
                items(visibleRevisions, key = { it.id }) { revision -> WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("#${revision.sequence} · ${InvestigationPresentation.title(revision.step)}", style = MaterialTheme.typography.titleMedium)
                    val detail = InvestigationPresentation.detail(revision.step) { id ->
                        revisions.singleOrNull { it.id == id }?.let { "#${it.sequence}" } ?: id
                    }
                    Text(detail)
                    Text("Recorded by ${revision.author} · ${DateFormat.getDateTimeInstance().format(Date(revision.recordedAtEpochMillis))}", style = MaterialTheme.typography.bodySmall)
                    if (mode == "history") {
                        Text("Revision reason: ${revision.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
            else -> {
                item { WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text("Turn saved evidence into an investigation", style = MaterialTheme.typography.titleLarge)
                    Text("Start with a reported complaint and a saved diagnostic case. Add attributed observations, competing explanations and questions that could distinguish them. Manually record what was found; uncertainty stays visible.")
                    Button(onClick = { creationId = java.util.UUID.randomUUID().toString(); mode = "create" }, enabled = !state.busy && state.cases.isNotEmpty()) { Text("Start investigation") }
                    if (state.cases.isEmpty()) Text("Create a diagnostic case first using a saved scan and completed drive, then reload here.")
                    OutlinedButton(onClick = { coordinator.reload() }, enabled = !state.busy) { Text("Reload investigations") }
                } }
                if (state.listings.isEmpty() && !state.busy) item { Text("No investigations saved yet.") }
                items(state.listings, key = { it.storageKey }) { entry -> WorkspacePanel(Modifier.fillMaxWidth()) {
                    Text(entry.complaint, style = MaterialTheme.typography.titleMedium)
                    if (entry.error != null) Text("Cannot open: ${entry.error}. File preserved.", color = MaterialTheme.colorScheme.error)
                    else {
                        Text("${entry.revisionCount} saved revisions")
                        OutlinedButton(onClick = { openId = entry.id; mode = "detail"; coordinator.reload(entry.id) }, enabled = !state.busy) { Text("Open investigation") }
                    }
                } }
            }
        }
    }
}

@Composable
private fun InvestigationField(label: String, value: String, busy: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 4_000) change(it) }, label = { Text(label) }, enabled = !busy,
        modifier = Modifier.fillMaxWidth(), minLines = 2, supportingText = { Text("${value.length}/4000") })
}

@Composable
private fun InvestigationCreateForm(cases: List<DiagnosticCase>, author: String, onAuthor: (String) -> Unit,
    busy: Boolean, submit: (String, String) -> Unit) {
    var complaint by rememberSaveable { mutableStateOf("") }
    var caseId by rememberSaveable { mutableStateOf<String?>(null) }
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text("Start from a reported complaint", style = MaterialTheme.typography.titleLarge)
        InvestigationField("Complaint / symptoms as reported", complaint, busy) { complaint = it }
        InvestigationField("Recorded by", author, busy, onAuthor)
        Text("Choose the frozen case to retain. Vehicle description and complaint are reports, not verified facts.")
        cases.forEach { case ->
            FilterChip(selected = caseId == case.id, enabled = !busy, onClick = { caseId = case.id }, label = {
                Text("Case ${case.id.take(8)} · ${case.scan.capture?.origin} scan + ${case.drive.origin} drive")
            })
        }
        Button(onClick = { submit(checkNotNull(caseId), complaint) }, enabled = !busy && caseId != null && complaint.isNotBlank() && author.isNotBlank()) { Text("Save new investigation") }
    }
}

@Composable
private fun InvestigationStepForm(mode: String, saved: SavedInvestigation, author: String,
    onAuthor: (String) -> Unit, busy: Boolean, submit: (String, InvestigationStep) -> Unit) {
    var text by rememberSaveable(mode) { mutableStateOf("") }
    var provenance by rememberSaveable(mode) { mutableStateOf("") }
    var reason by rememberSaveable(mode) { mutableStateOf("") }
    var outcomes by rememberSaveable(mode) { mutableStateOf("") }
    var selectedIds by rememberSaveable(mode) { mutableStateOf(listOf<String>()) }
    var selectedRef by rememberSaveable(mode) { mutableStateOf<Int?>(null) }
    var selectedTestId by rememberSaveable(mode) { mutableStateOf<String?>(null) }
    var observationKind by rememberSaveable(mode) { mutableStateOf("REPORTED") }
    var outcome by rememberSaveable(mode) { mutableStateOf("INCONCLUSIVE") }
    var effect by rememberSaveable(mode) { mutableStateOf("UNCHANGED") }
    var questionKind by rememberSaveable(mode) { mutableStateOf("REVIEW_RETAINED_EVIDENCE") }
    var error by rememberSaveable(mode) { mutableStateOf<String?>(null) }
    val revisions = saved.journal.revisions
    val observations = revisions.filter { it.step is InvestigationStep.Observation }
    val hypotheses = revisions.filter { it.step is InvestigationStep.Hypothesis }
    val tests = revisions.filter { (it.step as? InvestigationStep.ProposedTest)?.kind?.let { kind -> kind != InvestigationTestKind.ACTUATOR_UNSUPPORTED } == true }
    val choices = remember(saved) { InvestigationPresentation.choices(saved) }
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    WorkspacePanel(Modifier.fillMaxWidth()) {
        Text(when (mode) { "observation" -> "Add attributed observation"; "hypothesis" -> "Add an unproven hypothesis"; "question" -> "Distinguish competing explanations"; else -> "Record a result or correction" }, style = MaterialTheme.typography.titleLarge)
        InvestigationField("Recorded by", author, busy, onAuthor)
        InvestigationField("Why add this revision?", reason, busy) { reason = it }
        when (mode) {
            "observation" -> {
                listOf("REPORTED" to "Reported · unverified", "MANUALLY_OBSERVED" to "Manually observed · attributed", "RETAINED" to "Retained measured evidence").forEach { (key, label) ->
                    FilterChip(selected = observationKind == key, enabled = !busy, onClick = { observationKind = key }, label = { Text(label) })
                }
                if (observationKind == "RETAINED") {
                    Text("Only available typed readings and retained qualified intervals are offered. Missing banks/measurements are not inferred.")
                    choices.forEachIndexed { index, choice ->
                        FilterChip(selected = selectedRef == index, enabled = !busy, onClick = { selectedRef = index }, label = { Text(choice.label) })
                        if (selectedRef == index) Text(choice.provenance, style = MaterialTheme.typography.bodySmall)
                    }
                    if (choices.isEmpty()) Text("No measured readings or qualified intervals retained in this case.")
                } else {
                    InvestigationField("Observation (include value / units / conditions if known)", text, busy) { text = it }
                    InvestigationField("Provenance / method / actual observation time (or unknown)", provenance, busy) { provenance = it }
                    Text("Manual entries are author-attributed, not independently verified. A purge click is not sealing or flow evidence.")
                }
            }
            "hypothesis" -> {
                InvestigationField("Possible explanation · not a diagnosis", text, busy) { text = it }
                Text("Select observations motivating this hypothesis. Reported claims remain unverified.")
                observations.forEach { r -> InvestigationSelection(r, r.id in selectedIds, busy) { toggle(r.id) } }
                if (observations.isEmpty()) Text("Add observations first.")
            }
            "question" -> {
                InvestigationField("Question that distinguishes the alternatives", text, busy) { text = it }
                InvestigationField("What differing outcomes would mean (and limitations)", outcomes, busy) { outcomes = it }
                listOf("REVIEW_RETAINED_EVIDENCE" to "Review retained evidence", "MANUAL_RESULT_ONLY" to "Manually record an external result").forEach { (key, label) ->
                    FilterChip(selected = questionKind == key, enabled = !busy, onClick = { questionKind = key }, label = { Text(label) })
                }
                Text("Choose at least two competing hypotheses. This records a question; it does not run a vehicle test or supply a live procedure.")
                hypotheses.forEach { r -> InvestigationSelection(r, r.id in selectedIds, busy) { toggle(r.id) } }
                if (hypotheses.size < 2) Text("Add at least two hypotheses first.")
            }
            "result" -> {
                Text("Choose the question being answered")
                tests.forEach { r -> InvestigationSelection(r, r.id == selectedTestId, busy) { selectedTestId = r.id; selectedIds = emptyList(); effect = "UNCHANGED" } }
                val previous = saved.journal.activeResults().singleOrNull { (it.step as InvestigationStep.TestResult).testId == selectedTestId }
                previous?.let { Text("This will correct result #${it.sequence}. The original stays in Full revision history.") }
                InvestigationField("Actual result and limits", text, busy) { text = it }
                InvestigationField("Result provenance / method / time (or unknown)", provenance, busy) { provenance = it }
                InvestigationOutcome.entries.forEach { value -> FilterChip(selected = outcome == value.name, enabled = !busy,
                    onClick = { outcome = value.name; if (value != InvestigationOutcome.RECORDED) effect = "UNCHANGED" }, label = { Text(value.name.lowercase()) }) }
                Text("Select supporting observations. Reported claims cannot qualify support or weakening.")
                observations.forEach { r -> InvestigationSelection(r, r.id in selectedIds, busy) { toggle(r.id) } }
                Text("Effect on one selected hypothesis (author judgment, not cause certainty)")
                val test = tests.singleOrNull { it.id == selectedTestId }?.step as? InvestigationStep.ProposedTest
                val eligibleHypotheses = hypotheses.filter { it.id in test?.hypothesisIds.orEmpty() }
                var effectHypothesis by rememberSaveable(selectedTestId) { mutableStateOf<String?>(null) }
                eligibleHypotheses.forEach { r -> InvestigationSelection(r, effectHypothesis == r.id, busy) { effectHypothesis = r.id } }
                HypothesisEffect.entries.forEach { value -> FilterChip(selected = effect == value.name, enabled = !busy && (value == HypothesisEffect.UNCHANGED || outcome == "RECORDED"),
                    onClick = { effect = value.name }, label = { Text(value.name.lowercase()) }) }
                InvestigationField("Why does this result support / weaken / leave it unchanged?", outcomes, busy) { outcomes = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy, onClick = {
                    error = runCatching {
                        require(text.isNotBlank() && provenance.isNotBlank() && outcomes.isNotBlank() && author.isNotBlank() && reason.isNotBlank()) { "Complete result, provenance, rationale, author and revision reason." }
                        val testId = requireNotNull(selectedTestId) { "Select a question." }
                        val h = requireNotNull(effectHypothesis) { "Select the hypothesis this judgment concerns." }
                        if (effect != "UNCHANGED") require(selectedIds.isNotEmpty() && observations.filter { it.id in selectedIds }.all { (it.step as InvestigationStep.Observation).kind != InvestigationObservationKind.REPORTED }) { "Support / weakening needs measured or manually observed evidence, not reported claims." }
                        val step = InvestigationStep.TestResult(testId, enumValueOf(outcome), text.trim(), provenance.trim(), selectedIds,
                            listOf(InvestigationEffect(h, enumValueOf(effect), outcomes.trim())), previous?.id)
                        saved.journal.append(java.util.UUID.randomUUID().toString(), 1, author, reason, step)
                        submit(reason, step)
                    }.exceptionOrNull()?.message
                }) { Text(if (previous == null) "Save attributed result" else "Save result correction") }
            }
        }
        if (mode != "result") {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, onClick = {
                error = runCatching {
                    require(author.isNotBlank() && reason.isNotBlank()) { "Enter who is recording this and why." }
                    val step = when (mode) {
                        "observation" -> if (observationKind == "RETAINED") choices[requireNotNull(selectedRef) { "Choose retained evidence." }].observation() else {
                            require(text.isNotBlank() && provenance.isNotBlank()) { "Enter the observation and its provenance." }
                            InvestigationStep.Observation(enumValueOf(observationKind), text.trim(), provenance.trim())
                        }
                        "hypothesis" -> { require(text.isNotBlank() && selectedIds.isNotEmpty()) { "Enter an explanation and select observations." }; InvestigationStep.Hypothesis(text.trim(), selectedIds) }
                        else -> {
                            require(text.isNotBlank() && outcomes.isNotBlank() && selectedIds.size >= 2) { "Enter a question, distinguishing outcomes and at least two hypotheses." }
                            InvestigationStep.ProposedTest(enumValueOf(questionKind), text.trim(), selectedIds, outcomes.trim())
                        }
                    }
                    saved.journal.append(java.util.UUID.randomUUID().toString(), 1, author, reason, step)
                    submit(reason, step)
                }.exceptionOrNull()?.message
            }) { Text("Save revision") }
        }
    }
}

@Composable
private fun InvestigationSelection(revision: InvestigationRevision, selected: Boolean, busy: Boolean, click: () -> Unit) {
    val summary = when (val step = revision.step) {
        is InvestigationStep.Observation -> step.description
        is InvestigationStep.Hypothesis -> step.explanation
        is InvestigationStep.ProposedTest -> step.question
        else -> InvestigationPresentation.title(step)
    }
    FilterChip(selected = selected, enabled = !busy, onClick = click, label = { Text("#${revision.sequence} · $summary") })
}
