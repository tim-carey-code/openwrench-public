package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test

class GraphFirstLayoutTest {
    private val ids = listOf("rpm", "speed", "coolant")
    private val base = GraphFirstLayout.fromSensorIds(ids)
    private fun graph(id: String) = "GRAPH:$id"
    private fun card(id: String) = "COMPACT_VALUE:$id"

    @Test fun sameSignalCanHaveIndependentGraphAndCard() {
        val changed = base.update(graph("rpm")) { it.copy(visible = false, expanded = false, pinned = true) }
        assertEquals(6, changed.modules.size)
        assertEquals(WorkspaceModule("rpm", WorkspaceModuleKind.COMPACT_VALUE), changed.modules.first { it.key == card("rpm") })
        assertFalse(changed.modules.first { it.key == graph("rpm") }.visible)
        assertEquals(listOf("rpm", "speed", "coolant"), ids) // Selection input remains immutable.
    }

    @Test fun hideShowAndCollapseExpandAreReversibleWithoutLosingOrderOrHeight() {
        val tall = base.update(graph("speed")) { it.copy(height = WorkspaceGraphHeight.TALL) }
        var edited = tall
        repeat(20) {
            edited = edited.update(graph("speed")) { it.copy(visible = false, expanded = false) }
                .update(graph("speed")) { it.copy(visible = true, expanded = true) }
        }
        assertEquals(tall, edited)
    }

    @Test fun pinsPrecedePeersAndMoveStaysInsideKindAndPinGroup() {
        val pinned = base.update(graph("speed")) { it.copy(pinned = true) }
        assertEquals(listOf("speed", "rpm", "coolant"), pinned.ordered(WorkspaceModuleKind.GRAPH).map { it.sensorId })
        assertEquals(pinned, pinned.move(graph("speed"), 1))
        val moved = pinned.move(graph("coolant"), -1)
        assertEquals(listOf("speed", "coolant", "rpm"), moved.ordered(WorkspaceModuleKind.GRAPH).map { it.sensorId })
        assertEquals(base.ordered(WorkspaceModuleKind.COMPACT_VALUE), moved.ordered(WorkspaceModuleKind.COMPACT_VALUE))
        assertEquals(moved, moved.move("unknown", 1))
        assertEquals(moved, moved.move(graph("rpm"), 100))
    }

    @Test fun hiddenModulesRetainPlaceAndCanAlwaysBeRecovered() {
        val hidden = base.update(graph("rpm")) { it.copy(visible = false) }.move(graph("rpm"), 1)
        assertEquals(listOf("speed", "coolant"), hidden.visible(WorkspaceModuleKind.GRAPH, ids).map { it.sensorId })
        val shown = hidden.update(graph("rpm")) { it.copy(visible = true) }
        assertEquals(listOf("speed", "rpm", "coolant"), shown.visible(WorkspaceModuleKind.GRAPH, ids).map { it.sensorId })
    }

