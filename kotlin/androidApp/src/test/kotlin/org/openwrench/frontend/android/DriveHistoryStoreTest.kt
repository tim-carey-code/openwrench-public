package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

internal fun historyTestSession(instance: Long = 1, completed: Boolean = false): LiveHealthSession {
    val pids = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    val a = LiveHealthAccumulator(7, "7E8", 0, pids, pids, instance)
    fun scalar(pid: Int, value: Double, time: Long) = a.offer(NativeReading(pid, DecodedReading.Scalar(value)), time)
    scalar(0x0C, 800.0, 100); scalar(0x0D, 0.0, 200)
    a.offer(NativeReading(0x03, DecodedReading.Structured(mapOf("system_1" to "closed loop", "system_2" to "engine off"))), 300)
    a.offer(NativeReading(0x01, DecodedReading.Structured(mapOf("mil_on" to "false", "dtc_count" to "0"))), 400)
    scalar(0x05, 70.0, 500)
    scalar(0x0C, 2_400.0, 20_000); scalar(0x0D, 30.0, 20_100)
    a.offer(NativeReading(0x01, DecodedReading.Structured(mapOf("mil_on" to "true", "dtc_count" to "1"))), 20_300)
    for (at in 21_000L..35_500L step 500) scalar(0x05, 85.0, at)
    if (completed) a.stop(36_000, "Health monitoring stopped by user")
    return a.snapshot
}

internal fun historyTestDrive(id: String = UUID.randomUUID().toString(), epoch: Long = 1_000_000): SavedDrive {
    val s = historyTestSession(completed = true)
    val view = LiveHealthDelivery.present(NativeUiState(connected = true, liveRunning = true,
        liveHealthEnabled = true, sessionGeneration = s.generation, liveHealthSession = s.copy(stoppedAtMillis = null)), 36_000)
    // This helper deliberately remains an old nine-series archive fixture. Current capture
    // is tested separately; changing it must not accidentally stop testing old file versions.
    val review = s.driveReview!!.present(s.driveReview.elapsedMillis).let { it.copy(
        series = it.series.filter { series -> series.pid in setOf(0x0C, 0x04, 0x10, 0x06, 0x07, 0x08, 0x09, 0x05, 0x0D) }) }
    return SavedDrive(id, epoch, epoch + 36_000, SavedDriveStatus.COMPLETED, s.endReason, "7E8", 7,
        view.reportText().substringBefore("Drive review:") + review.reportText(), review)
}

class DriveHistoryStoreTest {
    private fun directory() = Files.createTempDirectory("ow-drive-history-test").toFile()

    @Test fun bothSchemaFiveVariantsReopenWithoutRewritingAndMigrateLosslesslyOnSave() {
        val dir = directory()
        try {
            val original = historyTestDrive()
            val maf = original.review.series.single { it.pid == 0x10 }.copy(points = listOf(
                LiveHealthChartPoint(100, 3.2), LiveHealthChartPoint(1200, null, true),
                LiveHealthChartPoint(2400, 4.5, true)))
            val preview = original.copy(review = original.review.copy(series =
                original.review.series.filterNot { it.pid == 0x10 } + maf))
            val main = historyTestDrive().let { it.copy(origin = DriveEvidenceOrigin.HARDWARE,
                review = it.review.copy(series = it.review.series.filterNot { s -> s.pid == 0x10 })) }
            for (drive in listOf(main, preview)) {
                val root = Json.parseToJsonElement(DriveHistoryCodec.encode(drive)).jsonObject
                val legacy = JsonObject((if (drive == preview) root - "origin" else root) +
                    ("schema" to JsonPrimitive("openwrench.drive-history/5"))).toString()
                val file = File(dir, "drive-history-v1/${drive.id}.json")
                checkNotNull(file.parentFile).mkdirs(); file.writeText(legacy)
                val reopened = DriveHistoryStore(dir)
                assertTrue(reopened.list().any { it.id == drive.id })
                assertEquals(drive, reopened.read(drive.id))
                assertEquals(legacy, file.readText(), "Reading must not rewrite legacy evidence")
                reopened.upsert(reopened.read(drive.id))
                assertTrue(file.readText().contains("openwrench.drive-history/6"))
                assertEquals(drive, DriveHistoryStore(dir).read(drive.id))
            }
            assertEquals(maf, DriveHistoryStore(dir).read(preview.id).review.series.single { it.pid == 0x10 })
        } finally { dir.deleteRecursively() }
    }

