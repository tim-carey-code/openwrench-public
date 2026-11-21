package org.openwrench.frontend
import kotlin.test.*
import org.junit.Test
class GraphFirstPresentationTest {
    @Test fun compactStripIsOneRowAndUsesRealAvailableIds() {
        assertEquals(listOf("rpm", "speed"), compactOverviewIds(listOf("speed", "rpm", "coolant"), listOf("missing", "rpm", "rpm"), 2))
        assertTrue(compactOverviewIds(emptyList(), listOf("rpm"), 4).isEmpty())
        assertEquals(4, compactOverviewIds((0..20).map { "$it" }, emptyList(), 10).size)
    }
    @Test fun prominentPlotsHaveFiniteBoundedHeight() {
        assertEquals(180f, workspacePlotHeightDp(false, 1f))
        assertEquals(280f, workspacePlotHeightDp(true, 1f))
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1f, 2f, 10f)) {
            assertTrue(workspacePlotHeightDp(true, scale) in 280f..560f)
        }
    }
    @Test fun relativeAxisIsIndependentOfSourceClockOrigin() {
        for (origin in listOf(0L, 1_000_000L, 1_700_000_000_000L)) {
            assertEquals(0f, workspaceTimeFraction(origin, origin + 60_000, 60_000))
            assertEquals(.5f, workspaceTimeFraction(origin + 30_000, origin + 60_000, 60_000))
            assertEquals(1f, workspaceTimeFraction(origin + 60_000, origin + 60_000, 60_000))
        }
        assertEquals("Time · seconds before this signal's window end", workspaceTimeCaption(false))
        assertTrue(workspaceTimeCaption(true).endsWith("Chart frozen"))
    }
}
