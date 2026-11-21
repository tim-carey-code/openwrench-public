package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.*

class LiveHealthDriveReviewTest {
    private val stationary = LiveHealthContextKey("Stationary with engine running", "closed loop")
    private val moving = LiveHealthContextKey("Moving", "closed loop")
    private fun scalar(pid: Int, value: Double) = NativeReading(pid, DecodedReading.Scalar(value))
    private fun status(mil: Boolean, count: Int) = NativeReading(0x01,
        DecodedReading.Structured(mapOf("mil_on" to mil.toString(), "dtc_count" to count.toString())))
    private fun fuel(value: String) = NativeReading(0x03, DecodedReading.Structured(mapOf("system_1" to value, "system_2" to "engine off")))

    @Test fun recapKeepsActualWholeMonitorEndpointsAndSeparatesSampledConditions() {
        val r = LiveHealthDriveRecorder(4, 9, 10_000)
        r.offer(scalar(0x0D, 0.0), 10_100, stationary)
        r.offer(scalar(0x0C, 700.0), 10_200, stationary)
        r.offer(scalar(0x05, 74.0), 10_300, stationary)
        r.offer(scalar(0x0D, 30.0), 20_100, moving)
        r.offer(scalar(0x0C, 2_000.0), 20_200, moving)
        r.offer(scalar(0x05, 80.0), 20_300, moving)
        val review = r.snapshot().present(12_000)
        val rpm = review.signals.single { it.pid == 0x0C }
        assertEquals(700.0, rpm.firstValue)
        assertEquals(2_000.0, rpm.lastValue)
        assertEquals(700.0, rpm.minimum)
        assertEquals(2_000.0, rpm.maximum)
        assertEquals(200, rpm.firstOffsetMillis)
        assertEquals(10_200, rpm.lastOffsetMillis)
        assertEquals(3, review.conditions.single { it.condition == "Moving" }.sampleCount)
        assertTrue(review.highlights.any { it.contains("stationary observed range") })
        assertEquals(12_000, review.elapsedMillis)
        assertFalse(review.retentionNotes.any { it.contains("distance driven") })
        assertTrue(review.reportText().contains("not continuous driving duration"))
    }

    @Test fun chartsRetainActualOffsetsOnCommonTimelineAndPreserveNoDataBreaks() {
        val r = LiveHealthDriveRecorder(4, 1, 1_000)
        r.offer(scalar(0x0C, 700.0), 1_100, stationary)
        r.offer(scalar(0x04, 20.0), 1_300, stationary)
        r.offer(scalar(0x0C, 710.0), 1_700, stationary)
        r.offer(NativeReading(0x0C, null), 1_800, stationary)
        r.offer(scalar(0x0C, 720.0), 1_900, stationary)
        r.offer(scalar(0x0C, 730.0), 2_200, stationary)
        r.offer(scalar(0x0C, 740.0), 14_000, stationary)
        val review = r.snapshot()
        val rpm = review.series.single { it.pid == 0x0C }.points
        assertEquals(listOf(900L, 1_200L, 13_000L), rpm.map { it.offsetMillis })
        assertEquals(listOf(720.0, 730.0, 740.0), rpm.map { it.value })
        assertTrue(rpm.first().breakBefore, "NO DATA hidden by bucket must remain a line break")
        assertTrue(rpm.last().breakBefore, "Long receipt gap must break line")
        val load = review.series.single { it.pid == 0x04 }.points.single()
        assertEquals(300, load.offsetMillis)
        assertNotEquals(load.offsetMillis, rpm.first().offsetMillis, "No fabricated simultaneous snapshots")
    }

    @Test fun mafChartsPreserveNoDataAndTimingGapsThroughArchiveRoundTrip() {
        val recorder = LiveHealthDriveRecorder(4, 11, 1_000)
        recorder.offer(scalar(0x10, 3.2), 1_100, stationary)
        recorder.offer(NativeReading(0x10, null), 1_200, stationary)
        recorder.offer(scalar(0x10, 3.4), 1_900, stationary)
        recorder.offer(scalar(0x10, 3.8), 2_200, moving)
        recorder.offer(NativeReading(0x10, null), 3_200, moving)
        recorder.offer(scalar(0x10, 4.4), 4_200, moving)
        recorder.offer(status(true, 1), 4_300, moving)
        recorder.offer(scalar(0x10, 5.0), 16_000, moving)
        recorder.stop(17_000, "Synthetic test complete")
        val review = recorder.snapshot()
        val maf = review.series.single { it.pid == 0x10 }
        assertEquals("g/s", maf.unit)
        assertEquals(listOf(900L, 1_200L, 2_200L, 3_200L, 15_000L), maf.points.map { it.offsetMillis })
        assertEquals(listOf(3.4, 3.8, null, 4.4, 5.0), maf.points.map { it.value })
        assertEquals(listOf(true, false, true, true, true), maf.points.map { it.breakBefore })
        assertEquals(5, review.signals.single { it.pid == 0x10 }.sampleCount)
        val event = review.bookmarks.single().chartSeries().single { it.pid == 0x10 }
        assertTrue(event.points.any { it.value == null }, "Event charts preserve the original MAF NO DATA receipts")
        assertTrue(event.points.last().breakBefore, "Long MAF receipt gaps must not be connected")
        val drive = historyTestDrive().copy(generation = 4, review = review,
            report = "OPENWRENCH live health monitoring\nSynthetic MAF evidence\n" + review.reportText())
        val encoded = DriveHistoryCodec.encode(drive)
        assertTrue(encoded.contains("openwrench.drive-history/6"))
        val reopened = DriveHistoryCodec.decode(encoded)
        assertEquals(9, reopened.review.series.size)
        assertEquals(drive, reopened)
        assertEquals(maf, reopened.historicalReview().series.single { it.pid == 0x10 })
    }

