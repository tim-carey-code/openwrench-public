package org.openwrench.frontend

import kotlin.test.*

class LiveTelemetryToolbarTest {
    private val charts = setOf(LiveToolbarAction.PAUSE, LiveToolbarAction.RANGE, LiveToolbarAction.FIT, LiveToolbarAction.EXPAND)

    @Test fun everyChartActionRemainsReachableExactlyOnceAcrossWidthTransitions() {
        for (mode in listOf(LiveDisplayMode.GRAPHS, LiveDisplayMode.BOTH)) {
            for (width in 96..1600) {
                val layout = liveToolbarLayout(width.toFloat(), mode)
                val all = layout.inline + layout.overflow
                assertEquals(LiveToolbarAction.entries.toSet() - LiveToolbarAction.VALUES_FILTER, all.toSet())
                assertEquals(all.size, all.toSet().size)
                assertTrue(layout.inline.size * 48 <= width)
                assertTrue(LiveToolbarAction.FULLSCREEN in layout.inline)
                assertTrue(LiveToolbarAction.SETTINGS in layout.inline)
            }
        }
    }

    @Test fun valuesNeverExposesChartOnlyActionsButKeepsItsOwnFilter() {
        for (width in listOf(96f, 144f, 192f, 240f, 360f, 1280f)) {
            val layout = liveToolbarLayout(width, LiveDisplayMode.VALUES)
            val all = layout.inline + layout.overflow
            assertTrue(all.none { it in charts })
            assertTrue(LiveToolbarAction.VALUES_FILTER in all)
            assertTrue(LiveToolbarAction.PIDS in all)
            assertEquals(5, all.size)
        }
    }

    @Test fun phonePrioritizesModePidPauseAndTimeWithOtherChartControlsInSettings() {
        val layout = liveToolbarLayout(336f, LiveDisplayMode.BOTH)
        assertTrue(listOf(LiveToolbarAction.MODE, LiveToolbarAction.PIDS, LiveToolbarAction.PAUSE, LiveToolbarAction.RANGE).all { it in layout.inline })
        assertEquals(listOf(LiveToolbarAction.EXPAND), layout.overflow)
        assertEquals(7 * 48, layout.inline.size * 48)
    }

    @Test fun narrowViewportKeepsExitAndSettingsDirectAndMovesEverythingElseIntoMenu() {
        val layout = liveToolbarLayout(96f, LiveDisplayMode.GRAPHS)
        assertEquals(listOf(LiveToolbarAction.FULLSCREEN, LiveToolbarAction.SETTINGS), layout.inline)
        assertTrue(listOf(LiveToolbarAction.MODE, LiveToolbarAction.PIDS, LiveToolbarAction.PAUSE).all { it in layout.overflow })
    }

    @Test fun wideViewportHasOneRowWithNoDuplicateOverflowControls() {
        val layout = liveToolbarLayout(1280f, LiveDisplayMode.BOTH)
        assertEquals(8, layout.inline.size)
        assertTrue(layout.overflow.isEmpty())
    }

    @Test fun resizingDoesNotMutatePresentationSelectionOrPauseState() {
        val state = LiveDisplayState().apply {
            mode = LiveDisplayMode.BOTH; fullScreen = true; fitScale = true
            expanded = true; range = GraphTimeRange.FIFTEEN; selectedValuesOnly = true
            plotSelection = listOf("rpm", "coolant")
        }
        for (width in listOf(1280f, 96f, 390f, 848f)) liveToolbarLayout(width, state.mode)
        assertEquals(listOf("rpm", "coolant"), state.plotSelection)
        assertEquals(GraphTimeRange.FIFTEEN, state.range)
        assertTrue(state.fullScreen && state.fitScale && state.expanded && state.selectedValuesOnly)
        assertEquals(LiveDisplayMode.BOTH, state.mode)
    }

    @Test fun impossibleOrNonfiniteWidthsRetainBothEssentialTargetsForScrollableFallback() {
        for (width in listOf(0f, 48f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(listOf(LiveToolbarAction.FULLSCREEN, LiveToolbarAction.SETTINGS), liveToolbarLayout(width, LiveDisplayMode.GRAPHS).inline)
        }
    }
}
