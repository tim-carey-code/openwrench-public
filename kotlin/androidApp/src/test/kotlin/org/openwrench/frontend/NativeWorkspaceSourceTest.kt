package org.openwrench.frontend

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeWorkspaceSourceTest {
    @Test
    fun importedSnapshotsRemainHistoricalAcrossAllWorkspaceLabels() {
        assertEquals("HISTORICAL", sourceBadgeLabel(DashboardSource.SNAPSHOT))
        assertEquals("Saved Snapshot", dashboardHeading(DashboardSource.SNAPSHOT))
        assertEquals("Saved snapshot", workspaceSourceDescription(DashboardSource.SNAPSHOT))
    }

    @Test
    fun simulatedRecordedAndHardwareSourcesKeepDistinctLabels() {
        assertEquals("SIMULATED", sourceBadgeLabel(DashboardSource.DEMO))
        assertEquals("SIMULATED", sourceBadgeLabel(DashboardSource.SCRIPTED))
        assertEquals("RECORDED", sourceBadgeLabel(DashboardSource.CIVIC_REPLAY))
        assertEquals("LIVE SOURCE", sourceBadgeLabel(DashboardSource.HARDWARE))
        assertEquals("Demo Dashboard", dashboardHeading(DashboardSource.DEMO))
        assertEquals("Scripted Dashboard", dashboardHeading(DashboardSource.SCRIPTED))
        assertEquals("Recorded Dashboard", dashboardHeading(DashboardSource.CIVIC_REPLAY))
        assertEquals("Live Dashboard", dashboardHeading(DashboardSource.HARDWARE))
        assertEquals("Offline demo", workspaceSourceDescription(DashboardSource.DEMO))
        assertEquals("Scripted session", workspaceSourceDescription(DashboardSource.SCRIPTED))
        assertEquals("Recorded playback", workspaceSourceDescription(DashboardSource.CIVIC_REPLAY))
        assertEquals("Read-only session", workspaceSourceDescription(DashboardSource.HARDWARE))
    }
}