    @Test fun warningWindowPreservesActualBeforeAndAfterReceiptsAndDoesNotDuplicateActiveState() {
        val r = LiveHealthDriveRecorder(4, 11, 0)
        r.offer(status(false, 0), 0, stationary)
        for (at in 1_000L..20_000L step 1_000) r.offer(scalar(0x0C, 700.0), at, stationary)
        r.offer(status(true, 1), 20_100, stationary)
        val first = r.snapshot().bookmarks.single()
        assertFalse(first.initialObservation)
        assertEquals(20_100, first.offsetMillis)
        assertTrue(first.samples.all { it.offsetMillis in 5_100..20_100 })
        assertEquals(4, first.sessionGeneration)
        assertEquals(11, first.instanceId)
        r.offer(status(true, 1), 25_000, stationary)
        r.offer(scalar(0x04, 21.0), 35_000, moving)
        r.offer(scalar(0x0C, 2_000.0), 35_101, moving)
        val bookmark = r.snapshot().bookmarks.single()
        assertTrue(bookmark.complete)
        assertEquals(14_900, bookmark.postWindowMillis)
        assertTrue(bookmark.samples.any { it.offsetMillis == 35_000L && it.pid == 0x04 })
        assertFalse(bookmark.samples.any { it.offsetMillis > 35_100 })
        assertTrue(bookmark.samples.all { it.sessionGeneration == 4L })
        assertTrue(bookmark.observation.contains("not fault-onset"))
    }

    @Test fun initialWarningsAndStatusGapsAreHonestAndFreshClearRearms() {
        val r = LiveHealthDriveRecorder(2, 1, 0)
        r.offer(status(true, 2), 100, stationary)
        assertTrue(r.snapshot().bookmarks.single().initialObservation)
        r.offer(NativeReading(0x01, null), 30_000, stationary)
        r.offer(status(true, 2), 90_000, moving)
        assertEquals(1, r.snapshot().bookmarks.size, "Unknown interval cannot rebookmark same active warning")
        r.offer(status(true, 3), 150_000, moving)
        assertEquals(2, r.snapshot().bookmarks.size)
        assertTrue(r.snapshot().bookmarks.last().initialObservation, "Count increase across status gap has unknown timing")
        r.offer(status(false, 0), 160_000, moving)
        r.offer(status(true, 1), 170_000, moving)
        assertEquals(3, r.snapshot().bookmarks.size)
        assertFalse(r.snapshot().bookmarks.last().initialObservation)
        r.offer(fuel("open loop: system fault"), 170_100, moving)
        r.offer(fuel("open loop: system fault"), 172_000, moving)
        assertEquals(4, r.snapshot().bookmarks.size)
        assertTrue(r.snapshot().bookmarks.last().observation.contains("system_1 fault state first observed"))
    }

    @Test fun stoppingFreezesPartialPostWindowAndNewRecorderCannotMixSessions() {
        val r = LiveHealthDriveRecorder(3, 5, 1_000)
        r.offer(status(true, 1), 2_000, stationary)
        r.offer(scalar(0x05, 80.0), 3_000, stationary)
        r.stop(4_000, "App left the foreground")
        val old = r.snapshot()
        val event = old.bookmarks.single()
        assertTrue(event.complete)
        assertTrue(event.collectionStatus.contains("Partial post-window: App left the foreground"))
        assertEquals(1_000, event.postWindowMillis)
        r.offer(scalar(0x05, 90.0), 5_000, moving)
        assertEquals(old, r.snapshot())
        val newer = LiveHealthDriveRecorder(4, 6, 5_000)
        assertTrue(newer.snapshot().bookmarks.isEmpty())
        assertTrue(newer.snapshot().signals.isEmpty())
        assertNotEquals(old.instanceId, newer.snapshot().instanceId)
    }

