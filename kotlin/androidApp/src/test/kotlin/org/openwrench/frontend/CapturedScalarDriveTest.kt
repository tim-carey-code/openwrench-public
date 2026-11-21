package org.openwrench.frontend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.openwrench.core.SignalValue
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Decoder-valid fictional bench receipts, never a claim about a vehicle's capabilities. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturedScalarDriveTest {
    private val pids = ObdDecoder.scalarPids.sorted()
    private val decoded by lazy { pids.associateWith(::decodeValue) }
    private fun value(pid: Int) = decoded.getValue(pid)
    private fun decodeValue(pid: Int): DecodedReading.Scalar = (1..8).firstNotNullOf { bytes ->
        val response = "41" + pid.toString(16).padStart(2, '0') + "00".repeat(bytes)
        runCatching { ObdDecoder.decode(pid, response) as DecodedReading.Scalar }.getOrNull()
    }
    private fun health(supported: Set<Int>) = LiveHealthAccumulator(7, null, 0, supported,
        supported.intersect((LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()), 1)
    private fun archive(review: LiveHealthDriveReview) = SavedDrive(
        "11111111-1111-1111-1111-111111111111", 1_000_000, 1_000_000 + review.elapsedMillis,
        SavedDriveStatus.COMPLETED, "Fictional bench complete", null, 7,
        "OPENWRENCH live health monitoring\nFictional bench, not a vehicle\n" + review.reportText(), review,
        DriveEvidenceOrigin.SYNTHETIC)
    private fun fullDrive(): SavedDrive {
        val recorder = health(pids.toSet())
        for (base in listOf(100L, 4_100L)) pids.forEachIndexed { index, pid ->
            recorder.offer(NativeReading(pid, value(pid)), base + index)
        }
        recorder.stop(4_200, "Fictional bench complete")
        return archive(recorder.snapshot.driveReview!!)
    }
    private fun zip(drive: SavedDrive): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("drives/${drive.id}.json"))
            zip.write(DriveHistoryCodec.encode(drive).toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun capturedFullCatalogSurvivesOrdinarySaveReloadJsonAndZipImport() {
        val drive = fullDrive()
        assertEquals(55, drive.review.series.size)
        assertEquals(55, drive.review.signals.size)
        assertEquals(pids.toSet(), drive.review.series.map { it.pid }.toSet())
        assertTrue(drive.review.conditions.flatMap { it.signals }.all { it.pid in LiveHealthInputs.scalarPids })
        assertTrue(drive.review.series.all { it.points.size == 2 })
        val directory = Files.createTempDirectory("ow-captured-scalar-bench").toFile()
        try {
            DriveHistoryStore(directory).upsert(drive)
            val reopened = DriveHistoryStore(directory).read(drive.id)
            assertEquals(drive, reopened)
            assertEquals(drive, DriveReplayImport.read(DriveHistoryCodec.encode(reopened).byteInputStream()).single())
            assertEquals(drive, DriveReplayImport.read(zip(reopened).inputStream()).single())
            val rows = SavedDriveReplayState(SavedDrivePlayback.from(reopened), positionMillis = 4_100).dashboardSignals()
            assertEquals(55, rows.size)
            rows.forEach { row ->
                val pid = row.definition.pid!!.drop(2).toInt(16)
                val expected = value(pid).value
                assertEquals(ObdDecoder.catalog.getValue(pid).unit, row.definition.unit)
                assertEquals(if (ObdDecoder.isIntegerScalar(pid)) SignalValue.Integer(expected.toLong()) else SignalValue.Number(expected), row.value)
                assertTrue(row.retainedReplay)
                assertContains(row.reason, "Not live")
                assertContains(row.reason, "ECU capability support is unverified")
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nonHealthReceiptsNeverExpandHealthAssessmentOrInventUnsampledValues() {
        val recorder = health(setOf(0x0C, 0x5C, 0xA6, 0x03, 0xFF))
        recorder.offer(NativeReading(0x0C, DecodedReading.Scalar(0.0)), 100)
        val assessment = recorder.snapshot.driveReview!!.assessment
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(0.0)), 200)
        recorder.offer(NativeReading(0x5C, null), 1_200)
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(80.0)), 2_200)
        recorder.offer(NativeReading(0x0C, DecodedReading.Scalar(700.0)), 2_300)
        recorder.offer(NativeReading(0xFF, DecodedReading.Scalar(42.0)), 2_400)
        recorder.stop(3_000, "Synthetic stop")
        val stopped = recorder.snapshot
        val review = stopped.driveReview!!
        assertEquals(setOf(0x0C, 0x5C, 0xA6), review.series.map { it.pid }.toSet())
        assertEquals(setOf(0x0C), stopped.latest.keys)
        assertEquals(2, stopped.receiptCount)
        assertTrue(review.conditions.flatMap { it.signals }.all { it.pid == 0x0C })
        assertEquals(assessment?.packId, review.assessment?.packId)
        val oil = review.series.single { it.pid == 0x5C }
        assertEquals(listOf(200L, 1_200L, 2_200L), oil.points.map { it.offsetMillis })
        assertEquals(listOf(0.0, null, 80.0), oil.points.map { it.value })
        assertTrue(oil.points.last().breakBefore)
        val rows = SavedDriveReplayState(SavedDrivePlayback.from(archive(review)), positionMillis = 2_300).dashboardSignals()
        val unsampled = rows.single { it.definition.pid == "01A6" }
        assertEquals(SignalAvailability.NOT_SAMPLED, unsampled.availability)
        assertNull(unsampled.value); assertTrue(unsampled.trend.points.isEmpty())
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(90.0)), 4_000)
        assertEquals(stopped, recorder.snapshot)
    }

    @Test fun twentyMinuteWindowRemainsBoundedWithoutDiscardingAnyChannel() {
        val recorder = SavedDriveTelemetryRecorder(0, pids.toSet())
        repeat(1_501) { second -> pids.forEachIndexed { index, pid ->
            recorder.offer(NativeReading(pid, value(pid)), second * 1_000L + index)
        } }
        recorder.stop(1_501_000)
        val emptyHealth = LiveHealthDriveRecorder(7, 1, 0, retainCharts = false).snapshot()
        val review = recorder.applyTo(emptyHealth)
        assertEquals(55, review.series.size)
        assertTrue(review.series.all { it.points.isNotEmpty() && it.points.size <= 363 })
        assertTrue(review.series.sumOf { it.points.size } <= DriveHistoryCodec.MAX_EXTENDED_POINTS)
        assertTrue(review.series.all { series -> series.points.all { it.offsetMillis >= review.elapsedMillis - 20 * 60_000 } })
        assertTrue(review.series.all { it.points.first().offsetMillis < review.elapsedMillis - 19 * 60_000 })
        assertTrue(review.signals.all { it.sampleCount == 1_501L })
        assertTrue(review.retentionNotes.any { "four-second" in it })
        assertFalse(review.retentionNotes.any { it.startsWith("0 older telemetry") })
        assertTrue(DriveHistoryCodec.encode(archive(review)).toByteArray().size < DriveHistoryCodec.MAX_FILE_BYTES)
        assertEquals(review, DriveHistoryCodec.decode(DriveHistoryCodec.encode(archive(review))).review)
    }

    @Test fun adaptiveBucketsPreserveHiddenNoDataAndRecoveryBreaksWithoutRetiming() {
        val recorder = SavedDriveTelemetryRecorder(0, pids.toSet())
        val values = listOf(100L to 0.0, 4_100L to 700.0, 8_100L to null,
            8_500L to 800.0, 12_100L to 900.0, 24_200L to 1_000.0)
        values.forEach { (at, value) -> recorder.offer(NativeReading(0x0C, value?.let { DecodedReading.Scalar(it) }), at) }
        val review = recorder.applyTo(LiveHealthDriveRecorder(7, 1, 0, retainCharts = false).snapshot())
        val points = review.series.single { it.pid == 0x0C }.points
        assertEquals(listOf(100L, 4_100L, 8_500L, 12_100L, 24_200L), points.map { it.offsetMillis })
        assertEquals(listOf(true, false, true, false, true), points.map { it.breakBefore })
        val playback = SavedDrivePlayback.from(archive(review))
        val rows = SavedDriveReplayState(playback, positionMillis = 8_400).dashboardSignals()
        val rpm = rows.single { it.definition.pid == "010C" }
        assertEquals(SignalValue.Number(800.0), rpm.value)
        assertEquals(listOf(listOf(0.0, 700.0), listOf(800.0)), rpm.trend.segments(8_500).map { segment -> segment.map { it.value } })
        assertEquals(54, rows.count { it.availability == SignalAvailability.NOT_SAMPLED })
    }

    @Test fun invalidUnknownStructuredAndOutOfOrderDataCannotBecomeSavedScalarReceipts() {
        val recorder = SavedDriveTelemetryRecorder(100, setOf(0x1F, 0x5C, 0x03, 0xFF))
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(0.0)), 200)
        for ((pid, reading) in listOf(
            0xFF to DecodedReading.Scalar(1.0), 0x03 to DecodedReading.Scalar(1.0),
            0x1F to DecodedReading.Scalar(0.5), 0x5C to DecodedReading.Scalar(Double.NaN),
            0x5C to DecodedReading.Structured(emptyMap()))) recorder.offer(NativeReading(pid, reading), 300)
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(90.0)), 150)
        recorder.offer(NativeReading(0x5C, DecodedReading.Scalar(90.0)), 200)
        val review = recorder.applyTo(LiveHealthDriveRecorder(7, 1, 100, retainCharts = false).snapshot())
        assertEquals(setOf(0x1F, 0x5C), review.series.map { it.pid }.toSet())
        assertEquals(listOf(LiveHealthChartPoint(100, 0.0, true)), review.series.single { it.pid == 0x5C }.points)
        assertTrue(review.series.single { it.pid == 0x1F }.points.isEmpty())
    }

    @Test fun twentyFourSelectionsSurviveReplayPauseResumeRestartAndSourceChange() = runTest {
        val player = SavedDriveReplayPlayer(backgroundScope, object : FrontendClock {
            override fun elapsedMillis() = testScheduler.currentTime
            override fun utc() = "Not a source receipt clock"
        })
        val original = fullDrive()
        player.load(SavedDrivePlayback.from(original))
        player.play(); runCurrent(); advanceTimeBy(4_100); player.pause()
        val rows = player.state.value.dashboardSignals()
        val selected = rows.take(24).fold(emptyList<String>()) { ids, row -> togglePlot(ids, row.definition.id) }
        assertEquals(24, selected.size)
        assertEquals(24, liveValueRows(rows, selected, true).size)
        val layout = GraphFirstLayout.fromSensorIds(rows.map { it.definition.id }).withDisplaySelection(rows.map { it.definition.id }, selected)
        val graphs = layout.visible(WorkspaceModuleKind.GRAPH, selected)
        assertEquals(24, graphs.size)
        assertEquals(selected.toSet(), graphs.map { it.sensorId }.toSet())
        assertEquals(12, mechanicGraphRows(graphs, 1_262f, 1f, false, true).size)
        assertEquals(24, mechanicGraphRows(graphs, 372f, 2f, false, true).size)
        val serialized = WorkspaceLayoutCodec.encode(layout)
        assertEquals(selected.toSet(), persistedWorkspacePlotIds(rows, emptyList(), serialized).toSet())
        val paused = player.state.value
        advanceTimeBy(3_000); runCurrent()
        assertEquals(paused, player.state.value)
        player.play(); runCurrent(); advanceTimeBy(100); player.setActive(false)
        assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
        player.restart()
        assertEquals(0L, player.state.value.positionMillis)
        assertEquals(55, player.state.value.dashboardSignals().size)
        val subset = original.copy(review = original.review.copy(series = original.review.series.take(2), signals = original.review.signals.take(2)))
        player.load(SavedDrivePlayback.from(subset))
        assertEquals(2, player.state.value.dashboardSignals().size)
        assertSame(subset, player.state.value.recording!!.drive)
        player.close()
        assertTrue(player.state.value.dashboardSignals().isEmpty())
        assertEquals(55, original.review.series.size, "Playback must not alter the saved source")
    }
}
