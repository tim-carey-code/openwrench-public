package org.openwrench.frontend

import kotlin.test.*

class ChartToolbarTest {
    @Test fun toolbarKeepsFourThenTwoThenOneFullSizedColumns() {
        assertEquals(4, chartToolbarColumns(360f, 85f))
        assertEquals(2, chartToolbarColumns(320f, 85f))
        assertEquals(2, chartToolbarColumns(360f, 150f))
        assertEquals(1, chartToolbarColumns(280f, 150f))
        assertEquals(4, chartToolbarColumns(400f, 100f))
        assertEquals(2, chartToolbarColumns(399f, 100f))
        assertEquals(1, chartToolbarColumns(199f, 100f))
    }

    @Test fun pausedCaptionStillNamesTheSourceAndDoesNotClaimRecording() {
        assertTrue(graphFocusCaption(DashboardSource.DEMO, true).contains("Simulated"))
        assertTrue(graphFocusCaption(DashboardSource.SNAPSHOT, true).contains("Historical"))
        assertTrue(graphFocusCaption(DashboardSource.CIVIC_REPLAY, true).contains("Recorded"))
        assertTrue(graphFocusCaption(DashboardSource.HARDWARE, true).contains("acquisition unchanged"))
        DashboardSource.entries.forEach { source ->
            assertFalse(graphFocusCaption(source, false).contains("Recording"))
            assertTrue(graphFocusCaption(source, false).contains("Pause freezes charts only"))
        }
    }
}