    @Test fun longTripsAndWarningBurstsKeepExplicitMemoryBounds() {
        val r = LiveHealthDriveRecorder(4, 1, 0)
        repeat(3_000) { index ->
            r.offer(scalar(0x0C, 700.0 + index), index * 1_000L, moving)
            r.offer(scalar(0x05, 70.0), index * 1_000L + 1, moving)
        }
        var review = r.snapshot()
        assertTrue(review.series.all { it.points.size <= 1_200 })
        assertEquals(3_000, review.signals.single { it.pid == 0x0C }.sampleCount)
        assertEquals(700.0, review.signals.single { it.pid == 0x0C }.firstValue)
        assertTrue(review.series.flatMap { it.points }.all { review.elapsedMillis - it.offsetMillis <= 20 * 60_000 })
        assertTrue(review.present(review.elapsedMillis + 20 * 60_000 + 1).series.all { it.points.isEmpty() })
        var at = 3_001_000L
        repeat(16) { index ->
            r.offer(status(false, 0), at++, moving)
            r.offer(status(true, index + 1), at++, moving)
        }
        review = r.snapshot()
        assertEquals(12, review.bookmarks.size)
        assertEquals(4, review.droppedBookmarks)
        repeat(2_000) { r.offer(scalar(0x0C, 2_000.0), at++, moving) }
        review = r.snapshot()
        assertTrue(review.bookmarks.all { it.samples.size <= 512 })
        assertTrue(review.bookmarks.any { it.samplesDropped > 0 })
        assertTrue(review.bookmarks.any { it.postWindowTruncated })
    }

    @Test fun copiedBookmarkPreviewIsBoundedAndDoesNotHidePrebufferTruncation() {
        val r = LiveHealthDriveRecorder(4, 1, 0)
        repeat(2_000) { r.offer(scalar(0x0C, 700.0), it.toLong(), stationary) }
        r.offer(status(true, 1), 2_000, stationary)
        assertTrue(r.snapshot().bookmarks.single().preWindowTruncated)
        assertTrue(r.snapshot().bookmarks.single().collectionStatus.contains("Pre-window limited"))
        var at = 2_001L
        repeat(11) { index ->
            r.offer(status(false, 0), at++, stationary)
            r.offer(status(true, index + 2), at++, stationary)
        }
        repeat(2_000) { r.offer(scalar(0x0C, 800.0), at++, stationary) }
        val review = r.snapshot()
        val text = review.reportText()
        assertEquals(12, review.bookmarks.size)
        assertTrue(review.bookmarks.all { it.samples.size > 64 })
        assertTrue(text.contains("Showing 64 of"))
        assertTrue(text.contains("omitted earlier receipts are not included"))
        assertTrue(text.length < 150_000, "Copied drive preview has ${text.length} characters")
        assertTrue(text.lineSequence().count { it.startsWith("+") } <= 12 * 64)
    }

    @Test fun decimatedBookmarkTracesCannotJoinAcrossAnOmittedNoDataMarker() {
        val r = LiveHealthDriveRecorder(4, 1, 0)
        r.offer(status(true, 1), 0, stationary)
        // The absence receipt occupies an odd index and is discarded on overflow.
        r.offer(NativeReading(0x0C, null), 1, stationary)
        for (at in 2L..600L) r.offer(scalar(0x0C, 700.0), at, stationary)
        val event = r.snapshot().bookmarks.single()
        assertTrue(event.samplesDropped > 0)
        assertFalse(event.samples.any { it.pid == 0x0C && it.offsetMillis == 1L })
        val rpm = event.chartSeries().single { it.pid == 0x0C }
        assertTrue(rpm.points.all { it.breakBefore }, "Bounded traces cannot assert continuity through discarded receipts")
        assertTrue(rpm.points.all { point -> event.samples.any { it.pid == 0x0C && it.offsetMillis == point.offsetMillis && it.value == point.value } })
        val intact = LiveHealthDriveRecorder(4, 2, 0)
        intact.offer(status(true, 1), 0, stationary)
        intact.offer(scalar(0x0C, 700.0), 1, stationary)
        intact.offer(NativeReading(0x0C, null), 2, stationary)
        intact.offer(scalar(0x0C, 710.0), 3, stationary)
        assertTrue(intact.snapshot().bookmarks.single().chartSeries().single().points.last().breakBefore)
    }

    @Test fun zeroAndStaleMotionDoNotInventDrivingRecapEvidence() {
        val a = LiveHealthAccumulator(4, "7E8", 0, setOf(0x0C, 0x0D, 0x05), setOf(0x0C, 0x0D, 0x05))
        a.offer(scalar(0x0C, 700.0), 100)
        a.offer(scalar(0x0D, 0.0), 200)
        a.offer(scalar(0x05, 80.0), 300)
        a.offer(scalar(0x05, 81.0), 20_000)
        val review = a.snapshot.driveReview!!
        assertTrue(review.highlights.any { it.startsWith("No valid scalar readings have been observed in a moving") })
        assertFalse(review.conditions.any { it.condition == "Moving" })
        assertEquals(81.0, review.conditions.single { it.condition == "Unknown" }.signals.single { it.pid == 0x05 }.lastValue)
    }
}
