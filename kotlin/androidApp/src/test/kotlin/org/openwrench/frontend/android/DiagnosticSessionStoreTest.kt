package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.*

class DiagnosticSessionStoreTest {
    private fun uuid() = UUID.randomUUID().toString()
    private fun record(): DiagnosisRecord {
        val start = 1_000_000L
        return DiagnosisRecord(uuid(), start, start, DiagnosisStatus.RECORDING,
            listOf(DiagnosisSegment(uuid(), 7, "7E8", start, 1234)))
    }
    private fun rpm(at: Long = 100) = DiagnosisSample(at, 0x0C, 800.0, null, "rpm", "AVAILABLE")
    private fun withStore(action: (File, DiagnosticSessionStore) -> Unit) {
        val dir = Files.createTempDirectory("ow-diagnosis-test").toFile()
        try { action(dir, DiagnosticSessionStore(dir)) } finally { dir.deleteRecursively() }
    }
    private fun entries(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return result
    }

    @Test fun retainedSchemaFiveDrivesSurviveUpgradeAndExportWithoutSourceHistory() = withStore { dir, _ ->
        for (withOrigin in listOf(false, true)) {
            val drive = historyTestDrive().copy(origin = if (withOrigin) DriveEvidenceOrigin.HARDWARE else DriveEvidenceOrigin.UNKNOWN)
            val r = record().let { it.copy(status = DiagnosisStatus.COMPLETED,
                segments = it.segments.map { s -> s.copy(driveId = drive.id, endedAtOffsetMillis = 36_000) }) }
            val store = DiagnosticSessionStore(dir, driveProvider = { drive })
            store.create(r); store.save(r)
            val file = File(dir, "diagnoses-v1/${r.id}/drives/${drive.id}.json")
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val legacy = JsonObject((if (withOrigin) root else root - "origin") +
                ("schema" to JsonPrimitive("openwrench.drive-history/5"))).toString()
            file.writeText(legacy)
            val upgraded = DiagnosticSessionStore(dir) // Original drive history has rotated away.
            assertEquals(r, upgraded.read(r.id))
            assertEquals(drive, upgraded.readDrive(r.id, drive.id))
            val out = ByteArrayOutputStream(); upgraded.exportZip(r.id, out)
            val zip = entries(out.toByteArray())
            assertTrue(zip.keys.any { it.startsWith("charts/") })
            assertEquals(legacy, file.readText())
            assertEquals(drive, DiagnosticSessionStore(dir).readDrive(r.id, drive.id))
        }
    }

