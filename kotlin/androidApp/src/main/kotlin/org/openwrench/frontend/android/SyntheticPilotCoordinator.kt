package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class SyntheticPilotState(val storage: OutcomeSandboxState? = null, val preview: SyntheticPilotPreview? = null,
    val busy: Boolean = false, val error: String? = null)

/** Presentation delivery cancels independently of admitted atomic writes; reopening reveals durable state. */
internal class SyntheticPilotCoordinator(private val repository: SyntheticPilotRepository, private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(SyntheticPilotState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private fun work(write: Boolean = false, action: () -> OutcomeSandboxState) {
        if (state.value.busy) return
        val token = generation
        mutable.update { it.copy(busy = true, error = null, preview = null) }
        scope.launch {
            try {
                val storage = withContext(if (write) io + NonCancellable else io) { action() }
                val preview = withContext(io) { storage.committed?.contract?.let(SyntheticPilotReasoning::preview) }
                if (token == generation) mutable.value = SyntheticPilotState(storage, preview)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) mutable.update { it.copy(error = error.message ?: "Pilot operation failed; files preserved") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun load() = work { repository.inspect() }
    fun start() = work(true) { repository.start() }
    fun append(expected: String, draft: SyntheticOutcomeDraft) = work(true) { repository.append(expected, draft, now()) }
    fun recover(expected: String?, pending: String) = work(true) { repository.recover(expected, pending) }
    fun leave() { generation++; mutable.update { it.copy(busy = false, preview = null) } }
}
