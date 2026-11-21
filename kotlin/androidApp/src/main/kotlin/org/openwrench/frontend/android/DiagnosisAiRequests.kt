package org.openwrench.frontend.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class AiAnalysisPhase { RUNNING, READY, FAILED }

internal data class AiAnalysisNotice(val id: String, val phase: AiAnalysisPhase)

/** Main-thread owned; the scope belongs to the app process, not an Activity. */
internal class DiagnosisAiRequests(private val scope: CoroutineScope) {
    private val active = MutableStateFlow<String?>(null)
    val pending = active.asStateFlow()
    private val latestNotice = MutableStateFlow<AiAnalysisNotice?>(null)
    val notice = latestNotice.asStateFlow()
    private val completed = linkedMapOf<String, Result<String>>()

    var revision = 0L
        private set

    fun result(id: String): Result<String>? = completed[id]

    fun dismissNotice(notice: AiAnalysisNotice) {
        if (latestNotice.value === notice && notice.phase != AiAnalysisPhase.RUNNING) {
            latestNotice.value = null
        }
    }

    fun submit(id: String, previewRevision: Long, request: suspend () -> String): Boolean {
        if (active.value != null || previewRevision != revision) return false
        revision++
        active.value = id
        completed.remove(id)
        val running = AiAnalysisNotice(id, AiAnalysisPhase.RUNNING)
        latestNotice.value = running
        scope.launch {
            try {
                val outcome = try {
                    Result.success(request())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                completed[id] = outcome
                if (completed.size > 8) completed.remove(completed.keys.first())
            } finally {
                active.value = null
                val phase = completed[id]?.let {
                    if (it.isSuccess) AiAnalysisPhase.READY else AiAnalysisPhase.FAILED
                }
                if (latestNotice.value === running) {
                    latestNotice.value = phase?.let { AiAnalysisNotice(id, it) }
                }
            }
        }
        return true
    }
}
