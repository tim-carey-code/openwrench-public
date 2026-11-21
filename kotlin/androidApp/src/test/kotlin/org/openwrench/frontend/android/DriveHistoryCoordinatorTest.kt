package org.openwrench.frontend.android

import kotlinx.coroutines.test.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriveHistoryCoordinatorTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun first() { check(queue.isNotEmpty()); queue.removeFirst().run() }
        fun last() { check(queue.isNotEmpty()); queue.removeLast().run() }
        fun all() { while (queue.isNotEmpty()) first() }
    }

    @Test fun olderCatalogResultsCannotRestoreDeletedRowsOrHideNewSavedDrives() {
        val dir = Files.createTempDirectory("ow-drive-catalog-order").toFile()
        val main = ManualDispatcher(); val io = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        try {
            val history = DriveHistoryCoordinator(DriveHistoryStore(dir), scope, io) { 1_000_000L }
            main.all(); io.first(); main.all() // Initial catalog.
            history.record(historyTestSession(instance = 1, completed = true))
            main.all(); io.first(); main.all()
            val a = history.state.value.drives.single().id
            history.record(historyTestSession(instance = 2, completed = true))
            main.all(); io.first() // B is on disk; its older whole-catalog UI result is still queued.
            history.delete(a)
            main.last(); io.first(); main.last() // Publish deletion before B's delayed save result.
            assertEquals(1, history.state.value.drives.size)
            assertFalse(history.state.value.drives.any { it.id == a })
            main.all()
            assertEquals(1, history.state.value.drives.size)
            assertFalse(history.state.value.drives.any { it.id == a })
            assertEquals("Drive saved automatically to History & Reports", history.state.value.saveMessage)

            history.retry(); main.all() // Queue a refresh from before C exists.
            history.record(historyTestSession(instance = 3, completed = true)); main.all()
            io.first(); io.first() // Refresh result precedes C's newer save result in the UI queue.
            main.last() // Publish C first.
            assertEquals(2, history.state.value.drives.size)
            main.all() // Older refresh must not hide C.
            assertEquals(2, history.state.value.drives.size)
            assertFalse(history.state.value.drives.any { it.id == a })
        } finally { scope.cancel(); main.all(); dir.deleteRecursively() }
    }

    @Test fun terminalSnapshotReplacesQueuedCheckpointAndSurvivesProcessRecreation() = runTest {
        val dir = Files.createTempDirectory("ow-drive-save").toFile()
        try {
            val store = DriveHistoryStore(dir)
            val history = DriveHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
            runCurrent()
            val active = historyTestSession()
            history.record(active)
            history.record(historyTestSession(completed = true))
            runCurrent()
            val row = history.state.value.drives.single()
            assertEquals(SavedDriveStatus.COMPLETED, row.status)
            assertNull(history.state.value.error)
            assertNull(history.state.value.activeId)
            history.record(active) // Late old checkpoint cannot downgrade a completed monitor.
            runCurrent()
            assertEquals(SavedDriveStatus.COMPLETED, store.read(row.id).status)
            val fresh = DriveHistoryCoordinator(DriveHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler)) { 2_000_000L }
            runCurrent()
            fresh.open(row.id); runCurrent()
            assertEquals(row.id, fresh.state.value.opened?.id)
            assertTrue(fresh.state.value.opened!!.review.bookmarks.isNotEmpty())
            assertEquals(36_000, fresh.state.value.opened!!.review.elapsedMillis)
            assertTrue(fresh.state.value.opened!!.report.contains("Status: historical"))
            assertFalse(fresh.state.value.opened!!.report.contains("Status: running"))
            assertTrue(fresh.state.value.opened!!.report.contains("Recorded ECU status:"))
            assertFalse(fresh.state.value.opened!!.report.contains("Monitoring stopped; latest=unknown"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun checkpointCadenceDoesNotWriteForEveryReceiptAndRestartUsesNewArchiveId() = runTest {
        val dir = Files.createTempDirectory("ow-drive-cadence").toFile()
        try {
            var wall = 1_000_000L
            val history = DriveHistoryCoordinator(DriveHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler)) { wall }
            runCurrent()
            val base = historyTestSession()
            history.record(base); runCurrent()
            val id = history.state.value.drives.single().id
            val firstSaved = history.state.value.drives.single().savedAtEpochMillis
            wall += 1_000
            history.record(base.copy(driveReview = base.driveReview!!.copy(elapsedMillis = base.driveReview.elapsedMillis + 9_999)))
            runCurrent()
            assertEquals(firstSaved, history.state.value.drives.single().savedAtEpochMillis)
            wall += 1_000
            history.record(base.copy(driveReview = base.driveReview.copy(elapsedMillis = base.driveReview.elapsedMillis + 10_000)))
            runCurrent()
            assertEquals(wall, history.state.value.drives.single().savedAtEpochMillis)
            history.record(historyTestSession(instance = 2, completed = true)); runCurrent()
            assertEquals(2, history.state.value.drives.size)
            assertEquals(2, history.state.value.drives.map { it.id }.distinct().size)
            assertTrue(history.state.value.drives.any { it.id == id })
        } finally { dir.deleteRecursively() }
    }

    @Test fun restartRestoresCheckpointAsFrozenInterruptedEvidence() = runTest {
        val dir = Files.createTempDirectory("ow-drive-recovery").toFile()
        try {
            val history = DriveHistoryCoordinator(DriveHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
            runCurrent(); history.record(historyTestSession()); runCurrent()
            val id = history.state.value.drives.single().id
            val fresh = DriveHistoryCoordinator(DriveHistoryStore(dir), backgroundScope, StandardTestDispatcher(testScheduler)) { 9_000_000L }
            runCurrent(); fresh.open(id); runCurrent()
            assertEquals(SavedDriveStatus.CHECKPOINT, fresh.state.value.opened!!.status)
            assertTrue(fresh.state.value.opened!!.report.contains("historical at the saved point"))
            assertFalse(fresh.state.value.opened!!.report.contains("Monitoring has stopped"))
            assertFalse(fresh.state.value.opened!!.report.contains("Monitoring ended"))
            assertNull(fresh.state.value.activeId)
            val elapsed = fresh.state.value.opened!!.historicalReview().elapsedMillis
            advanceTimeBy(60_000); runCurrent()
            assertEquals(elapsed, fresh.state.value.opened!!.historicalReview().elapsedMillis)
            assertTrue(fresh.state.value.opened!!.copyText().contains("completion unconfirmed"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun deleteCancelsPendingSaveAndCloseCancelsPendingOpen() = runTest {
        val dir = Files.createTempDirectory("ow-drive-delete").toFile()
        try {
            val store = DriveHistoryStore(dir)
            val history = DriveHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
            runCurrent(); history.record(historyTestSession()); runCurrent()
            val id = history.state.value.drives.single().id
            history.open(id); history.closeReview(); runCurrent()
            assertNull(history.state.value.opened)
            history.record(historyTestSession(completed = true))
            history.delete(id); runCurrent()
            assertTrue(store.list().isEmpty())
            assertTrue(history.state.value.drives.isEmpty())
            history.retry(); runCurrent()
            assertTrue(store.list().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedDriveRetentionIsBoundedAndRetryCanPersistQueuedRuns() = runTest {
        val dir = Files.createTempDirectory("ow-drive-errors").toFile()
        val blocked = java.io.File(dir, "blocked-root").apply { writeText("not a directory") }
        try {
            val history = DriveHistoryCoordinator(DriveHistoryStore(blocked), backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
            runCurrent()
            repeat(25) { index -> history.record(historyTestSession(instance = index + 1L, completed = true)); runCurrent() }
            assertTrue(history.state.value.error!!.contains("queue is full"))
            assertTrue(history.state.value.drives.isEmpty())
            assertTrue(blocked.delete()); assertTrue(blocked.mkdir())
            history.retry(); runCurrent()
            assertEquals(20, history.state.value.drives.size)
            assertNull(history.state.value.error)
            assertTrue(history.state.value.drives.all { it.status == SavedDriveStatus.COMPLETED })
        } finally { dir.deleteRecursively() }
    }
}
