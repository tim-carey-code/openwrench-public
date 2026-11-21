package org.openwrench.frontend.android

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanHistoryStoreTest {
    private val report = "OPENWRENCH read-only diagnostic scan\nStatus: captured\nStored codes: P0300\nVIN omitted.\n"

    @Test fun savesOnlyExplicitReportsAndOptionalVin() {
        val directory = Files.createTempDirectory("openwrench-history-").toFile()
        try {
            val store = ScanHistoryStore(directory)
            assertTrue(store.load().isEmpty())
            val withoutVin = ScanHistoryCodec.newEntry(report, null, 1_000)
            val withVin = ScanHistoryCodec.newEntry(report, "AAAAAAAAAAAAAAAAA", 2_000)
            store.add(withoutVin)
            store.add(withVin)
            val reloaded = ScanHistoryStore(directory).load()
            assertEquals(listOf(withVin, withoutVin), reloaded)
            assertFalse(reloaded[1].copyText().contains("AAAAAAAAAAAAAAAAA"))
            assertTrue(reloaded[0].copyText().contains("VIN (explicitly included): AAAAAAAAAAAAAAAAA"))
            assertEquals(listOf(withVin), store.delete(withoutVin.id))
            assertEquals(listOf(withVin), ScanHistoryStore(directory).load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedHistoryCannotBeOverwrittenBySaving() {
        val directory = Files.createTempDirectory("openwrench-history-").toFile()
        try {
            val file = directory.resolve("scan-history-v1.json")
            file.writeText("{broken")
            val store = ScanHistoryStore(directory)
            assertFailsWith<Exception> { store.add(ScanHistoryCodec.newEntry(report, null)) }
            assertEquals("{broken", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsInvalidVinAndNeverEvictsHistorySilently() {
        assertFailsWith<IllegalArgumentException> { ScanHistoryCodec.newEntry(report, "INVALID") }
        val directory = Files.createTempDirectory("openwrench-history-").toFile()
        try {
            val store = ScanHistoryStore(directory)
            repeat(ScanHistoryCodec.MAX_ENTRIES) { store.add(ScanHistoryCodec.newEntry(report, null, (it + 1).toLong())) }
            assertFailsWith<IllegalArgumentException> { store.add(ScanHistoryCodec.newEntry(report, null, 100)) }
            assertEquals(ScanHistoryCodec.MAX_ENTRIES, store.load().size)
        } finally { directory.deleteRecursively() }
    }
}
