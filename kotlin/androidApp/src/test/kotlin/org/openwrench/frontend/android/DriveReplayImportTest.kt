package org.openwrench.frontend.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DriveReplayImportTest {
    private fun encoded(drive: SavedDrive) = DriveHistoryCodec.encode(drive).toByteArray(Charsets.UTF_8)
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun read(bytes: ByteArray) = DriveReplayImport.read(bytes.inputStream())
    private fun entry(drive: SavedDrive) = "drives/${drive.id}.json" to encoded(drive)

    @Test fun directJsonPreservesMetadataAndDoesNotCloseCallerStream() {
        val drive = historyTestDrive().copy(origin = DriveEvidenceOrigin.SYNTHETIC,
            status = SavedDriveStatus.CHECKPOINT, endReason = null)
        var closed = false
        val input = object : java.io.ByteArrayInputStream(encoded(drive)) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(listOf(drive), DriveReplayImport.read(input))
        assertFalse(closed)
    }

    @Test fun zipImportsMultipleRecordingsNewestFirstAndSkipsReports() {
        val first = historyTestDrive(epoch = 1_000).copy(origin = DriveEvidenceOrigin.UNKNOWN)
        val second = historyTestDrive(epoch = 2_000).copy(origin = DriveEvidenceOrigin.SYNTHETIC)
        val bytes = archive("report.txt" to "Report without recordings".toByteArray(),
            "drives/" to byteArrayOf(), entry(first), "readings.csv" to "time,value\n0,1".toByteArray(), entry(second))
        assertEquals(listOf(second, first), read(bytes))
    }

    @Test fun importedDriveAiUsesOnlyTheSelectedHistoricalDrive() {
        val selected = historyTestDrive().copy(origin = DriveEvidenceOrigin.UNKNOWN,
            status = SavedDriveStatus.CHECKPOINT, endReason = null)
        val other = historyTestDrive().copy(origin = DriveEvidenceOrigin.SYNTHETIC)
        val bytes = archive(entry(selected), entry(other), "report.txt" to "UNSELECTED_REPORT".toByteArray())
        val report = importedDriveAiReport(read(bytes).first { it.id == selected.id })

        assertContains(report, "Imported drive · historical evidence")
        assertContains(report, selected.id)
        assertContains(report, "Recorded origin: UNKNOWN")
        assertContains(report, "Storage status: checkpoint")
        assertContains(report, "No fresh stored-code scan is inferred")
        assertFalse(other.id in report)
        assertFalse("UNSELECTED_REPORT" in report)
    }

    @Test fun importDoesNotWriteOrReplaceHistoryOrExtractFiles() {
        val root = Files.createTempDirectory("ow-replay-read-only").toFile()
        try {
            val stored = historyTestDrive()
            DriveHistoryStore(root).upsert(stored)
            val file = File(root, "drive-history-v1/${stored.id}.json")
            val before = file.readBytes()
            val imported = stored.copy(origin = DriveEvidenceOrigin.SYNTHETIC)
            val selected = File(root, "selected.zip")
            selected.writeBytes(archive(entry(imported), "report.txt" to byteArrayOf(1, 2)))
            val paths = root.walkTopDown().map { it.relativeTo(root).path }.toList()
            selected.inputStream().use { assertEquals(listOf(imported), DriveReplayImport.read(it)) }
            assertContentEquals(before, file.readBytes())
            assertEquals(paths, root.walkTopDown().map { it.relativeTo(root).path }.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyJsonRemainsUnknownRatherThanInferringHardware() {
        val drive = historyTestDrive()
        val legacy = DriveHistoryCodec.encode(drive).replace("openwrench.drive-history/6", "openwrench.drive-history/5")
            .replace("\"origin\":\"UNKNOWN\",", "")
        assertEquals(drive, read(legacy.toByteArray()).single())
    }

    @Test fun reportOnlyZipClearlyReportsNoRecordings() {
        val failure = assertFails { read(archive("report.txt" to "Not a drive".toByteArray())) }
        assertTrue(failure.message.orEmpty().contains("No saved drive recordings"))
        assertFails { read(archive()) }
    }

    @Test fun malformedAndUnsupportedDriveEntriesFailWholeImport() {
        val valid = historyTestDrive()
        val other = historyTestDrive()
        for (bad in listOf("{broken", "{}", "[]", DriveHistoryCodec.encode(other).replace("drive-history/6", "drive-history/99"))) {
            assertFails { read(bad.toByteArray()) }
            assertFails { read(archive(entry(valid), "drives/${other.id}.json" to bad.toByteArray())) }
        }
        assertFails { read(archive("drives/not-a-uuid.json" to encoded(valid))) }
    }

    @Test fun malformedUtf8IsNotSilentlyReplaced() {
        val drive = historyTestDrive()
        val valid = encoded(drive)
        val malformed = valid + byteArrayOf(0xc3.toByte(), 0x28)
        assertFails { read(malformed) }
        assertFails { read(archive("drives/${drive.id}.json" to malformed)) }
    }

    @Test fun unsafePathsAreRejectedEvenForIgnoredReportEntries() {
        val drive = historyTestDrive()
        for (name in listOf("../report.txt", "/report.txt", "C:/report.txt", "dir\\report.txt", "dir/../report.txt",
            "dir/./report.txt", "dir//report.txt")) {
            assertFails(name) { read(archive(entry(drive), name to byteArrayOf())) }
        }
    }

    @Test fun mismatchedAndDuplicateDriveIdsAreRejected() {
        val drive = historyTestDrive()
        val other = historyTestDrive()
        assertFails { read(archive("drives/${other.id}.json" to encoded(drive))) }
        assertFails { read(archive(entry(drive), "drives/${other.id}.json" to encoded(drive))) }
    }

    @Test fun duplicateArchiveNamesAreRejected() {
        val drive = historyTestDrive()
        val bytes = archive(entry(drive), "one.txt" to byteArrayOf(), "two.txt" to byteArrayOf())
        // Replacing equal-length names in both headers creates the duplicate ZipOutputStream forbids.
        val needle = "two.txt".toByteArray()
        val replacement = "one.txt".toByteArray()
        for (i in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) replacement.copyInto(bytes, i)
        }
        assertFails { read(bytes) }
    }

    @Test fun truncatedCentralDirectoryAndPayloadCorruptionAreRejected() {
        val drive = historyTestDrive()
        val bytes = archive(entry(drive))
        assertFails { read(bytes.copyOf(bytes.size - 1)) }
        val central = (0..bytes.size - 4).first { i -> bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
            bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
        assertFails { read(bytes.copyOf(central)) }
        val corrupt = bytes.copyOf()
        // Alter the central directory CRC: local data is valid but the archive is inconsistent.
        corrupt[central + 16] = (corrupt[central + 16].toInt() xor 1).toByte()
        assertFails { read(corrupt) }
    }

    @Test fun directAndZipDriveSizeBoundsAreEnforced() {
        val drive = historyTestDrive()
        val oversized = ByteArray(DriveHistoryCodec.MAX_FILE_BYTES + 1) { 32 }
        assertFails { read(oversized) }
        assertFails { read(archive("drives/${drive.id}.json" to oversized)) }
    }

    @Test fun compressedInputReadsAtMostLimitPlusOneByte() {
        var received = 0
        val input = object : InputStream() {
            override fun read(): Int { received++; return 32 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(32, offset, offset + length); received += length; return length
            }
        }
        assertFails { DriveReplayImport.read(input) }
        assertEquals(DriveReplayImport.MAX_INPUT_BYTES + 1, received)
    }

    @Test fun decompressionBudgetAlsoCountsIgnoredReports() {
        val bytes = archive(entry(historyTestDrive()), "first.txt" to ByteArray(13_000_000),
            "second.txt" to ByteArray(13_000_000))
        assertTrue(bytes.size < DriveReplayImport.MAX_INPUT_BYTES)
        val failure = assertFails { read(bytes) }
        assertTrue(failure.message.orEmpty().contains("expanded data"))
    }

    @Test fun driveAndEntryCountLimitsAreEnforced() {
        val drives = (1..DriveReplayImport.MAX_DRIVES + 1).map { entry(historyTestDrive()) }
        assertFails { read(archive(*drives.toTypedArray())) }
        assertEquals(DriveReplayImport.MAX_DRIVES, read(archive(*drives.dropLast(1).toTypedArray())).size)
        val entries = (1..DriveReplayImport.MAX_ENTRIES).map { "report-$it.txt" to byteArrayOf() }
        assertFails { read(archive(entry(historyTestDrive()), *entries.toTypedArray())) }
    }
}