    @Test fun allHiddenIsValidAndResetRestoresDefaults() {
        val hidden = base.copy(modules = base.modules.map { it.copy(visible = false) })
        assertTrue(hidden.visible(WorkspaceModuleKind.GRAPH, ids).isEmpty())
        assertTrue(hidden.visible(WorkspaceModuleKind.COMPACT_VALUE, ids).isEmpty())
        assertEquals(base, GraphFirstLayout.fromSensorIds(ids))
        assertEquals(hidden, WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(hidden), ids).layout)
    }

    @Test fun selectionChangesFilterRenderingWithoutEditingLayout() {
        val configured = base.update(graph("rpm")) { it.copy(pinned = true) }
        assertEquals(listOf("speed"), configured.visible(WorkspaceModuleKind.GRAPH, listOf("speed")).map { it.sensorId })
        assertEquals(ids, configured.visible(WorkspaceModuleKind.COMPACT_VALUE, ids).map { it.sensorId })
        assertEquals(3, configured.visible(WorkspaceModuleKind.GRAPH, ids).size)
        assertTrue(configured.visible(WorkspaceModuleKind.GRAPH, emptyList()).isEmpty())
    }

    @Test fun normalizationDropsUnknownsAndDuplicatesAndAppendsNewSignals() {
        val old = GraphFirstLayout(listOf(WorkspaceModule("gone"), WorkspaceModule("rpm", visible = false),
            WorkspaceModule("rpm", visible = true)))
        val normalized = old.normalize(listOf("rpm", "new", "new"))
        assertEquals(4, normalized.modules.size)
        assertFalse(normalized.modules.first { it.key == graph("rpm") }.visible)
        assertEquals(2, normalized.modules.count { it.sensorId == "new" })
        assertEquals(normalized, normalized.normalize(listOf("rpm", "new")))
        assertTrue(normalized.normalize(emptyList()).modules.isEmpty())
    }

    @Test fun persistedLayoutRoundTripsEveryPreferenceAndOrder() {
        val edited = base.move(graph("rpm"), 1).update(card("coolant")) { it.copy(pinned = true, expanded = false) }
            .update(graph("speed")) { it.copy(visible = false, height = WorkspaceGraphHeight.COMPACT) }
        val decoded = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(edited), ids)
        assertNull(decoded.notice)
        assertEquals(edited, decoded.layout)
    }

    @Test fun missingCorruptAndUnknownVersionsFallBackWithoutMutatingInput() {
        assertEquals(base, WorkspaceLayoutCodec.decode(null, ids).layout)
        for (raw in listOf("not json", "{}", "[]", "{\"version\":999,\"modules\":[]}",
            "{\"version\":1,\"modules\":[{}]}", " ".repeat(WorkspaceLayoutCodec.MAX_CHARS + 1))) {
            val read = WorkspaceLayoutCodec.decode(raw, ids)
            assertEquals(base, read.layout)
            assertNotNull(read.notice)
        }
    }

    @Test fun malformedTypesAndUnknownHeightDoNotLeakIntoLayout() {
        val raw = WorkspaceLayoutCodec.encode(base)
        for (invalid in listOf(raw.replace("\"pinned\":false", "\"pinned\":\"false\""),
            raw.replace("STANDARD", "UNBOUNDED"), raw.replace("\"rpm\"", "\"\""))) {
            assertNotNull(WorkspaceLayoutCodec.decode(invalid, ids).notice)
            assertEquals(base, WorkspaceLayoutCodec.decode(invalid, ids).layout)
        }
    }

    @Test fun boundsLimitModuleCountAndHeightIncludingInvalidFontScales() {
        assertEquals(256, GraphFirstLayout.fromSensorIds((0..1000).map { "$it" }).modules.size)
        for (height in WorkspaceGraphHeight.entries) for (scale in listOf(-1f, 1f, 2f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(workspaceModuleHeightDp(height, false, scale) in 120f..560f)
            assertTrue(workspaceModuleHeightDp(height, true, scale) in 280f..560f)
        }
        assertEquals(120f, workspaceModuleHeightDp(WorkspaceGraphHeight.COMPACT, false, 1f))
        assertEquals(280f, workspaceModuleHeightDp(WorkspaceGraphHeight.COMPACT, true, 1f))
    }

    @Test fun changingFieldsCannotChangeModuleIdentity() {
        val changed = base.update(graph("rpm")) { it.copy(sensorId = "speed", kind = WorkspaceModuleKind.COMPACT_VALUE) }
        assertEquals(base, changed)
    }
    @Test fun applyingPartialReplayEditsPreservesOtherSourcePreferences() {
        val original = base.update(graph("speed")) { it.copy(visible = false, pinned = true, height = WorkspaceGraphHeight.TALL) }
            .move(card("coolant"), -1)
        val read = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(original), listOf("rpm"))
        assertEquals(2, read.layout.modules.size)
        val edited = read.layout.update(graph("rpm")) { it.copy(height = WorkspaceGraphHeight.COMPACT) }
        val saved = mergeWorkspaceLayout(read.stored, edited)
        val restored = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(saved), ids).layout
        assertEquals(original.modules.filter { it.sensorId != "rpm" }, restored.modules.filter { it.sensorId != "rpm" })
        assertEquals(WorkspaceGraphHeight.COMPACT, restored.modules.first { it.key == graph("rpm") }.height)
    }

    @Test fun mergingNewModulesPreservesCompleteEditedOrderAndDormantOrder() {
        val stored = GraphFirstLayout.fromSensorIds(listOf("speed", "rpm", "voltage"))
            .update(graph("speed")) { it.copy(visible = false, height = WorkspaceGraphHeight.TALL) }
            .move(card("voltage"), -1)
        val currentIds = listOf("rpm", "coolant", "maf")
        val read = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(stored), currentIds)
        val edited = read.layout.move(graph("coolant"), -1).move(graph("maf"), -1)
            .move(card("maf"), -1).move(card("maf"), -1)
        val currentKeys = edited.modules.map { it.key }.toSet()
        val merged = mergeWorkspaceLayout(read.stored, edited)
        // New and previously saved keys must retain the complete draft sequence, not separate groups.
        assertEquals(edited.modules, merged.modules.filter { it.key in currentKeys })
        assertEquals(stored.modules.filter { it.key !in currentKeys }, merged.modules.filter { it.key !in currentKeys })
        assertEquals(edited, WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(merged), currentIds).layout)
        assertEquals(merged, mergeWorkspaceLayout(merged, edited))
    }

    @Test fun explicitResetCanClearAbsentSettingsButCancelLeavesOriginalIntact() {
        val original = base.update(graph("speed")) { it.copy(visible = false) }
        val resetDraft = GraphFirstLayout.fromSensorIds(listOf("rpm"))
        assertEquals(resetDraft, mergeWorkspaceLayout(original, resetDraft, resetAll = true))
        assertFalse(original.modules.first { it.key == graph("speed") }.visible)
        val returned = mergeWorkspaceLayout(original, resetDraft).normalize(ids)
        assertFalse(returned.modules.first { it.key == graph("speed") }.visible)
    }

    @Test fun nonNumericSignalsOfferCardsWithoutInertGraphControls() {
        val rows = demoDashboardSignals()
        val all = rows.map { it.definition.id }
        val numeric = rows.filter { it.definition.isGraphable() }.map { it.definition.id }
        val typed = GraphFirstLayout.fromSensorIds(all, numeric)
        assertEquals(numeric, typed.ordered(WorkspaceModuleKind.GRAPH).map { it.sensorId })
        assertEquals(all, typed.ordered(WorkspaceModuleKind.COMPACT_VALUE).map { it.sensorId })
        val older = GraphFirstLayout.fromSensorIds(all) // A prior layout may contain now-inapplicable kinds.
        val normalized = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(older), all, numeric).layout
        assertEquals(typed, normalized)
    }

}
