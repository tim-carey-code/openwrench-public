package org.openwrench.frontend.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.nativecore.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Synthetic/scripted evidence only. No adapter discovery or physical transport. */
internal fun caseScanFixture(): SavedScan {
    val rows = listOf(NativeReading(1, ObdDecoder.decode(1, "410180070000"), "7E8"),
        NativeReading(5, DecodedReading.Scalar(90.0), "7E8"), NativeReading(12, null))
    val c = ScanCapture(UUID.randomUUID().toString(), ScanOrigin.HARDWARE, UUID.randomUUID().toString(), "7E8",
        1_000, 2_000, 950, 2_001, rows.map { it.pid }.toSet(), rows,
        listOf(DiagnosticCodeRead(DtcService.STORED, DiagnosticReplyStatus.AVAILABLE, listOf("P0171"), "7E8", "Synthetic stored result"),
            DiagnosticCodeRead(DtcService.PENDING, DiagnosticReplyStatus.NO_DATA),
            DiagnosticCodeRead(DtcService.PERMANENT, DiagnosticReplyStatus.UNSUPPORTED, sourceEcu = "7E8")),
        FreezeFrameRead(DiagnosticReplyStatus.AVAILABLE, "P0171",
            listOf(NativeReading(8, DecodedReading.Scalar(4.0), "7E8"), NativeReading(9, DecodedReading.Scalar(14.0), "7E8"),
                NativeReading(5, null, "7E8")), "7E8", setOf(2, 5, 8, 9), "Synthetic frame, original capture time unknown",
            mapOf(5 to DiagnosticReplyStatus.UNAVAILABLE)))
    return ScanHistoryCodec.newEntry("OPENWRENCH read-only diagnostic scan\nSYNTHETIC TEST ONLY\nVIN omitted.\n", null, 3_000, c)
}
internal fun caseDriveFixture(): SavedDrive {
    val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
    repeat(11) {
        val at = 100 + it * 3_000L
        fun offer(pid: Int, value: Double, offset: Long) = r.offer(NativeReading(pid, DecodedReading.Scalar(value)), at + offset)
        offer(12, 900.0, 0); offer(13, 0.0, 1); offer(4, 30.0, 2); offer(5, 90.0, 3)
        r.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at + 4)
        offer(6, 5.0, 5); offer(7, 15.0, 6)
    }
    r.stop(36_000)
    val original = historyTestDrive()
    val review = original.review.copy(assessment = r.snapshot())
    return original.copy(report = "OPENWRENCH live health monitoring\nSYNTHETIC TEST ONLY\n" + review.reportText(), review = review, origin = DriveEvidenceOrigin.SYNTHETIC)
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticCaseTest {
    @Test fun exactTypedScanRoundTripAcrossRestartIncludesBankTwoFrameAndMissingReadings() {
        val dir = Files.createTempDirectory("ow-typed-scan").toFile()
        try {
            val scan = caseScanFixture(); ScanHistoryStore(dir).add(scan)
            val reopened = ScanHistoryStore(dir).load().single()
            assertEquals(scan, reopened)
            assertEquals(listOf(8, 9, 5), reopened.capture!!.frameZero!!.readings.map { it.pid })
            assertEquals(DiagnosticReplyStatus.UNAVAILABLE, reopened.capture!!.frameZero!!.unavailablePids[5])
            assertNull(reopened.capture!!.readings.single { it.pid == 12 }.value)
            assertEquals(scan.capture!!.services, reopened.capture!!.services)
        } finally { dir.deleteRecursively() }
    }
    @Test fun emptyNoDataUnsupportedUnavailableUncapturedAndMissingTriggerRemainDistinct() {
        val scan = caseScanFixture(); val c = scan.capture!!
        for (status in DiagnosticReplyStatus.entries) {
            val changed = scan.copy(capture = c.copy(services = listOf(DiagnosticCodeRead(DtcService.STORED, status)),
                frameZero = FreezeFrameRead(status, advertisedPids = if (status == DiagnosticReplyStatus.AVAILABLE) setOf(2) else emptySet())))
            val reopened = ScanHistoryCodec.decode(ScanHistoryCodec.encode(listOf(changed))).single()
            assertEquals(changed, reopened)
            val report = DiagnosticCaseBuilder.create(reopened, caseDriveFixture(), true).reportText()
            assertContains(report, "Stored (03): $status")
            assertContains(report, "Pending (07): NOT CAPTURED")
            assertContains(report, "trigger not returned / unknown")
            if (status == DiagnosticReplyStatus.AVAILABLE) assertContains(report, "Available response: zero codes returned")
        }
        val uncaptured = scan.copy(capture = c.copy(services = emptyList(), frameZero = null))
        assertContains(DiagnosticCaseBuilder.create(uncaptured, caseDriveFixture(), true).reportText(), "Frame zero: NOT CAPTURED")
    }
    @Test fun legacyReportsRemainExactAndCannotProvideMachineRelationships() {
        val scan = caseScanFixture().copy(capture = null)
        val v2 = Json.parseToJsonElement(ScanHistoryCodec.encode(listOf(scan))).jsonObject
        val v1 = JsonObject(v2 + ("schema_version" to JsonPrimitive("openwrench.scan-history/1")) +
            ("entries" to JsonArray(v2.getValue("entries").jsonArray.map { JsonObject(it.jsonObject - "capture") })))
        val reopened = ScanHistoryCodec.decode(v1.toString()).single()
        assertEquals(scan, reopened); assertEquals(scan.report, reopened.report); assertNull(reopened.capture)
        assertEquals(scan.copyText(), reopened.copyText())
        assertFails { DiagnosticCaseBuilder.create(reopened, caseDriveFixture(), true) }
        assertEquals(reopened, ScanHistoryCodec.decode(ScanHistoryCodec.encode(listOf(reopened))).single())
    }
    @Test fun explicitConfirmationCompletionAndSourceLimitsAreEnforced() {
        val s = caseScanFixture(); val d = caseDriveFixture()
        assertFails { DiagnosticCaseBuilder.create(s, d, false) }
        assertFails { DiagnosticCaseBuilder.create(s, d.copy(status = SavedDriveStatus.CHECKPOINT), true) }
        assertFails { DiagnosticCaseBuilder.create(s, d.copy(status = SavedDriveStatus.INTERRUPTED), true) }
        for (drive in listOf(d, d.copy(sourceEcu = null), d.copy(sourceEcu = "7E9", origin = DriveEvidenceOrigin.HARDWARE), d.copy(origin = DriveEvidenceOrigin.UNKNOWN)))
            assertContains(DiagnosticCaseBuilder.create(s, drive, true).reportText(), "Observations remain separately attributed")
        val matched = DiagnosticCaseBuilder.create(s, d.copy(origin = DriveEvidenceOrigin.HARDWARE), true)
        assertContains(matched.reportText(), "does not verify the same ECU, fault or event")
        assertContains(matched.reportText(), "Original ECU capture time: unknown")
        for (source in listOf("7E9", null)) {
            val frame = s.capture!!.frameZero!!
            val mixed = s.copy(capture = s.capture.copy(frameZero = frame.copy(readings = frame.readings.map {
                if (it.pid == 8) it.copy(sourceEcu = source) else it
            })))
            assertContains(DiagnosticCaseBuilder.create(mixed, d.copy(origin = DriveEvidenceOrigin.HARDWARE), true).reportText(),
                "Observations remain separately attributed")
        }
        val imported = s.copy(capture = s.capture!!.copy(origin = ScanOrigin.IMPORTED, sessionId = null, sourceEcu = null,
            services = emptyList(), frameZero = null, readings = s.capture!!.readings.map { it.copy(sourceEcu = null) }))
        assertContains(DiagnosticCaseBuilder.create(imported, d, true).reportText(), "synthetic or imported")
    }
    @Test fun caseSurvivesSourceDeletionRotationAndRestartWithExactAssessmentAndPolicyEvidence() {
        val dir = Files.createTempDirectory("ow-case-retention").toFile()
        try {
            val s = caseScanFixture().copy(vin = "AAAAAAAAAAAAAAAAA"); val d = caseDriveFixture()
            val scans = ScanHistoryStore(dir); val drives = DriveHistoryStore(dir, maximumDrives = 1)
            scans.add(s); drives.upsert(d)
            val c = DiagnosticCaseBuilder.create(s, d, true, 5_000)
            assertTrue(c.drive.review.assessment!!.findings.flatMap { it.fuelIntervals }.isNotEmpty())
            DiagnosticCaseStore(dir).add(c)
            val text = c.reportText(); val evidence = c.drive.review.assessment
            scans.delete(s.id); drives.upsert(historyTestDrive(epoch = d.startedAtEpochMillis + 1_000_000))
            assertFails { drives.read(d.id) }
            val reopened = DiagnosticCaseStore(dir).load().single()
            assertEquals(c, reopened); assertEquals(text, reopened.reportText()); assertEquals(evidence, reopened.drive.review.assessment)
            assertNull(reopened.scan.vin); assertFalse(text.contains("AAAAAAAAAAAAAAAAA"))
            assertEquals(d.report, reopened.drive.report)
            assertEquals(d.review.assessment!!.findings.flatMap { it.fuelIntervals }.map { it.policyVersion },
                reopened.drive.review.assessment!!.findings.flatMap { it.fuelIntervals }.map { it.policyVersion })
            assertEquals(emptyList(), DiagnosticCaseStore(dir).delete(c.id)); assertEquals(1, drives.list().size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun typedValidationRejectsInconsistentDuplicateNonfiniteUnknownAndUnboundedEvidence() {
        val s = caseScanFixture(); val c = s.capture!!; val f = c.frameZero!!
        val invalid = listOf(c.copy(schemaVersion = 99), c.copy(captureId = "bad"), c.copy(sourceEcu = "adapter"),
            c.copy(services = c.services + c.services.first()),
            c.copy(services = listOf(DiagnosticCodeRead(DtcService.STORED, DiagnosticReplyStatus.NO_DATA, listOf("P0171")))),
            c.copy(services = listOf(DiagnosticCodeRead(DtcService.STORED, DiagnosticReplyStatus.AVAILABLE, listOf("P9999")))),
            c.copy(readings = c.readings + c.readings.first()), c.copy(eligiblePids = setOf(255)),
            c.copy(readings = c.readings.map { if (it.pid == 5) it.copy(value = DecodedReading.Scalar(Double.NaN)) else it }),
            c.copy(frameZero = f.copy(unavailablePids = emptyMap())), c.copy(frameZero = f.copy(status = DiagnosticReplyStatus.NO_DATA)),
            c.copy(frameZero = f.copy(readings = f.readings + NativeReading(255, null))),
            c.copy(frameZero = f.copy(unavailablePids = mapOf(5 to DiagnosticReplyStatus.AVAILABLE))))
        invalid.forEach { assertFails { ScanHistoryCodec.encode(listOf(s.copy(capture = it))) } }
        val encoded = ScanHistoryCodec.encode(listOf(s))
        assertFails { ScanHistoryCodec.decode(encoded.replaceFirst("\"version\": 1", "\"version\": 99")) }
        assertFails { ScanHistoryCodec.decode(encoded.replaceFirst("\"capture\":", "\"capture\":null,\"capture\":")) }
        val d = caseDriveFixture(); val a = d.review.assessment!!
        val bad = d.copy(review = d.review.copy(assessment = a.copy(findings = a.findings.map { finding ->
            finding.copy(fuelIntervals = finding.fuelIntervals.map { it.copy(policyVersion = 99) }) })))
        assertFails { DiagnosticCaseBuilder.create(s, bad, true) }
    }
    @Test fun corruptOversizedAndFutureFilesArePreservedAndBlockMutation() {
        val dir = Files.createTempDirectory("ow-case-bad").toFile()
        try {
            val c = DiagnosticCaseBuilder.create(caseScanFixture(), caseDriveFixture(), true)
            val file = File(dir, "diagnostic-cases-v1.json")
            for (bad in listOf("{broken", DiagnosticCaseCodec.encode(listOf(c)).replace("openwrench.diagnostic-cases/1", "openwrench.diagnostic-cases/99"))) {
                file.writeText(bad)
                assertFails { DiagnosticCaseStore(dir).load() }; assertFails { DiagnosticCaseStore(dir).add(c) }
                assertFails { DiagnosticCaseStore(dir).delete(c.id) }; assertEquals(bad, file.readText())
            }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(DiagnosticCaseCodec.MAX_BYTES + 1L) }
            assertFails { DiagnosticCaseStore(dir).add(c) }; assertEquals(DiagnosticCaseCodec.MAX_BYTES + 1L, file.length())
            file.delete()
            repeat(DiagnosticCaseCodec.MAX_ENTRIES) { DiagnosticCaseStore(dir).add(c.copy(id = UUID.randomUUID().toString())) }
            assertFails { DiagnosticCaseStore(dir).add(c) }; assertEquals(DiagnosticCaseCodec.MAX_ENTRIES, DiagnosticCaseStore(dir).load().size)
            val scanFile = File(dir, "scan-history-v1.json")
            scanFile.writeText(ScanHistoryCodec.encode(listOf(c.scan)).replace("openwrench.scan-history/2", "openwrench.scan-history/99"))
            val bytes = scanFile.readBytes(); assertFails { ScanHistoryStore(dir).add(c.scan) }; assertContentEquals(bytes, scanFile.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun canceledPreparationAndStaleSaveCannotAttachAnotherSelectionOrRecreateDeletedCase() = runTest {
        val dir = Files.createTempDirectory("ow-case-race").toFile()
        try {
            val scans = ScanHistoryStore(dir); val drives = DriveHistoryStore(dir); val cases = DiagnosticCaseStore(dir)
            val s = caseScanFixture(); val d = caseDriveFixture(); scans.add(s); drives.upsert(d)
            val history = DiagnosticCaseCoordinator(cases, scans, drives, backgroundScope, StandardTestDispatcher(testScheduler)) { 9_000 }
            runCurrent(); history.begin(); runCurrent()
            history.prepare(s.id, d.id, true); history.cancel(); runCurrent(); assertNull(history.state.value.preview)
            history.begin(); runCurrent(); history.prepare(s.id, d.id, false); runCurrent(); assertNull(history.state.value.preview)
            history.prepare(s.id, d.id, true); runCurrent(); val preview = history.state.value.preview!!
            history.save("stale id"); runCurrent(); assertTrue(cases.load().isEmpty())
            history.save(preview.id); history.save(preview.id); history.cancel(); runCurrent()
            assertNull(history.state.value.opened)
            assertEquals(1, cases.load().size)
            history.delete(preview.id); history.save(preview.id); runCurrent(); assertTrue(cases.load().isEmpty())
            history.open(preview.id); history.cancel(); runCurrent(); assertNull(history.state.value.opened)
            // A selected source deleted before preparation cannot be replaced by another record.
            history.begin(); runCurrent(); scans.delete(s.id); history.prepare(s.id, d.id, true); runCurrent()
            assertNull(history.state.value.preview); assertNotNull(history.state.value.error)
        } finally { dir.deleteRecursively() }
    }
    @Test fun leavingDuringCaseBeginOrOpenInvalidatesOnlyPendingPresentation() = runTest {
        val dir = Files.createTempDirectory("ow-case-back-pending").toFile()
        try {
            val scans = ScanHistoryStore(dir); val drives = DriveHistoryStore(dir); val cases = DiagnosticCaseStore(dir)
            val scan = caseScanFixture(); val drive = caseDriveFixture(); scans.add(scan); drives.upsert(drive)
            val saved = DiagnosticCaseBuilder.create(scan, drive, true, 9_000); cases.add(saved)
            val history = DiagnosticCaseCoordinator(cases, scans, drives, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent()
            history.begin(); assertTrue(history.state.value.busy); history.cancel(); runCurrent()
            assertFalse(history.state.value.selecting); assertNull(history.state.value.preview)
            history.open(saved.id); assertTrue(history.state.value.busy); history.cancel(); runCurrent()
            assertNull(history.state.value.opened)
            assertEquals(listOf(saved), cases.load())
            assertEquals(scan, scans.load().single()); assertEquals(drive, drives.read(drive.id))
            history.open(saved.id); runCurrent(); assertEquals(saved, history.state.value.opened)
        } finally { dir.deleteRecursively() }
    }

    @Test fun admittedScanSaveSurvivesUiObserverDisposalAndSerializesDelete() = runTest {
        val dir = Files.createTempDirectory("ow-scan-race").toFile()
        try {
            val store = ScanHistoryStore(dir)
            val history = ScanHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent(); val scan = caseScanFixture()
            history.save(scan)
            assertTrue(history.state.value.busy)
            history.delete(scan.id) // Mutation admission rejects delete while saving.
            runCurrent(); assertEquals(listOf(scan), store.load())
            history.delete(scan.id)
            assertTrue(history.state.value.busy)
            history.save(scan); runCurrent(); assertTrue(store.load().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectedTypedSaveSurfacesFailureWithoutEscapingUiAdmission() = runTest {
        val dir = Files.createTempDirectory("ow-scan-validation-error").toFile()
        try {
            val store = ScanHistoryStore(dir)
            val history = ScanHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent(); val scan = caseScanFixture()
            history.save(scan.report, null, scan.capture!!.copy(captureId = "invalid"))
            runCurrent(); assertNotNull(history.state.value.error); assertFalse(history.state.value.busy)
            assertTrue(store.load().isEmpty())
            val repeated = scan.capture.copy(services = listOf(DiagnosticCodeRead(DtcService.STORED,
                DiagnosticReplyStatus.AVAILABLE, listOf("P0171", "P0171"), "7E8")))
            history.save(scan.report, null, repeated); runCurrent()
            assertNull(history.state.value.error)
            assertEquals(listOf("P0171", "P0171"), store.load().single().capture!!.services.single().codes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun schemaFourDriveRetainsPackThreeIntervalsButLeavesUnrecordedOriginUnknown() {
        val d = caseDriveFixture()
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(d)).jsonObject
        val old = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/4")))
        val reopened = DriveHistoryCodec.decode(old.toString())
        assertEquals(d.copy(origin = DriveEvidenceOrigin.UNKNOWN), reopened)
        assertEquals(d.review.assessment, reopened.review.assessment)
    }

    @Test fun maximumRetainedPairedEvidenceIsCopiedExactlyWithBoundedClipboardReport() {
        val original = caseDriveFixture()
        val findings = (1..2).map { bank ->
            val intervals = (0 until FuelCorrectionPolicy.MAX_INTERVALS).map { interval ->
                val start = interval * 70_000L + 100
                val pairs = (0 until FuelCorrectionPolicy.MAX_PAIRS).map { receipt ->
                    val at = start + receipt * 200L
                    FuelCorrectionPair(FuelObservation(at + 4, 5.0), FuelObservation(at + 5, 15.0),
                        FuelObservation(at, 900.0), FuelObservation(at + 1, 30.0), FuelObservation(at + 2, 90.0),
                        FuelObservation(at + 3, 0.0), at + 3)
                }
                fun bounds(obs: FuelObservation) = FuelCorrectionBounds(obs, obs)
                FuelCorrectionInterval(1, bank, "Stationary with engine running", FuelCorrectionRecorder.CLOSED,
                    FuelCorrectionRecorder.CLOSED, FuelCorrectionPattern.REINFORCING, pairs,
                    FuelCorrectionContextBounds(bounds(pairs.first().rpm), bounds(pairs.first().load), bounds(pairs.first().coolant)))
            }
            DriveHealthFinding("fuel-pattern-bank-$bank", "Synthetic maximum bank $bank", DriveHealthDisposition.REVIEW,
                "Original synthetic finding", listOf("Retained paired measurements"), listOf("Measure the documented conditions again"),
                listOf("Cause unknown"), intervals.first().firstOffsetMillis, intervals.last().lastOffsetMillis,
                intervals.sumOf { it.pairs.size }.toLong(), "Stationary with engine running", fuelIntervals = intervals)
        }
        val assessment = DriveHealthAssessment("standard-obd-drive", "3", 560_000, findings, emptyList(), emptyList())
        val drive = original.copy(review = original.review.copy(elapsedMillis = 560_000, assessment = assessment))
        val c = DiagnosticCaseBuilder.create(caseScanFixture(), drive, true)
        val reopened = DiagnosticCaseCodec.decode(DiagnosticCaseCodec.encode(listOf(c))).single()
        assertEquals(assessment, reopened.drive.review.assessment)
        assertEquals(4096, reopened.drive.review.assessment!!.findings.sumOf { f -> f.fuelIntervals.sumOf { it.pairs.size } })
        assertTrue(c.reportText().toByteArray().size < 200_000)
        assertContains(c.reportText(), "Report shows the first three and last three")
    }

    @Test fun generatesClearlyLabeledEmulatorFixturesWithoutAnyPhysicalAdapter() {
        // Build outputs only; usable through adb run-as in the documented visual-review procedure.
        val dir = File("build/diagnostic-case-fixtures").apply { deleteRecursively(); mkdirs() }
        val scan = caseScanFixture().let { s -> s.copy(capture = s.capture!!.copy(origin = ScanOrigin.IMPORTED_SIMULATOR,
            sessionId = null, sourceEcu = null, readings = s.capture!!.readings.map { it.copy(sourceEcu = null) }, services = emptyList(), frameZero = null)) }
        assertFalse(ScanHistoryCodec.encode(listOf(scan)).contains("real_ecu"))
        val drive = caseDriveFixture()
        File(dir, "scan-history-v1.json").writeText(ScanHistoryCodec.encode(listOf(scan, caseScanFixture().copy(capture = null))))
        val populated = caseScanFixture().let { s -> s.copy(capture = s.capture!!.copy(origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        // Keep typed frame evidence for emulator inspection, with an explicit synthetic banner in the report.
        val c = DiagnosticCaseBuilder.create(populated, drive, true)
        File(dir, "diagnostic-cases-v1.json").writeText(DiagnosticCaseCodec.encode(listOf(c)))
        File(dir, "${drive.id}.json").writeText(DriveHistoryCodec.encode(drive))
    }
}
