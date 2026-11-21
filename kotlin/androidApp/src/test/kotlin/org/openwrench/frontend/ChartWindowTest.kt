package org.openwrench.frontend

import kotlin.test.*

class ChartWindowTest {
    @Test fun fullRangeAndMissingBoundsKeepRetention() {
        assertEquals(15_000L, chartWindowStart(15_000L, 200_000L, null))
        assertNull(chartWindowStart(null, 200_000L, 60_000L))
        assertEquals(15_000L, chartWindowStart(15_000L, null, 60_000L))
    }
    @Test fun recentRangeNeverExtendsBeforeRetainedEvidence() {
        assertEquals(140_000L, chartWindowStart(15_000L, 200_000L, 60_000L))
        assertEquals(15_000L, chartWindowStart(15_000L, 200_000L, 300_000L))
        assertEquals(15_000L, chartWindowStart(15_000L, 15_000L, 60_000L))
    }
    @Test fun changingRangeDoesNotChangeStoredBounds() {
        val start = 15_000L
        val end = 200_000L
        chartWindowStart(start, end, 60_000L)
        assertEquals(start, chartWindowStart(start, end, null))
    }
}
