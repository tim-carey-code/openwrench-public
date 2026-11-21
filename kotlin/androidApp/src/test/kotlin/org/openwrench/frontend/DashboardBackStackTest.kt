package org.openwrench.frontend

import kotlin.test.*

class DashboardBackStackTest {
    @Test fun actualRootLetsAndroidExitWithoutAnInventedDestination() {
        val root = DashboardBackStack()
        assertFalse(root.canGoBack)
        assertSame(root, root.back())
        assertEquals(DashboardPage.DIAGNOSIS, root.current.page)
    }

    @Test fun nonlinearVisitsUnwindInActualOrderIncludingRepeatedDestinations() {
        var state = DashboardBackStack()
        val visits = listOf(DashboardPage.ADVANCED, DashboardPage.SESSION, DashboardPage.HISTORY,
            DashboardPage.DASHBOARD, DashboardPage.ADVANCED, DashboardPage.SIGNALS)
        visits.forEach { state = state.visit(it) }
        (visits.dropLast(1).reversed() + DashboardPage.DIAGNOSIS).forEach { expected ->
            state = state.back(); assertEquals(expected, state.current.page)
        }
        assertFalse(state.canGoBack)
    }

    @Test fun repeatedCurrentDestinationDoesNotCreatePhantomBackSteps() {
        val state = DashboardBackStack().visit(DashboardPage.DASHBOARD)
        repeat(20) { assertSame(state, state.visit(DashboardPage.DASHBOARD)) }
        assertFalse(state.back().canGoBack)
    }

    @Test fun graphAndValueModesUnwindBeforeEarlierPagesAndSurviveSaveRestore() {
        var state = DashboardBackStack().visit(DashboardPage.DASHBOARD)
            .visit(DashboardPage.DASHBOARD, LiveDisplayMode.GRAPHS)
            .visit(DashboardPage.DASHBOARD, LiveDisplayMode.VALUES).visit(DashboardPage.SESSION)
        state = DashboardBackStack.restored(state.saved())
        assertEquals(DashboardPage.SESSION, state.current.page)
        for (mode in listOf(LiveDisplayMode.VALUES, LiveDisplayMode.GRAPHS, LiveDisplayMode.BOTH)) {
            state = state.back()
            assertEquals(DashboardPage.DASHBOARD, state.current.page)
            assertEquals(mode, state.current.mode)
        }
        assertEquals(DashboardPage.DIAGNOSIS, state.back().current.page)
    }

    @Test fun replayAutoNavigationIsConsumedAcrossRotationAndBack() {
        val started = DashboardBackStack().visit(DashboardPage.HISTORY).consumeReplaySelection("drive-a:1")
        val away = started.visit(DashboardPage.ADVANCED)
        val restored = DashboardBackStack.restored(away.saved())
        assertSame(restored, restored.consumeReplaySelection("drive-a:1"))
        val back = started.back()
        assertEquals(DashboardPage.HISTORY, back.current.page)
        assertSame(back, back.consumeReplaySelection("drive-a:1"))
    }

    @Test fun newReplaySelectionsNavigateOnceAndClosedSourceCanBeSelectedAgain() {
        val first = DashboardBackStack().consumeReplaySelection("drive-a:1").visit(DashboardPage.HISTORY)
        val restart = first.consumeReplaySelection("drive-a:2")
        assertEquals(DashboardPage.DASHBOARD, restart.current.page)
        assertSame(restart, restart.consumeReplaySelection("drive-a:2"))
        val closed = restart.back().consumeReplaySelection(null)
        assertEquals(DashboardPage.HISTORY, closed.current.page)
        assertEquals(DashboardPage.DASHBOARD, closed.consumeReplaySelection("drive-a:2").current.page)
    }

    @Test fun nestedTabsDoNotOutrankANewerPageVisit() {
        var state = DashboardBackStack().visit(DashboardPage.HISTORY).visit(DashboardPage.LEGACY_HISTORY)
            .visit(DashboardPage.LEGACY_HISTORY, recordsTab = IndividualRecordsTab.SCANS)
            .visit(DashboardPage.ADVANCED).visit(DashboardPage.LEGACY_HISTORY)
        state = DashboardBackStack.restored(state.saved())
        assertEquals(IndividualRecordsTab.SCANS, state.current.recordsTab)
        state = state.back(); assertEquals(DashboardPage.ADVANCED, state.current.page)
        state = state.back(); assertEquals(DashboardPage.LEGACY_HISTORY, state.current.page)
        assertEquals(IndividualRecordsTab.SCANS, state.current.recordsTab)
        state = state.back(); assertEquals(IndividualRecordsTab.DRIVES, state.current.recordsTab)
        assertEquals(DashboardPage.HISTORY, state.back().current.page)
    }

