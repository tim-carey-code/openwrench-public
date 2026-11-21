package org.openwrench.frontend

import kotlin.test.*

class MechanicGraphLayoutTest {
    @Test fun realContentWidthAndFontSizeBoundTwoReadableColumns() {
        assertEquals(1, mechanicGraphColumns(647.99f, 1f, false))
        assertEquals(2, mechanicGraphColumns(648f, 1f, false))
        // An 800dp tablet with the 80dp rail and 18dp workspace padding still fits.
        assertEquals(2, mechanicGraphColumns(800f - 80f - 18f, 1f, false))
        assertEquals(1, mechanicGraphColumns(390f - 18f, 1f, false))
        assertEquals(1, mechanicGraphColumns(1280f - 80f - 18f, 2f, false))
        assertEquals(2, mechanicGraphColumns(1536f - 80f - 18f, 2f, false))
        assertEquals(1, mechanicGraphColumns(1287.99f, 2f, false))
        assertEquals(2, mechanicGraphColumns(1288f, 2f, false))
    }

    @Test fun fullScreenExpandedAndInvalidConstraintsFallBackSafely() {
        assertEquals(1, mechanicGraphColumns(4000f, 1f, true))
        for (width in listOf(-100f, 0f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(1, mechanicGraphColumns(width, 1f, false))
        for (scale in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(1, mechanicGraphColumns(4000f, scale, false))
        assertEquals(2, mechanicGraphColumns(648f, .8f, false))
    }

    @Test fun pairingPreservesPinnedReorderedHiddenAndCollapsedModuleState() {
        val selected = listOf("rpm", "coolant", "speed", "load", "stft", "stft-bank-2")
        val layout = GraphFirstLayout.fromSensorIds(selected)
            .update("GRAPH:stft-bank-2") { it.copy(pinned = true, height = WorkspaceGraphHeight.TALL) }
            .update("GRAPH:coolant") { it.copy(visible = false) }
            .update("GRAPH:load") { it.copy(expanded = false, height = WorkspaceGraphHeight.COMPACT) }
            .move("GRAPH:speed", -1)
        val modules = layout.visible(WorkspaceModuleKind.GRAPH, selected)
        val before = modules.toList()
        val groups = mechanicGraphRows(modules, 1000f, 1f, false, true)
        assertEquals(listOf(2, 2, 1), groups.map { it.size })
        assertEquals(before, groups.flatten())
        assertEquals("stft-bank-2", groups.first().first().sensorId)
        assertFalse(groups.flatten().any { it.sensorId == "coolant" })
        assertFalse(groups.flatten().first { it.sensorId == "load" }.expanded)
        assertEquals(WorkspaceGraphHeight.TALL, groups.first().first().height)
        assertEquals(before, modules)
        assertEquals(before, mechanicGraphRows(modules, 300f, 2f, false, true).flatten())
    }

    @Test fun emptySingleOddAndLegacyViewsDoNotLoseOrInventModules() {
        val modules = (0..4).map { WorkspaceModule("signal-$it") }
        assertTrue(mechanicGraphRows(emptyList(), 1000f, 1f, false, true).isEmpty())
        assertEquals(listOf(listOf(modules[0])), mechanicGraphRows(modules.take(1), 1000f, 1f, false, true))
        assertEquals(listOf(2, 2, 1), mechanicGraphRows(modules, 1000f, 1f, false, true).map { it.size })
        assertEquals(List(5) { 1 }, mechanicGraphRows(modules, 1000f, 1f, false, false).map { it.size })
        assertEquals(List(5) { 1 }, mechanicGraphRows(modules, 1000f, 1f, true, true).map { it.size })
        val changedSource = listOf(modules[4], modules[1], modules[0])
        assertEquals(changedSource, mechanicGraphRows(changedSource, 1000f, 1f, false, true).flatten())
    }
}
