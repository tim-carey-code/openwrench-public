package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.openwrench.nativecore.ScanOrigin
import java.nio.file.Files
import kotlin.test.*

/** Synthetic captures, no adapter access. Exercise the same app-private paths used across APK updates. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanAutoSaveTest {
    @Test fun deletedScanStaysDeletedWhenNextScanIsCanceled() = runTest {
        deletedScanStaysDeletedAfterUnsuccessfulScan(failed = false)
    }

    @Test fun deletedScanStaysDeletedWhenNextScanFails() = runTest {
        deletedScanStaysDeletedAfterUnsuccessfulScan(failed = true)
    }

    private suspend fun TestScope.deletedScanStaysDeletedAfterUnsuccessfulScan(failed: Boolean) {
        val dir = Files.createTempDirectory("ow-auto-deleted").toFile()
        try {
            val store = ScanHistoryStore(dir)
            val history = ScanHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler))
            val captureA = caseScanFixture().capture!!
            val completedA = NativeUiState(connected = true, snapshot = captureA.readings, scanCapture = captureA)
            val states = MutableStateFlow(completedA)
            val observer = backgroundScope.launch { history.autoSaveCompletedScans(states) }
            runCurrent()
            assertEquals(captureA, store.load().single().capture)

            history.delete(store.load().single().id)
            runCurrent()
            assertTrue(store.load().isEmpty())

            // B starts while the controller still holds A's completed evidence.
            states.value = completedA.copy(snapshotInProgress = true)
            runCurrent()
            assertTrue(store.load().isEmpty())
            // Cancellation/failure clears progress without publishing a new capture.
            states.value = completedA.copy(connected = false, faulted = failed,
                detail = if (failed) "Session read failed" else "Adapter released")
            runCurrent()
            assertNull(history.state.value.error)
            assertTrue(history.state.value.entries.isEmpty())
            assertTrue(ScanHistoryStore(dir).load().isEmpty())

            // Ineligible states also must not reset the last observed capture ID.
            states.value = NativeUiState()
            runCurrent()
            states.value = completedA
            runCurrent()
            assertTrue(store.load().isEmpty())

            // A later successful acquisition must still save exactly once.
            val captureC = caseScanFixture().capture!!
            val completedC = completedA.copy(snapshot = captureC.readings, scanCapture = captureC)
            states.value = completedC.copy(snapshotInProgress = true)
            runCurrent()
            assertTrue(store.load().isEmpty())
            states.value = completedC
            runCurrent()
            val saved = store.load().single()
            assertEquals(captureC, saved.capture)
            states.value = completedC.copy(detail = "Unrelated UI update")
            runCurrent()
            assertEquals(listOf(saved), store.load())
            observer.cancel()
        } finally { dir.deleteRecursively() }
    }

    @Test fun hardwareCaptureAutoSavesOnceAcrossRepeatedDeliveryAndProcessRestart() = runTest {
        val dir = Files.createTempDirectory("ow-auto-scan").toFile()
        try {
            val fixture = caseScanFixture()
            val capture = fixture.capture!!
            val dispatcher = StandardTestDispatcher(testScheduler)
            val history = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, dispatcher)
            // Queued while the initial disk load is still pending.
            history.autoSave(fixture.report, capture)
            history.autoSave(fixture.report, capture)
            // backgroundScope work must be run explicitly on the shared scheduler.
            runCurrent()
            assertNull(history.state.value.error)
            assertEquals(1, ScanHistoryStore(dir).load().size)
            assertNull(ScanHistoryStore(dir).load().single().vin)
            val original = ScanHistoryStore(dir).load().single()

            // A new process reopening the same app-private directory must not duplicate a capture.
            val reopened = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, dispatcher)
            reopened.autoSave(fixture.report, capture)
            runCurrent()
            assertEquals(listOf(original), ScanHistoryStore(dir).load())
            assertEquals(listOf(original), reopened.state.value.entries)
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitVinOptInEnrichesSameSavedRecordWithoutDuplicatingOrLeakingVin() = runTest {
        val dir = Files.createTempDirectory("ow-auto-vin").toFile()
        try {
            val fixture = caseScanFixture()
            val capture = fixture.capture!!
            val history = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler))
            history.autoSave(fixture.report, capture)
            runCurrent()
            val before = ScanHistoryStore(dir).load().single()
            assertNull(before.vin)
            history.save(fixture.report, "AAAAAAAAAAAAAAAAA", capture)
            assertTrue(history.state.value.busy)
            runCurrent()
            val after = ScanHistoryStore(dir).load().single()
            assertEquals(before.id, after.id)
            assertEquals(before.capture, after.capture)
            assertEquals(before.report, after.report)
            assertEquals("AAAAAAAAAAAAAAAAA", after.vin)
            history.autoSave(fixture.report, capture)
            runCurrent()
            assertEquals(after, ScanHistoryStore(dir).load().single())
        } finally { dir.deleteRecursively() }
    }

    @Test fun importedAndSimulatedCapturesAreNotAutomaticallySaved() = runTest {
        val dir = Files.createTempDirectory("ow-auto-origin").toFile()
        try {
            val fixture = caseScanFixture()
            val history = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler))
            for (origin in listOf(ScanOrigin.IMPORTED, ScanOrigin.SIMULATOR, ScanOrigin.IMPORTED_SIMULATOR, ScanOrigin.SYNTHETIC)) {
                history.autoSave(fixture.report, fixture.capture!!.copy(origin = origin))
            }
            runCurrent()
            assertFalse(history.state.value.busy)
            assertTrue(ScanHistoryStore(dir).load().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptHistoryIsNeverOverwrittenAndBlocksAutoSave() = runTest {
        val dir = Files.createTempDirectory("ow-auto-corrupt").toFile()
        try {
            val file = dir.resolve("scan-history-v1.json")
            file.writeText("{broken")
            val fixture = caseScanFixture()
            val history = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler))
            history.autoSave(fixture.report, fixture.capture!!)
            runCurrent()
            assertFalse(history.state.value.available)
            assertNotNull(history.state.value.error)
            assertEquals("{broken", file.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun autoSaveKeepsExistingScansCasesAndDrivesDuringInPlaceHistoryReopen() = runTest {
        val dir = Files.createTempDirectory("ow-upgrade-history").toFile()
        try {
            val oldScan = caseScanFixture()
            val drive = caseDriveFixture()
            val scanStore = ScanHistoryStore(dir)
            val driveStore = DriveHistoryStore(dir)
            val caseStore = DiagnosticCaseStore(dir)
            scanStore.add(oldScan)
            driveStore.upsert(drive)
            val savedCase = DiagnosticCaseBuilder.create(oldScan, drive, true)
            caseStore.add(savedCase)
            val caseFile = File(dir, "diagnostic-cases-v1.json")
            val caseBytes = caseFile.readText().replace("openwrench.drive-history/6", "openwrench.drive-history/5")
            caseFile.writeText(caseBytes)
            // Seed the exact main schema-5 shape before reopening the upgraded stores.
            val driveFile = File(dir, "drive-history-v1/${drive.id}.json")
            val mainBytes = driveFile.readText().replace("openwrench.drive-history/6", "openwrench.drive-history/5")
            driveFile.writeText(mainBytes)
            // A diagnosis-preview schema 5 lacks origin, and may contain MAF series.
            val preview = historyTestDrive()
            val root = Json.parseToJsonElement(DriveHistoryCodec.encode(preview)).jsonObject
            val previewBytes = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/5"))).toString()
            val previewFile = File(dir, "drive-history-v1/${preview.id}.json").apply { writeText(previewBytes) }
            val originalDrive = driveStore.read(drive.id)
            val originalCase = caseStore.load().single()

            // Reopening a new coordinator/store is the on-disk schema/persistence contract.
            val nextScan = caseScanFixture()
            val fresh = ScanHistoryCoordinator(ScanHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler))
            fresh.autoSave(nextScan.report, nextScan.capture!!)
            runCurrent()

            assertEquals(2, ScanHistoryStore(dir).load().size)
            assertEquals(oldScan, ScanHistoryStore(dir).load().last())
            assertEquals(originalDrive, DriveHistoryStore(dir).read(drive.id))
            assertEquals(originalCase, DiagnosticCaseStore(dir).load().single())
            assertEquals(preview, DriveHistoryStore(dir).read(preview.id))
            assertEquals(caseBytes, caseFile.readText())
            assertEquals(mainBytes, driveFile.readText())
            assertEquals(previewBytes, previewFile.readText())
            DriveHistoryStore(dir).upsert(preview)
            assertEquals(preview, DriveHistoryStore(dir).read(preview.id))
            assertEquals(originalCase, DiagnosticCaseStore(dir).load().single())
            assertEquals(oldScan, ScanHistoryStore(dir).load().last())
        } finally { dir.deleteRecursively() }
    }

    @Test fun pendingAutoSaveRejectsDeleteUntilItCompletes() = runTest {
        val dir = Files.createTempDirectory("ow-auto-admission").toFile()
        try {
            val store = ScanHistoryStore(dir)
            val oldScan = caseScanFixture()
            store.add(oldScan)
            val history = ScanHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent()
            assertFalse(history.state.value.busy)

            val nextScan = caseScanFixture()
            history.autoSave(nextScan.report, nextScan.capture!!)
            assertTrue(history.state.value.busy)
            history.delete(oldScan.id) // Rejected before the queued save starts.
            runCurrent()
            assertFalse(history.state.value.busy)
            assertEquals(2, store.load().size)
            assertEquals(oldScan, store.load().last())

            history.delete(oldScan.id)
            runCurrent()
            assertNull(history.state.value.error)
            assertEquals(nextScan.capture, store.load().single().capture)
        } finally { dir.deleteRecursively() }
    }
}
