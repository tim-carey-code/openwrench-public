package org.openwrench.frontend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.openwrench.core.SignalValue
import org.openwrench.core.display
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.ObdDecoder
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Entirely synthetic test receipts; no user or vehicle capture is bundled. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedDriveReplayTest {
    private fun series(pid: Int, vararg points: LiveHealthChartPoint): LiveHealthChartSeries {
        val metadata = ObdDecoder.catalog.getValue(pid)
        return LiveHealthChartSeries(pid, metadata.name, metadata.unit, points.toList())
    }
    private fun drive(vararg series: LiveHealthChartSeries): SavedDrive {
        val chosen = series.toList().ifEmpty { listOf(series(0x0C,
            LiveHealthChartPoint(30_000, 700.0, true), LiveHealthChartPoint(30_400, 900.0), LiveHealthChartPoint(30_750, 1100.0))) }
        val points = chosen.flatMap { it.points }
        val review = LiveHealthDriveReview(1, 123456, 100_000, emptyList(), emptyList(), emptyList(), chosen,
            emptyList(), listOf("Synthetic unit test only"), 0, points.minOfOrNull { it.offsetMillis }, points.maxOfOrNull { it.offsetMillis })
        return SavedDrive("11111111-1111-1111-1111-111111111111", 1_000_000, 1_100_000,
            SavedDriveStatus.COMPLETED, "Synthetic test", null, 1, "OPENWRENCH live health monitoring\nTEST ONLY", review,
            DriveEvidenceOrigin.SYNTHETIC)
    }
    private fun player(scope: TestScope) = SavedDriveReplayPlayer(scope.backgroundScope, object : FrontendClock {
        override fun elapsedMillis() = scope.testScheduler.currentTime
        override fun utc() = "NEVER USE THIS AS A RECORDED TIME"
    })
    private fun row(player: SavedDriveReplayPlayer) = player.state.value.dashboardSignals().first()

    @Test fun actualIntervalsFinalReceiptAndLoopArePreserved() = runTest {
        val player = player(this)
        val drive = drive()
        player.load(SavedDrivePlayback.from(drive))
        assertEquals(30_000L, player.state.value.originalOffsetMillis)
        assertEquals("700", row(player).value!!.display())
        player.play(); runCurrent(); advanceTimeBy(399); player.pause()
        assertEquals("700", row(player).value!!.display())
        player.play(); runCurrent(); advanceTimeBy(1); runCurrent()
        assertEquals("900", row(player).value!!.display())
        advanceTimeBy(350); runCurrent()
        assertEquals("1100", row(player).value!!.display())
        advanceTimeBy(999); player.pause()
        assertEquals(0L, player.state.value.loop)
        assertEquals("1100", row(player).value!!.display())
        player.play(); runCurrent(); advanceTimeBy(1); runCurrent()
        assertEquals(1L, player.state.value.loop)
        assertEquals(30_000L, player.state.value.originalOffsetMillis)
        assertEquals("700", row(player).value!!.display())
        assertEquals(listOf(30_000L), row(player).trend.points.filter { it.value != null }.map { it.atMillis })
        assertSame(drive, player.state.value.recording!!.drive)
    }

    @Test fun pauseRestartBackgroundAndStopNeverAutoconnectOrResume() = runTest {
        val player = player(this)
        player.play(); player.restart(); assertEquals(ReplayPhase.EMPTY, player.state.value.phase)
        player.load(SavedDrivePlayback.from(drive())); player.play(); runCurrent()
        advanceTimeBy(500); player.setActive(false)
        val paused = player.state.value
        player.setActive(true); advanceTimeBy(5000); runCurrent()
        assertEquals(paused, player.state.value)
        player.restart(); assertEquals(0L, player.state.value.positionMillis)
        assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
        player.play(); runCurrent(); advanceTimeBy(450); player.restart()
        assertEquals(ReplayPhase.PLAYING, player.state.value.phase)
        assertEquals(0L, player.state.value.positionMillis)
        player.close(); advanceTimeBy(5000); runCurrent()
        assertEquals(ReplayPhase.EMPTY, player.state.value.phase)
        assertTrue(player.state.value.dashboardSignals().isEmpty())
    }

    @Test fun nullReceiptsAndBreakMarkersRemainGapsAndZeroIsARealValue() {
        val recording = SavedDrivePlayback.from(drive(series(0x0C,
            LiveHealthChartPoint(30_000, 0.0), LiveHealthChartPoint(30_100, null),
            LiveHealthChartPoint(30_200, 0.0), LiveHealthChartPoint(30_300, 100.0, true))))
        fun row(at: Long) = SavedDriveReplayState(recording, positionMillis = at).dashboardSignals().single()
        assertEquals(SignalValue.Number(0.0), row(0).value)
        assertEquals(SignalAvailability.UNAVAILABLE, row(150).availability)
        assertNull(row(150).value)
        assertEquals(SignalValue.Number(0.0), row(200).value)
        val segments = row(300).trend.segments(30_300)
        assertEquals(listOf(listOf(0.0), listOf(0.0), listOf(100.0)), segments.map { it.map { p -> p.value } })
    }

    @Test fun expiryDoesNotInventTimesSourceAgesOrFillMissingSignals() {
        val recording = SavedDrivePlayback.from(drive(
            series(0x0C, LiveHealthChartPoint(30_000, 700.0), LiveHealthChartPoint(40_000, 800.0)),
            series(0x05, LiveHealthChartPoint(35_000, 80.0))))
        fun rows(at: Long) = SavedDriveReplayState(recording, positionMillis = at).dashboardSignals()
        assertEquals(SignalAvailability.NOT_SAMPLED, rows(0).last().availability)
        assertNull(rows(0).last().value)
        assertEquals(SignalAvailability.AVAILABLE, rows(4999).first().availability)
        assertEquals(SignalAvailability.STALE, rows(5000).first().availability)
        assertNull(rows(5000).first().value)
        assertEquals(5000L, rows(5000).first().ageMillis)
        assertTrue(rows(5000).all { it.observedAt == null && it.trend.points.all { p -> p.observedAt == null } })
        assertContains(rows(5000).first().reason, "source age were not stored")
        assertEquals(SignalValue.Number(80.0), rows(5000).last().value)
    }

    @Test fun unknownAndSyntheticProvenanceStayExplicit() {
        val original = drive()
        for ((origin, label) in listOf(DriveEvidenceOrigin.SYNTHETIC to "Synthetic", DriveEvidenceOrigin.UNKNOWN to "unknown", DriveEvidenceOrigin.HARDWARE to "hardware")) {
            val source = original.copy(origin = origin)
            val state = SavedDriveReplayState(SavedDrivePlayback.from(source))
            assertEquals(origin, state.recording!!.drive.origin)
            assertContains(state.dashboardSignals().first().reason, label)
            assertContains(state.dashboardSignals().first().reason, "Not live")
        }
    }

    @Test fun summariesAndSingleValuesAreNotTurnedIntoFictionalDrives() {
        for (points in listOf(emptyList(), listOf(LiveHealthChartPoint(0, 1.0)),
            listOf(LiveHealthChartPoint(0, 1.0), LiveHealthChartPoint(0, 2.0)),
            listOf(LiveHealthChartPoint(0, null), LiveHealthChartPoint(100, null)))) {
            assertFailsWith<IllegalArgumentException> { SavedDrivePlayback.from(drive(series(0x0C, *points.toTypedArray()))) }
        }
    }

    @Test fun admissionBlocksHardwareAndPendingCleanup() {
        assertTrue(canStartDriveReplay(NativeUiState()))
        assertTrue(canStartDriveReplay(NativeUiState(origin = ReadingOrigin.SIMULATOR)))
        assertTrue(canStartDriveReplay(NativeUiState(origin = ReadingOrigin.REPLAY)))
        for (state in listOf(NativeUiState(connected = true), NativeUiState(busy = true),
            NativeUiState(cleanupUncertain = true), NativeUiState(liveRunning = true), NativeUiState(stopPending = true))) {
            assertFalse(canStartDriveReplay(state))
        }
    }

    @Test fun playbackNeverRewritesOrDuplicatesSavedHistory() = runTest {
        val root = Files.createTempDirectory("ow-demo-test").toFile()
        try {
            val store = DriveHistoryStore(root)
            val original = drive()
            store.upsert(original)
            val file = File(root, "drive-history-v1/${original.id}.json")
            val bytes = file.readBytes()
            val player = player(this)
            player.load(SavedDrivePlayback.from(store.read(original.id))); player.play(); runCurrent()
            advanceTimeBy(7000); runCurrent(); player.restart(); player.close()
            assertContentEquals(bytes, file.readBytes())
            assertEquals(listOf(original.id), store.list().map { it.id })
            assertEquals(original, store.read(original.id))
        } finally { root.deleteRecursively() }
    }

    @Test fun delayedTicksSkipWholeLoopsWithoutPriorPassSamples() = runTest {
        var now = 0L
        val player = SavedDriveReplayPlayer(backgroundScope, object : FrontendClock {
            override fun elapsedMillis() = now
            override fun utc() = "unused"
        })
        player.load(SavedDrivePlayback.from(drive())); player.play(); runCurrent()
        now = 1750 * 3 + 450
        advanceTimeBy(250); runCurrent()
        assertEquals(3L, player.state.value.loop)
        assertEquals(450L, player.state.value.positionMillis)
        assertEquals(listOf(30_000L, 30_400L), row(player).trend.points.filter { it.value != null }.map { it.atMillis })
    }

    @Test fun choosingTheSameDriveAgainCreatesAnExplicitNewSelection() = runTest {
        val player = player(this)
        val recording = SavedDrivePlayback.from(drive())
        player.load(recording)
        val selection = player.state.value.selectionGeneration
        player.play(); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(selection, player.state.value.selectionGeneration)
        player.load(recording)
        assertTrue(player.state.value.selectionGeneration > selection)
        assertEquals(0L, player.state.value.positionMillis)
        assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
    }
}