    @Test fun completedDiagnosisUsesTerminalReserveToRetainLargeFinalDriveBeforeRotation() = withStore { dir, _ ->
        val base = historyTestDrive()
        val checkpoint = base.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null)
        val terminal = base.copy(report = base.report + "\nFINAL_EVIDENCE\n" +
            "x".repeat(60_000 - DriveHistoryCodec.encode(base).toByteArray().size - 64))
        var source: SavedDrive? = checkpoint
        val store = DiagnosticSessionStore(dir, maximumSessionBytes = 65_536, driveProvider = { source })
        val r = record().let { it.copy(segments = it.segments.map { part -> part.copy(driveId = base.id) }) }
        store.create(r); store.save(r)
        val file = File(dir, "diagnoses-v1/${r.id}/drives/${base.id}.json")
        assertEquals(checkpoint, DriveHistoryCodec.decode(file.readText()))
        source = terminal
        assertTrue(DriveHistoryCodec.encode(terminal).toByteArray().size + 65_536 / 4 > 65_536,
            "The old unconditional metadata reserve rejects this otherwise fitting terminal archive")
        val finished = r.copy(status = DiagnosisStatus.COMPLETED,
            segments = r.segments.map { it.copy(endedAtOffsetMillis = 36_000) })
        store.save(finished)
        assertEquals(terminal, DriveHistoryCodec.decode(file.readText()))
        assertTrue(File(dir, "diagnoses-v1/${r.id}").walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 65_536)
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        assertEquals(terminal, DriveHistoryCodec.decode(entries(out.toByteArray()).getValue("drives/${base.id}.json")))
        source = null // Legacy standalone history rotates only after terminal evidence was retained.
        val reopened = DiagnosticSessionStore(dir, maximumSessionBytes = 65_536)
        assertEquals(terminal, reopened.readDrive(r.id, base.id))
        assertNull(reopened.readDriveEvidence(r.id, base.id).warning)
        val after = ByteArrayOutputStream(); reopened.exportZip(r.id, after)
        assertEquals(entries(out.toByteArray()), entries(after.toByteArray()))
    }

    @Test fun oversizedTerminalEvidenceExportsLatestWithWarningAndRetainsHonestCheckpointAfterRotation() = withStore { dir, _ ->
        val base = historyTestDrive()
        val checkpoint = base.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null)
        val terminal = base.copy(report = "OPENWRENCH live health monitoring\nFINAL_TOO_LARGE\n" + "x".repeat(70_000))
        var source: SavedDrive? = checkpoint
        val store = DiagnosticSessionStore(dir, maximumSessionBytes = 65_536, driveProvider = { source })
        val r = record().let { it.copy(segments = it.segments.map { part -> part.copy(driveId = base.id) }) }
        store.create(r); store.appendSamples(r.id, r.segments.single().id, listOf(rpm())); store.save(r.copy(segments = r.segments.map { it.copy(sampleCount = 1) }))
        val file = File(dir, "diagnoses-v1/${r.id}/drives/${base.id}.json")
        val original = file.readText()
        val csv = File(dir, "diagnoses-v1/${r.id}/readings.csv").readText()
        source = terminal
        store.save(store.read(r.id).copy(status = DiagnosisStatus.COMPLETED,
            segments = store.read(r.id).segments.map { it.copy(endedAtOffsetMillis = 36_000) }))
        val available = store.readDriveEvidence(r.id, base.id)
        assertEquals(terminal, available.drive)
        assertContains(available.warning.orEmpty(), "could not be retained")
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        assertEquals(terminal, DriveHistoryCodec.decode(zip.getValue("drives/${base.id}.json")))
        assertContains(zip.getValue("report.txt"), "could not be retained")
        assertContains(zip.getValue("report.txt"), "FINAL_TOO_LARGE")
        assertEquals(original, file.readText())
        assertEquals(csv, File(dir, "diagnoses-v1/${r.id}/readings.csv").readText())
        source = null
        val reopened = DiagnosticSessionStore(dir, maximumSessionBytes = 65_536)
        val partial = reopened.readDriveEvidence(r.id, base.id)
        assertEquals(checkpoint, partial.drive)
        assertContains(partial.warning.orEmpty(), "Partial checkpoint only")
        val after = ByteArrayOutputStream(); reopened.exportZip(r.id, after)
        val partialZip = entries(after.toByteArray())
        assertEquals(checkpoint, DriveHistoryCodec.decode(partialZip.getValue("drives/${base.id}.json")))
        assertContains(partialZip.getValue("report.txt"), "terminal drive evidence is unavailable")
        assertFalse(partialZip.getValue("report.txt").contains("FINAL_TOO_LARGE"))
        assertEquals(csv, partialZip.getValue("readings.csv"))
        assertEquals(original, file.readText())
    }

    @Test fun sourceRotationDuringOversizedExportCannotMixTerminalReportWithCheckpointCharts() = withStore { dir, _ ->
        val base = historyTestDrive()
        val checkpoint = base.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null)
        val terminal = base.copy(report = "OPENWRENCH live health monitoring\n" + "x".repeat(70_000))
        var source: SavedDrive? = checkpoint
        var rotate = false
        var reads = 0
        val store = DiagnosticSessionStore(dir, maximumSessionBytes = 65_536, driveProvider = {
            if (rotate && reads++ > 0) null else source
        })
        val r = record().let { it.copy(segments = it.segments.map { part -> part.copy(driveId = base.id) }) }
        store.create(r); store.save(r)
        val file = File(dir, "diagnoses-v1/${r.id}/drives/${base.id}.json")
        val original = file.readText()
        source = terminal
        store.save(r.copy(status = DiagnosisStatus.COMPLETED,
            segments = r.segments.map { it.copy(endedAtOffsetMillis = 36_000) }))
        rotate = true
        val failure = assertFails { store.exportZip(r.id, ByteArrayOutputStream()) }
        assertContains(failure.message.orEmpty(), "changed during export")
        assertEquals(original, file.readText())
    }

    @Test fun failedTerminalArchiveReplacementPreservesDurableCheckpointAndReceipts() = withStore { dir, _ ->
        val terminal = historyTestDrive()
        val checkpoint = terminal.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null)
        var source: SavedDrive? = checkpoint
        val store = DiagnosticSessionStore(dir, driveProvider = { source })
        val r = record().let { it.copy(segments = it.segments.map { part -> part.copy(driveId = terminal.id) }) }
        store.create(r); store.save(r)
        val file = File(dir, "diagnoses-v1/${r.id}/drives/${terminal.id}.json")
        val original = file.readText()
        val receiptFile = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        val receipts = receiptFile.readText()
        // Simulate an unavailable temporary-write target, never remove the durable archive.
        val blocked = File(file.path + ".tmp").apply { mkdir() }
        val marker = File(blocked, "keep").apply { writeText("existing interruption evidence") }
        source = terminal
        assertFails { store.readDriveEvidence(r.id, terminal.id) }
        assertEquals(original, file.readText())
        assertEquals(receipts, receiptFile.readText())
        assertTrue(marker.exists())
        source = null
        assertEquals(checkpoint, store.readDriveEvidence(r.id, terminal.id).drive)
        assertContains(store.readDriveEvidence(r.id, terminal.id).warning.orEmpty(), "Partial checkpoint only")
    }

    @Test fun processRecoveryCountsDurableRowsEvenWhenMetadataCheckpointLagged() = withStore { dir, store ->
        val r = record(); store.create(r)
        store.appendSamples(r.id, r.segments.single().id, listOf(rpm(), rpm(200)))
        assertEquals(DiagnosisStatus.RECORDING, store.read(r.id).status)
        assertEquals(2L, store.read(r.id).segments.single().sampleCount)
        val recovered = DiagnosticSessionStore(dir).read(r.id)
        assertEquals(DiagnosisStatus.INTERRUPTED, recovered.status)
        assertEquals(2L, recovered.segments.single().sampleCount)
        assertEquals(200L, recovered.segments.single().endedAtOffsetMillis)
        assertContains(recovered.segments.single().endReason.orEmpty(), "interrupted")
        assertEquals(DiagnosisStatus.RECORDING, DiagnosisCodec.decode(File(dir, "diagnoses-v1/${r.id}/manifest.json").readText()).status)
    }

    @Test fun resumeUsesNewSegmentAndPreservesPriorReceiptProvenance() = withStore { dir, store ->
        val r = record(); store.create(r); store.appendSamples(r.id, r.segments.single().id, listOf(rpm()))
        val fresh = DiagnosticSessionStore(dir)
        val recovered = fresh.read(r.id)
        val second = DiagnosisSegment(uuid(), 9, "7E9", 2_000_000, 10)
        val resumed = recovered.copy(status = DiagnosisStatus.RECORDING, segments = recovered.segments + second)
        fresh.save(resumed); fresh.appendSamples(r.id, second.id, listOf(rpm(5)))
        val out = ByteArrayOutputStream(); fresh.exportZip(r.id, out)
        val exported = entries(out.toByteArray()).getValue("readings.csv")
        assertContains(exported, "\"7\",\"7E8\",\"1000100\",\"100\"")
        assertContains(exported, "\"9\",\"7E9\",\"2000005\",\"5\"")
        assertEquals(listOf(1L, 1L), fresh.read(r.id).segments.map { it.sampleCount })
        assertFails { fresh.appendSamples(r.id, r.segments.single().id, listOf(rpm(300))) }
    }

    @Test fun zipContainsDecodedAndUnavailableReceiptsScansAndNoVinOrAdapterFields() = withStore { _, store ->
        val r = record().copy(scanReports = listOf("OPENWRENCH read-only diagnostic scan\nStored codes: none returned\nVIN omitted.\n"))
        store.create(r)
        store.appendSamples(r.id, r.segments.single().id, listOf(rpm(),
            DiagnosisSample(200, 0x05, null, null, "°C", "NO_DATA"),
            DiagnosisSample(250, 0x03, null, "system_1=closed loop, system_2=\"unknown\"", "", "AVAILABLE")))
        val ended = r.copy(status = DiagnosisStatus.COMPLETED, segments = r.segments.map { it.copy(endedAtOffsetMillis = 300) })
        store.save(ended)
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        assertEquals(setOf("manifest.json", "report.txt", "readings.csv", "ai-request.md", "ai-evidence.json"), zip.keys)
        val index = Json.parseToJsonElement(zip.getValue("ai-evidence.json")).jsonObject
        assertTrue(index.getValue("drives").jsonArray.isEmpty())
        assertEquals(JsonNull, index.getValue("segments").jsonArray.single().jsonObject["drive_id"])
        assertContains(zip.getValue("ai-request.md"), "Competing explanations")
        assertContains(zip.getValue("readings.csv"), "\"0105\",\"\",\"\",\"°C\",\"NO_DATA\"")
        assertContains(zip.getValue("readings.csv"), "system_1=closed loop, system_2=\"\"unknown\"\"")
        assertContains(zip.getValue("report.txt"), "Attached scan 1")
        assertFalse(zip.getValue("manifest.json").contains("\"vin\""))
        assertFalse(zip.getValue("manifest.json").contains("adapter"))
        assertEquals(3L, DiagnosisCodec.decode(zip.getValue("manifest.json")).segments.single().sampleCount)
    }

    @Test fun unknownOrDuplicateMetadataAndInvalidIdentityAreRejectedWithoutOverwrite() = withStore { dir, store ->
        val r = record(); store.create(r)
        val f = File(dir, "diagnoses-v1/${r.id}/manifest.json")
        val future = f.readText().replace("openwrench.diagnosis/1", "openwrench.diagnosis/999")
        f.writeText(future)
        val unreadable = DiagnosticSessionStore(dir).list().single()
        assertEquals(DiagnosisStatus.INTERRUPTED, unreadable.status)
        assertContains(unreadable.note.orEmpty(), "unreadable diagnosis archive")
        assertFails { store.save(r) }
        val healthy = record(); store.create(healthy)
        assertEquals(setOf(r.id, healthy.id), store.list().map { it.id }.toSet())
        assertEquals(future, f.readText())
        val encoded = DiagnosisCodec.encode(r)
        assertFails { DiagnosisCodec.decode(encoded.replaceFirst("\"id\":", "\"id\":\"duplicate\",\"id\":")) }
        assertFails { DiagnosisCodec.encode(r.copy(id = "../outside")) }
        assertFails { DiagnosisCodec.encode(r.copy(segments = r.segments.map { it.copy(sourceEcu = "device address") })) }
        assertFails { DiagnosisCodec.encode(r.copy(scanReports = listOf("OPENWRENCH read-only diagnostic scan\nVIN (explicitly included): TEST"))) }
    }

    @Test fun incompleteFinalCsvIsPreservedAndRecoverablePrefixExportsWithoutInventingAReceipt() = withStore { dir, store ->
        val r = record(); store.create(r); store.appendSamples(r.id, r.segments.single().id, listOf(rpm()))
        val f = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        f.appendText("\"partial")
        val corrupt = f.readText()
        val recovered = DiagnosticSessionStore(dir).read(r.id)
        assertEquals(DiagnosisStatus.INTERRUPTED, recovered.status)
        assertEquals(1L, recovered.segments.single().sampleCount)
        assertEquals(1L, recovered.segments.single().droppedSamples)
        assertContains(recovered.note.orEmpty(), "trailing bytes preserved")
        assertFails { store.appendSamples(r.id, r.segments.single().id, listOf(rpm(200))) }
        assertFails { store.save(recovered) }
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        assertFalse(zip.getValue("readings.csv").contains("partial"))
        assertContains(zip.getValue("report.txt"), "Incomplete final CSV row")
        val index = Json.parseToJsonElement(zip.getValue("ai-evidence.json")).jsonObject
        assertEquals("INTERRUPTED", index.getValue("status").jsonPrimitive.content)
        val segment = index.getValue("segments").jsonArray.single().jsonObject
        assertEquals(1L, segment.getValue("retained_receipts").jsonPrimitive.long)
        assertEquals(1L, segment.getValue("dropped_receipts").jsonPrimitive.long)
        assertEquals(corrupt, f.readText())
    }

    @Test fun invalidBatchIsRejectedInFullAndDoesNotChangeTheDurableFile() = withStore { dir, store ->
        val r = record(); store.create(r)
        val f = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        val original = f.readBytes()
        for (bad in listOf(rpm().copy(value = Double.NaN), rpm().copy(unit = "%"), rpm().copy(availability = "MISSING"),
            rpm().copy(value = null), rpm().copy(elapsedMillis = -1), rpm().copy(pid = 0xFF),
            DiagnosisSample(100, 0x03, null, "=HYPERLINK(\"x\")", "", "AVAILABLE"))) {
            assertFails { store.appendSamples(r.id, r.segments.single().id, listOf(rpm(), bad)) }
            assertContentEquals(original, f.readBytes())
        }
        store.appendSamples(r.id, r.segments.single().id, listOf(rpm(200)))
        assertFails { store.appendSamples(r.id, r.segments.single().id, listOf(rpm(100))) }
        assertEquals(1L, store.read(r.id).segments.single().sampleCount)
    }

    @Test fun completeButCorruptCsvRowIsNotSilentlyTreatedAsInterruptedData() = withStore { dir, store ->
        val r = record(); store.create(r); store.appendSamples(r.id, r.segments.single().id, listOf(rpm()))
        val f = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        val corrupt = f.readText().replace("\"7E8\"", "\"7E9\"")
        f.writeText(corrupt)
        assertFails { DiagnosticSessionStore(dir).read(r.id) }
        assertFails { store.exportZip(r.id, ByteArrayOutputStream()) }
        assertEquals(corrupt, f.readText())
    }

    @Test fun capacityFailureKeepsPreviousRowsAndStillAllowsClosingTheRecord() = withStore { dir, _ ->
        val store = DiagnosticSessionStore(dir, maximumSessionBytes = 4_096)
        val r = record(); store.create(r)
        store.appendSamples(r.id, r.segments.single().id, listOf(rpm()))
        val f = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        val previous = f.readBytes()
        assertFails { store.appendSamples(r.id, r.segments.single().id, (1..100).map { rpm(it * 100L) }) }
        assertContentEquals(previous, f.readBytes())
        store.save(r.copy(status = DiagnosisStatus.PAUSED, segments = r.segments.map { it.copy(endedAtOffsetMillis = 100, droppedSamples = 100) }))
        assertEquals(DiagnosisStatus.PAUSED, store.read(r.id).status)
    }

    @Test fun failedPartialAppendRollsBackAndExplicitRetryDoesNotDuplicateRows() = withStore { dir, _ ->
        var fail = true
        val store = DiagnosticSessionStore(dir, appendBytes = { file, bytes ->
            if (fail) {
                file.write(bytes, 0, bytes.size / 2)
                throw java.io.IOException("Synthetic disk write failure")
            }
            file.write(bytes)
        })
        val r = record(); store.create(r)
        val f = File(dir, "diagnoses-v1/${r.id}/readings.csv")
        val previous = f.readBytes()
        assertFails { store.appendSamples(r.id, r.segments.single().id, listOf(rpm())) }
        assertContentEquals(previous, f.readBytes())
        fail = false
        store.appendSamples(r.id, r.segments.single().id, listOf(rpm()))
        assertEquals(1L, store.read(r.id).segments.single().sampleCount)
        assertFails { store.save(r.copy(status = DiagnosisStatus.COMPLETED,
            segments = r.segments.map { it.copy(endedAtOffsetMillis = 50) })) }
    }

    @Test fun sessionLimitNeverDeletesAnotherDiagnosis() = withStore { dir, _ ->
        val store = DiagnosticSessionStore(dir, maximumSessions = 1)
        val r = record(); store.create(r)
        assertFails { store.create(record()) }
        assertEquals(r.id, store.list().single().id)
    }

    @Test fun savedDriveIsPreservedWithChartsAfterDriveHistoryRotation() = withStore { dir, _ ->
        val drive = historyTestDrive()
        var available: SavedDrive? = drive
        val store = DiagnosticSessionStore(dir, driveProvider = { available })
        val r = record().copy(segments = record().segments.map { it.copy(driveId = drive.id) })
        store.create(r); store.save(r)
        available = null
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        assertEquals(drive, DriveHistoryCodec.decode(zip.getValue("drives/${drive.id}.json")))
        assertContains(zip.getValue("charts/${drive.id}.csv"), "elapsed_ms")
        assertContains(zip.getValue("report.txt"), "Recorded drive assessment and evidence")
        assertEquals(drive, DiagnosticSessionStore(dir).readDrive(r.id, drive.id))
    }

    @Test fun exportStreamsSeveralLongDriveReportsWithTheirOwnCharts() = withStore { dir, _ ->
        val first = historyTestDrive().copy(report = "OPENWRENCH live health monitoring\nFIRST-DRIVE\n" + "A".repeat(180_000))
        val second = historyTestDrive().copy(report = "OPENWRENCH live health monitoring\nSECOND-DRIVE\n" + "B".repeat(180_000))
        val byId = listOf(first, second).associateBy { it.id }
        val store = DiagnosticSessionStore(dir, driveProvider = byId::get)
        val base = record()
        val segments = listOf(first, second).mapIndexed { index, drive ->
            DiagnosisSegment(uuid(), 7, "7E8", 1_000_000L + index * 100_000, 1_234L + index * 100_000,
                endedAtOffsetMillis = 36_000, driveId = drive.id)
        }
        val r = base.copy(status = DiagnosisStatus.COMPLETED, segments = segments)
        store.create(r)
        val out = ByteArrayOutputStream()
        store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        val report = zip.getValue("report.txt")
        assertContains(report, "FIRST-DRIVE")
        assertContains(report, "SECOND-DRIVE")
        assertEquals(2, Regex("Recorded drive assessment and evidence").findAll(report).count())
        assertTrue(listOf(first, second).all { "drives/${it.id}.json" in zip && "charts/${it.id}.csv" in zip })
    }

    @Test fun cachedTerminalDriveCannotBeReplacedByAnOlderCheckpoint() = withStore { dir, _ ->
        val drive = historyTestDrive()
        var available = drive
        val store = DiagnosticSessionStore(dir, driveProvider = { available })
        val r = record().let { it.copy(segments = it.segments.map { s -> s.copy(driveId = drive.id) }) }
        store.create(r); store.save(r)
        available = drive.copy(status = SavedDriveStatus.CHECKPOINT)
        store.save(r)
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        assertEquals(SavedDriveStatus.COMPLETED, DriveHistoryCodec.decode(entries(out.toByteArray()).getValue("drives/${drive.id}.json")).status)
    }

    @Test fun absentLinkedEvidenceIsExplicitAndNoChartIsInvented() = withStore { _, store ->
        val driveId = uuid()
        val r = record().let { it.copy(segments = it.segments.map { s -> s.copy(driveId = driveId) }) }
        store.create(r)
        val out = ByteArrayOutputStream(); store.exportZip(r.id, out)
        val zip = entries(out.toByteArray())
        assertContains(zip.getValue("report.txt"), "Linked drive evidence unavailable: $driveId")
        assertFalse(zip.keys.any { it.startsWith("charts/") })
    }

    @Test fun tornCreateAndDamagedCsvAreVisibleWithoutBlockingHealthyHistory() = withStore { dir, store ->
        val healthy = record(); store.create(healthy)
        val damaged = record(); store.create(damaged)
        File(dir, "diagnoses-v1/${damaged.id}/readings.csv").writeText("corrupt complete row\n")
        val orphan = uuid()
        File(dir, "diagnoses-v1/$orphan").mkdirs()
        File(dir, "diagnoses-v1/$orphan/manifest.json.tmp").writeText("{incomplete")
        val rows = store.list().associateBy { it.id }
        assertEquals(setOf(healthy.id, damaged.id, orphan), rows.keys)
        assertEquals(DiagnosisStatus.RECORDING, rows.getValue(healthy.id).status)
        assertContains(rows.getValue(damaged.id).note.orEmpty(), "receipt evidence is unavailable or corrupt")
        assertContains(rows.getValue(orphan).note.orEmpty(), "Incomplete or unreadable")
        assertFails { store.read(damaged.id) }
        assertFails { store.read(orphan) }
        assertFails { store.create(record().copy(id = orphan)) }
        val another = record(); store.create(another)
        assertEquals(4, store.list().size)
        assertEquals("{incomplete", File(dir, "diagnoses-v1/$orphan/manifest.json.tmp").readText())
    }

    @Test fun linkedDriveOpeningRejectsForeignCaseAndMismatchedCachedProvenance() = withStore { dir, _ ->
        val drive = historyTestDrive()
        val store = DiagnosticSessionStore(dir, driveProvider = { drive })
        val r = record().let { it.copy(segments = it.segments.map { s -> s.copy(driveId = drive.id) }) }
        store.create(r); store.save(r)
        assertFails { store.readDrive(r.id, uuid()) }
        val f = File(dir, "diagnoses-v1/${r.id}/drives/${drive.id}.json")
        f.writeText(f.readText().replace("\"ecu\":\"7E8\"", "\"ecu\":\"7E9\""))
        assertFails { DiagnosticSessionStore(dir).readDrive(r.id, drive.id) }
    }
}