    @Test fun retainedDriveAndCaseDetailsBelongToTheVisitThatOpenedThem() {
        for (tab in listOf(IndividualRecordsTab.DRIVES, IndividualRecordsTab.CASES)) {
            val opened = DashboardBackStack().visit(DashboardPage.LEGACY_HISTORY, recordsTab = tab)
            // SaveableStateProvider uses this visit ID: only its state owns the detail Back.
            val detailVisit = opened.current.id
            val revisited = opened.visit(DashboardPage.ADVANCED).visit(DashboardPage.LEGACY_HISTORY)
            val restored = DashboardBackStack.restored(revisited.saved())
            assertNotEquals(detailVisit, restored.current.id)
            assertEquals(DashboardPage.ADVANCED, restored.back().current.page)
            assertEquals(detailVisit, restored.back().back().current.id)
            // Reopening the same destination after popping must not reuse disposed detail state.
            assertNotEquals(restored.current.id, restored.back().visit(DashboardPage.LEGACY_HISTORY).current.id)
        }
    }

    @Test fun corruptOrUnknownSavedNavigationFallsBackWithoutTrappingBack() {
        for (saved in listOf(emptyList(), listOf("2", "", "DIAGNOSIS|BOTH"),
            listOf("1", "", "REMOVED|BOTH"), listOf("1", "", "DIAGNOSIS|REMOVED"))) {
            assertEquals(DashboardBackStack(), DashboardBackStack.restored(saved))
        }
    }

    @Test fun historyIsBoundedButRetainsRecentActualVisits() {
        var state = DashboardBackStack()
        repeat(1000) { state = state.visit(if (it % 2 == 0) DashboardPage.SESSION else DashboardPage.HISTORY) }
        assertEquals(128, state.visits.size)
        repeat(127) { state = state.back() }
        assertFalse(state.canGoBack)
        assertEquals(DashboardPage.SESSION, state.current.page)
    }

    @Test fun fullscreenIsAnOverlayRestoringTheActualPreviousMode() {
        LiveDisplayMode.entries.forEach { mode ->
            val state = LiveDisplayState().apply { this.mode = mode }
            state.enterFullScreen(); state.enterFullScreen()
            assertTrue(state.fullScreen); assertEquals(LiveDisplayMode.GRAPHS, state.mode)
            state.mode = LiveDisplayMode.VALUES // fullscreen-only view changes do not rewrite the underlying tab
            state.exitFullScreen(); state.exitFullScreen()
            assertFalse(state.fullScreen); assertEquals(mode, state.mode)
        }
    }

    @Test fun exitingFullscreenKeepsPauseSelectionUnitsAndChartPreferences() {
        val rows = demoDashboardSignals()
        val state = LiveDisplayState().apply {
            mode = LiveDisplayMode.VALUES; frozen = rows; plotSelection = listOf("rpm")
            imperial = true; fitScale = true; expanded = true; range = GraphTimeRange.THIRTY
        }
        state.enterFullScreen(); state.exitFullScreen()
        assertSame(rows, state.frozen); assertEquals(listOf("rpm"), state.plotSelection)
        assertTrue(state.imperial && state.fitScale && state.expanded)
        assertEquals(GraphTimeRange.THIRTY, state.range)
    }

    @Test fun activityRetentionKeepsFrozenPresentationForTheSameSource() {
        val retention = DashboardDisplayRetention()
        val state = retention.state(DashboardSource.DEMO, "same-content", LiveDisplayMode.BOTH)
        val rows = demoDashboardSignals()
        state.frozen = rows; state.enterFullScreen()
        val afterRecreation = retention.state(DashboardSource.DEMO, "same-content", LiveDisplayMode.VALUES)
        assertSame(state, afterRecreation); assertSame(rows, afterRecreation.frozen)
        assertTrue(afterRecreation.fullScreen)
        afterRecreation.exitFullScreen(); assertEquals(LiveDisplayMode.BOTH, afterRecreation.mode)
    }

    @Test fun changedSourceDoesNotReuseAnotherSourcesFrozenReadings() {
        val retention = DashboardDisplayRetention()
        val before = retention.state(DashboardSource.DEMO, "a", LiveDisplayMode.BOTH)
        before.frozen = demoDashboardSignals(); before.enterFullScreen()
        val after = retention.state(DashboardSource.SNAPSHOT, "b", LiveDisplayMode.VALUES)
        assertNotSame(before, after); assertNull(after.frozen); assertFalse(after.fullScreen)
        assertEquals(LiveDisplayMode.VALUES, after.mode)
    }
}
