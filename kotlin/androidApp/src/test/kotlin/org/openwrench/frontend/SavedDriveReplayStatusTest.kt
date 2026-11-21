package org.openwrench.frontend

import org.openwrench.frontend.android.*
import kotlin.test.*
import org.openwrench.nativecore.ObdDecoder

class SavedDriveReplayStatusTest {
    @Test fun everyProvenanceStaysExplicitlyNotLive() {
        assertEquals("Synthetic replay · Not live", replaySourceSummary(DriveEvidenceOrigin.SYNTHETIC))
        assertEquals("Recorded replay · Not live", replaySourceSummary(DriveEvidenceOrigin.HARDWARE))
        assertEquals("Replay · Source unknown · Not live", replaySourceSummary(DriveEvidenceOrigin.UNKNOWN))
    }
    @Test fun widthsAndFontScalesReflowBeforeStatusLosesRoom() {
        for (width in listOf(296f, 336f, 576f, 776f, 1176f)) {
            for (scale in listOf(1f, 1.3f, 2f, 3f)) {
                if (!replayStatusStacks(width, scale)) assertTrue(width - 96 >= 160 * scale)
            }
        }
        assertFalse(replayStatusStacks(296f, 1f))
        assertTrue(replayStatusStacks(296f, 2f))
        assertFalse(replayStatusStacks(776f, 3f))
    }
    @Test fun timesStayReadableBeyondOneHour() {
        assertEquals("0:00", replayTime(-1))
        assertEquals("0:59", replayTime(59999))
        assertEquals("5:00", replayTime(300000))
        assertEquals("61:01", replayTime(3661000))
    }
    @Test fun retainedSegmentProgressExcludesOriginalOffsetAndEndHold() {
        val metadata=ObdDecoder.catalog.getValue(12)
        val series=LiveHealthChartSeries(12,metadata.name,metadata.unit,listOf(
            LiveHealthChartPoint(30000,700.0),LiveHealthChartPoint(330000,710.0)))
        val review=LiveHealthDriveReview(1,0,330000,emptyList(),emptyList(),emptyList(),listOf(series),
            emptyList(),emptyList(),0,30000,330000)
        val drive=SavedDrive("11111111-1111-1111-1111-111111111111",1,330001,SavedDriveStatus.COMPLETED,
            null,null,1,"OPENWRENCH live health monitoring\nSynthetic test",review,DriveEvidenceOrigin.SYNTHETIC)
        val recording=SavedDrivePlayback.from(drive)
        assertEquals("Paused · 0:00 / 5:00",replayProgress(SavedDriveReplayState(recording,ReplayPhase.PAUSED)))
        assertEquals("Playing · 1:01 / 5:00",replayProgress(SavedDriveReplayState(recording,ReplayPhase.PLAYING,61000)))
        assertEquals("Playing · 5:00 / 5:00",replayProgress(SavedDriveReplayState(recording,ReplayPhase.PLAYING,300999)))
    }
}
