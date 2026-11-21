package org.openwrench.frontend

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

/** Narrow storage seam for failure/interruption tests; history and acquisition are never accessed. */
internal interface WorkspaceLayoutStorage {
    suspend fun read(): String?
    suspend fun write(encoded: String): Boolean
}

private class AndroidWorkspaceLayoutStorage(private val context: Context) : WorkspaceLayoutStorage {
    private fun preferences() = context.getSharedPreferences("workspace-modules-v1", Context.MODE_PRIVATE)
    override suspend fun read(): String? = withContext(Dispatchers.IO) { preferences().getString("layout", null) }
    override suspend fun write(encoded: String): Boolean = withContext(Dispatchers.IO) {
        preferences().edit().putString("layout", encoded).commit()
    }
}

/** Owned above navigation. Android storage performs disk access off the UI thread. */
internal class WorkspaceLayoutPreferences(private val storage: WorkspaceLayoutStorage) {
    var raw by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        try { raw = storage.read(); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { raw = null; error = "Could not read module layout. Defaults shown." }
        ready = true
    }

    suspend fun save(layout: GraphFirstLayout, resetAll: Boolean = false): Boolean {
        try {
            val stored = WorkspaceLayoutCodec.decode(raw, emptyList()).stored
            val encoded = WorkspaceLayoutCodec.encode(mergeWorkspaceLayout(stored, layout, resetAll))
            if (storage.write(encoded)) { raw = encoded; error = null; return true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve displayed state and report a failed save. */ }
        error = "Could not save module layout. Your displayed layout is unchanged."
        return false
    }
}

/** Synchronous gate precedes coroutine launch, so two taps in one frame cannot submit twice. */
internal class WorkspaceLayoutSubmission {
    private var applied = false
    var saving by mutableStateOf(false)
        private set

    fun submit(scope: CoroutineScope, save: suspend () -> Boolean, onSaved: () -> Unit): Job? {
        if (saving || applied) return null
        saving = true
        // Enter try/finally immediately, including cancellation before the first suspension.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { if (save()) { applied = true; onSaved() } }
            finally { saving = false }
        }
    }
}

@Composable
internal fun rememberWorkspaceLayoutPreferences(): WorkspaceLayoutPreferences {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { WorkspaceLayoutPreferences(AndroidWorkspaceLayoutStorage(context)) }
    LaunchedEffect(state) { state.load() }
    return state
}
