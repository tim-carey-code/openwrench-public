package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openwrench.core.*
import java.util.UUID

data class InvestigationRecommendationPreview(val headRevisionId: String, val report: InvestigationReasoningReport,
    val requestedStartingReportId: String? = null, val requestedTimingReportId: String? = null)

data class InvestigationWorkspaceState(val listings: List<InvestigationListing> = emptyList(),
    val cases: List<DiagnosticCase> = emptyList(), val opened: SavedInvestigation? = null,
    val busy: Boolean = false, val error: String? = null,
    val recommendations: InvestigationRecommendationPreview? = null)

/** One admitted operation per workspace. Expected-head writes prevent stale or double submissions.
 * Leaving the page cancels delivery, but an admitted atomic write finishes on IO before cancellation.
 */
class InvestigationCoordinator(private val store: InvestigationStore, private val caseStore: DiagnosticCaseStore,
    private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val mutable = MutableStateFlow(InvestigationWorkspaceState())
    val state = mutable.asStateFlow()
    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Operation failed; existing history was preserved") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun reload(openId: String? = null) = work {
        val listings = withContext(io) { store.list() }
        // A case-history error must not hide independent, already retained investigations.
        val cases = withContext(io) { runCatching { caseStore.load() } }
        val opened = if (openId == null) null else withContext(io) { store.load(openId) }
        mutable.value = InvestigationWorkspaceState(listings, cases.getOrDefault(emptyList()), opened, true,
            cases.exceptionOrNull()?.let { "Case choices unavailable: ${it.message}. Saved investigations can still be reopened." })
    }
    fun create(caseId: String, complaint: String, author: String, investigationId: String = newId()) = work {
        input(complaint, "Describe the reported complaint"); input(author, "Enter who is recording this")
        val saved = withContext(io + NonCancellable) {
            // A saved-state creation token makes an uncertain completion safe to retry after recreation.
            store.find(investigationId)?.let { existing ->
                val first = existing.journal.revisions.first()
                require((first.step as InvestigationStep.Complaint).reported == complaint.trim() &&
                    first.author == author.trim() && existing.cases.first().id == caseId) {
                    "This creation was already saved with different input. Return to the list and reopen it."
                }
                return@withContext existing
            }
            val case = caseStore.load().singleOrNull { it.id == caseId } ?: error("Select an available saved case")
            val record = DiagnosticInvestigation(investigationId, listOf(InvestigationRevision(newId(), 1, now(), author.trim(),
                "Start from a reported complaint", InvestigationStep.Complaint(complaint.trim()))))
            store.create(SavedInvestigation(record, listOf(case)))
        }
        mutable.update { it.copy(opened = saved, recommendations = null) }
    }
    fun append(expectedHead: String, author: String, reason: String, step: InvestigationStep) {
        if (state.value.busy) return
        val selected = state.value.opened ?: return
        work { appendTo(selected, expectedHead, author, reason, step) }
    }
    /** Preview is never persisted; case and exact journal head are explicit. */
    fun previewRecommendations(expectedHead: String, caseId: String, startingReportId: String? = null, timingReportId: String? = null) {
        if (state.value.busy) return
        val selected = state.value.opened ?: return
        mutable.update { it.copy(recommendations = null) }
        work {
            val preview = withContext(io) {
                val current = store.load(selected.journal.id)
                require(current.journal.revisions.last().id == expectedHead) { "Investigation changed; reload before previewing" }
                InvestigationRecommendationPreview(expectedHead, InvestigationReasoning.recommend(current, caseId,
                    selectedStartingRevisionId = startingReportId, selectedTimingRevisionId = timingReportId), startingReportId, timingReportId)
            }
            mutable.update { it.copy(recommendations = preview) }
        }
    }
    fun adoptRecommendedQuestion(expectedHead: String, caseId: String, questionId: InvestigationQuestionId,
        hypothesisIds: List<String>, author: String, reason: String) {
        if (state.value.busy) return
        val selected = state.value.opened ?: return
        val preview = state.value.recommendations
        work {
            require(preview != null && preview.headRevisionId == expectedHead && preview.report.caseId == caseId) {
                "Preview this case at the current revision before recording its question"
            }
            val recommendation = preview.report.recommendations.singleOrNull { it.question.id == questionId }
                ?: error("This question is not in the selected preview")
            // This stores only descriptive ProposedTest metadata, never an executable procedure.
            input(reason, "Explain why this question is being recorded")
            require(reason.length <= 3_500) { "Use at most 3,500 characters for the reason; case and preview references are added to the audit" }
            val auditReason = "$reason\nCatalog ${preview.report.catalogVersion} question preview: retained case $caseId; journal revision $expectedHead; " +
                "selected starting report ${preview.requestedStartingReportId ?: preview.report.selectedStartingReport?.revisionId ?: "none/unknown-or-conflicting"}; " +
                "selected timing report ${preview.requestedTimingReportId ?: preview.report.selectedTimingReport?.revisionId ?: "none/unknown-or-conflicting"}."
            appendTo(selected, expectedHead, author, auditReason, recommendation.question.proposal(hypothesisIds))
        }
    }
    private suspend fun appendTo(selected: SavedInvestigation, expectedHead: String, author: String,
        reason: String, step: InvestigationStep) {
        input(author, "Enter who is recording this"); input(reason, "Explain why this revision is being added")
        require(selected.journal.revisions.last().id == expectedHead) { "Investigation changed; reload before saving" }
        val saved = withContext(io + NonCancellable) {
            store.append(selected.journal.id, expectedHead, newId(), now(), author.trim(), reason.trim(), step)
        }
        mutable.update { it.copy(opened = saved, recommendations = null) }
    }
    private fun input(text: String, message: String) {
        require(text.isNotBlank()) { message }
        require(text.length <= 4_000 && '\u0000' !in text) { "Use at most 4,000 characters without null characters" }
    }
}
