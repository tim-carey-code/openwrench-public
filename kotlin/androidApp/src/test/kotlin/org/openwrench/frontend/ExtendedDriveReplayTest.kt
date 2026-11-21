package org.openwrench.frontend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.*

/** Synthetic bench data tests every scalar; the Legacy-inspired artifact keeps unknowns null. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtendedDriveReplayTest {
    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }
    private fun artifact() = DriveReplayImport.read(text("SYNTHETIC-Legacy-inspired-55-channel-5min-drive.json").byteInputStream()).single()
    private fun bench(): SavedDrive {
        val series = ObdDecoder.scalarPids.sorted().map { pid ->
            val m = ObdDecoder.catalog.getValue(pid)
            LiveHealthChartSeries(pid, m.name, m.unit, listOf(LiveHealthChartPoint(0, 0.0), LiveHealthChartPoint(1000, 1.0)))
        }
        return artifact().let { it.copy(review = it.review.copy(series = series, signals = emptyList(), retainedFromOffsetMillis = 0, retainedToOffsetMillis = 1000)) }
    }
    private fun json(d: SavedDrive) = Json.parseToJsonElement(DriveHistoryCodec.encode(d)).jsonObject
    private fun schema(root: JsonObject, version: Int) = JsonObject(root + ("schema" to JsonPrimitive("openwrench.drive-history/$version")))
    private fun changeReview(root: JsonObject, key: String, value: JsonElement) = JsonObject(root +
        ("review" to JsonObject(root.getValue("review").jsonObject + (key to value))))
    private fun zip(d: SavedDrive): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("drives/${d.id}.json"))
            zip.write(DriveHistoryCodec.encode(d).toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun everyScalarRoundTripsAndReplaysWithCanonicalUnitsAndTypes() {
        val drive = bench()
        val decoded = DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive))
        assertEquals(drive, decoded)
        assertEquals("openwrench.drive-history/7", json(drive).getValue("schema").jsonPrimitive.content)
        val rows = SavedDriveReplayState(SavedDrivePlayback.from(decoded), positionMillis = 1000).dashboardSignals()
        assertEquals(55, rows.size)
        assertEquals(ObdDecoder.scalarPids, rows.map { it.definition.pid!!.drop(2).toInt(16) }.toSet())
        rows.forEach { row ->
            val pid = row.definition.pid!!.drop(2).toInt(16)
            assertEquals(ObdDecoder.catalog.getValue(pid).unit, row.definition.unit)
            assertEquals(if (ObdDecoder.isIntegerScalar(pid)) SignalValue.Integer(1) else SignalValue.Number(1.0), row.value)
            assertContains(row.reason, "ECU capability support is unverified")
            assertContains(row.reason, "Synthetic"); assertContains(row.reason, "Not live")
        }
    }

    @Test fun pickerAndInspectorPresentationNeverClaimArchivedChannelsAreVehicleSupported() {
        val rows = SavedDriveReplayState(SavedDrivePlayback.from(artifact())).dashboardSignals()
        assertEquals("55 retained replay channels · ECU capability unverified", retainedReplayCatalogSummary(rows))
        assertEquals(55, graphPickerRows(rows, "", supportedOnly = false).size)
        assertTrue(graphPickerRows(rows, "", supportedOnly = true).isEmpty())
        assertEquals(55, filterSignals(rows, "", SignalSupport.UNKNOWN, null).size)
        assertTrue(filterSignals(rows, "", SignalSupport.SUPPORTED, null).isEmpty())
        rows.forEach { row ->
            assertEquals("Retained replay channel · ECU capability unverified", row.supportDescription)
            assertEquals(SignalSupport.UNKNOWN, row.capabilitySupport)
            assertFalse("supported" in row.supportDescription)
        }
        assertEquals(8, rows.count { it.availability == SignalAvailability.UNAVAILABLE })
        val hardware = rows.first().copy(retainedReplay = false)
        assertEquals("supported", hardware.supportDescription)
        assertEquals(SignalSupport.SUPPORTED, hardware.capabilitySupport)
    }

    @Test fun legacyVersionsKeepOriginalReadBoundsAndOldCompatibleExport() {
        val seed = DriveHistoryCodec.decode(text("legacy-synthetic-v6.json")); val root = json(seed)
        assertEquals("openwrench.drive-history/6", root.getValue("schema").jsonPrimitive.content)
        for (version in 1..6) {
            fun oldShape(input: JsonObject): JsonObject {
                var old = schema(input, version)
                if (version < 5) old = JsonObject(old - "origin")
                if (version == 1) old = JsonObject(old + ("review" to JsonObject(old.getValue("review").jsonObject - "assessment")))
                return old
            }
            val decoded = DriveHistoryCodec.decode(oldShape(root).toString())
            assertEquals(seed.review.series, decoded.review.series)
            assertEquals(if (version < 5) DriveEvidenceOrigin.UNKNOWN else DriveEvidenceOrigin.SYNTHETIC, decoded.origin)
            assertEquals("openwrench.drive-history/6", json(decoded).getValue("schema").jsonPrimitive.content)
            assertFails { DriveHistoryCodec.decode(oldShape(json(bench())).toString()) }
        }
        assertEquals(DriveEvidenceOrigin.UNKNOWN, DriveHistoryCodec.decode(JsonObject(schema(root, 5) - "origin").toString()).origin)
        assertFails { DriveHistoryCodec.decode(JsonObject(root - "origin").toString()) }
    }

    @Test fun extendedSummariesRoundTripWithoutWideningHealthConditions() {
        val d = bench()
        val summary = d.review.series.map { s -> LiveHealthDriveSignal(s.pid, s.name, s.unit, 2, 0.0, 1.0, 0.0, 1.0, 0, 1000) }
        val full = d.copy(review = d.review.copy(signals = summary))
        assertEquals(full, DriveHistoryCodec.decode(DriveHistoryCodec.encode(full)))
        assertFails { DriveHistoryCodec.encode(full.copy(review = full.review.copy(conditions = listOf(LiveHealthDriveCondition("Moving", 110, summary))))) }
        val runtime = summary.single { it.pid == 0x1F }.copy(lastValue = 1.5, maximum = 1.5)
        assertFails { DriveHistoryCodec.encode(full.copy(review = full.review.copy(signals = listOf(runtime)))) }
    }

    @Test fun aSingleGenericPidCannotEnterHealthConditionsOrBookmarkEvidence() {
        val d = bench(); val m = ObdDecoder.catalog.getValue(0x1F)
        val summary = LiveHealthDriveSignal(0x1F, m.name, m.unit, 2, 0.0, 1.0, 0.0, 1.0, 0, 1000)
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(conditions =
            listOf(LiveHealthDriveCondition("Moving", 2, listOf(summary)))))) }
        val row = JsonArray(listOf(0x1F, 2, 0, 1, 0, 1, 0, 1000).map { JsonPrimitive(it) })
        val conditions = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("Moving"), JsonArray(listOf(row))))))
        assertFails { DriveHistoryCodec.decode(changeReview(json(d), "conditions", conditions).toString()) }
        val sample = LiveHealthEventSample(0x1F, m.name, m.unit, 0, 1.0, "1 s", d.generation)
        val bookmark = LiveHealthWarningBookmark(1, 0, "Synthetic", "Synthetic", false, "Complete", true,
            0, 0, listOf(sample), 0, d.generation, d.review.instanceId)
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(bookmarks = listOf(bookmark)))) }
        val validSample = sample.copy(pid = 0x0C, name = ObdDecoder.catalog.getValue(0x0C).name, unit = "rpm")
        val valid = json(d.copy(review = d.review.copy(bookmarks = listOf(bookmark.copy(samples = listOf(validSample))))))
        val b = valid.getValue("review").jsonObject.getValue("bookmarks").jsonArray.single().jsonObject
        val badSample = JsonArray(listOf(JsonPrimitive(0x1F), JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive("1 s")))
        assertFails { DriveHistoryCodec.decode(changeReview(valid, "bookmarks", JsonArray(listOf(
            JsonObject(b + ("samples" to JsonArray(listOf(badSample))))))).toString()) }
    }

    @Test fun unknownStructuredAndDuplicateScalarIdsFailInBothDirections() {
        val d = bench(); val root = json(d)
        for (pid in (0..255).toSet() - ObdDecoder.scalarPids) {
            val bad = d.review.series.first().copy(pid = pid)
            assertFails("PID $pid") { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = listOf(bad)))) }
            val row = JsonArray(listOf(JsonPrimitive(pid), JsonPrimitive(2000), JsonArray(emptyList())))
            assertFails("PID $pid") { DriveHistoryCodec.decode(changeReview(root, "series", JsonArray(listOf(row))).toString()) }
            val scalar = JsonArray(listOf(pid, 1, 0, 0, 0, 0, 0, 0).map { JsonPrimitive(it) })
            assertFails { DriveHistoryCodec.decode(changeReview(root, "signals", JsonArray(listOf(scalar))).toString()) }
        }
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = listOf(d.review.series.first(), d.review.series.first())))) }
        assertFails { DriveHistoryCodec.decode(DriveHistoryCodec.encode(d).replaceFirst("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")) }
    }

    @Test fun extendedRequiresOriginAndRejectsUnknownFieldsVersionsAndInvalidValues() {
        val d = bench(); val root = json(d)
        assertFails { DriveHistoryCodec.decode(JsonObject(root - "origin").toString()) }
        assertFails { DriveHistoryCodec.decode(JsonObject(root + ("origin" to JsonPrimitive("LIVE"))).toString()) }
        assertFails { DriveHistoryCodec.decode(JsonObject(root + ("vehicle_support" to JsonPrimitive("guessed"))).toString()) }
        assertFails { DriveHistoryCodec.decode(schema(root, 8).toString()) }
        for ((pid, value) in listOf(0x1F to 1.5, 0x1F to -1.0, 0x1F to 65536.0, 0x30 to 256.0, 0x5C to Double.NaN, 0x5E to Double.POSITIVE_INFINITY)) {
            val s = d.review.series.single { it.pid == pid }.copy(points = listOf(LiveHealthChartPoint(0, value)))
            assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = listOf(s)))) }
        }
        val runtimeRow = JsonArray(listOf(JsonPrimitive(0x1F), JsonPrimitive(2000),
            JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1.25), JsonPrimitive(false)))))))
        assertFails { DriveHistoryCodec.decode(changeReview(root, "series", JsonArray(listOf(runtimeRow))).toString()) }
    }

    @Test fun perSeriesGlobalAndByteBudgetsNeverSilentlyTruncate() {
        val d = bench()
        val series = d.review.series.take(17).mapIndexed { index, s -> s.copy(points =
            List(if (index == 16) 800 else 1200) { LiveHealthChartPoint(it.toLong(), 0.0) }) }
        val bounded = d.copy(review = d.review.copy(series = series, retainedToOffsetMillis = 1199))
        assertEquals(20_000, DriveHistoryCodec.decode(DriveHistoryCodec.encode(bounded)).review.series.sumOf { it.points.size })
        val extra = series.dropLast(1) + series.last().copy(points = series.last().points + LiveHealthChartPoint(800, null))
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = extra))) }
        val encoded = json(bounded); val raw = encoded.getValue("review").jsonObject.getValue("series").jsonArray.toMutableList()
        val last = raw.last().jsonArray
        raw[raw.lastIndex] = JsonArray(last.dropLast(1) + JsonArray(last.last().jsonArray + JsonArray(listOf(JsonPrimitive(800), JsonNull, JsonPrimitive(false)))))
        assertFails { DriveHistoryCodec.decode(changeReview(encoded, "series", JsonArray(raw)).toString()) }
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = listOf(series.first().copy(points = List(1201) { LiveHealthChartPoint(it.toLong(), 0.0) }))))) }
        assertFails { DriveReplayImport.read((" ".repeat(DriveHistoryCodec.MAX_FILE_BYTES) + "{}").byteInputStream()) }
        for (points in listOf(listOf(LiveHealthChartPoint(-1, 0.0)), listOf(LiveHealthChartPoint(300001, 0.0)), listOf(LiveHealthChartPoint(2, 0.0), LiveHealthChartPoint(1, 0.0)))) {
            assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(series = listOf(series.first().copy(points = points))))) }
        }
    }

    @Test fun exactUserArtifactCoversCatalogWithTruthfulUnavailableChannelsAndGaps() {
        val d = artifact()
        assertEquals(DriveEvidenceOrigin.SYNTHETIC, d.origin); assertNull(d.sourceEcu); assertNull(d.review.assessment)
        assertEquals(300000L, d.review.elapsedMillis); assertContains(d.report, "NOT a verified ECU capability list")
        assertEquals(ObdDecoder.scalarPids, d.review.series.map { it.pid }.toSet()); assertEquals(47, d.review.signals.size)
        val nullOnly = setOf(0x21, 0x30, 0x31, 0x4D, 0x4E, 0xA6, 0x5B, 0xA5)
        d.review.series.forEach { s ->
            assertEquals((0..300).map { it * 1000L }, s.points.map { it.offsetMillis })
            assertNull(s.points[211].value); assertNull(s.points[212].value); assertTrue(s.points[213].breakBefore)
            if (s.pid in nullOnly) assertTrue(s.points.all { it.value == null }) else assertEquals(299, s.points.count { it.value != null })
            s.points.mapNotNull { it.value }.forEach { value -> assertRepresentable(s.pid, value) }
        }
        val replay = SavedDrivePlayback.from(d)
        fun rows(at: Long) = SavedDriveReplayState(replay, positionMillis = at).dashboardSignals()
        assertEquals(55, rows(300000).size)
        assertTrue(rows(211000).all { it.availability == SignalAvailability.UNAVAILABLE && it.value == null })
        assertEquals(47, rows(213000).count { it.availability == SignalAvailability.AVAILABLE })
        assertTrue(rows(300000).all { r -> r.trend.points.all { it.atMillis in 240000..300000 && it.observedAt == null } })
        assertEquals(SignalValue.Integer(1200), rows(300000).single { it.definition.id == "runtime" }.value)
        assertEquals(SignalValue.Number(0.0), rows(300000).single { it.definition.id == "speed" }.value)
    }

    @Test fun fictionalPressureVariantsDescribeOneQuantizedSystem() {
        val series = artifact().review.series.associateBy { it.pid }
        for (index in 0..300) {
            fun value(pid: Int) = series.getValue(pid).points[index].value
            if (index in 211..212) {
                assertTrue(listOf(0x0A, 0x22, 0x23, 0x59, 0x0B, 0x33).all { value(it) == null })
            } else {
                val absolute = value(0x59)!!; val gauge = value(0x23)!!
                assertTrue(abs(absolute - (gauge + value(0x33)!!)) <= 5.00001)
                assertTrue(abs(value(0x22)!! - (absolute - value(0x0B)!!)) <= .03951)
                assertTrue(abs(value(0x0A)!! - gauge) <= 1.50001)
            }
        }
    }

    @Test fun directZipAndCodecRoundTripsPreserveReceiptsWithoutHistoryWrites() {
        val d = artifact()
        assertEquals(d, DriveHistoryCodec.decode(DriveHistoryCodec.encode(d)))
        assertEquals(d, DriveReplayImport.read(zip(d).inputStream()).single())
        val dir = Files.createTempDirectory("ow-extended-drive-test").toFile()
        try {
            val store = DriveHistoryStore(dir); val old = historyTestDrive(); store.upsert(old)
            val before = File(dir, "drive-history-v1/${old.id}.json").readBytes()
            SavedDrivePlayback.from(DriveReplayImport.read(zip(d).inputStream()).single())
            assertContentEquals(before, File(dir, "drive-history-v1/${old.id}.json").readBytes())
            assertEquals(listOf(old.id), store.list().map { it.id })
        } finally { dir.deleteRecursively() }
    }

    @Test fun fiveMinuteLoopPauseRestartBackgroundAndCloseKeepSyntheticTiming() = runTest {
        val p = SavedDriveReplayPlayer(backgroundScope, object : FrontendClock {
            override fun elapsedMillis() = testScheduler.currentTime
            override fun utc() = error("Replay must not invent UTC observations")
        })
        p.load(SavedDrivePlayback.from(artifact())); assertEquals(301000L, p.state.value.recording!!.durationMillis)
        p.play(); runCurrent(); advanceTimeBy(300000); runCurrent()
        assertEquals(0L, p.state.value.loop); assertEquals(300000L, p.state.value.positionMillis)
        advanceTimeBy(1000); runCurrent(); assertEquals(1L, p.state.value.loop); assertEquals(0L, p.state.value.positionMillis)
        assertTrue(p.state.value.dashboardSignals().all { it.trend.points.all { point -> point.atMillis == 0L } })
        p.setActive(false); val frozen = p.state.value; advanceTimeBy(5000); p.setActive(true); runCurrent()
        assertEquals(frozen, p.state.value); p.restart(); assertEquals(ReplayPhase.PAUSED, p.state.value.phase)
        p.close(); assertTrue(p.state.value.dashboardSignals().isEmpty())
    }

    @Test fun nullOnlyAndLateChannelsStayUnavailableWithoutBlockingOtherSeries() {
        val d = bench(); val nullSeries = d.review.series.first().copy(points = listOf(LiveHealthChartPoint(0, null), LiveHealthChartPoint(1000, null)))
        val late = d.review.series[1].copy(points = listOf(LiveHealthChartPoint(500, 1.0), LiveHealthChartPoint(1000, 2.0)))
        val mixed = d.copy(review = d.review.copy(series = listOf(nullSeries, late)))
        val initial = SavedDriveReplayState(SavedDrivePlayback.from(mixed)).dashboardSignals()
        assertEquals(SignalAvailability.UNAVAILABLE, initial.first().availability)
        assertEquals(SignalAvailability.NOT_SAMPLED, initial.last().availability)
        assertFails { SavedDrivePlayback.from(d.copy(review = d.review.copy(series = listOf(nullSeries)))) }
    }

    /** Invert standard scalar encoding, then check the actual shared decoder. */
    private fun assertRepresentable(pid: Int, value: Double) {
        val percent = setOf(0x04,0x11,0x2E,0x2F,0x45,0x47,0x48,0x49,0x4A,0x4B,0x4C,0x52,0x5A,0x5B,0x8D)
        val (scale, offset, bytes) = when (pid) {
            in percent -> Triple(100.0 / 255, 0.0, 1)
            in setOf(0x05,0x0F,0x46,0x5C) -> Triple(1.0, -40.0, 1)
            in setOf(0x06,0x07,0x08,0x09) -> Triple(100.0 / 128, -100.0, 1)
            in setOf(0x61,0x62,0x8E) -> Triple(1.0, -125.0, 1)
            0x0A -> Triple(3.0, 0.0, 1)
            0x0B,0x0D,0x33 -> Triple(1.0, 0.0, 1)
            0x0C -> Triple(.25, 0.0, 2)
            0x0E -> Triple(.5, -64.0, 1)
            0x10 -> Triple(.01, 0.0, 2)
            0x30 -> Triple(1.0, 0.0, 1)
            0x1F,0x21,0x31,0x4D,0x4E,0x63 -> Triple(1.0, 0.0, 2)
            0x22 -> Triple(.079, 0.0, 2)
            0x23,0x59 -> Triple(10.0, 0.0, 2)
            0x32 -> Triple(.25, 0.0, 2)
            0x3C -> Triple(.1, -40.0, 2)
            0x42,0xA4 -> Triple(.001, 0.0, 2)
            0x43 -> Triple(100.0 / 255, 0.0, 2)
            0x44 -> Triple(2.0 / 65536, 0.0, 2)
            0x50 -> Triple(10.0, 0.0, 1)
            0x53 -> Triple(.005, 0.0, 2)
            0x5D -> Triple(1.0 / 128, -210.0, 2)
            0x5E -> Triple(.05, 0.0, 2)
            0xA5 -> Triple(.5, 0.0, 1)
            0xA6 -> Triple(.1, 0.0, 4)
            else -> error("Missing scalar fixture test mapping $pid")
        }
        val raw = ((value - offset) / scale).roundToLong()
        val encoded = if (pid == 0x32) raw and 0xffff else raw
        assertTrue(encoded in 0 until (1L shl (bytes * 8)), "PID $pid raw range")
        val reply = "41" + pid.toString(16).padStart(2, '0') + encoded.toString(16).padStart(bytes * 2, '0')
        val actual = assertIs<DecodedReading.Scalar>(ObdDecoder.decode(pid, reply))
        assertTrue(abs(value - actual.value) < 1e-8, "PID $pid: $value != ${actual.value}")
    }
}
