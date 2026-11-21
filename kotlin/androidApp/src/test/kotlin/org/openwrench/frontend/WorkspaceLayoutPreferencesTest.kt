package org.openwrench.frontend

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.Test
import org.openwrench.core.SignalValue
import org.openwrench.core.SignalSupport
import org.openwrench.frontend.android.DemoScenario

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceLayoutPreferencesTest {
    private val defaults = GraphFirstLayout.fromSensorIds(listOf("rpm", "coolant"))
    private class MemoryStorage(var value: String? = null) : WorkspaceLayoutStorage {
        var succeed = true
        var throwOnWrite = false
        var writes = 0
        override suspend fun read() = value
        override suspend fun write(encoded: String): Boolean {
            writes++
            if (throwOnWrite) error("Scripted storage failure")
            if (succeed) value = encoded
            return succeed
        }
    }

    @Test fun selectionAndDensityPublishOnlyAfterOneSuccessfulStorageTransaction() = runTest {
        val storage = MemoryStorage(WorkspaceLayoutCodec.encode(defaults))
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val initial = preferences.raw
        val edit = defaults.withDisplaySelection(listOf("rpm", "coolant"), listOf("coolant"))
            .copy(cardDensity = WorkspaceCardDensity.LARGE)
        var selectionPublished = false
        storage.succeed = false
        WorkspaceLayoutSubmission().submit(this, { preferences.save(edit) }, { selectionPublished = true })?.join()
        assertFalse(selectionPublished); assertEquals(initial, storage.value); assertEquals(initial, preferences.raw)
        storage.succeed = true
        WorkspaceLayoutSubmission().submit(this, { preferences.save(edit) }, { selectionPublished = true })?.join()
        assertTrue(selectionPublished)
        val reloaded = WorkspaceLayoutPreferences(storage); reloaded.load()
        assertEquals(edit, WorkspaceLayoutCodec.decode(reloaded.raw, listOf("rpm", "coolant")).layout)
    }

    @Test fun partialSourceSaveAndReloadRetainsDormantPreferences() = runTest {
        val original = defaults.update("GRAPH:coolant") { it.copy(visible = false, height = WorkspaceGraphHeight.TALL) }
        val storage = MemoryStorage(WorkspaceLayoutCodec.encode(original))
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val snapshot = WorkspaceLayoutCodec.decode(preferences.raw, listOf("rpm")).layout
            .update("GRAPH:rpm") { it.copy(pinned = true) }
        assertTrue(preferences.save(snapshot))
        val restarted = WorkspaceLayoutPreferences(storage); restarted.load()
        val restored = WorkspaceLayoutCodec.decode(restarted.raw, listOf("rpm", "coolant")).layout
        assertEquals(original.modules.filter { it.sensorId == "coolant" }, restored.modules.filter { it.sensorId == "coolant" })
        assertTrue(restored.modules.first { it.key == "GRAPH:rpm" }.pinned)
    }

    @Test fun newlyAvailableSignalReorderSurvivesApplyAndReload() = runTest {
        val storage = MemoryStorage(WorkspaceLayoutCodec.encode(GraphFirstLayout.fromSensorIds(listOf("rpm"))))
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val ids = listOf("rpm", "coolant")
        val edited = WorkspaceLayoutCodec.decode(preferences.raw, ids).layout.move("GRAPH:coolant", -1)
        assertEquals(listOf("coolant", "rpm"), edited.ordered(WorkspaceModuleKind.GRAPH).map { it.sensorId })
        assertTrue(preferences.save(edited))
        val restarted = WorkspaceLayoutPreferences(storage); restarted.load()
        assertEquals(edited, WorkspaceLayoutCodec.decode(restarted.raw, ids).layout)
    }

    @Test fun falseOrThrowingSaveKeepsDisplayedStateAndReportsFailure() = runTest {
        val storage = MemoryStorage(WorkspaceLayoutCodec.encode(defaults))
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val initial = preferences.raw
        val edit = defaults.update("GRAPH:rpm") { it.copy(visible = false) }
        storage.succeed = false
        assertFalse(preferences.save(edit)); assertEquals(initial, preferences.raw); assertNotNull(preferences.error)
        storage.throwOnWrite = true
        assertFalse(preferences.save(edit)); assertEquals(initial, preferences.raw)
        storage.throwOnWrite = false; storage.succeed = true
        assertTrue(preferences.save(edit)); assertNull(preferences.error)
    }

    @Test fun readFailureShowsDefaultsWithoutWriting() = runTest {
        var writes = 0
        val storage = object : WorkspaceLayoutStorage {
            override suspend fun read(): String? = error("Scripted read failure")
            override suspend fun write(encoded: String): Boolean { writes++; return true }
        }
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        assertTrue(preferences.ready); assertNull(preferences.raw); assertNotNull(preferences.error); assertEquals(0, writes)
    }

    @Test fun repeatedApplyIsGuardedBeforeDispatchAndClosesOnlyOnce() = runTest {
        val finish = CompletableDeferred<Boolean>()
        val submission = WorkspaceLayoutSubmission()
        var writes = 0; var closed = 0
        val first = submission.submit(this, { writes++; finish.await() }, { closed++ })
        assertTrue(submission.saving)
        assertNull(submission.submit(this, { writes++; true }, { closed++ }))
        assertEquals(1, writes)
        finish.complete(true); first!!.join()
        assertEquals(1, closed); assertFalse(submission.saving)
        // A queued tap after persistence finishes but before Dialog disposal is ignored too.
        assertNull(submission.submit(this, { writes++; true }, { closed++ }))
        assertEquals(1, writes)
    }

    @Test fun failedApplyLeavesEditorOpenAndAllowsRetry() = runTest {
        val submission = WorkspaceLayoutSubmission(); var closed = 0
        submission.submit(this, { false }, { closed++ })!!.join()
        assertEquals(0, closed); assertFalse(submission.saving)
        submission.submit(this, { true }, { closed++ })!!.join()
        assertEquals(1, closed); assertFalse(submission.saving)
    }

    @Test fun interruptedSaveReleasesGateWithoutPretendingSuccess() = runTest {
        val submission = WorkspaceLayoutSubmission(); var closed = 0
        val job = submission.submit(this, { awaitCancellation() }, { closed++ })!!
        assertTrue(submission.saving)
        job.cancelAndJoin()
        assertFalse(submission.saving); assertEquals(0, closed)
        submission.submit(this, { true }, { closed++ })!!.join()
        assertEquals(1, closed)
    }

    @Test fun cancellationAfterDiskCommitReloadsPersistedStateWithoutFalseUiSuccess() = runTest {
        var disk = WorkspaceLayoutCodec.encode(defaults)
        val storage = object : WorkspaceLayoutStorage {
            override suspend fun read() = disk
            override suspend fun write(encoded: String): Boolean { disk = encoded; awaitCancellation() }
        }
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val initial = preferences.raw
        val changed = defaults.update("GRAPH:rpm") { it.copy(visible = false) }
        val submission = WorkspaceLayoutSubmission(); var closed = 0
        val job = submission.submit(this, { preferences.save(changed) }, { closed++ })!!
        job.cancelAndJoin()
        assertFalse(submission.saving); assertEquals(0, closed); assertEquals(initial, preferences.raw)
        val recreated = WorkspaceLayoutPreferences(storage); recreated.load()
        assertEquals(changed, WorkspaceLayoutCodec.decode(recreated.raw, listOf("rpm", "coolant")).layout)
    }
    @Test fun mechanicResetApplyAndReloadKeepUnsampledDefaultsWithoutChangingLegacyReset() = runTest {
        val rows = listOf(
            DashboardSignal(nativeSignalDefinition(0x0C, SignalSupport.SUPPORTED), SignalAvailability.AVAILABLE, SignalValue.Number(700.0)),
            DashboardSignal(nativeSignalDefinition(0x05, SignalSupport.SUPPORTED), SignalAvailability.NOT_SAMPLED, null),
            DashboardSignal(nativeSignalDefinition(0x06, SignalSupport.SUPPORTED), SignalAvailability.UNAVAILABLE, null),
            DashboardSignal(nativeSignalDefinition(0x08, SignalSupport.SUPPORTED), SignalAvailability.NOT_SAMPLED, null),
        )
        val ids = rows.map { it.definition.id }
        val defaults = GraphFirstLayout.fromSensorIds(ids)
        val storage = MemoryStorage(WorkspaceLayoutCodec.encode(defaults.withDisplaySelection(ids, listOf("rpm"))))
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        val selected = workspaceResetSelection(rows, emptyList(), mechanic = true)
        assertEquals(listOf("rpm", "coolant", "stft", "stft-bank-2"), selected)
        assertEquals(mechanicDefaultSelection(rows, emptyList()), selected)
        assertEquals(listOf("rpm"), workspaceResetSelection(rows, emptyList(), mechanic = false))
        var published: List<String>? = null
        // The editor's Reset chooses active defaults; Apply commits layout and display selection together.
        WorkspaceLayoutSubmission().submit(this,
            { preferences.save(defaults.withDisplaySelection(ids, selected), resetAll = true) },
            { published = selected })!!.join()
        assertEquals(selected, published); assertEquals(1, storage.writes)
        val restarted = WorkspaceLayoutPreferences(storage); restarted.load()
        assertEquals(selected, persistedWorkspacePlotIds(rows, emptyList(), restarted.raw, mechanicDefaultSelection(rows, emptyList())))
        assertEquals(listOf(SignalAvailability.AVAILABLE, SignalAvailability.NOT_SAMPLED, SignalAvailability.UNAVAILABLE, SignalAvailability.NOT_SAMPLED), rows.map { it.availability })
    }

    @Test fun promotionPreservesExistingAndDormantSettingsWithoutWriting() = runTest {
        val rows = mechanicDemoRows(DemoScenario.TIMED_WORKSPACE)
        val ids = rows.map { it.definition.id }
        val selected = listOf("stft-bank-2", "rpm")
        val saved = GraphFirstLayout.fromSensorIds(ids).withDisplaySelection(ids, selected)
            .update("GRAPH:stft-bank-2") { it.copy(pinned = true, height = WorkspaceGraphHeight.TALL) }
            .update("GRAPH:rpm") { it.copy(expanded = false) }
            .update("COMPACT_VALUE:coolant") { it.copy(visible = false) }
            .move("COMPACT_VALUE:speed", -1)
            .copy(cardDensity = WorkspaceCardDensity.LARGE, hideUnavailable = true)
        val encoded = WorkspaceLayoutCodec.encode(saved)
        val storage = MemoryStorage(encoded)
        val preferences = WorkspaceLayoutPreferences(storage); preferences.load()
        assertEquals(selected, regularWorkspacePlotIds(rows, emptyList(), preferences.raw))
        assertEquals(listOf("rpm"), regularWorkspacePlotIds(rows.filter { it.definition.id == "rpm" }, emptyList(), preferences.raw))
        val restarted = WorkspaceLayoutPreferences(storage); restarted.load()
        assertEquals(selected, regularWorkspacePlotIds(rows, emptyList(), restarted.raw))
        assertEquals(saved, WorkspaceLayoutCodec.decode(restarted.raw,
            (mechanicTopIds + ids).distinct().filter { it in ids }).layout)
        assertEquals(encoded, restarted.raw); assertEquals(encoded, storage.value)
        assertEquals(0, storage.writes)
    }

    @Test fun promotionPreservesLegacyImplicitAndExplicitEmptySelectionUntilReset() {
        val rows = listOf(
            DashboardSignal(nativeSignalDefinition(0x0C, SignalSupport.SUPPORTED), SignalAvailability.AVAILABLE, SignalValue.Number(700.0)),
            DashboardSignal(nativeSignalDefinition(0x05, SignalSupport.SUPPORTED), SignalAvailability.NOT_SAMPLED, null),
            DashboardSignal(nativeSignalDefinition(0x08, SignalSupport.SUPPORTED), SignalAvailability.UNAVAILABLE, null))
        val ids = rows.map { it.definition.id }
        val legacy = GraphFirstLayout.fromSensorIds(ids)
        val encoded = WorkspaceLayoutCodec.encode(legacy)
        assertEquals(listOf("rpm"), regularWorkspacePlotIds(rows, emptyList(), encoded))
        assertEquals(persistedWorkspacePlotIds(rows, emptyList(), encoded), regularWorkspacePlotIds(rows, emptyList(), encoded))
        assertEquals(listOf("rpm", "coolant", "stft-bank-2"), regularWorkspacePlotIds(rows, emptyList(), null))
        assertTrue(regularWorkspacePlotIds(rows, emptyList(),
            WorkspaceLayoutCodec.encode(legacy.withDisplaySelection(ids, emptyList()))).isEmpty())
        val reset = WorkspaceLayoutCodec.encode(legacy.withDisplaySelection(ids, mechanicDefaultSelection(rows, emptyList())))
        assertEquals(listOf("rpm", "coolant", "stft-bank-2"), regularWorkspacePlotIds(rows, emptyList(), reset))
        for (bad in listOf("broken", "{\"version\":99}"))
            assertEquals(persistedWorkspacePlotIds(rows, emptyList(), bad), regularWorkspacePlotIds(rows, emptyList(), bad))
    }

}
