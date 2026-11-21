package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RetainedPreviewSelection(val investigationId: String, val context: CombinedPreviewSelection)
internal data class RetainedFrontendState(val loading: Boolean = false, val outcome: RetainedResult? = null)
/** Explicit retained IO/lifetime owner. No application/runtime/UI factory instantiates this candidate. */
internal class RetainedInvestigationService(private val repository: RetainedInvestigationRepository,
    private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val guard = Any()
    private var admitted = false
    private var generation = 0L
    private var previewGeneration = 0L
    private var selection: RetainedPreviewSelection? = null
    private val mutable = MutableStateFlow(RetainedFrontendState())
    val state = mutable.asStateFlow()
    private fun withoutPreview(result: RetainedResult?): RetainedResult? =
        if (result is RetainedResult.Previewed) RetainedResult.Opened(result.inspection) else result
    fun selectPreview(next: RetainedPreviewSelection?) = synchronized(guard) {
        if (selection != next) {
            selection = next; previewGeneration++
            mutable.value = mutable.value.copy(outcome = withoutPreview(mutable.value.outcome))
        }
    }
    fun dispatch(action: RetainedAction): CombinedAdmission = synchronized(guard) {
        if (!scope.isActive) return@synchronized CombinedAdmission.INACTIVE
        if (admitted) return@synchronized CombinedAdmission.BUSY
        if (action is RetainedAction.Preview && selection != RetainedPreviewSelection(action.id, action.request.selection()))
            return@synchronized CombinedAdmission.SELECTION_MISMATCH
        admitted = true
        val ownerToken = generation
        val previewToken = ++previewGeneration
        mutable.value = RetainedFrontendState(true, withoutPreview(mutable.value.outcome))
        val write = action is RetainedAction.Create || action is RetainedAction.Append || action is RetainedAction.Attach || action is RetainedAction.Recover || action is RetainedAction.ExportOriginals
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch(start = CoroutineStart.ATOMIC) {
            var result: RetainedResult? = null
            try {
                result = withContext(if (write) io + NonCancellable else io) { repository.execute(action) }
                ensureActive()
            } finally {
                synchronized(guard) {
                    admitted = false
                    if (result != null && ownerToken == generation && coroutineContext.isActive) {
                        val r = result
                        val deliver = if (r is RetainedResult.Previewed &&
                            (previewToken != previewGeneration || selection != RetainedPreviewSelection(r.submitted.id, r.submitted.request.selection()))) withoutPreview(r) else r
                        mutable.value = RetainedFrontendState(false, deliver)
                    }
                }
            }
        }
        CombinedAdmission.ACCEPTED
    }
    fun detach() = synchronized(guard) {
        generation++; previewGeneration++; selection = null
        mutable.value = mutable.value.copy(outcome = withoutPreview(mutable.value.outcome))
    }
}
