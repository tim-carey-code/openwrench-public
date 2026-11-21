package org.openwrench.frontend

import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.openwrench.frontend.android.LiveHealthChartPoint
import org.openwrench.frontend.android.LiveHealthChartSeries
import org.openwrench.frontend.android.LiveHealthDriveReview

@OptIn(ExperimentalCoroutinesApi::class)
class SavedChartPresentationTest {
    @Test fun lifecycleStopFlagHaltsCursorWithoutCancellationOrRecomposition() = runTest {
        var playing = true
        val cursors = mutableListOf<Long>()
        val playback = async {
            runSavedChartPlayback(SavedChartPlayback(1_000, true), 10_000,
                { playing }, { testScheduler.currentTime }, cursors::add)
        }
        runCurrent()
        advanceTimeBy(67)
        runCurrent()
        assertEquals(listOf(1_000L, 1_033L, 1_066L), cursors)
        playing = false // Simulates ON_STOP without cancelling the coroutine.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SavedChartPlayback(1_066, false), playback.await())
        assertEquals(listOf(1_000L, 1_033L, 1_066L), cursors)
    }

    @Test fun cancellingPlaybackStopsFurtherCursorUpdates() = runTest {
        val cursors = mutableListOf<Long>()
        val job = launch {
            runSavedChartPlayback(SavedChartPlayback(1_000, true), 10_000,
                { true }, { testScheduler.currentTime }, cursors::add)
        }
        runCurrent()
        advanceTimeBy(34)
        runCurrent()
        assertEquals(listOf(1_000L, 1_033L), cursors)
        job.cancelAndJoin()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1_000L, 1_033L), cursors)
        assertTrue(job.isCancelled)
    }

    @Test fun playbackLoopClampsEndAndAnAlreadyStoppedFlagEmitsNothing() = runTest {
        val cursors = mutableListOf<Long>()
        val end = runSavedChartPlayback(SavedChartPlayback(1_000, true), 1_050,
            { true }, { testScheduler.currentTime }, cursors::add)
        assertEquals(listOf(1_000L, 1_033L, 1_050L), cursors)
        assertEquals(SavedChartPlayback(1_050, false), end)
        cursors.clear()
        val stopped = runSavedChartPlayback(SavedChartPlayback(1_000, true), 10_000,
            { false }, { testScheduler.currentTime }, cursors::add)
        assertEquals(SavedChartPlayback(1_000, false), stopped)
        assertTrue(cursors.isEmpty())
    }

    @Test fun playbackUsesElapsedTimeAndStopsExactlyAtEnd() {
        val playing = SavedChartPlayback(10_000).play(10_000, 30_000)
        assertEquals(SavedChartPlayback(11_234, true), playing.advance(1_234, 30_000))
        assertEquals(SavedChartPlayback(30_000, false), playing.advance(90_000, 30_000))
        assertEquals(playing, playing.advance(-1, 30_000))
    }

    @Test fun repeatedPauseResumesAtCursorAndEndRestartsAtRetainedStart() {
        val paused = SavedChartPlayback(14_000, true).pause().pause()
        assertEquals(SavedChartPlayback(14_000), paused.advance(5_000, 30_000))
        assertEquals(SavedChartPlayback(14_000, true), paused.play(10_000, 30_000))
        assertEquals(SavedChartPlayback(10_000, true), SavedChartPlayback(30_000).play(10_000, 30_000))
        assertEquals(SavedChartPlayback(20_000, true), paused.play(20_000, 30_000))
        assertEquals(SavedChartPlayback(10_000), paused.play(10_000, 10_000))
    }

    @Test fun playbackAcrossGapDoesNotInventReceiptOrFillUnavailableValue() {
        val unavailable = LiveHealthChartPoint(1_000, null, true)
        val points = listOf(unavailable, LiveHealthChartPoint(20_000, 30.0, true))
        val middle = SavedChartPlayback(1_000).play(1_000, 20_000).advance(9_000, 20_000)
        assertNull(savedChartReceipt(points, middle.cursor))
        assertEquals(unavailable, savedChartReceipt(points, 1_000))
        assertEquals(points.last(), savedChartReceipt(points, 19_000))
        assertNull(savedChartReceipt(emptyList(), 10_000))
        assertEquals(2, points.size)
    }

    @Test fun fittedScaleUsesVisibleValuesWhileStableScaleKeepsRetainedExtremes() {
        val series = LiveHealthChartSeries(12, "RPM", "rpm", listOf(
            LiveHealthChartPoint(0, 100.0), LiveHealthChartPoint(10_000, 20.0),
            LiveHealthChartPoint(11_000, 30.0), LiveHealthChartPoint(12_000, Double.NaN)))
        assertEquals(20.0 to 30.0, savedChartBounds(series, 10_000, 12_000, true))
        assertEquals(20.0 to 100.0, savedChartBounds(series, 10_000, 12_000, false))
        assertEquals(19.5 to 20.5, savedChartBounds(series, 10_000, 10_000, true))
        assertNull(savedChartBounds(series, 12_000, 12_000, true))
        assertNull(savedChartBounds(series.copy(points = emptyList()), 0, 100, false))
    }

    @Test fun pauseFreezesSnapshotWithoutChangingCollectionOrRepeatedlyReplacingIt() {
        val original = LiveHealthDriveReview(1, 0, 1_000, emptyList(), emptyList(), emptyList(),
            listOf(LiveHealthChartSeries(12, "RPM", "rpm", listOf(LiveHealthChartPoint(1_000, 800.0)))),
            emptyList(), emptyList(), 0, 1_000, 1_000)
        val newer = original.copy(elapsedMillis = 2_000, series = emptyList())
        val frozen = freezeChartReview(null, original)
        assertSame(original, freezeChartReview(frozen, newer))
        assertEquals(2_000L, newer.elapsedMillis)
        assertEquals(1, original.series.single().points.size)
    }
}
