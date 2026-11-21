package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.frontend.android.*

/** Actual user-facing synthetic file through the importer and Figma presentation contract. */
class FigmaExtendedReplayTest {
    private fun playback() = SavedDrivePlayback.from(DriveReplayImport.read(
        checkNotNull(javaClass.getResourceAsStream("/SYNTHETIC-Legacy-inspired-55-channel-5min-drive.json"))).single())

    @Test fun allRetainedChannelsSurviveDisplaySelectionPersistenceWithoutCapabilityClaims() {
        val rows = SavedDriveReplayState(playback()).dashboardSignals()
        val ids = defaultWorkspacePlotIds(rows, emptyList())
        assertEquals(55, ids.size)
        assertEquals(8, rows.count { it.availability == SignalAvailability.UNAVAILABLE })
        rows.forEach { assertEquals("Retained replay channel · ECU capability unverified", workspaceSupportLabel(it, false)) }
        val layout = GraphFirstLayout.fromSensorIds(ids).withDisplaySelection(ids, ids - "rpm")
        assertEquals(110, layout.modules.size)
        val raw = WorkspaceLayoutCodec.encode(layout)
        assertEquals((ids - "rpm").toSet(), persistedWorkspacePlotIds(rows, emptyList(), raw).toSet())
        assertEquals(layout, WorkspaceLayoutCodec.decode(raw, ids).layout)
        val shown = referenceOverviewRoles(layout.copy(hideUnavailable = true), layout, rows)
        assertTrue(shown.all { role -> rows.single { it.definition.id == role.id }.availability == SignalAvailability.AVAILABLE })
        rows.filter { it.availability == SignalAvailability.UNAVAILABLE }.forEach {
            val shown = workspaceValuePresentation(it, false)
            assertFalse(shown.available)
            assertEquals("—", shown.value)
            assertEquals("Unavailable", shown.status)
        }
    }

    @Test fun actualGapAndRecoveryStayEmptyInFigmaTracesAndScatter() {
        val drive = playback()
        val gapRows = SavedDriveReplayState(drive, positionMillis = 212000).dashboardSignals()
        assertTrue(gapRows.all { it.value == null })
        val recovered = SavedDriveReplayState(drive, positionMillis = 213000).dashboardSignals()
        assertEquals(47, recovered.count { it.availability == SignalAvailability.AVAILABLE })
        val rpm = recovered.single { it.definition.id == "rpm" }
        val trace = graphFocusTrace(rpm, null, false, GraphTimeRange.SIXTY, false)
        val points = trace.segments.flatten()
        assertFalse(points.any { it.atMillis in 211000L..212000L && it.value != null })
        assertTrue(points.any { it.atMillis == 213000L && it.value != null })
        assertTrue(trace.segments.size >= 2)
        val scatter = workspaceSourceScatter(DashboardSource.CIVIC_REPLAY, recovered, null, false, GraphTimeRange.SIXTY, false)
        assertTrue(scatter.isNotEmpty())
        assertFalse(scatter.any { it.atMillis in 211000L..212000L })
        assertTrue(workspaceSourceScatter(DashboardSource.HARDWARE, recovered, null, false, GraphTimeRange.SIXTY, false).isEmpty())
    }
}