    @Test fun schemaSixRequiresOriginAndSchemaFiveRejectsMalformedOrUnknownFields() {
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(historyTestDrive())).jsonObject
        assertFails { DriveHistoryCodec.decode(JsonObject(root - "origin").toString()) }
        val legacy = root + ("schema" to JsonPrimitive("openwrench.drive-history/5"))
        for (bad in listOf(JsonNull, JsonPrimitive("GUESSED"), JsonPrimitive(4))) {
            assertFails { DriveHistoryCodec.decode(JsonObject(legacy + ("origin" to bad)).toString()) }
        }
        assertFails { DriveHistoryCodec.decode(JsonObject((legacy - "origin") + ("extra" to JsonPrimitive(1))).toString()) }
    }

    @Test fun versionedRoundTripRetainsRecapChartsAndEventWindowsAcrossStoreInstances() {
        val dir = directory()
        try {
            val drive = historyTestDrive()
            assertEquals(drive, DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive)))
            DriveHistoryStore(dir).upsert(drive)
            val reopened = DriveHistoryStore(dir)
            assertEquals(drive.id, reopened.list().single().id)
            assertEquals(drive, reopened.read(drive.id))
            assertTrue(reopened.read(drive.id).review.series.any { it.points.isNotEmpty() })
            assertTrue(reopened.read(drive.id).review.bookmarks.single().samples.isNotEmpty())
            assertTrue(reopened.read(drive.id).copyText().startsWith("OPENWRENCH saved drive · historical"))
            assertFalse(reopened.read(drive.id).report.contains("0902"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun unfinishedTemporaryWriteDoesNotReplaceTheLastDurableCheckpoint() {
        val dir = directory()
        try {
            val drive = historyTestDrive()
            DriveHistoryStore(dir).upsert(drive)
            File(dir, "drive-history-v1/${drive.id}.json.tmp").writeText("{partial")
            assertEquals(drive, DriveHistoryStore(dir).read(drive.id))
            assertEquals(1, DriveHistoryStore(dir).list().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptAndFutureVersionArchivesArePreservedAndBlockAutomaticRotation() {
        val dir = directory()
        try {
            val drive = historyTestDrive()
            DriveHistoryStore(dir).upsert(drive)
            val file = File(dir, "drive-history-v1/${drive.id}.json")
            val future = file.readText().replace("openwrench.drive-history/6", "openwrench.drive-history/99")
            file.writeText(future)
            val store = DriveHistoryStore(dir)
            assertFails { store.list() }
            assertFails { store.upsert(historyTestDrive()) }
            assertEquals(future, file.readText())
            file.writeText("{broken")
            assertFails { DriveHistoryStore(dir).upsert(historyTestDrive()) }
            assertEquals("{broken", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun codecRejectsInvalidBoundsDuplicatesAndIdentityBeforeWriting() {
        val drive = historyTestDrive()
        val text = DriveHistoryCodec.encode(drive)
        assertFails { DriveHistoryCodec.decode(text.replaceFirst("\"schema\":", "\"id\":\"duplicate\",\"schema\":")) }
        assertFails { DriveHistoryCodec.decode("[".repeat(50) + "0" + "]".repeat(50)) }
        assertFails { DriveHistoryCodec.encode(drive.copy(id = "../outside")) }
        assertFails { DriveHistoryCodec.encode(drive.copy(sourceEcu = "unverified-device")) }
        val badSignal = drive.review.signals.first().copy(firstValue = Double.NaN)
        assertFails { DriveHistoryCodec.encode(drive.copy(review = drive.review.copy(signals = listOf(badSignal)))) }
        val badEvent = drive.review.bookmarks.single().copy(sessionGeneration = 123)
        assertFails { DriveHistoryCodec.encode(drive.copy(review = drive.review.copy(bookmarks = listOf(badEvent)))) }
        val badPoint = drive.review.series.first().copy(points = listOf(LiveHealthChartPoint(drive.review.elapsedMillis + 1, 1.0)))
        assertFails { DriveHistoryCodec.encode(drive.copy(review = drive.review.copy(series = listOf(badPoint)))) }
    }

    @Test fun retentionRotatesOldestKnownDrivesAndLeavesScanHistoryUntouched() {
        val dir = directory()
        try {
            val scans = File(dir, "scan-history-v1.json").apply { writeText("existing scan data") }
            val store = DriveHistoryStore(dir, maximumDrives = 2)
            val first = historyTestDrive(epoch = 1_000)
            val second = historyTestDrive(epoch = 2_000)
            val third = historyTestDrive(epoch = 3_000)
            store.upsert(first); store.upsert(second); store.upsert(third)
            assertEquals(listOf(third.id, second.id), store.list().map { it.id })
            assertFalse(File(dir, "drive-history-v1/${first.id}.json").exists())
            assertEquals("existing scan data", scans.readText())
            store.delete(second.id)
            assertEquals(third.id, DriveHistoryStore(dir).list().single().id)
        } finally { dir.deleteRecursively() }
    }

    @Test fun byteBudgetAndPartialRotationFailureCanRecoverWithoutStaleCatalog() {
        val dir = directory()
        try {
            val records = (1..3).map { historyTestDrive(epoch = it * 1_000L) }
            val initial = DriveHistoryStore(dir)
            records.forEach(initial::upsert)
            var deletes = 0
            var fail = true
            val store = DriveHistoryStore(dir, maximumDrives = 1, deleteFile = {
                deletes++
                if (fail && deletes == 2) false else it.delete()
            })
            val newest = historyTestDrive(epoch = 4_000)
            assertFails { store.upsert(newest) }
            fail = false
            store.upsert(newest)
            assertEquals(newest.id, store.list().single().id)
            assertEquals(newest, DriveHistoryStore(dir).read(newest.id))
            val size = DriveHistoryCodec.encode(newest).toByteArray().size.toLong()
            val bytes = DriveHistoryStore(dir, maximumBytes = size + 100)
            val next = historyTestDrive(epoch = 5_000)
            bytes.upsert(next)
            assertEquals(next.id, bytes.list().single().id)
        } finally { dir.deleteRecursively() }
    }

    @Test fun checkpointRecoveryFreezesIncompleteWarningWindowAsHistorical() {
        val drive = historyTestDrive()
        val bookmark = drive.review.bookmarks.single().copy(complete = false, postWindowMillis = 1_000,
            collectionStatus = "Collecting following receipts")
        val checkpoint = drive.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null,
            review = drive.review.copy(bookmarks = listOf(bookmark)))
        val recovered = DriveHistoryCodec.decode(DriveHistoryCodec.encode(checkpoint))
        val frozen = recovered.historicalReview().bookmarks.single()
        assertTrue(frozen.complete)
        assertEquals(1_000, frozen.postWindowMillis)
        assertTrue(frozen.collectionStatus.contains("interrupted"))
        assertTrue(recovered.copyText().contains("Partial saved window"))
        assertEquals(checkpoint.review.series, recovered.historicalReview().series)
    }

    @Test fun cachedRotationValidatesAllVictimsBeforeDeletingAnyHealthyArchive() {
        val dir = directory()
        try {
            val records = (1..3).map { historyTestDrive(epoch = it * 1_000L) }
            records.forEach { DriveHistoryStore(dir).upsert(it) }
            val store = DriveHistoryStore(dir, maximumDrives = 1)
            store.list() // Catalog was valid before a stored file became unreadable.
            val damaged = File(dir, "drive-history-v1/${records[1].id}.json")
            damaged.writeText("{broken")
            assertFails { store.upsert(historyTestDrive(epoch = 4_000)) }
            assertTrue(File(dir, "drive-history-v1/${records[0].id}.json").exists())
            assertEquals("{broken", damaged.readText())
        } finally { dir.deleteRecursively() }
    }
}
