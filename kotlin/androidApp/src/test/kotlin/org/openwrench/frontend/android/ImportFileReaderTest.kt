package org.openwrench.frontend.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFileReaderTest {
    private class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private fun snapshot() = checkNotNull(javaClass.getResourceAsStream("/native-snapshot-reference.json")).use { it.readBytes() }
    private fun archive(drive: SavedDrive) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("drives/${drive.id}.json"))
            zip.write(DriveHistoryCodec.encode(drive).toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun validSnapshotUsesStrictCodecAndClosesStream() {
        val source = Tracked(snapshot())
        assertEquals(41, SnapshotCodec.decode(ImportFileReader.snapshotText { source }).readings.size)
        assertTrue(source.closed)
    }

    @Test fun validDriveJsonAndDiagnosisZipUseSameReaderAndCloseStreams() {
        val drive = historyTestDrive()
        for (bytes in listOf(DriveHistoryCodec.encode(drive).toByteArray(), archive(drive))) {
            val source = Tracked(bytes)
            assertEquals(listOf(drive), ImportFileReader.drives { source })
            assertTrue(source.closed)
        }
    }

    @Test fun malformedAndOversizedInputsFailWithoutLeakingStreams() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(SnapshotCodec.MAX_BYTES + 1) { 32 })) {
            val source = Tracked(bytes)
            assertFails { ImportFileReader.snapshotText { source } }
            assertTrue(source.closed)
        }
        val malformedSnapshot = Tracked("{broken".toByteArray())
        assertFails { SnapshotCodec.decode(ImportFileReader.snapshotText { malformedSnapshot }) }
        assertTrue(malformedSnapshot.closed)
        for (bytes in listOf("{broken".toByteArray(), archive(historyTestDrive()).dropLast(12).toByteArray())) {
            val source = Tracked(bytes)
            assertFails { ImportFileReader.drives { source } }
            assertTrue(source.closed)
        }
        assertFails { ImportFileReader.snapshotText { null } }
        assertFails { ImportFileReader.drives { null } }
    }

    @Test fun interruptedAndDeniedReadsPreserveSelectionAndPermitRetry() {
        val gate = ImportRequestGate()
        val existing = listOf(historyTestDrive())
        var published = existing
        for (snapshotRead in listOf(false, true)) {
            var closed = false
            val source = object : InputStream() {
                override fun read(): Int = throw IOException("synthetic interrupted stream")
                override fun close() { closed = true }
            }
            val request = gate.begin()
            assertFails {
                if (snapshotRead) ImportFileReader.snapshotText { source }
                else {
                    val parsed = ImportFileReader.drives { source }
                    gate.complete(request) { published = parsed }
                }
            }
            assertTrue(closed)
            assertEquals(existing, published)
        }
        assertFailsWith<SecurityException> { ImportFileReader.drives { throw SecurityException() } }
        val next = historyTestDrive()
        val request = gate.begin()
        val parsed = ImportFileReader.drives { archive(next).inputStream() }
        assertTrue(gate.complete(request) { published = parsed })
        assertEquals(listOf(next), published)
    }

    @Test fun successfulAndFailedImportsDoNotWriteSavedHistory() {
        val dir = Files.createTempDirectory("ow-import-history").toFile()
        try {
            DriveHistoryStore(dir).upsert(historyTestDrive())
            fun inventory() = dir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(dir).path to it.readBytes().toList() }
            val before = inventory()
            assertTrue(before.isNotEmpty())
            val imported = historyTestDrive()
            assertEquals(listOf(imported), ImportFileReader.drives { archive(imported).inputStream() })
            SnapshotCodec.decode(ImportFileReader.snapshotText { snapshot().inputStream() })
            assertFails { ImportFileReader.drives { "invalid".byteInputStream() } }
            assertEquals(before, inventory())
        } finally { dir.deleteRecursively() }
    }

    @Test fun delayedSnapshotCannotReplaceANewerDriveSelection() = runTest {
        val gate = ImportRequestGate()
        val releaseFirst = CompletableDeferred<Unit>()
        var published = "existing replay"
        val first = gate.begin()
        launch {
            releaseFirst.await()
            val parsed = SnapshotCodec.decode(ImportFileReader.snapshotText { snapshot().inputStream() })
            gate.complete(first) { published = "old snapshot ${parsed.readings.size}" }
        }
        val second = gate.begin()
        val drive = historyTestDrive()
        val parsed = ImportFileReader.drives { archive(drive).inputStream() }
        gate.complete(second) { published = parsed.single().id }
        releaseFirst.complete(Unit); runCurrent()
        assertEquals(drive.id, published)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun explicitInterruptionRejectsLateCompletionAndAllowsRetry() {
        val gate = ImportRequestGate()
        val request = gate.begin()
        val parsed = ImportFileReader.drives { archive(historyTestDrive()).inputStream() }
        var published = "existing replay"
        gate.cancel()
        assertFalse(gate.complete(request) { published = parsed.single().id })
        assertEquals("existing replay", published)
        assertTrue(gate.complete(gate.begin()) { published = parsed.single().id })
        assertEquals(parsed.single().id, published)
    }
}
