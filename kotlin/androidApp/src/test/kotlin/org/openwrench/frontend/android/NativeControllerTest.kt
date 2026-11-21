package org.openwrench.frontend.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import org.openwrench.frontend.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.openwrench.core.*
import org.openwrench.core.InputRequirement
import org.openwrench.core.ObservationSource
import org.openwrench.core.SignalAvailability
import org.openwrench.core.SignalValue
import org.openwrench.nativecore.DtcService
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.NativeTransport
import org.openwrench.nativecore.ObdDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fake adapters and virtual time only: no Bluetooth discovery, USB access, or Android device. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeControllerTest {
    @Test fun savingAnOlderPublishedScanAfterAnotherEcuConnectsRetainsItsAcquisitionEnvelope() = runTest {
        var wall = 1_000_000L
        fun transport(source: String) = object : NativeTransport {
            var headers = false
            val requests = mutableListOf<String>()
            override fun open() {}
            override fun close() {}
            fun frame(ecu: String, payload: String) = ecu + (payload.length / 2).toString(16).padStart(2, '0') + payload
            override fun exchange(command: String, timeoutMillis: Long): String {
                requests += command
                val reply = when (command) {
                    "ATZ", "ATI" -> "ELM327 SYNTHETIC"
                    "ATH1" -> { headers = true; "OK" }
                    "ATH0" -> { headers = false; "OK" }
                    "ATE0", "ATL0", "ATS0", "ATSP0", "ATCAF1", "ATD0" -> "OK"
                    "ATDPN" -> "A6"
                    "0100" -> if (headers) frame(source, "410008000000") + "\r" + frame("7EA", "410008000000")
                        else "410008000000\r410008000000"
                    "0105" -> frame(source, "41057D")
                    "03" -> { wall += 25; frame(source, "4300") }
                    else -> error("Unexpected synthetic request $command")
                }
                return reply + "\r>"
            }
        }
        val c = NativeController(this, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, { wall })
        val first = transport("7E8"); c.connect { first }; runCurrent()
        c.requestSnapshot(); advanceTimeBy(500); runCurrent()
        val captured = c.state.value.scanCapture!!
        assertEquals("7E8", captured.sourceEcu)
        assertEquals(1_000_000L, captured.startedAtEpochMillis)
        assertEquals(1_000_025L, captured.completedAtEpochMillis)
        assertEquals(org.openwrench.nativecore.ScanOrigin.HARDWARE, captured.origin)
        val report = DiagnosticScanDelivery.present(c.state.value).reportText()
        c.disconnect(); runCurrent(); wall = 8_000_000L
        val second = transport("7E9"); c.connect { second }; runCurrent()
        assertEquals("7E9", c.state.value.sourceEcu)
        assertEquals(captured, c.state.value.scanCapture)
        val dir = java.nio.file.Files.createTempDirectory("ow-original-provenance").toFile()
        try {
            ScanHistoryStore(dir).add(ScanHistoryCodec.newEntry(report, c.state.value.scanCaptureVin, wall, c.state.value.scanCapture))
            val reopened = ScanHistoryStore(dir).load().single()
            assertEquals(captured, reopened.capture); assertEquals(8_000_000L, reopened.savedAtEpochMillis)
            assertNull(reopened.vin)
            assertFalse(second.requests.contains("03"))
        } finally { dir.deleteRecursively() }
        c.requestSnapshot(); advanceTimeBy(500); runCurrent()
        assertTrue(c.state.value.scanCapture!!.captureId != captured.captureId)
        assertTrue(c.state.value.scanCapture!!.sessionId != captured.sessionId)
        c.disconnect(); runCurrent()
    }

    @Test fun automaticDriveHistorySavesTerminalAndInterruptedRunsWithoutChangingAdapterOwnership() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("ow-controller-drive-history").toFile()
        try {
            val store = DriveHistoryStore(dir)
            val history = DriveHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.attachDriveHistory(history)
            val transport = FakeTransport(setOf(0x01, 0x03, 0x0C, 0x0D), clock = { testScheduler.currentTime })
            controller.connect { transport }; runCurrent()
            controller.startLiveHealth(); advanceTimeBy(1_000); runCurrent()
            controller.stopLiveHealth(); runCurrent()
            assertEquals(SavedDriveStatus.COMPLETED, history.state.value.drives.single().status)
            val completed = store.read(history.state.value.drives.single().id)
            val assessment = completed.review.assessment!!
            assertEquals("standard-obd-drive", assessment.packId)
            assertEquals(DriveHealthDisposition.INSUFFICIENT,
                assessment.findings.single { it.checkId == "trim-bank-1" }.disposition)
            assertEquals(1, Regex("Vehicle health assessment:").findAll(completed.copyText()).count())
            advanceTimeBy(60_000); runCurrent()
            // Dashboard reads and later UI clocks cannot mutate a finished, saved assessment.
            assertEquals(assessment, store.read(completed.id).review.assessment)
            controller.startLiveHealth(); advanceTimeBy(1_000); runCurrent()
            controller.setActive(false); runCurrent()
            assertEquals(2, store.list().size)
            assertEquals(setOf(SavedDriveStatus.COMPLETED, SavedDriveStatus.INTERRUPTED), store.list().map { it.status }.toSet())
            val interrupted = store.read(store.list().first { it.status == SavedDriveStatus.INTERRUPTED }.id)
            assertEquals("App left the foreground", interrupted.endReason)
            assertEquals(1, transport.opens)
            assertFalse(controller.state.value.cleanupUncertain)
            assertFalse(transport.requests.any { it.first == "03" || it.first == "0902" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun failingDriveStorageDoesNotFaultTheSessionOrAddWireRequests() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("ow-controller-drive-error").toFile()
        try {
            val blocked = java.io.File(dir, "blocked").apply { writeText("not a directory") }
            suspend fun transcript(persist: Boolean): List<String> {
                val controller = NativeController(this@runTest, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
                val history = DriveHistoryCoordinator(DriveHistoryStore(blocked), backgroundScope, StandardTestDispatcher(testScheduler)) { 1_000_000L }
                if (persist) controller.attachDriveHistory(history)
                val transport = FakeTransport(setOf(0x01, 0x03, 0x0C, 0x0D), clock = { testScheduler.currentTime })
                controller.connect { transport }; runCurrent()
                controller.startLiveHealth(); advanceTimeBy(3_000); runCurrent()
                controller.stopLiveHealth(); runCurrent()
                assertTrue(controller.state.value.connected)
                assertFalse(controller.state.value.faulted)
                if (persist) assertTrue(history.state.value.error != null)
                controller.disconnect(); runCurrent()
                return transport.requests.map { it.first }
            }
            assertEquals(transcript(false), transcript(true))
        } finally { dir.deleteRecursively() }
    }

    @Test fun warningContextIsExplicitExclusiveAndRegularScansDoNotInheritIt() = runTest {
        val transport = FakeTransport(setOf(0x05, 0x0C), clock = { testScheduler.currentTime })
        transport.warningReplies = mapOf("07" to "470133\r>", "0A" to "4A0000\r>")
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        c.startLive(); advanceTimeBy(500); runCurrent()
        assertEquals(ControllerAdmission.LIVE_RUNNING, c.requestSnapshot(includeWarningContext = true))
        assertFalse(transport.requests.any { it.first == "07" })
        c.stopLive(); advanceTimeBy(500); runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, c.requestSnapshot(includeWarningContext = true))
        assertEquals(ControllerAdmission.BUSY, c.requestVin())
        advanceTimeBy(500); runCurrent()
        assertTrue(c.state.value.connected)
        val context = c.state.value.diagnosticContext!!
        assertEquals(listOf("P0133"), context.codes.single { it.service == DtcService.PENDING }.codes)
        assertEquals(1, transport.requests.count { it.first == "03" })
        assertEquals(1, transport.opens)
        assertTrue(DiagnosticScanDelivery.present(c.state.value).reportText().contains("Pending codes (07)"))
        c.requestSnapshot(); advanceTimeBy(500); runCurrent()
        assertNull(c.state.value.diagnosticContext)
        assertEquals(1, transport.requests.count { it.first == "07" })
        c.disconnect(); runCurrent()
    }

    @Test fun warningContextCancellationCannotPublishOrProceedToMoreServices() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        transport.onStoredCodeRead = { c.disconnect() }
        c.requestSnapshot(includeWarningContext = true); advanceTimeBy(500); runCurrent()
        assertNull(c.state.value.diagnosticContext)
        assertNull(c.state.value.snapshot)
        assertFalse(transport.requests.any { it.first in setOf("07", "0A", "020000") })
        assertFalse(c.state.value.connected)
    }

    @Test fun malformedPendingReplyPreservesFailedReleaseGateAndPublishesNoPartialScan() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        transport.warningReplies = mapOf("07" to "4700\r>")
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        transport.closeFailures = 2
        c.requestSnapshot(includeWarningContext = true); advanceTimeBy(500); runCurrent()
        assertTrue(c.state.value.faulted)
        assertTrue(c.state.value.cleanupUncertain)
        assertNull(c.state.value.snapshot)
        assertNull(c.state.value.diagnosticContext)
        assertFalse(transport.requests.any { it.first == "0A" })
        assertEquals(ControllerAdmission.CLEANUP_UNCERTAIN, c.requestSnapshot(includeWarningContext = true))
        c.retryRelease(); runCurrent()
        assertFalse(c.state.value.cleanupUncertain)
    }

    @Test fun monitoringTrimsReachDisplayAndPresetReusesTheirRatesThenKeepsThemAfterFinish() = runTest {
        val trims = setOf(0x06, 0x07, 0x08, 0x09)
        val transport = FakeTransport(trims + setOf(0x0C, 0x0D, 0x03), clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        c.state.value.watched.keys.filter { it != 0x0C }.forEach { c.watch(it, null) }
        c.startLiveHealth(); advanceTimeBy(5_000); runCurrent()
        val plan = c.state.value.healthPlan.acquisition
        assertTrue(trims.all { c.state.value.readings[it]?.reading?.value != null })
        assertTrue(c.state.value.dashboardSignals(testScheduler.currentTime).filter { it.definition.id in setOf("stft", "ltft", "stft-bank-2", "ltft-bank-2") }
            .all { it.availability == SignalAvailability.AVAILABLE })
        assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
        assertEquals(plan, c.state.value.healthPlan.acquisition)
        assertTrue(trims.all { c.state.value.watched[it] == plan[it] })
        c.stopLiveHealth(); advanceTimeBy(5_000); runCurrent()
        assertTrue(trims.all { it in c.state.value.readings && it in c.state.value.watched })
        assertEquals(1, transport.opens)
        c.disconnect(); runCurrent()
    }

    @Test fun showingAlreadyMonitoredTrimsDoesNotAddRequestsOrRestartTheScheduler() = runTest {
        suspend fun transcript(promote: Boolean): List<Pair<String, Long>> {
            val transport = FakeTransport(setOf(0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x03), clock = { testScheduler.currentTime })
            val c = NativeController(this@runTest, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            c.connect { transport }; runCurrent()
            c.state.value.watched.keys.filter { it != 0x0C }.forEach { c.watch(it, null) }
            c.startLiveHealth(); advanceTimeBy(3_000); runCurrent()
            val before = transport.requests.size; val at = testScheduler.currentTime
            if (promote) assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
            advanceTimeBy(5_000); runCurrent()
            val requests = transport.requests.drop(before).map { it.first to it.second - at }
            c.disconnect(); runCurrent()
            return requests
        }
        assertEquals(transcript(false), transcript(true))
    }

    @Test fun unselectedMonitorReadingsDisappearWhenMonitoringEndsWithoutThePreset() = runTest {
        val transport = FakeTransport(setOf(0x06, 0x0C), clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent(); c.watch(0x06, null)
        c.startLiveHealth(); advanceTimeBy(3_000); runCurrent()
        assertTrue(0x06 in c.state.value.readings)
        c.stopLiveHealth(); runCurrent()
        assertFalse(0x06 in c.state.value.readings)
        assertFalse(0x06 in c.state.value.watched)
        c.disconnect(); runCurrent()
    }

    @Test fun presetAddsMissingReadsBeyondFormerCapacityDuringOrdinaryLivePolling() = runTest {
        val pids = ObdDecoder.scalarPids.take(30).toSet() + setOf(0x06, 0x07, 0x08, 0x09)
        val transport = FakeTransport(pids, clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        c.state.value.watched.keys.toList().forEach { c.watch(it, null) }
        pids.filter { it !in setOf(0x06, 0x07, 0x08, 0x09) }.take(24).forEach { c.watch(it, WatchRate.LOW) }
        val before = c.state.value.watched
        assertEquals(24, before.size)
        assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
        assertEquals(before.keys + setOf(0x06, 0x07, 0x08, 0x09), c.state.value.watched.keys)
        c.stopLive(); advanceTimeBy(500); runCurrent()
        c.state.value.watched.keys.toList().forEach { c.watch(it, null) }; c.watch(0x0C, WatchRate.HIGH)
        c.startLive(); advanceTimeBy(300); runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
        assertEquals(setOf(0x0C, 0x06, 0x07, 0x08, 0x09), c.state.value.watched.keys)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(setOf(0x06, 0x07, 0x08, 0x09).all { c.state.value.readings[it]?.reading?.value != null })
        c.disconnect(); runCurrent()
    }

    @Test fun fuelTrimButtonStartsLiveFromStoppedAndNeverRequestsUnsupportedBanks() = runTest {
        val transport = FakeTransport(setOf(0x06, 0x07, 0x0C), clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        assertEquals(ControllerAdmission.DISCONNECTED, c.prepareFuelTrims())
        c.connect { transport }; runCurrent()
        c.state.value.watched.keys.filter { it != 0x0C }.forEach { c.watch(it, null) }
        assertFalse(c.state.value.liveRunning)
        assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
        assertTrue(c.state.value.liveRunning)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(c.state.value.dashboardSignals(testScheduler.currentTime).filter { it.definition.id in setOf("stft", "ltft") }
            .all { it.availability == SignalAvailability.AVAILABLE })
        assertFalse(transport.requests.any { it.first == "0108" || it.first == "0109" })
        assertEquals(1, transport.opens)
        c.disconnect(); runCurrent()
    }

    @Test fun customDashboardAddsTrimsWithoutDiscardingInFlightRpmOrEarlierLog() = runTest {
        val defaults = setOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0F, 0x10, 0x0B, 0x42)
        val trims = setOf(0x06, 0x07, 0x08, 0x09)
        val transport = FakeTransport(defaults + trims, clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        assertEquals(defaults + trims, c.state.value.watched.keys)
        trims.forEach { c.watch(it, null) }
        assertEquals(defaults, c.state.value.watched.keys)
        c.startLive(); advanceTimeBy(2_000); runCurrent()
        assertFalse(transport.requests.any { it.first in setOf("0106", "0107", "0108", "0109") })
        val generation = c.state.value.sessionGeneration
        val before = c.state.value.readings[0x0C]!!.receivedMillis
        var promoted = false
        transport.onRead = {
            if (transport.requests.last().first == "010C" && !promoted) {
                promoted = true
                assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
                assertEquals(before, c.state.value.readings[0x0C]!!.receivedMillis)
            }
        }
        advanceTimeBy(400); runCurrent()
        assertTrue(promoted)
        assertTrue(c.state.value.readings[0x0C]!!.receivedMillis > before, "In-flight RPM is retained without a scheduler-generation reset")
        advanceTimeBy(8_000); runCurrent()
        assertEquals(generation, c.state.value.sessionGeneration)
        assertEquals(defaults + trims, c.state.value.watched.keys)
        assertTrue(trims.all { c.state.value.readings[it]?.reading?.value != null })
        assertFalse(c.state.value.liveHealthEnabled, "The preset does not start health monitoring")
        val log = c.liveDiagnosticSnapshot()
        assertTrue(log.contains("live selection updated selected=13"))
        assertTrue(log.contains("0107:2000ms"))
        assertTrue(log.contains("command=010C values="))
        assertEquals(1, transport.opens)
        c.disconnect(); runCurrent()
    }

    @Test fun fuelTrimsShareMonitorOnlyReadsBeyondFormerCapacityWithoutInterruptingTheDrive() = runTest {
        val trims = setOf(0x06, 0x07, 0x08, 0x09)
        val health = LiveHealthInputs.scalarPids.toSet()
        val extra = ObdDecoder.scalarPids.filter { it !in health }.take(20).toSet()
        val transport = FakeTransport(extra + health, clock = { testScheduler.currentTime })
        val c = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        c.connect { transport }; runCurrent()
        c.state.value.watched.keys.toList().forEach { c.watch(it, null) }
        extra.forEach { c.watch(it, WatchRate.LOW) }
        c.startLiveHealth(); runCurrent()
        assertEquals(extra + health, c.state.value.healthPlan.acquisition.keys)
        val before = c.state.value
        assertTrue(trims.all { it in before.healthPlan.acquisition })
        assertEquals(ControllerAdmission.ACCEPTED, c.prepareFuelTrims())
        assertEquals(before.healthPlan.acquisition, c.state.value.healthPlan.acquisition)
        assertEquals(before.sessionGeneration, c.state.value.sessionGeneration)
        assertEquals(before.liveHealthSession, c.state.value.liveHealthSession)
        assertEquals(before.watched.keys + trims, c.state.value.watched.keys)
        assertTrue(c.state.value.liveHealthEnabled)
        c.disconnect(); runCurrent()
    }

    private class FakeTransport(
        private val pids: Set<Int> = setOf(0x0C),
        private val clock: () -> Long,
    ) : NativeTransport {
        var opens = 0
        var closes = 0
        var closeFailures = 0
        var isOpen = false
        var onRead: (() -> Unit)? = null
        var onStoredCodeRead: (() -> Unit)? = null
        var readFailure: NativeDiagnosticException? = null
        var noData = false
        var vinResponse = "NO DATA\r>"
        var scalarResponses = emptyMap<Int, String>()
        var fuelResponse = "41030200\r>"
        var dtcResponse = "43000000000000\r>"
        var warningReplies = emptyMap<String, String>()
        val requests = mutableListOf<Pair<String, Long>>()
        val timeouts = mutableListOf<Long>()

        override fun open() { opens++; isOpen = true }
        override fun close() {
            closes++
            if (closeFailures > 0) {
                closeFailures--
                throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Fake release failure")
            }
            isOpen = false
        }

        override fun exchange(command: String, timeoutMillis: Long): String {
            assertTrue(isOpen)
            requests.add(command to clock())
            timeouts += timeoutMillis
            return when (command) {
                "ATZ", "ATI" -> "ELM327 v1.5\r>"
                "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0" -> "OK\r>"
                "0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "01E0" -> {
                    val base = command.drop(2).toInt(16)
                    val nextBase = base + 0x20
                    val advertised = pids.filter { it in (base + 1) until nextBase }.toMutableSet()
                    if (pids.any { it >= nextBase }) advertised.add(nextBase)
                    val bitmap = advertised.fold(0L) { bits, pid -> bits or (1L shl (nextBase - pid)) }
                    "41${base.toString(16).padStart(2, '0')}${bitmap.toString(16).padStart(8, '0')}\r>"
                }
                "0902" -> vinResponse
                "ATDPN" -> "A3\r>"
                "03" -> { onStoredCodeRead?.invoke(); dtcResponse }
                "07", "0A", "020000" -> warningReplies[command] ?: "NO DATA\r>"
                else -> {
                    val pid = command.drop(2).toInt(16)
                    assertTrue(command.startsWith("01"))
                    assertTrue(pid in pids && (pid in ObdDecoder.scalarPids || pid == 0x03 || pid == 0x01))
                    onRead?.invoke()
                    readFailure?.let { throw it }
                    if (noData) "NO DATA\r>" else scalarResponses[pid] ?: when (pid) {
                        0x01 -> "410100070000\r>"
                        0x03 -> fuelResponse
                        0x04 -> "410480\r>"
                        0x06 -> "410680\r>"
                        0x07 -> "410780\r>"
                        0x08 -> "410880\r>"
                        0x09 -> "410980\r>"
                        0x0C -> "410C1AF8\r>"
                        0x05 -> "41057D\r>"
                        0x0D -> "410D2A\r>"
                        else -> "NO DATA\r>"
                    }
                }
            }
        }
    }

    @Test
    fun liveHealthJoinsTheSingleOwnerAndStoppingItPreservesDashboardAcquisition() = runTest {
        val transport = FakeTransport(pids = setOf(0x01, 0x03, 0x05, 0x06, 0x07, 0x0C, 0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        controller.state.value.watched.keys.filter { it != 0x0C }.forEach { controller.watch(it, null) }
        val dashboard = controller.state.value.watched
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLive())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLiveHealth())
        advanceTimeBy(5_000); runCurrent()
        val current = controller.state.value
        assertTrue(current.liveRunning && current.liveHealthEnabled)
        assertEquals(dashboard, current.watched)
        assertEquals(1, transport.opens)
        assertTrue(current.liveHealthSession!!.receiptCount > 10)
        assertTrue(transport.requests.any { it.first == "0103" })
        assertTrue(transport.requests.any { it.first == "0101" })
        assertFalse(transport.requests.any { it.first == "03" || it.first == "0902" })
        assertEquals("Moving", LiveHealthDelivery.present(current, testScheduler.currentTime).currentCondition)
        controller.stopLiveHealth(); runCurrent()
        val atStop = controller.state.value.liveHealthSession!!.receiptCount
        val requestsAtStop = transport.requests.size
        advanceTimeBy(1_000); runCurrent()
        assertTrue(controller.state.value.liveRunning)
        assertFalse(controller.state.value.liveHealthEnabled)
        assertEquals(atStop, controller.state.value.liveHealthSession!!.receiptCount)
        assertTrue(transport.requests.drop(requestsAtStop).all { it.first == "010C" })
        assertEquals(LiveHealthPhase.PAUSED, LiveHealthDelivery.present(controller.state.value).phase)
        controller.disconnect(); runCurrent()
        assertEquals(1, transport.closes)
        assertEquals(LiveHealthPhase.HISTORICAL, LiveHealthDelivery.present(controller.state.value).phase)
    }

    @Test
    fun liveHealthCanStartAcquisitionAndCannotLeakAcrossStopReconnectOrLifecycle() = runTest {
        val transport = FakeTransport(pids = setOf(0x03, 0x05, 0x0C, 0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLiveHealth())
        advanceTimeBy(2_000); runCurrent()
        assertTrue(controller.state.value.liveRunning)
        controller.stopLive(); runCurrent()
        assertFalse(controller.state.value.liveHealthEnabled)
        advanceTimeBy(200); runCurrent()
        assertEquals(LiveHealthPhase.PAUSED, LiveHealthDelivery.present(controller.state.value).phase)
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLiveHealth())
        assertEquals(0, controller.state.value.liveHealthSession!!.receiptCount)
        advanceTimeBy(1_000); runCurrent()
        val oldGeneration = controller.state.value.sessionGeneration
        controller.setActive(false); runCurrent()
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.liveHealthEnabled)
        assertEquals(LiveHealthPhase.HISTORICAL, LiveHealthDelivery.present(controller.state.value).phase)
        controller.setActive(true)
        controller.connect { transport }; runCurrent()
        assertTrue(controller.state.value.sessionGeneration > oldGeneration)
        assertEquals(LiveHealthPhase.HISTORICAL, LiveHealthDelivery.present(controller.state.value).phase)
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLiveHealth())
        assertEquals(controller.state.value.sessionGeneration, controller.state.value.liveHealthSession!!.generation)
        assertEquals(0, controller.state.value.liveHealthSession!!.receiptCount)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun liveHealthCanJoinPendingDashboardReadAndOnlySupportedStructuredInputs() = runTest {
        val transport = FakeTransport(pids = setOf(0x01, 0x03, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        var admission: ControllerAdmission? = null
        transport.onRead = {
            if (admission == null) admission = controller.startLiveHealth()
        }
        controller.startLive(); advanceTimeBy(1_000); runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, admission)
        assertEquals(1, transport.opens)
        assertTrue(controller.state.value.liveHealthSession!!.receiptCount > 0)
        assertEquals(setOf(0x01, 0x03), controller.state.value.healthPlan.structured.keys)
        controller.disconnect(); runCurrent()

        val structured = FakeTransport(pids = setOf(0x01, 0x03), clock = { testScheduler.currentTime })
        controller.connect { structured }; runCurrent()
        assertTrue(controller.state.value.watched.isEmpty())
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLiveHealth())
        advanceTimeBy(500); runCurrent()
        assertTrue(structured.requests.any { it.first == "0101" })
        assertTrue(structured.requests.any { it.first == "0103" })
        assertEquals("Unknown", LiveHealthDelivery.present(controller.state.value).currentCondition)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun liveHealthFailurePreservesReleaseGuardAndRemovesCurrentFlags() = runTest {
        val transport = FakeTransport(pids = setOf(0x01, 0x03, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        controller.startLiveHealth(); advanceTimeBy(1_000); runCurrent()
        transport.readFailure = NativeDiagnosticException(ErrorKind.TIMEOUT, "Synthetic monitor timeout")
        // Fail both the native fault cleanup and the controller's final release attempt.
        transport.closeFailures = 2
        advanceTimeBy(1_000); runCurrent()
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.liveHealthEnabled)
        assertTrue(controller.state.value.cleanupUncertain)
        assertEquals(ControllerAdmission.CLEANUP_UNCERTAIN, controller.startLiveHealth())
        val view = LiveHealthDelivery.present(controller.state.value)
        assertEquals(LiveHealthPhase.HISTORICAL, view.phase)
        assertTrue(view.flags.isEmpty())
        assertEquals("Unknown", view.currentCondition)
        controller.retryRelease(); runCurrent()
        assertFalse(controller.state.value.cleanupUncertain)
    }

    @Test
    fun liveHealthStoppingDuringReadSuppressesLateMonitorEvidence() = runTest {
        val transport = FakeTransport(pids = setOf(0x03, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        transport.onRead = { controller.stopLiveHealth() }
        controller.startLiveHealth()
        advanceTimeBy(500); runCurrent()
        assertFalse(controller.state.value.liveHealthEnabled)
        assertEquals(0, controller.state.value.liveHealthSession!!.receiptCount)
        assertTrue(controller.state.value.liveRunning)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun scanUsesConnectedSessionAndRecordsItsGenerationWithoutStartingLive() = runTest {
        val transport = FakeTransport(pids = setOf(0x05, 0x0C),
            clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) {
            testScheduler.currentTime
        }
        controller.connect { transport }; runCurrent()
        val generation = controller.state.value.sessionGeneration
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        assertTrue(controller.state.value.snapshotInProgress)
        advanceTimeBy(1_000); runCurrent()
        val captured = controller.state.value
        assertFalse(captured.snapshotInProgress)
        assertEquals(generation, captured.snapshotSessionGeneration)
        assertEquals(setOf(0x05, 0x0C), captured.snapshotEligiblePids)
        assertEquals(listOf(0x05, 0x0C), captured.snapshot?.map { it.pid })
        assertEquals(emptyList(), captured.storedDtcs?.codes)
        assertFalse(captured.liveRunning)
        assertEquals(listOf("0105", "010C", "03"), transport.requests.map { it.first }
            .filter { it == "0105" || it == "010C" || it == "03" })
        controller.disconnect(); runCurrent()
    }

    @Test
    fun guidedIdleCaptureSamplesForSixtySecondsThenScansOnSameSession() = runTest {
        var finalScanWallLag = 0L
        val transport = FakeTransport(pids = setOf(0x05, 0x06, 0x07, 0x0C, 0x0D),
            clock = { testScheduler.currentTime }).apply {
            onStoredCodeRead = { finalScanWallLag = 12_000L }
        }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler),
            wallClock = { 1_000_000L + testScheduler.currentTime + finalScanWallLag },
            clock = { testScheduler.currentTime })
        controller.connect { transport }; runCurrent()
        val generation = controller.state.value.sessionGeneration
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        assertEquals(IdleHealthPhase.CAPTURING, controller.state.value.idleHealthPhase)
        assertEquals(ControllerAdmission.BUSY, controller.requestSnapshot())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(IdleHealthPhase.CAPTURING, controller.state.value.idleHealthPhase)
        assertTrue(controller.state.value.idleHealthElapsedMillis in 29_000..30_000)
        advanceTimeBy(31_000); runCurrent()
        val completed = controller.state.value
        val report = requireNotNull(completed.idleHealthReport)
        assertEquals(IdleHealthStatus.COMPLETE, report.status)
        assertEquals(60_000L, report.elapsedMillis)
        assertEquals(72_000L, report.completedAtWallMillis - report.startedAtWallMillis,
            "Final scan completion must remain later than the 60-second capture")
        assertEquals(IdleHealthPhase.IDLE, completed.idleHealthPhase)
        assertEquals(generation, report.sessionGeneration)
        assertEquals(generation, completed.snapshotSessionGeneration)
        assertEquals(DiagnosticScanStatus.CAPTURED, report.scan?.status)
        assertEquals(emptyList(), report.scan?.storedDtcs?.codes)
        assertTrue(report.signals.first { it.pid == 0x0C }.sampleCount >= 2)
        val rpmReceipts = report.signals.first { it.pid == 0x0C }.samples.map { it.receivedAtMonotonicMillis }
        assertTrue(rpmReceipts.distinct().size >= 2)
        assertTrue(rpmReceipts.last() - rpmReceipts.first() >= 50_000)
        assertFalse(report.signals.first { it.pid == 0x42 }.supported)
        assertFalse(completed.liveRunning)
        assertEquals(1, transport.opens)
        assertEquals(1, transport.requests.count { it.first == "03" })
        assertTrue(transport.requests.none { it.first == "04" })
        controller.disconnect(); runCurrent()
        assertEquals(report, controller.state.value.idleHealthReport)
    }

    @Test
    fun cancellingIdleCaptureRetainsPartialEvidenceWithoutScanningOrDisconnecting() = runTest {
        val transport = FakeTransport(pids = setOf(0x0C, 0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        advanceTimeBy(2_200); runCurrent()
        controller.cancelIdleHealth()
        advanceTimeBy(1_000); runCurrent()
        val state = controller.state.value
        assertTrue(state.connected)
        assertFalse(state.busy)
        assertEquals(IdleHealthPhase.IDLE, state.idleHealthPhase)
        assertEquals(IdleHealthStatus.CANCELLED, state.idleHealthReport?.status)
        assertNull(state.idleHealthReport?.scan)
        assertTrue(state.idleHealthReport!!.signals.any { it.sampleCount > 0 })
        assertTrue(transport.requests.none { it.first == "03" })
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        controller.disconnect(); runCurrent()
    }

    @Test
    fun cancellingDuringFinalScanStopsBetweenReadsReleasesAdapterAndRetainsTimedEvidence() = runTest {
        val transport = FakeTransport(pids = setOf(0x0C, 0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        transport.onRead = {
            if (controller.state.value.idleHealthPhase == IdleHealthPhase.SCANNING) controller.cancelIdleHealth()
        }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        advanceTimeBy(61_000); runCurrent()
        val state = controller.state.value
        assertFalse(state.connected)
        assertFalse(state.busy)
        assertEquals(IdleHealthStatus.CANCELLED, state.idleHealthReport?.status)
        assertNull(state.idleHealthReport?.scan)
        assertTrue(state.idleHealthReport!!.signals.first { it.pid == 0x0C }.sampleCount >= 2)
        assertTrue(transport.requests.none { it.first == "03" })
        assertFalse(transport.isOpen)
    }

    @Test
    fun guidedIdleReportDoesNotReuseAnEarlierConnectionsScan() = runTest {
        val firstTransport = FakeTransport(pids = setOf(0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { firstTransport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        val oldScanGeneration = controller.state.value.snapshotSessionGeneration
        controller.disconnect(); runCurrent()

        val secondTransport = FakeTransport(pids = setOf(0x0C), clock = { testScheduler.currentTime })
        controller.connect { secondTransport }; runCurrent()
        assertTrue(controller.state.value.sessionGeneration != oldScanGeneration)
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        advanceTimeBy(1_500); runCurrent()
        controller.cancelIdleHealth()
        advanceTimeBy(1_000); runCurrent()
        assertNull(controller.state.value.idleHealthReport?.scan)
        assertEquals(IdleHealthStatus.CANCELLED, controller.state.value.idleHealthReport?.status)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun failedFinalScanLeavesPartialTimedEvidenceAndFaultsTheSession() = runTest {
        val transport = FakeTransport(pids = setOf(0x0C, 0x0D),
            clock = { testScheduler.currentTime }).apply { dtcResponse = "440000\r>" }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        advanceTimeBy(61_000); runCurrent()
        val state = controller.state.value
        assertFalse(state.connected)
        assertTrue(state.faulted)
        assertEquals(IdleHealthStatus.PARTIAL, state.idleHealthReport?.status)
        assertNull(state.idleHealthReport?.scan)
        assertTrue(state.idleHealthReport!!.signals.first { it.pid == 0x0C }.sampleCount >= 2)
        assertFalse(transport.isOpen)
    }

    @Test
    fun leavingForegroundCancelsIdleCaptureAndReleasesTheAdapter() = runTest {
        val transport = FakeTransport(pids = setOf(0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.startIdleHealth())
        advanceTimeBy(2_000); runCurrent()
        controller.setActive(false); runCurrent()
        val readsAtStop = transport.requests.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(readsAtStop, transport.requests.size)
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.busy)
        assertEquals(IdleHealthPhase.IDLE, controller.state.value.idleHealthPhase)
        assertFalse(transport.isOpen)
    }

    @Test
    fun reconnectPreservesCompletedScanUntilAnotherScanSucceeds() = runTest {
        val firstTransport = FakeTransport(pids = setOf(0x05), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { firstTransport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        val previous = controller.state.value
        assertEquals(listOf(0x05), previous.snapshot?.map { it.pid })
        assertEquals(emptyList(), previous.storedDtcs?.codes)
        controller.disconnect(); runCurrent()

        val secondTransport = FakeTransport(pids = setOf(0x0C), clock = { testScheduler.currentTime })
        controller.connect { secondTransport }
        val opening = controller.state.value
        assertEquals(previous.snapshot, opening.snapshot)
        assertEquals(previous.storedDtcs, opening.storedDtcs)
        assertEquals(previous.snapshotSessionGeneration, opening.snapshotSessionGeneration)
        assertEquals(previous.snapshotCapturedMillis, opening.snapshotCapturedMillis)
        assertEquals(previous.snapshotEligiblePids, opening.snapshotEligiblePids)
        assertEquals(DiagnosticScanStatus.HISTORICAL, DiagnosticScanDelivery.present(opening).status)
        runCurrent()
        assertEquals(previous.snapshot, controller.state.value.snapshot)
        assertEquals(previous.storedDtcs, controller.state.value.storedDtcs)
        assertEquals(DiagnosticScanStatus.HISTORICAL, DiagnosticScanDelivery.present(controller.state.value).status)

        secondTransport.readFailure = NativeDiagnosticException(ErrorKind.TIMEOUT, "Synthetic scan failure")
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(previous.snapshot, controller.state.value.snapshot)
        assertEquals(previous.snapshotCapturedMillis, controller.state.value.snapshotCapturedMillis)
        assertEquals(previous.storedDtcs, controller.state.value.storedDtcs)
        assertEquals(DiagnosticScanStatus.HISTORICAL, DiagnosticScanDelivery.present(controller.state.value).status)
    }

    @Test
    fun historicalScanExportsOnlyItsOwnVinAfterAnotherVehicleConnects() = runTest {
        val vinA = "TESTV1N1234567890"
        val vinB = "TESTV1N1234567891"
        fun encodedVinResponse(vin: String) = "490201" + vin.map { it.code.toString(16).padStart(2, '0') }
            .joinToString("").uppercase() + "\r>"
        val firstTransport = FakeTransport(pids = setOf(0x05), clock = { testScheduler.currentTime }).apply {
            vinResponse = encodedVinResponse(vinA)
        }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { firstTransport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        assertNull(controller.state.value.snapshotVin)
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestVin())
        advanceTimeBy(1_000); runCurrent()
        val scanA = controller.state.value
        assertEquals(vinA, scanA.vin)
        assertEquals(vinA, scanA.snapshotVin)
        controller.disconnect(); runCurrent()

        val secondTransport = FakeTransport(pids = setOf(0x0C), clock = { testScheduler.currentTime }).apply {
            vinResponse = encodedVinResponse(vinB)
        }
        controller.connect { secondTransport }; runCurrent()
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestVin())
        advanceTimeBy(1_000); runCurrent()
        val beforeScanB = controller.state.value
        assertEquals(vinB, beforeScanB.vin)
        assertEquals(vinA, beforeScanB.snapshotVin)
        assertEquals(listOf(0x05), beforeScanB.snapshot?.map { it.pid })
        assertEquals(vinA, SnapshotCodec.decode(SnapshotCodec.encode(beforeScanB, true,
            beforeScanB.snapshotSessionGeneration)).vin)
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(beforeScanB, false,
            beforeScanB.snapshotSessionGeneration)).vin)
        assertFailsWith<IllegalArgumentException> {
            SnapshotCodec.encode(beforeScanB, true, beforeScanB.sessionGeneration)
        }

        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        val scanB = controller.state.value
        assertEquals(vinB, scanB.snapshotVin)
        assertEquals(listOf(0x0C), scanB.snapshot?.map { it.pid })
        assertEquals(vinB, SnapshotCodec.decode(SnapshotCodec.encode(scanB, true,
            scanB.snapshotSessionGeneration)).vin)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun mode03NoDataStaysUnknownAndMalformedRerunCannotReplacePreviousScan() = runTest {
        val transport = FakeTransport(pids = setOf(0x05), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) {
            testScheduler.currentTime
        }
        controller.connect { transport }; runCurrent()
        transport.dtcResponse = "NO DATA\r>"
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        val first = controller.state.value
        assertNull(first.storedDtcs?.codes)
        assertTrue(first.connected)
        transport.dtcResponse = "44013300000000\r>"
        assertEquals(ControllerAdmission.ACCEPTED, controller.requestSnapshot())
        advanceTimeBy(1_000); runCurrent()
        val failed = controller.state.value
        assertFalse(failed.connected)
        assertEquals(first.snapshotCapturedMillis, failed.snapshotCapturedMillis)
        assertEquals(first.storedDtcs, failed.storedDtcs)
    }

    @Test
    fun disconnectImmediatelyAfterConnectDoesNotLeaveBusyOwner() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        controller.disconnect()
        runCurrent()
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.cleanupUncertain)
        assertFalse(transport.isOpen)
    }

    @Test
    fun ownerRejectsSecondConnectionAndStopsPollingOnBackground() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        var secondFactoryCalls = 0
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        assertTrue(controller.state.value.connected)
        controller.connect { secondFactoryCalls++; transport }
        assertEquals(0, secondFactoryCalls)
        controller.setActive(false)
        runCurrent()
        val count = transport.requests.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(count, transport.requests.size)
        assertFalse(transport.isOpen)
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.readings.isEmpty())
        controller.setActive(true)
        runCurrent()
        assertEquals(1, transport.opens, "Returning to the foreground requires explicit reconnect")
    }

    @Test
    fun lateReadCannotRestoreStoppedWatch() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        transport.onRead = { controller.stopLive() }
        controller.connect { transport }
        runCurrent()
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.state.value.connected)
        assertFalse(controller.state.value.liveRunning)
        assertTrue(controller.state.value.readings.isEmpty())
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun lateReadCannotRestoreDisconnectedSession() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        transport.onRead = { controller.disconnect() }
        controller.connect { transport }
        runCurrent()
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.busy)
        assertTrue(controller.state.value.readings.isEmpty())
        assertFalse(transport.isOpen)
    }

    @Test
    fun highPriorityUsesMinimumIntervalAndGlobalRequestSpacing() = runTest {
        val transport = FakeTransport(setOf(0x05, 0x0C, 0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.watch(0x05, WatchRate.HIGH)
        controller.watch(0x0C, WatchRate.HIGH)
        controller.watch(0x0D, WatchRate.HIGH)
        controller.startLive()
        advanceTimeBy(2_500)
        runCurrent()
        val reads = transport.requests.filter { it.first in setOf("0105", "010C", "010D") }
        assertTrue(reads.size >= 6)
        reads.zipWithNext().forEach { (previous, next) -> assertTrue(next.second - previous.second >= 20) }
        reads.groupBy { it.first }.values.forEach { pidReads ->
            pidReads.zipWithNext().forEach { (previous, next) -> assertTrue(next.second - previous.second >= 200) }
        }
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun allAdvertisedScalarsAreSelectedAndReadWithoutStartingAutomaticallyOrRequestingOtherPids() = runTest {
        val pids = ObdDecoder.scalarPids
        val transport = FakeTransport(pids + setOf(0x01, 0x03, 0xFF), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertTrue(controller.state.value.connected)
        assertEquals(pids, controller.state.value.watched.keys)
        assertEquals(pids, controller.state.value.healthPlan.acquisition.keys)
        assertEquals(pids.size, controller.state.value.watchPresentation().selection.size)
        assertFalse(controller.state.value.liveRunning)
        assertTrue(transport.requests.none { it.first.removePrefix("01").toIntOrNull(16) in pids })
        controller.watch(0x03, WatchRate.LOW); controller.watch(0xFF, WatchRate.LOW)
        assertEquals(pids, controller.state.value.watched.keys)
        controller.watch(0xA6, null)
        assertFalse(0xA6 in controller.state.value.watched)
        controller.watch(0xA6, WatchRate.LOW)
        assertEquals(pids, controller.state.value.watched.keys)
        assertEquals(ControllerAdmission.ACCEPTED, controller.startLive())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(pids, controller.state.value.readings.keys)
        assertTrue(controller.state.value.readings.filterKeys { it !in setOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D) }
            .values.all { it.reading.value == null }, "NO DATA is never fabricated as a value")
        assertFalse(transport.requests.any { it.first in setOf("0101", "0103", "01FF", "03", "0902") })
        controller.stopLive(); advanceTimeBy(500); runCurrent()
        val stopped = transport.requests.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(stopped, transport.requests.size)
        assertTrue(controller.state.value.readings.isEmpty())
        controller.disconnect(); runCurrent()
        assertEquals(1, transport.opens)
        assertEquals(1, transport.closes)
    }

    @Test
    fun twelveLiveSignalsPublishForSixtySecondsWithoutBlockingRpmOrStop() = runTest {
        val pids = listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x10)
        var wireMillis = 0L
        val clock = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(pids.toSet(), clock)
        transport.scalarResponses = pids.associateWith { pid ->
            "41${pid.toString(16).padStart(2, '0')}${if (pid in setOf(0x0C, 0x10)) "1AF8" else "80"}\r>"
        }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), clock)
        controller.connect { transport }; runCurrent()
        assertEquals(pids.size, controller.state.value.watched.size)
        pids.forEach { controller.watch(it, liveWatchRate(it)) }
        transport.onRead = { wireMillis += 80 }
        controller.startLive()
        val start = clock()
        while (clock() - start < 60_000) { advanceTimeBy(20); runCurrent() }
        assertTrue(controller.state.value.connected)
        assertEquals(12, controller.state.value.readings.count { it.value.reading.value != null })
        assertTrue(controller.state.value.readings.values.all { clock() - it.receivedMillis < 5_000 })
        val reads = transport.requests.filter { it.first != "0100" && it.first.startsWith("01") }
        assertTrue(reads.size >= 550, "Observed ${reads.size} total scripted reads")
        val rpm = reads.filter { it.first == "010C" }
        val rpmGaps = rpm.zipWithNext().map { (a, b) -> b.second - a.second }
        println("SCRIPTED 60s / 12 signals / 80ms replies: reads=${reads.size}, rpmReads=${rpm.size}, " +
            "rpmMeanIntervalMs=${rpmGaps.average()}, rpmMaxIntervalMs=${rpmGaps.max()}, " +
            "maxFinalReceiptAgeMs=${controller.state.value.readings.values.maxOf { clock() - it.receivedMillis }}")
        // Startup and the end boundary can exclude a receipt; measure actual spacing.
        assertTrue(rpmGaps.average() <= 510, "RPM should sustain about 2 Hz under this scripted load")
        assertTrue(rpmGaps.all { it <= 600 })
        val count = reads.size
        controller.stopLive(); advanceTimeBy(500); runCurrent()
        assertEquals(count, transport.requests.count { it.first != "0100" && it.first.startsWith("01") })
        assertTrue(controller.state.value.readings.isEmpty())
        controller.disconnect(); runCurrent()
    }

    @Test
    fun failedConnectionLogNeverShowsThePriorVehicleRun() = runTest {
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { error("adapter unavailable") }; runCurrent()
        val first = controller.liveDiagnosticSnapshot()
        assertTrue(first.contains("connection opening"))
        assertTrue(first.contains("session failure kind=IllegalStateException"))
        assertFalse(first.contains("command=010C values="))

        val transport = FakeTransport(clock = { testScheduler.currentTime })
        controller.connect { transport }; runCurrent()
        controller.startLive(); advanceTimeBy(250); runCurrent()
        assertTrue(controller.liveDiagnosticSnapshot().contains("command=010C values="))
        controller.disconnect(); runCurrent()
        controller.connect { error("reconnect unavailable") }; runCurrent()
        val retry = controller.liveDiagnosticSnapshot()
        assertTrue(retry.contains("session failure kind=IllegalStateException"))
        assertFalse(retry.contains("command=010C values="))
    }

    @Test
    fun copiedLiveDiagnosticIncludesDecodedRpmAndNoDataFromController() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        controller.startLive(); advanceTimeBy(250); runCurrent()
        assertTrue(controller.liveDiagnosticSnapshot().contains("command=010C values="))
        assertTrue(controller.liveDiagnosticSnapshot().contains("result=VALUE value=1726.0"))
        transport.noData = true
        advanceTimeBy(1_000); runCurrent()
        assertTrue(controller.liveDiagnosticSnapshot().contains("result=NO_DATA"))
        controller.stopLive(); advanceTimeBy(100); runCurrent()
        assertTrue(controller.liveDiagnosticSnapshot().contains("running=false"))
        controller.disconnect(); runCurrent()
        assertTrue(controller.liveDiagnosticSnapshot().contains("noData="))
    }

    @Test
    fun capabilityGatesBlockIneligibleSelections() = runTest {
        val eligible = ObdDecoder.scalarPids.filter { it in 1..31 }.toSet()
        val transport = FakeTransport(eligible, clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        eligible.forEach { controller.watch(it, WatchRate.NORMAL) }
        assertEquals(eligible.size, controller.state.value.watched.size)
        controller.watch(0xFF, WatchRate.HIGH)
        controller.watch(0x01, WatchRate.HIGH)
        assertEquals(eligible.size, controller.state.value.watched.size)
        assertFalse(0xFF in controller.state.value.watched)
        assertFalse(0x01 in controller.state.value.watched)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun noDataPreservesConnectionWhileProtocolFailureStopsWithoutRetry() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime }).also { it.noData = true }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.state.value.connected)
        assertNull(controller.state.value.readings[0x0C]?.reading?.value)
        assertTrue(0x0C in controller.state.value.readings)
        assertEquals(SignalAvailability.UNAVAILABLE, controller.state.value.healthObservations.last().availability)
        transport.noData = false
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(controller.state.value.healthObservations.any { it.availability == SignalAvailability.AVAILABLE })
        assertTrue(controller.state.value.healthObservations.none { it.availability == SignalAvailability.UNAVAILABLE },
            "Recovery starts a new evidence window")
        transport.readFailure = NativeDiagnosticException(ErrorKind.ADAPTER, "BUS ERROR")
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.supported.isEmpty())
        assertFalse(transport.isOpen)
        val reads = transport.requests.count { it.first == "010C" }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(reads, transport.requests.count { it.first == "010C" })
        assertTrue(controller.state.value.readings.isEmpty())
    }

    @Test
    fun uncertainCleanupBlocksNewAdapterUntilExplicitReleaseSucceeds() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        transport.closeFailures = 1
        controller.disconnect()
        runCurrent()
        assertTrue(controller.state.value.cleanupUncertain)
        var factories = 0
        controller.connect { factories++; transport }
        assertEquals(0, factories)
        controller.retryRelease()
        runCurrent()
        assertFalse(controller.state.value.cleanupUncertain)
        assertFalse(transport.isOpen)
        controller.connect { factories++; transport }
        runCurrent()
        assertEquals(1, factories)
        assertTrue(controller.state.value.connected)
        controller.disconnect()
        runCurrent()
    }


    @Test
    fun highPriorityCannotStarveOtherDueSignals() = runTest {
        val transport = FakeTransport(setOf(0x04, 0x05, 0x0C, 0x0D, 0x0F), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        listOf(0x05, 0x0C, 0x0D).forEach { controller.watch(it, WatchRate.HIGH) }
        controller.watch(0x04, WatchRate.NORMAL)
        controller.watch(0x0F, WatchRate.LOW)
        controller.startLive()
        // Under overload intervals are floors, not guarantees; allow two low-rate service windows.
        advanceTimeBy(25_000)
        runCurrent()
        assertTrue(transport.requests.count { it.first == "0104" } >= 4)
        assertTrue(transport.requests.count { it.first == "010F" } >= 2)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun connectWaitsForExplicitStartAndStopKeepsAdapterIdle() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(controller.state.value.liveRunning)
        assertEquals(0, transport.requests.count { it.first == "010C" })
        controller.startLive()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.state.value.readings.isNotEmpty())
        controller.stopLive()
        assertTrue(controller.state.value.readings.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(controller.state.value.stopPending)
        assertFalse(controller.state.value.busy)
        assertTrue(transport.isOpen)
        val count = transport.requests.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(count, transport.requests.size)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun overloadedWirePreservesPriorityShareWithoutStarvation() = runTest {
        var wireMillis = 0L
        val now = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(setOf(0x04, 0x05, 0x0C), clock = now)
        transport.onRead = { wireMillis += 1_500 }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }
        runCurrent()
        controller.watch(0x0C, WatchRate.HIGH)
        controller.watch(0x05, WatchRate.NORMAL)
        controller.watch(0x04, WatchRate.LOW)
        controller.startLive()
        advanceTimeBy(20_000)
        runCurrent()
        val high = transport.requests.count { it.first == "010C" }
        val normal = transport.requests.count { it.first == "0105" }
        val low = transport.requests.count { it.first == "0104" }
        assertTrue(high > normal, "High-priority readings receive more service under overload")
        assertTrue(normal > low)
        assertTrue(low >= 2, "Low-priority readings must not starve")
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun mixedAndFailedDemoFixturesKeepTheirAvailabilityStates() = runTest {
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.demo(DemoScenario.MIXED)
        assertFalse(0x10 in controller.state.value.supported)
        assertEquals("Stale", controller.state.value.demoLabels[0x0C])
        assertFalse(0x0C in controller.state.value.readings)
        assertNull(controller.state.value.snapshot)
        controller.demo(DemoScenario.LOAD_ERROR)
        assertTrue(controller.state.value.readings.isEmpty())
        assertTrue(controller.state.value.supported.isEmpty())
        controller.disconnect()
        assertEquals(ReadingOrigin.HARDWARE, controller.state.value.origin)
    }

    @Test
    fun simulatorAndReplayRemainExplicitlySeparateFromHardware() = runTest {
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.demo()
        assertEquals(ReadingOrigin.SIMULATOR, controller.state.value.origin)
        assertFalse(controller.state.value.connected)
        assertEquals(11, controller.state.value.readings.size)
        val replay = listOf(NativeReading(0x0C, DecodedReading.Scalar(1000.0)))
        val importedVin = "TESTV1N1234567890"
        controller.replay(replay, "Saved adapter", simulated = true, vin = importedVin)
        assertEquals(importedVin, controller.state.value.snapshotVin)
        assertEquals(importedVin, SnapshotCodec.decode(SnapshotCodec.encode(controller.state.value, true,
            controller.state.value.snapshotSessionGeneration)).vin)
        assertEquals(ReadingOrigin.REPLAY, controller.state.value.origin)
        assertFalse(controller.state.value.connected)
        assertEquals(replay, controller.state.value.snapshot)
        assertTrue(controller.state.value.readings.isEmpty())
        assertTrue(controller.state.value.healthObservations.all { it.source == ObservationSource.REPLAY })
        assertEquals(1, controller.state.value.healthObservations.map { it.sessionGeneration }.distinct().size)
        assertEquals(true, controller.state.value.healthSourceContext().replayOriginallySimulated)
    }

    @Test
    fun healthInputCannotDisplaceSelectedDashboardReadings() = runTest {
        val transport = FakeTransport(setOf(0x05, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.watch(0x05, null)
        val dashboard = controller.state.value.watched
        val update = controller.setHealthRequirements(listOf(InputRequirement("coolant", 2, 500, 5_000)))
        assertEquals(HealthPlanUpdateStatus.APPLIED, update.status)
        val plan = update.plan
        assertEquals(dashboard, plan.dashboard)
        assertEquals(HealthAdmission.CADENCE_CONFLICT, plan.decisions.single().reason)
        assertFalse(0x05 in plan.acquisition)
        assertFalse(0x05 in controller.state.value.watched)
        controller.startLive()
        val refused = controller.setHealthRequirements(listOf(InputRequirement("speed", 1, 0, 5_000)))
        assertEquals(HealthPlanUpdateStatus.RUNNING, refused.status)
        assertFalse(0x0D in controller.state.value.healthPlan.acquisition)
        advanceTimeBy(4_500)
        runCurrent()
        assertTrue(transport.requests.none { it.first == "0105" })
        assertEquals(HealthAdmission.CADENCE_CONFLICT, controller.state.value.healthPlan.decisions.single().reason)
        assertFalse(0x05 in controller.state.value.readings)
        assertTrue(controller.state.value.healthObservations.any { it.definition.pid == "010C" &&
            it.availability == SignalAvailability.AVAILABLE })
        assertTrue((controller.state.value.healthCadenceMillis[0x0C] ?: 0) > 0)
        controller.stopLive()
        assertEquals(SignalAvailability.STALE, controller.state.value.healthObservations.last().availability)
        advanceTimeBy(500)
        runCurrent()
        controller.disconnect()
        runCurrent()
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.healthObservations.any { it.availability == SignalAvailability.DISCONNECTED })
    }

    @Test
    fun healthOnlyInputUsesExistingReaderWhenDashboardSelectionIsEmpty() = runTest {
        val transport = FakeTransport(setOf(0x05, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.state.value.watched.keys.toList().forEach { controller.watch(it, null) }
        val update = controller.setHealthRequirements(listOf(InputRequirement("coolant", 2, 500, 5_000)))
        assertEquals(HealthAdmission.ADMITTED, update.plan.decisions.single().reason)
        controller.startLive()
        advanceTimeBy(4_500)
        runCurrent()
        assertTrue(transport.requests.any { it.first == "0105" })
        assertTrue(controller.state.value.readings.isEmpty())
        assertTrue(controller.state.value.healthObservations.any { it.definition.pid == "0105" &&
            it.availability == SignalAvailability.AVAILABLE })
        assertTrue((controller.state.value.healthCadenceMillis[0x05] ?: 0) > 0)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun cancellationCannotPublishLateHealthReceipt() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        transport.onRead = { controller.disconnect() }
        controller.connect { transport }
        runCurrent()
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.state.value.healthObservations.none { it.availability == SignalAvailability.AVAILABLE })
        assertTrue(controller.state.value.healthObservations.all { it.sessionGeneration == controller.state.value.sessionGeneration })
    }

    @Test
    fun lateReadFailureAfterDisconnectCannotPublishOldGenerationError() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        transport.onRead = { controller.disconnect() }
        transport.readFailure = NativeDiagnosticException(ErrorKind.ADAPTER, "Late scripted error")
        controller.connect { transport }
        runCurrent()
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.state.value.healthObservations.all { it.sessionGeneration == controller.state.value.sessionGeneration })
        assertTrue(controller.state.value.healthObservations.none { it.availability == SignalAvailability.ERROR })
        assertFalse(controller.state.value.faulted)
    }

    @Test
    fun dashboardReadRemainsFirstWhenHealthRequirementIsBlocked() = runTest {
        val transport = FakeTransport(setOf(0x05, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.watch(0x0C, WatchRate.NORMAL)
        controller.watch(0x05, null)
        controller.setHealthRequirements(listOf(InputRequirement("coolant", 1, 0, 5_000)))
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("010C", transport.requests.first { it.first in setOf("010C", "0105") }.first)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun slowScriptedHealthReadCannotMakeRpmGaugeStale() = runTest {
        var wireMillis = 0L
        val now = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(setOf(0x05, 0x0C), clock = now)
        var readsStarted = 0
        transport.onRead = { if (++readsStarted > 1) wireMillis += 3_000 }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }
        runCurrent()
        controller.watch(0x05, null)
        controller.setHealthRequirements(listOf(InputRequirement("coolant", 2, 500, 5_000)))
        controller.startLive()
        advanceTimeBy(20_000)
        runCurrent()
        val reads = transport.requests.filter { it.first in setOf("010C", "0105") }
        assertTrue(reads.count { it.first == "010C" } >= 4)
        assertTrue(reads.none { it.first == "0105" }, "Slow dashboard reads leave no safe health capacity")
        val completedRpm = controller.state.value.healthObservations.filter { it.definition.pid == "010C" &&
            it.availability == SignalAvailability.AVAILABLE }.map { it.receivedAtMonotonicMillis }
        assertTrue(completedRpm.size >= 4)
        assertTrue(completedRpm.zipWithNext().all { (a, b) -> b - a < 5_000 })
        assertEquals(HealthAdmission.CADENCE_CONFLICT, controller.state.value.healthPlan.decisions.single().reason)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun disconnectedPlanCannotReusePreviousVehicleCapabilities() = runTest {
        val first = FakeTransport(setOf(0x0C), clock = { testScheduler.currentTime })
        val second = FakeTransport(setOf(0x0D), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { first }
        runCurrent()
        controller.disconnect()
        runCurrent()
        assertTrue(controller.state.value.supported.isEmpty())
        val deferred = controller.setHealthRequirements(listOf(InputRequirement("rpm", 1, 0, 5_000)))
        assertEquals(HealthPlanUpdateStatus.DEFERRED_DISCONNECTED, deferred.status)
        assertEquals(HealthAdmission.NOT_ADVERTISED, deferred.plan.decisions.single().reason)
        controller.connect { second }
        runCurrent()
        assertEquals(HealthAdmission.NOT_ADVERTISED, controller.state.value.healthPlan.decisions.single().reason)
        assertFalse(0x0C in controller.state.value.healthPlan.acquisition)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun structuredHealthOnlyReceiptsKeepTypeProvenanceAndSupersedeNoData() = runTest {
        val transport = FakeTransport(setOf(0x03, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        controller.state.value.watched.keys.toList().forEach { controller.watch(it, null) }
        val update = controller.setHealthRequirements(listOf(InputRequirement("fuel-system-status", 1, 0, 5_000)))
        assertEquals(HealthAdmission.ADMITTED, update.plan.decisions.single().reason)
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        val first = controller.state.value.healthObservations.single()
        assertEquals("0103", first.definition.pid)
        assertEquals(null, first.definition.module)
        assertEquals(null, first.sourceTimestamp)
        assertEquals(ObservationSource.HARDWARE, first.source)
        assertEquals(controller.state.value.sessionGeneration, first.sessionGeneration)
        assertEquals(transport.requests.last { it.first == "0103" }.second, first.receivedAtMonotonicMillis)
        assertEquals("closed loop: oxygen-sensor feedback",
            (first.value as SignalValue.Structured).fields["system_1"])
        assertTrue(controller.state.value.readings.isEmpty())
        transport.noData = true
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(SignalAvailability.UNAVAILABLE, controller.state.value.healthObservations.single().availability)
        assertEquals(null, controller.state.value.healthObservations.single().value)
        transport.noData = false
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(controller.state.value.healthObservations.isNotEmpty())
        assertTrue(controller.state.value.healthObservations.all { it.availability == SignalAvailability.AVAILABLE },
            "Recovery replaces NO DATA and retains only new valid receipts")
        controller.stopLive()
        assertEquals(SignalAvailability.STALE, controller.state.value.healthObservations.single().availability)
        runCurrent()
        controller.disconnect()
        runCurrent()
        assertEquals(SignalAvailability.DISCONNECTED, controller.state.value.healthObservations.single().availability)
    }

    @Test
    fun structuredRequestDefersBehindDashboardAndCannotPublishAfterDisconnect() = runTest {
        val transport = FakeTransport(setOf(0x03, 0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }
        runCurrent()
        val blocked = controller.setHealthRequirements(listOf(InputRequirement("fuel-system-status", 1, 0, 5_000)))
        assertEquals(HealthAdmission.CADENCE_CONFLICT, blocked.plan.decisions.single().reason)
        controller.startLive()
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(transport.requests.none { it.first == "0103" })
        controller.stopLive()
        advanceTimeBy(300)
        runCurrent()
        controller.state.value.watched.keys.toList().forEach { controller.watch(it, null) }
        assertEquals(HealthAdmission.ADMITTED, controller.state.value.healthPlan.decisions.single().reason)
        transport.onRead = { if (transport.requests.last().first == "0103") controller.disconnect() }
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(transport.requests.any { it.first == "0103" })
        assertTrue(controller.state.value.healthObservations.none { it.availability == SignalAvailability.AVAILABLE })
        assertTrue(controller.state.value.healthObservations.all { it.sessionGeneration == controller.state.value.sessionGeneration })
    }

    @Test
    fun structuredProtocolFaultReleasesAndReplacementRechecksCapabilities() = runTest {
        val first = FakeTransport(setOf(0x03), clock = { testScheduler.currentTime }).also {
            it.fuelResponse = "410302\r>"
        }
        val second = FakeTransport(setOf(0x0C), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.setHealthRequirements(listOf(InputRequirement("fuel-system-status", 1, 0, 5_000)))
        controller.connect { first }
        runCurrent()
        assertEquals(HealthAdmission.ADMITTED, controller.state.value.healthPlan.decisions.single().reason)
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(controller.state.value.faulted)
        assertFalse(controller.state.value.connected)
        assertFalse(first.isOpen)
        assertEquals(SignalAvailability.ERROR, controller.state.value.healthObservations.single().availability)
        controller.connect { second }
        runCurrent()
        assertEquals(HealthAdmission.NOT_ADVERTISED, controller.state.value.healthPlan.decisions.single().reason)
        assertTrue(controller.state.value.healthObservations.isEmpty())
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun slowHealthOnlyScalarCannotInterleaveWithStructuredFuelRead() = runTest {
        var wireMillis = 0L
        val now = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(setOf(0x03, 0x05), clock = now)
        transport.onRead = { wireMillis += 3_000 }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }
        runCurrent()
        controller.state.value.watched.keys.toList().forEach { controller.watch(it, null) }
        val update = controller.setHealthRequirements(listOf(
            InputRequirement("coolant", 1, 0, 5_000),
            InputRequirement("fuel-system-status", 1, 0, 5_000)))
        assertEquals(listOf(HealthAdmission.ADMITTED, HealthAdmission.CADENCE_CONFLICT),
            update.plan.decisions.map { it.reason })
        assertTrue(update.plan.structured.isEmpty())
        controller.startLive()
        advanceTimeBy(12_000)
        runCurrent()
        assertTrue(transport.requests.count { it.first == "0105" } >= 3)
        assertTrue(transport.requests.none { it.first == "0103" })
        assertTrue(controller.state.value.healthObservations.all { it.definition.pid == "0105" })
        val latest = controller.state.value.healthObservations.last()
        val freshness = controller.state.value.healthInputFreshness(now()).single()
        assertEquals(now() - latest.receivedAtMonotonicMillis, freshness.receiptAgeMillis)
        assertEquals(SignalAvailability.AVAILABLE, freshness.latestAvailability)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun slowStructuredReadIsDeferredWheneverGaugeIsSelected() = runTest {
        var wireMillis = 0L
        val now = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(setOf(0x03, 0x0C), clock = now)
        transport.onRead = { wireMillis += 3_000 }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }
        runCurrent()
        controller.setHealthRequirements(listOf(InputRequirement("fuel-system-status", 2, 500, 5_000)))
        controller.startLive()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(transport.requests.none { it.first == "0103" })
        val rpm = controller.state.value.healthObservations.filter { it.definition.pid == "010C" &&
            it.availability == SignalAvailability.AVAILABLE }.map { it.receivedAtMonotonicMillis }
        assertTrue(rpm.size >= 3)
        assertTrue(rpm.zipWithNext().all { (a, b) -> b - a < 5_000 })
        assertEquals(HealthAdmission.CADENCE_CONFLICT, controller.state.value.healthPlan.decisions.single().reason)
        controller.disconnect()
        runCurrent()
    }

    @Test
    fun stopDuringSlowStructuredReadCannotPublishLateValue() = runTest {
        var wireMillis = 0L
        val now = { testScheduler.currentTime + wireMillis }
        val transport = FakeTransport(setOf(0x03), clock = now)
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        transport.onRead = {
            if (transport.requests.last().first == "0103") {
                wireMillis += 3_000
                controller.stopLive()
            }
        }
        controller.setHealthRequirements(listOf(InputRequirement("fuel-system-status", 1, 0, 5_000)))
        controller.connect { transport }
        runCurrent()
        assertTrue(controller.state.value.watched.isEmpty())
        controller.startLive()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, transport.requests.count { it.first == "0103" })
        assertFalse(controller.state.value.liveRunning)
        assertTrue(controller.state.value.healthObservations.none { it.availability == SignalAvailability.AVAILABLE })
        assertEquals(SignalAvailability.STALE, controller.state.value.healthObservations.single().availability)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, transport.requests.count { it.first == "0103" })
        controller.disconnect()
        runCurrent()
        assertFalse(transport.isOpen)
    }
    private fun syntheticProfile(mixed: Boolean = false, samples: Int = 1,
        budget: Long = 20_000, cap: Int = 12, context: Set<String> = emptySet()) = AssessmentProfile(
        AssessmentProfileIdentity("synthetic-controller-test", "1"), AssessmentMode.SYNTHETIC_MIXED_READ_ONLY,
        listOf(AssessmentInput(InputRequirement("rpm", samples, if (samples == 2) 100 else 0,
            5_000, "010C", null, "rpm", SignalType.NUMBER), context)) +
            if (mixed) listOf(AssessmentInput(InputRequirement("fuel-system-status", samples,
                if (samples == 2) 100 else 0, 5_000, "0103", null, null, SignalType.STRUCTURED))) else emptyList(),
        emptyList(), budget, cap, 500)

    private fun syntheticInputsProfile(pids: List<Int>, samples: Int = 1) = AssessmentProfile(
        AssessmentProfileIdentity("synthetic-consolidated-cohort", "1"), AssessmentMode.SYNTHETIC_MIXED_READ_ONLY,
        pids.map { pid ->
            val definition = NativeSignalCatalog.definition(pid, SignalSupport.SUPPORTED)
            AssessmentInput(InputRequirement(definition.id, samples, if (samples == 2) 100 else 0,
                5_000, definition.pid, expectedUnit = definition.unit, expectedType = definition.type))
        }, emptyList(), 20_000, pids.size * samples, 1_000)

    @Test
    fun focusedSixInputCohortCompletesAtExactCommandCap() = runTest {
        val pids = listOf(3, 6, 7, 12, 5, 4)
        val transport = FakeTransport(pids.toSet(), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        val watch = controller.state.value.watched
        val plan = controller.state.value.healthPlan
        controller.startAssessment(syntheticInputsProfile(pids), "six-inputs", AssessmentOperation.FOCUSED)
        advanceTimeBy(2_000); runCurrent()
        val result = controller.state.value.assessment!!
        assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
        assertEquals(6, result.attemptsDispatched)
        assertEquals(pids.toSet(), result.selectedEvidence.values.flatten()
            .map { it.definition.pid!!.drop(2).toInt(16) }.toSet())
        assertEquals(transport.requests.drop(8).map { it.second }.sorted(),
            result.selectedEvidence.values.flatten().map { it.receivedAtMonotonicMillis }.sorted())
        assertEquals(setOf("system_1", "system_2"), (result.selectedEvidence.getValue("fuel-system-status")
            .single().value as SignalValue.Structured).fields.keys)
        assertEquals(List(6) { 3_000L }, transport.timeouts.takeLast(6))
        assertEquals(watch, controller.state.value.watched)
        assertEquals(plan, controller.state.value.healthPlan)
        assertFalse(controller.state.value.liveRunning)
        val count = transport.requests.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(count, transport.requests.size)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun passiveRepeatedMixedDashboardCohortLeavesExactWireLogUnchanged() = runTest {
        val logs = mutableListOf<List<Pair<String, Long>>>()
        for (passive in listOf(false, true)) {
            val start = testScheduler.currentTime
            val transport = FakeTransport(setOf(5, 12), clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            controller.startLive()
            if (passive) controller.startAssessment(syntheticInputsProfile(listOf(12, 5), samples = 2),
                "passive-mixed", AssessmentOperation.PASSIVE)
            advanceTimeBy(4_000); runCurrent()
            if (passive) {
                val result = controller.state.value.assessment!!
                assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
                assertEquals(0, result.attemptsDispatched)
                assertEquals(mapOf("rpm" to 2, "coolant" to 2), result.selectedEvidence.mapValues { it.value.size })
            }
            controller.disconnect(); runCurrent()
            logs += transport.requests.map { it.first to it.second - start }
        }
        assertEquals(logs[0], logs[1])
    }

    @Test
    fun passiveNeverConsumesHealthOnlyStructuredReceipts() = runTest {
        val transport = FakeTransport(setOf(3, 12), clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        controller.watch(12, null)
        val profile = syntheticInputsProfile(listOf(3)).copy(totalBudgetMillis = 1_000)
        controller.setHealthRequirements(profile.required.map { it.requirement })
        controller.startLive()
        controller.startAssessment(profile, "health-only", AssessmentOperation.PASSIVE)
        advanceTimeBy(1_100); runCurrent()
        assertTrue(transport.requests.any { it.first == "0103" })
        val result = controller.state.value.assessment!!
        assertEquals(AcquisitionState.PARTIAL, result.state)
        assertEquals(0, result.attemptsDispatched)
        assertTrue(InputQualificationReason.MISSING in result.inputs.single().reasons)
        assertTrue(result.selectedEvidence.isEmpty())
        controller.disconnect(); runCurrent()
    }

    @Test
    fun focusedCompletionAtDeadlineCannotPublish() = runTest {
        var wire = 0L
        val now = { testScheduler.currentTime + wire }
        val transport = FakeTransport(clock = now)
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }; runCurrent()
        transport.onRead = { wire += 3_900 }
        controller.startAssessment(syntheticProfile(budget = 4_000, cap = 1), "late", AssessmentOperation.FOCUSED)
        advanceTimeBy(500); runCurrent()
        val result = controller.state.value.assessment!!
        assertEquals(AcquisitionState.PARTIAL, result.state)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, result.limitReason)
        assertEquals(1, result.attemptsDispatched)
        assertTrue(result.selectedEvidence.isEmpty())
        assertEquals(1, transport.requests.count { it.first == "010C" })
        assertEquals(3_000L, transport.timeouts.last())
        controller.disconnect(); runCurrent()
    }

    @Test
    fun admissionDistinguishesBackgroundStoppingAndEmptySelection() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        assertEquals(ControllerAdmission.DISCONNECTED, controller.startLive())
        controller.connect { transport }; runCurrent()
        controller.watch(12, null)
        assertEquals(ControllerAdmission.NO_INPUTS, controller.startLive())
        controller.watch(12, WatchRate.HIGH)
        controller.startLive(); controller.stopLive()
        assertEquals(ControllerAdmission.STOPPING, controller.requestVin())
        assertEquals(ControllerAdmission.STOPPING, controller.requestSnapshot())
        assertEquals(ControllerAdmission.STOPPING, controller.startLive())
        assertEquals(ControllerAdmission.STOPPING,
            controller.startAssessment(syntheticProfile(), "stopping", AssessmentOperation.FOCUSED))
        controller.setActive(false); runCurrent()
        assertEquals(ControllerAdmission.NOT_FOREGROUND, controller.requestVin())
        assertEquals(ControllerAdmission.NOT_FOREGROUND, controller.requestSnapshot())
        assertEquals(ControllerAdmission.NOT_FOREGROUND, controller.startLive())
        assertEquals(ControllerAdmission.NOT_FOREGROUND,
            controller.startAssessment(syntheticProfile(), "background", AssessmentOperation.FOCUSED))
    }

    @Test
    fun replacementCancelsOldInstanceAndDoesNotQueueBehindPendingRead() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        val profile = syntheticProfile()
        transport.onRead = {
            assertEquals(ControllerAdmission.BUSY,
                controller.replaceAssessment(profile, "blocked-replacement", AssessmentOperation.FOCUSED))
            assertEquals("old", controller.state.value.assessment?.instanceId)
            assertEquals(AcquisitionState.CANCELLED, controller.state.value.assessment?.state)
        }
        controller.startAssessment(profile, "old", AssessmentOperation.FOCUSED)
        advanceTimeBy(500); runCurrent()
        assertEquals(AcquisitionState.CANCELLED, controller.state.value.assessment?.state)
        assertEquals(1, transport.requests.count { it.first == "010C" })
        transport.onRead = null
        assertEquals(ControllerAdmission.ACCEPTED,
            controller.replaceAssessment(profile, "replacement", AssessmentOperation.FOCUSED))
        advanceTimeBy(500); runCurrent()
        assertEquals("replacement", controller.state.value.assessment?.instanceId)
        assertEquals(AcquisitionState.EVIDENCE_READY, controller.state.value.assessment?.state)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun passiveCollectionLeavesExactDashboardWireLogUnchanged() = runTest {
        val logs = mutableListOf<List<Pair<String, Long>>>()
        for (passive in listOf(false, true)) {
            val start = testScheduler.currentTime
            val transport = FakeTransport(setOf(0x03, 0x0C), clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            controller.startLive()
            if (passive) assertEquals(ControllerAdmission.ACCEPTED,
                controller.startAssessment(syntheticProfile(samples = 2), "passive", AssessmentOperation.PASSIVE))
            advanceTimeBy(2_000); runCurrent()
            if (passive) {
                assertEquals(AcquisitionState.EVIDENCE_READY, controller.state.value.assessment?.state)
                assertEquals(0, controller.state.value.assessment?.attemptsDispatched)
            }
            logs += transport.requests.map { it.first to it.second - start }
            controller.disconnect(); runCurrent()
        }
        assertEquals(logs[0], logs[1])
        assertTrue(logs[1].none { it.first == "0103" })
    }

    @Test
    fun focusedMixedCohortPreservesWatchRatesAndUsesIndividualCompletionTimes() = runTest {
        var wire = 0L
        val now = { testScheduler.currentTime + wire }
        val transport = FakeTransport(setOf(0x03, 0x0C), now)
        transport.onRead = { wire += 17 }
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), now)
        controller.connect { transport }; runCurrent()
        controller.watch(0x0C, WatchRate.LOW)
        val watch = controller.state.value.watched
        val plan = controller.state.value.healthPlan
        assertEquals(ControllerAdmission.ACCEPTED,
            controller.startAssessment(syntheticProfile(mixed = true, samples = 2, cap = 4), "mixed", AssessmentOperation.FOCUSED))
        advanceTimeBy(1_000); runCurrent()
        val result = controller.state.value.assessment!!
        assertEquals(AcquisitionState.EVIDENCE_READY, result.state)
        assertEquals(4, result.attemptsDispatched)
        val reads = transport.requests.filter { it.first in setOf("0103", "010C") }
        assertEquals(4, reads.size)
        val receipts = result.selectedEvidence.values.flatten().map { it.receivedAtMonotonicMillis }.sorted()
        assertEquals(reads.map { it.second + 17 }, receipts)
        assertEquals(watch, controller.state.value.watched)
        assertEquals(plan, controller.state.value.healthPlan)
        assertTrue(controller.state.value.readings.isEmpty())
        assertFalse(controller.state.value.liveRunning)
        assertTrue(transport.timeouts.drop(8).all { it == 3_000L })
        controller.disconnect(); runCurrent()
    }

    @Test
    fun exclusiveOperationsRejectImmediatelyWithoutHiddenQueue() = runTest {
        for (first in listOf("vin", "snapshot", "focused", "passive", "live")) {
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            val admitted = when (first) {
                "vin" -> controller.requestVin()
                "snapshot" -> controller.requestSnapshot()
                "live" -> controller.startLive()
                else -> controller.startAssessment(syntheticProfile(), first,
                    if (first == "passive") AssessmentOperation.PASSIVE else AssessmentOperation.FOCUSED)
            }
            assertEquals(ControllerAdmission.ACCEPTED, admitted)
            val refused = if (first == "live") ControllerAdmission.LIVE_RUNNING else ControllerAdmission.BUSY
            assertEquals(refused, controller.requestVin())
            assertEquals(refused, controller.requestSnapshot())
            assertEquals(refused, controller.startLive())
            assertEquals(refused, controller.startAssessment(syntheticProfile(), "blocked", AssessmentOperation.FOCUSED))
            controller.disconnect(); runCurrent()
            assertTrue(transport.requests.none { it.first == "0902" || it.first == "010C" })
        }
    }

    @Test
    fun focusedAdmissionWaitsForStoppedReadAndContextMapping() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        assertEquals(ControllerAdmission.CONTEXT_DEFERRED,
            controller.startAssessment(syntheticProfile(context = setOf("operating-state")), "context", AssessmentOperation.FOCUSED))
        transport.onRead = {
            controller.stopLive()
            assertEquals(ControllerAdmission.STOPPING,
                controller.startAssessment(syntheticProfile(), "too-early", AssessmentOperation.FOCUSED))
        }
        controller.startLive(); advanceTimeBy(100); runCurrent()
        assertEquals(ControllerAdmission.STOPPING,
            controller.startAssessment(syntheticProfile(), "still-stopping", AssessmentOperation.FOCUSED))
        advanceTimeBy(300); runCurrent()
        transport.onRead = null
        assertEquals(ControllerAdmission.ACCEPTED,
            controller.startAssessment(syntheticProfile(), "stopped", AssessmentOperation.FOCUSED))
        advanceTimeBy(500); runCurrent()
        assertEquals(AcquisitionState.EVIDENCE_READY, controller.state.value.assessment?.state)
        assertFalse(controller.state.value.liveRunning)
        controller.disconnect(); runCurrent()
    }

    @Test
    fun focusedBudgetAccountsForRemainingPacingAndFullExchangeAtBoundary() = runTest {
        for (budget in listOf(3_199L, 3_200L, 3_201L)) {
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            controller.startAssessment(syntheticProfile(budget = budget), "budget", AssessmentOperation.FOCUSED)
            advanceTimeBy(100); runCurrent()
            val fits = budget == 3_201L
            assertEquals(if (fits) 1 else 0, transport.requests.count { it.first == "010C" })
            assertEquals(if (fits) AcquisitionState.EVIDENCE_READY else AcquisitionState.PARTIAL,
                controller.state.value.assessment?.state)
            if (!fits) assertEquals(AcquisitionLimitReason.TIME_BUDGET, controller.state.value.assessment?.limitReason)
            assertEquals(if (fits) 1 else 0, controller.state.value.assessment?.attemptsDispatched)
            assertTrue(transport.timeouts.drop(8).all { it == 3_000L })
            controller.disconnect(); runCurrent()
        }
    }

    @Test
    fun delayedIoEqualityRefusesUnsentCommandAndReturnsSlot() = runTest {
        var dispatchDelay = 0L
        val delayedIo = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                    delay(dispatchDelay)
                    block.run()
                }
            }
        }
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, delayedIo) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        dispatchDelay = 900
        controller.startAssessment(syntheticProfile(budget = 4_000), "equality", AssessmentOperation.FOCUSED)
        advanceTimeBy(1_000); runCurrent() // first owner iteration at 100, IO starts at 1000
        val result = controller.state.value.assessment!!
        assertEquals(AcquisitionState.PARTIAL, result.state)
        assertEquals(AcquisitionLimitReason.TIME_BUDGET, result.limitReason)
        assertEquals(0, result.attemptsDispatched)
        assertEquals(0, transport.requests.count { it.first == "010C" })
        assertTrue(controller.state.value.connected)
        dispatchDelay = 0
        controller.disconnect(); runCurrent()
    }

    @Test
    fun noDataConsumesCapAndCanRecoverWithoutRetryingFaults() = runTest {
        for (recover in listOf(false, true)) {
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            var count = 0
            transport.onRead = { transport.noData = ++count == 1 || !recover }
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            controller.startAssessment(syntheticProfile(cap = 2), "no-data", AssessmentOperation.FOCUSED)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, count)
            assertEquals(if (recover) AcquisitionState.EVIDENCE_READY else AcquisitionState.PARTIAL,
                controller.state.value.assessment?.state)
            if (!recover) {
                assertEquals(AcquisitionLimitReason.COMMAND_CAP, controller.state.value.assessment?.limitReason)
                assertTrue(InputQualificationReason.NO_DATA in controller.state.value.assessment!!.inputs.single().reasons)
            }
            assertTrue(controller.state.value.connected)
            controller.disconnect(); runCurrent()
        }
    }

    @Test
    fun assessmentFaultRetainsFailedReleaseProtection() = runTest {
        val transport = FakeTransport(clock = { testScheduler.currentTime })
        val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        controller.connect { transport }; runCurrent()
        transport.closeFailures = 2
        transport.readFailure = NativeDiagnosticException(ErrorKind.TIMEOUT, "Synthetic timeout")
        controller.startAssessment(syntheticProfile(), "fault", AssessmentOperation.FOCUSED)
        advanceTimeBy(500); runCurrent()
        assertEquals(AcquisitionState.FAILED, controller.state.value.assessment?.state)
        assertTrue(controller.state.value.cleanupUncertain)
        assertEquals(ControllerAdmission.CLEANUP_UNCERTAIN, controller.startLive())
        var factories = 0
        controller.connect { factories++; transport }
        assertEquals(0, factories)
        assertEquals(1, transport.requests.count { it.first == "010C" })
        controller.retryRelease(); runCurrent()
        assertFalse(controller.state.value.cleanupUncertain)
    }

    @Test
    fun cancellationLifecycleAndReplacementSuppressLateCompletion() = runTest {
        for (stop in listOf("cancel", "background", "disconnect")) {
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            transport.onRead = {
                when (stop) {
                    "cancel" -> controller.cancelAssessment()
                    "background" -> controller.setActive(false)
                    else -> controller.disconnect()
                }
                if (stop == "cancel") assertEquals(ControllerAdmission.BUSY,
                    controller.startAssessment(syntheticProfile(), "replacement-too-early", AssessmentOperation.FOCUSED))
            }
            controller.startAssessment(syntheticProfile(), "old", AssessmentOperation.FOCUSED)
            advanceTimeBy(500); runCurrent()
            assertEquals(AcquisitionState.CANCELLED, controller.state.value.assessment?.state)
            assertTrue(controller.state.value.assessment!!.selectedEvidence.isEmpty())
            if (stop == "cancel") {
                transport.onRead = null
                assertEquals(ControllerAdmission.ACCEPTED,
                    controller.startAssessment(syntheticProfile(), "replacement", AssessmentOperation.FOCUSED))
                advanceTimeBy(500); runCurrent()
                assertEquals("replacement", controller.state.value.assessment?.instanceId)
                assertEquals(AcquisitionState.EVIDENCE_READY, controller.state.value.assessment?.state)
            } else {
                controller.setActive(true); advanceTimeBy(500); runCurrent()
                assertFalse(controller.state.value.connected)
                assertEquals(1, transport.opens)
            }
            controller.disconnect(); runCurrent()
        }
    }

    @Test
    fun delayedIoRechecksBudgetAndCancellationBeforeSending() = runTest {
        for (cancel in listOf(false, true)) {
            var dispatchDelay = 0L
            val delayedIo = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                        delay(dispatchDelay)
                        block.run()
                    }
                }
            }
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            val controller = NativeController(this, delayedIo) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            dispatchDelay = 5_000
            controller.startAssessment(syntheticProfile(budget = 4_000), "queued-io", AssessmentOperation.FOCUSED)
            advanceTimeBy(100); runCurrent()
            if (cancel) controller.cancelAssessment()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(0, transport.requests.count { it.first == "010C" })
            assertEquals(if (cancel) AcquisitionState.CANCELLED else AcquisitionState.PARTIAL,
                controller.state.value.assessment?.state)
            if (!cancel) {
                assertEquals(AcquisitionLimitReason.TIME_BUDGET, controller.state.value.assessment?.limitReason)
                assertEquals(0, controller.state.value.assessment?.attemptsDispatched)
            }
            dispatchDelay = 0
            controller.disconnect(); runCurrent()
        }
    }

    @Test
    fun oneInjectedElapsedClockDrivesReceiptsFreshnessAndReplayAcrossSuspendGap() = runTest {
        var elapsed = 20_000L
        val clock = platformClock { elapsed }
        val transport = FakeTransport(clock = clock::elapsedMillis)
        val controller = NativeController(this, StandardTestDispatcher(testScheduler), clock::elapsedMillis)
        controller.connect { transport }; runCurrent()
        controller.startLive(); advanceTimeBy(100); runCurrent()
        val captured = controller.state.value
        val receipt = captured.readings.getValue(0x0C).receivedMillis
        assertEquals(clock.elapsedMillis(), receipt)
        assertEquals(receipt, captured.healthObservations.single().receivedAtMonotonicMillis)
        assertEquals(0L, captured.dashboardSignals(clock.elapsedMillis()).single { it.definition.id == "rpm" }.ageMillis)
        val original = "2020-01-01T00:00:00Z"
        val recording = CivicRecording(CivicCaptureMetadata("Synthetic fixture", original,
            "backend_observation", "Synthetic clock test"), 20_000,
            listOf(nativeSignalDefinition(0x0C, SignalSupport.SUPPORTED)),
            listOf(RecordedObservation(0, "rpm", SignalAvailability.AVAILABLE,
                SignalValue.Number(700.0), original, 0, null),
                RecordedObservation(10_000, "rpm", SignalAvailability.AVAILABLE,
                    SignalValue.Number(900.0), original, 0, null)))
        val player = CivicReplayPlayer(backgroundScope, clock)
        player.load(ReplayLoadResult.Loaded(recording)); player.play(); runCurrent()
        // elapsedRealtime includes device suspend; no wall/UTC timestamp becomes a receipt.
        elapsed += 6_000
        advanceTimeBy(250); runCurrent()
        val row = captured.dashboardSignals(clock.elapsedMillis()).single { it.definition.id == "rpm" }
        assertEquals(6_000L, row.ageMillis)
        assertEquals(SignalAvailability.STALE, row.availability)
        assertEquals(6_000L, captured.healthInputFreshness(clock.elapsedMillis()).single().receiptAgeMillis)
        assertEquals(6_000L, player.state.value.positionMillis)
        assertEquals(original, player.state.value.recording!!.capture.capturedAtUtc)
        val incompatible = captured.copy(readings = mapOf(0x0C to captured.readings.getValue(0x0C).copy(receivedMillis = elapsed + 1)))
        val rejected = incompatible.dashboardSignals(elapsed).single { it.definition.id == "rpm" }
        assertNull(rejected.ageMillis)
        assertNull(rejected.value)
        assertEquals(SignalAvailability.STALE, rejected.availability)
        val foreignObservation = captured.healthObservations.single().copy(receivedAtMonotonicMillis = elapsed + 1)
        assertNull(captured.copy(healthObservations = listOf(foreignObservation)).healthInputFreshness(elapsed).single().receiptAgeMillis)
        player.close(); controller.disconnect(); runCurrent()
    }

    @Test
    fun passiveSessionFaultIsFailedAndLateFocusedFaultCannotReplaceCancellation() = runTest {
        for (passive in listOf(true, false)) {
            val transport = FakeTransport(clock = { testScheduler.currentTime })
            val controller = NativeController(this, StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
            controller.connect { transport }; runCurrent()
            if (passive) controller.startLive()
            controller.startAssessment(syntheticProfile(samples = 2), "fault", if (passive) AssessmentOperation.PASSIVE else AssessmentOperation.FOCUSED)
            transport.onRead = { if (!passive) controller.cancelAssessment() }
            transport.readFailure = NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Synthetic framing fault")
            advanceTimeBy(500); runCurrent()
            assertEquals(if (passive) AcquisitionState.FAILED else AcquisitionState.CANCELLED,
                controller.state.value.assessment?.state)
            assertTrue(controller.state.value.assessment!!.selectedEvidence.isEmpty())
            assertFalse(controller.state.value.connected)
            assertFalse(transport.isOpen)
            assertEquals(1, transport.requests.count { it.first == "010C" })
            controller.disconnect(); runCurrent()
        }
    }

}
