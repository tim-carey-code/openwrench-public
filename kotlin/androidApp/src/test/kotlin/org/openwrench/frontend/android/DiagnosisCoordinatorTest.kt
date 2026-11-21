package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.openwrench.nativecore.*
import org.openwrench.frontend.DashboardSource
import org.openwrench.frontend.diagnosisDriveHomePresentation
import org.openwrench.frontend.watchPresentation
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Scripted ELM responses and virtual clocks only; never opens a physical transport. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosisCoordinatorTest {
    private class Adapter(private val pids: Set<Int>? = null, private val numericScalars: Boolean = false) : NativeTransport {
        // Decoder-valid fictional all-zero payloads; only used by the full-catalog bench.
        private val scalarFrames by lazy { ObdDecoder.scalarPids.associateWith { pid ->
            (1..8).map { size -> "41" + pid.toString(16).padStart(2, '0') + "00".repeat(size) }
                .first { response -> runCatching { ObdDecoder.decode(pid, response) }.isSuccess } + "\r>"
        } }
        val requests = mutableListOf<String>()
        var onScan: (() -> Unit)? = null
        var coolant = "41057D\r>"
        var scanStoredAvailable = true
        var opens = 0
        var closeFailures = 0
        override fun open() { opens++ }
        override fun close() {
            if (closeFailures-- > 0) throw NativeDiagnosticException(ErrorKind.TRANSPORT, "scripted release failed")
        }
        override fun exchange(command: String, timeoutMillis: Long): String {
            requests += command
            if (pids != null && command.startsWith("01")) {
                val pid = command.drop(2).toInt(16)
                if (pid in ObdDecoder.rangeBases) {
                    val nextBase = pid + 0x20
                    val advertised = pids.filter { it in (pid + 1) until nextBase }.toMutableSet()
                    if (pids.any { it >= nextBase }) advertised.add(nextBase)
                    val bitmap = advertised.fold(0L) { bits, item -> bits or (1L shl (nextBase - item)) }
                    return "41${command.drop(2)}${bitmap.toString(16).padStart(8, '0')}\r>"
                }
                check(pid in pids) { "Unadvertised request: $command" }
                if (numericScalars && pid in ObdDecoder.scalarPids) return scalarFrames.getValue(pid)
            }
            return when (command) {
                "ATZ", "ATI" -> "ELM327 v1.5\r>"
                "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0" -> "OK\r>"
                "ATDPN" -> "A3\r>"
                "0100" -> "4100BE190000\r>" // 01,03,04,05,06,07,0C,0D,10
                "0101" -> "410100070000\r>"
                "0103" -> "41030200\r>"
                "0104" -> "410440\r>"
                "0105" -> coolant
                "0106" -> "410680\r>"
                "0107" -> "410778\r>"
                "010C" -> "410C0C80\r>"
                "010D" -> "410D00\r>"
                "0110" -> "NO DATA\r>"
                "015C" -> "415C78\r>"
                "03" -> { onScan?.invoke(); if (scanStoredAvailable) "430000\r>" else "NO DATA\r>" }
                "07", "0A", "020000" -> "NO DATA\r>"
                else -> if (pids != null && command.startsWith("01") && command.drop(2).toInt(16) in ObdDecoder.scalarPids) "NO DATA\r>" else error("Unexpected command: $command")
            }
        }
    }

    private class Harness(val scope: TestScope,
        val dir: File = Files.createTempDirectory("ow-diagnosis-coordinator").toFile(),
        maximumSessionBytes: Long = DiagnosticSessionStore.MAX_SESSION_BYTES,
        maximumSessions: Int = DiagnosticSessionStore.MAX_SESSIONS,
        diagnosisDispatcher: CoroutineDispatcher? = null,
        historyDispatcher: CoroutineDispatcher? = null,
        private val deleteOnClose: Boolean = true,
        supportedPids: Set<Int>? = null,
        numericScalars: Boolean = false,
        rejectAppend: () -> Boolean = { false },
    ) : AutoCloseable {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = NativeController(scope.backgroundScope, dispatcher, { scope.testScheduler.currentTime }, { 1_000_000 + scope.testScheduler.currentTime })
        val drives = DriveHistoryStore(dir)
        val history = DriveHistoryCoordinator(drives, scope.backgroundScope, historyDispatcher ?: dispatcher) { 1_000_000 + scope.testScheduler.currentTime }
        val store = DiagnosticSessionStore(dir, maximumSessionBytes, maximumSessions, driveProvider = drives::read,
            appendBytes = { file, bytes ->
                if (rejectAppend()) throw IllegalArgumentException("Diagnosis recording limit reached; scripted quota rejection")
                file.write(bytes)
            })
        val diagnosis = DiagnosisCoordinator(controller, history, store, scope.backgroundScope, diagnosisDispatcher ?: dispatcher,
            { scope.testScheduler.currentTime }, { 1_000_000 + scope.testScheduler.currentTime })
        val adapter = Adapter(supportedPids, numericScalars)
        val scans = ScanHistoryCoordinator(ScanHistoryStore(dir), scope.backgroundScope, dispatcher)
        init {
            controller.attachDriveHistory(history)
            scope.backgroundScope.launch { scans.autoSaveCompletedScans(controller.state) }
        }
        fun connect() { controller.connect { adapter }; scope.runCurrent() }
        fun elapse(ms: Long = 1_000) { scope.advanceTimeBy(ms); scope.runCurrent() }
        override fun close() { controller.disconnect(); scope.runCurrent(); if (deleteOnClose) dir.deleteRecursively() }
    }

    /** Holds only coordinator disk work; native reads and its lifecycle owner keep progressing. */
    private class DiskGate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var held = false
        private val queued = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val waiting get() = queued.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) queued.addLast(context to block) else delegate.dispatch(context, block)
        }
        fun releaseOne() {
            val (context, block) = queued.removeFirst()
            delegate.dispatch(context, block)
        }
        fun releaseAll() {
            held = false
            while (queued.isNotEmpty()) releaseOne()
        }
    }

    private fun zipText(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return entries
    }

    @Test fun guidedMilestoneKeepsOneRecordingAndPersistsReceiptProofThroughOfflineFinish() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(120_000)
            val running = h.diagnosis.state.value
            assertEquals(DiagnosisPhase.RECORDING, running.phase, running.error)
            val guidance = assertNotNull(running.guidance)
            assertTrue(guidance.initialAssessmentAvailable, guidance.reportText())
            assertEquals(org.openwrench.core.GuidedBankStatus.UNAVAILABLE, guidance.banks[1].status)
            assertEquals(org.openwrench.core.GuidedDiagnosisPolicy.MILESTONE, running.message)
            val id = running.current!!.id
            val samples = running.current.segments.single().sampleCount
            h.elapse(10_000)
            assertTrue(h.diagnosis.state.value.current!!.segments.single().sampleCount > samples)
            assertEquals(guidance.initialAssessmentOffsetMillis, h.diagnosis.state.value.guidance!!.initialAssessmentOffsetMillis)
            assertEquals(1, h.diagnosis.state.value.current!!.segments.size)
            h.controller.disconnect(); h.elapse()
            assertEquals(DiagnosisPhase.INTERRUPTED, h.diagnosis.state.value.phase)
            h.diagnosis.finish(); h.elapse(2_000)
            val saved = h.store.read(id)
            assertEquals(DiagnosisStatus.COMPLETED, saved.status)
            assertEquals(guidance.initialAssessmentOffsetMillis, saved.segments.single().guidance!!.initialAssessmentOffsetMillis)
            val out = ByteArrayOutputStream(); h.diagnosis.export(id, out)
            val exported = zipText(out.toByteArray())
            assertContains(exported.getValue("report.txt"), "Guided screening policy: 1")
            assertEquals(saved, DiagnosisCodec.decode(exported.getValue("manifest.json")))
            assertEquals(1, DriveReplayImport.read(out.toByteArray().inputStream()).size)
        }
    }

    @Test fun coldClosedLoopAndPartialScanRetainHonestPartialResults() = runTest {
        for (cold in listOf(true, false)) Harness(this).use { h ->
            runCurrent(); h.connect()
            if (cold) h.adapter.coolant = "41056F\r>" // 71 C
            else h.adapter.scanStoredAvailable = false
            h.diagnosis.start(); h.elapse(90_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertFalse(h.diagnosis.state.value.guidance!!.initialAssessmentAvailable)
            assertEquals(cold, h.diagnosis.state.value.guidance!!.scanSucceeded)
            h.diagnosis.finish(); h.elapse(2_000)
            val saved = h.store.read(h.diagnosis.state.value.current!!.id)
            assertEquals(DiagnosisStatus.COMPLETED, saved.status)
            assertFalse(saved.segments.single().guidance!!.initialAssessmentAvailable)
            assertContains(saved.reportText(), "Partial assessment")
            assertTrue(saved.segments.single().sampleCount > 0)
        }
    }

    @Test fun quotaRejectingFirstQualifyingBatchCannotPersistItsMilestone() = runTest {
        var reject: () -> Boolean = { false }
        Harness(this, rejectAppend = { reject() }).use { h ->
            reject = { h.diagnosis.state.value.guidance?.initialAssessmentAvailable == true &&
                h.diagnosis.state.value.current?.segments?.lastOrNull()?.guidance?.initialAssessmentAvailable != true }
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(120_000)
            assertEquals(DiagnosisPhase.INTERRUPTED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            val saved = h.store.read(h.diagnosis.state.value.current!!.id)
            assertTrue(saved.segments.single().droppedSamples > 0)
            assertFalse(saved.segments.single().guidance!!.initialAssessmentAvailable)
            assertFalse(h.diagnosis.state.value.guidance!!.initialAssessmentAvailable)
            val out = ByteArrayOutputStream(); h.diagnosis.export(saved.id, out)
            assertFalse(DiagnosisCodec.decode(zipText(out.toByteArray()).getValue("manifest.json"))
                .segments.single().guidance!!.initialAssessmentAvailable)
        }
    }

    @Test fun continuedDiagnosisUsesFreshSegmentEvidenceWithoutRepeatingTheMilestone() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(120_000)
            assertTrue(h.diagnosis.state.value.guidance!!.initialAssessmentAvailable)
            val id = h.diagnosis.state.value.current!!.id
            h.diagnosis.pause(); h.elapse(2_000)
            h.diagnosis.resume(id, true); h.elapse(3_000)
            assertFalse(h.diagnosis.state.value.guidance!!.initialAssessmentAvailable)
            h.elapse(120_000)
            assertTrue(h.diagnosis.state.value.guidance!!.initialAssessmentAvailable)
            assertFalse(h.diagnosis.state.value.message == org.openwrench.core.GuidedDiagnosisPolicy.MILESTONE)
            assertEquals(2, h.diagnosis.state.value.current!!.segments.size)
        }
    }

    @Test fun retainedSessionOptionsFinishPausedAndInterruptedEvidenceOffline() = runTest {
        for (interrupted in listOf(false, true)) {
            Harness(this).use { h ->
                runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
                if (!interrupted) { h.diagnosis.pause(); h.elapse() }
                h.controller.disconnect(); h.elapse()
                val before = h.diagnosis.state.value
                assertEquals(if (interrupted) DiagnosisPhase.INTERRUPTED else DiagnosisPhase.PAUSED, before.phase)
                val id = before.current!!.id
                val requests = h.adapter.requests.size
                val controls = diagnosisDriveHomePresentation(before.phase, DashboardSource.HARDWARE,
                    h.controller.state.value.watchPresentation(), adapterSelected = true, canStart = false, busy = before.busy)
                assertTrue(controls.retainedFinishEnabled)
                assertFalse(controls.continueEnabled)
                if (controls.retainedFinishEnabled) h.diagnosis.finish()
                h.elapse()
                assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
                assertEquals(id, h.diagnosis.state.value.current!!.id)
                assertEquals(requests, h.adapter.requests.size, "Offline finish must not contact the adapter")
                assertEquals(DiagnosisStatus.COMPLETED, h.store.read(id).status)
                assertTrue(h.store.read(id).segments.single().sampleCount > 0L)
            }
        }
    }

    @Test fun startAndFinishAutomaticallyScanRecordSaveAndExportWithoutVehicleWrites() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect()
            h.controller.startLive(); h.elapse()
            h.diagnosis.start(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(1, h.diagnosis.state.value.current!!.scanReports.size)
            h.elapse(5_000)
            val running = h.diagnosis.state.value.current!!
            assertTrue(running.segments.single().sampleCount > 0)
            assertEquals(h.controller.state.value.liveHealthSession!!.startedAtMillis, running.segments.single().startedAtMonotonicMillis)
            h.diagnosis.finish(); h.elapse(2_000)
            val record = h.diagnosis.state.value.current!!
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(DiagnosisStatus.COMPLETED, h.store.read(record.id).status)
            assertEquals(2, record.scanReports.size)
            val savedScans = ScanHistoryStore(h.dir).load()
            assertEquals(2, savedScans.size, "Both automatic diagnosis scans also reach standalone history")
            assertEquals(2, savedScans.map { it.capture!!.captureId }.distinct().size)
            assertTrue(savedScans.all { it.vin == null && it.capture!!.origin == ScanOrigin.HARDWARE })
            val savedDrive = h.drives.read(record.segments.single().driveId!!)
            assertEquals(DriveEvidenceOrigin.HARDWARE, savedDrive.origin)
            val case = DiagnosticCaseBuilder.create(savedScans.first(), savedDrive, true)
            DiagnosticCaseStore(h.dir).add(case)
            assertEquals(case, DiagnosticCaseStore(h.dir).load().single())
            assertEquals(savedDrive, h.store.readDrive(record.id, savedDrive.id))
            assertFalse(h.controller.state.value.liveRunning)
            assertEquals(1, h.adapter.opens)
            assertEquals(2, h.adapter.requests.count { it == "03" })
            assertTrue(h.adapter.requests.none { it in setOf("04", "08", "0902") || it.startsWith("2E") })
            val output = ByteArrayOutputStream()
            h.diagnosis.export(record.id, output)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
            }
            val aiReport = h.diagnosis.aiReport(record.id)
            assertContains(aiReport, record.reportText())
            assertContains(aiReport, savedDrive.copyText())
            assertContains(aiReport, "Recorded origin: HARDWARE")
            assertFalse(DiagnosticSessionStore.CSV_HEADER in aiReport)
            val csv = entries.getValue("readings.csv")
            assertTrue(csv.contains("NO_DATA"))
            assertEquals(record.segments.sumOf { it.sampleCount }.toInt(), csv.lines().filter { it.isNotBlank() }.size - 1)
            assertTrue(entries.keys.any { it.startsWith("charts/") })
            assertTrue(entries.getValue("report.txt").contains("Interpretation"))
            val index = Json.parseToJsonElement(entries.getValue("ai-evidence.json")).jsonObject
            assertEquals(record.id, index.getValue("diagnosis_id").jsonPrimitive.content)
            val reference = index.getValue("drives").jsonArray.single().jsonObject
            assertEquals("HARDWARE", reference.getValue("origin").jsonPrimitive.content)
            assertTrue(reference.getValue("assessment_present").jsonPrimitive.boolean)
            assertTrue(entries.containsKey(reference.getValue("archive").jsonPrimitive.content))
            assertTrue(entries.containsKey(reference.getValue("charts").jsonPrimitive.content))
            assertContains(entries.getValue("ai-request.md"), "Next distinguishing checks")
            assertFalse(index.getValue("cloud_upload_performed").jsonPrimitive.boolean)
        }
    }

    @Test fun diagnosisAcquiresAllScalarsDespiteReducedSelectionAndRestoresItAcrossPauseResume() = runTest {
        val pids = ObdDecoder.scalarPids
        Harness(this, supportedPids = pids + setOf(0x01, 0x03, 0xFF)).use { h ->
            runCurrent(); h.connect()
            pids.filter { it != 0x0C }.forEach { h.controller.watch(it, null) }
            val selection = h.controller.state.value.watched
            assertEquals(setOf(0x0C), selection.keys)
            h.diagnosis.start(); h.elapse(35_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(pids, h.controller.state.value.healthPlan.acquisition.keys)
            assertEquals(selection, h.controller.state.value.watched)
            val id = h.diagnosis.state.value.current!!.id
            h.diagnosis.pause(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.PAUSED, h.diagnosis.state.value.phase)
            assertFalse(h.controller.state.value.diagnosisAllScalars)
            assertEquals(selection.keys, h.controller.state.value.healthPlan.acquisition.keys)
            val stopped = h.adapter.requests.size
            h.elapse(2_000)
            assertEquals(stopped, h.adapter.requests.size)
            h.diagnosis.resume(id, true); h.elapse(35_000)
            assertEquals(pids, h.controller.state.value.healthPlan.acquisition.keys)
            h.diagnosis.finish(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(selection, h.controller.state.value.watched)
            assertFalse(h.controller.state.value.diagnosisAllScalars)
            val out = ByteArrayOutputStream()
            h.diagnosis.export(id, out)
            val csv = zipText(out.toByteArray()).getValue("readings.csv")
            pids.forEach { pid -> assertTrue(csv.contains("\"01${pid.toString(16).uppercase().padStart(2, '0')}\""), "Missing $pid") }
            assertTrue(csv.contains("\"015C\",\"80.0\""))
            assertTrue(csv.contains("NO_DATA"))
            val segments = h.store.read(id).segments
            assertEquals(2, segments.size)
            segments.forEach { segment ->
                val drive = h.drives.read(segment.driveId!!)
                assertEquals(pids, drive.review.series.map { it.pid }.toSet())
                assertTrue(drive.review.series.all { it.points.isNotEmpty() })
                assertEquals("°C", drive.review.series.single { it.pid == 0x5C }.unit)
                assertTrue(drive.review.series.single { it.pid == 0x5C }.points.any { it.value == 80.0 })
                assertTrue(drive.review.series.single { it.pid == 0xA6 }.points.all { it.value == null })
            }
            val imported = DriveReplayImport.read(out.toByteArray().inputStream())
            assertEquals(2, imported.size)
            assertTrue(imported.all { it.review.series.map { series -> series.pid }.toSet() == pids })
            assertFalse(h.adapter.requests.any { it in setOf("01FF", "04", "08", "0902") })
            assertEquals(1, h.adapter.opens)
        }
    }

    @Test fun ordinaryMonitorSavesAllAdvertisedScalarsWithoutChangingDisplaySelection() = runTest {
        val pids = ObdDecoder.scalarPids
        Harness(this, supportedPids = pids + setOf(0x01, 0x03, 0xFF), numericScalars = true).use { h ->
            runCurrent(); h.connect()
            pids.filter { it != 0x0C }.forEach { h.controller.watch(it, null) }
            val selection = h.controller.state.value.watched
            assertEquals(ControllerAdmission.ACCEPTED, h.controller.startLiveHealth(allSupportedScalars = true))
            h.elapse(35_000)
            val id = h.history.state.value.activeId!!
            assertEquals(selection, h.controller.state.value.watched)
            assertEquals(pids, h.controller.state.value.healthPlan.acquisition.keys)
            h.controller.stopLiveHealth(); h.elapse(2_000)
            val saved = h.drives.read(id)
            assertEquals(SavedDriveStatus.COMPLETED, saved.status)
            assertEquals(pids, saved.review.series.map { it.pid }.toSet())
            assertTrue(saved.review.series.all { it.points.size >= 2 && it.points.all { point -> point.value != null } })
            assertEquals(saved, DriveReplayImport.read(DriveHistoryCodec.encode(saved).byteInputStream()).single())
            assertEquals(selection.keys, h.controller.state.value.healthPlan.acquisition.keys)
            assertTrue(h.store.list().isEmpty(), "Ordinary recording must not invent a diagnosis session")
            assertFalse(h.adapter.requests.any { it in setOf("01FF", "03", "04", "08", "0902") })
        }
    }

    @Test fun diagnosticDriveCanRecordScalarOnlyVehicleWithoutInventingHealthInputs() = runTest {
        Harness(this, supportedPids = setOf(0x5C)).use { h ->
            runCurrent(); h.connect(); h.controller.watch(0x5C, null)
            h.diagnosis.start(); h.elapse(5_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(setOf(0x5C), h.controller.state.value.healthPlan.acquisition.keys)
            assertTrue(h.controller.state.value.liveHealthSession!!.admitted.isEmpty())
            h.diagnosis.finish(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            val out = ByteArrayOutputStream()
            h.diagnosis.export(h.diagnosis.state.value.current!!.id, out)
            assertTrue(zipText(out.toByteArray()).getValue("readings.csv").contains("\"015C\",\"80.0\""))
            assertTrue(h.controller.state.value.watched.isEmpty())
        }
    }

    @Test fun foregroundInterruptionRetainsOneParentAndResumeNeedsExplicitSameVehicleConfirmation() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            val id = h.diagnosis.state.value.current!!.id
            h.controller.setActive(false); h.elapse()
            assertEquals(DiagnosisPhase.INTERRUPTED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertTrue(h.store.read(id).segments.single().sampleCount > 0)
            val requests = h.adapter.requests.size
            h.elapse(2_000)
            assertEquals(requests, h.adapter.requests.size)
            h.controller.setActive(true); h.connect()
            h.diagnosis.resume(id, false); h.elapse()
            assertFalse(h.controller.state.value.liveHealthEnabled)
            h.diagnosis.resume(id, true); h.elapse(3_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(id, h.diagnosis.state.value.current!!.id)
            assertEquals(2, h.diagnosis.state.value.current!!.segments.size)
            assertEquals(2, h.diagnosis.state.value.current!!.segments.map { it.generation }.distinct().size)
            h.diagnosis.finish(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
        }
    }

    @Test fun failedInitialScanCannotAttachAnEarlierSnapshotOrBypassFailedRelease() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect()
            h.controller.requestSnapshot(); h.elapse()
            assertNotNull(h.controller.state.value.snapshot)
            h.adapter.closeFailures = 2
            h.adapter.onScan = { h.controller.setActive(false) }
            h.diagnosis.start(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.INTERRUPTED, h.diagnosis.state.value.phase)
            assertTrue(h.diagnosis.state.value.current!!.scanReports.isEmpty())
            assertTrue(h.controller.state.value.cleanupUncertain)
            h.diagnosis.start(); h.elapse()
            assertEquals(1, h.adapter.opens)
            assertTrue(h.controller.state.value.cleanupUncertain)
        }
    }

    @Test fun pausedSessionCanFinishOfflineAndIsNeverReplacedBySimulatorEvidence() = runTest {
        Harness(this).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            h.diagnosis.pause(); h.elapse()
            assertEquals(DiagnosisPhase.PAUSED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            h.controller.disconnect(); h.elapse()
            h.controller.demo(); runCurrent()
            h.diagnosis.finish(); h.elapse()
            val record = h.diagnosis.state.value.current!!
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertEquals(1, record.scanReports.size)
            assertTrue(record.note!!.contains("No final connected scan"))
            h.diagnosis.start(); runCurrent()
            assertEquals(record.id, h.diagnosis.state.value.current!!.id)
        }
    }

    @Test fun unwritableStoragePreventsDiagnosisFromStartingAnyNewHardwareReads() = runTest {
        val dir = Files.createTempDirectory("ow-diagnosis-blocked").toFile()
        try {
            val blocked = java.io.File(dir, "blocked").apply { writeText("not a directory") }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val controller = NativeController(backgroundScope, dispatcher) { testScheduler.currentTime }
            val history = DriveHistoryCoordinator(DriveHistoryStore(dir), backgroundScope, dispatcher) { 1_000_000 }
            controller.attachDriveHistory(history)
            val diagnosis = DiagnosisCoordinator(controller, history, DiagnosticSessionStore(blocked), backgroundScope,
                dispatcher, { testScheduler.currentTime }, { 1_000_000 })
            val adapter = Adapter()
            runCurrent(); controller.connect { adapter }; runCurrent()
            val before = adapter.requests.toList()
            diagnosis.start(); advanceTimeBy(1_000); runCurrent()
            assertEquals(before, adapter.requests)
            assertNotNull(diagnosis.state.value.error)
            assertFalse(controller.state.value.liveHealthEnabled)
            controller.disconnect(); runCurrent()
        } finally { dir.deleteRecursively() }
    }

    @Test fun reopenedPausedDiagnosisCannotScanDifferentConnectionWhenProcessGenerationRepeats() = runTest {
        Harness(this).use { first ->
            runCurrent(); first.connect(); first.diagnosis.start(); first.elapse(3_000)
            first.diagnosis.pause(); first.elapse()
            val saved = first.diagnosis.state.value.current!!
            assertEquals(DiagnosisStatus.PAUSED, first.store.read(saved.id).status)
            first.controller.disconnect(); first.elapse()
            Harness(this, dir = first.dir, deleteOnClose = false).use { reopened ->
                runCurrent(); reopened.connect()
                assertNotSame(first.adapter, reopened.adapter)
                assertEquals(saved.segments.single().generation, reopened.controller.state.value.sessionGeneration,
                    "The new process intentionally reuses the old numeric generation")
                reopened.diagnosis.open(saved.id); reopened.elapse()
                assertEquals(DiagnosisPhase.PAUSED, reopened.diagnosis.state.value.phase)
                val before = reopened.adapter.requests.toList()
                reopened.diagnosis.finish(); reopened.elapse()
                val finished = reopened.store.read(saved.id)
                assertEquals(DiagnosisStatus.COMPLETED, finished.status, reopened.diagnosis.state.value.error)
                assertEquals(saved.scanReports, finished.scanReports)
                assertEquals(before, reopened.adapter.requests, "Historical finish must not scan the unrelated connection")
                assertContains(finished.note.orEmpty(), "No final connected scan")
            }
        }
    }

    @Test fun failedNewCreateAndFailedOpenNeverRewriteThePreviousCompletedDiagnosis() = runTest {
        Harness(this, maximumSessions = 1).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            h.diagnosis.finish(); h.elapse(2_000)
            val saved = h.diagnosis.state.value.current!!
            assertEquals(DiagnosisStatus.COMPLETED, saved.status)
            val file = File(h.dir, "diagnoses-v1/${saved.id}/manifest.json")
            val original = file.readBytes()
            val requests = h.adapter.requests.toList()
            h.diagnosis.start(); h.elapse()
            assertNotNull(h.diagnosis.state.value.error)
            assertEquals(saved.id, h.diagnosis.state.value.current!!.id)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase)
            assertContentEquals(original, file.readBytes())
            assertEquals(DiagnosisStatus.COMPLETED, h.store.read(saved.id).status)
            assertEquals(requests, h.adapter.requests)

            val damagedId = UUID.randomUUID().toString()
            val damaged = File(h.dir, "diagnoses-v1/$damagedId").apply { mkdirs() }
            File(damaged, "manifest.json").writeText("{damaged")
            h.diagnosis.open(damagedId); h.elapse()
            assertNotNull(h.diagnosis.state.value.error)
            assertEquals(saved.id, h.diagnosis.state.value.current!!.id)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase)
            assertContentEquals(original, file.readBytes())
            assertEquals(DiagnosisStatus.COMPLETED, h.store.read(saved.id).status)
            assertEquals("{damaged", File(damaged, "manifest.json").readText())
        }
    }

    @Test fun pausingWhileReceiptAppendIsDelayedDrainsBothBatchesBeforeClosingSegment() = runTest {
        val gate = DiskGate(StandardTestDispatcher(testScheduler))
        try { Harness(this, diagnosisDispatcher = gate).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase)
            gate.held = true
            h.elapse(1_000)
            assertTrue(gate.waiting > 0, "Periodic flush should be waiting to save its open header")
            gate.releaseOne(); runCurrent()
            assertTrue(gate.waiting > 0, "The first receipt append is now held after the batch snapshot")
            val firstBatchReceipts = h.controller.state.value.liveHealthSession!!.receiptCount
            h.elapse(2_000)
            assertTrue(h.controller.state.value.liveHealthSession!!.receiptCount > firstBatchReceipts,
                "Adapter readings continue independently while disk work is held")
            h.diagnosis.pause(); runCurrent()
            assertEquals(DiagnosisPhase.PAUSING, h.diagnosis.state.value.phase)
            val expectedReceipts = h.controller.state.value.liveHealthSession!!.receiptCount
            gate.releaseAll(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.PAUSED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertNull(h.diagnosis.state.value.error)
            val record = h.diagnosis.state.value.current!!
            val saved = h.store.read(record.id)
            assertEquals(DiagnosisStatus.PAUSED, saved.status)
            assertEquals(expectedReceipts, saved.segments.single().sampleCount)
            assertEquals(0L, saved.segments.single().droppedSamples)
            assertNotNull(saved.segments.single().endedAtOffsetMillis)
            val out = ByteArrayOutputStream(); h.diagnosis.export(record.id, out)
            val raw = zipText(out.toByteArray()).getValue("readings.csv")
            assertEquals(expectedReceipts, (raw.lineSequence().count { it.isNotBlank() } - 1).toLong())
        } } finally { gate.releaseAll(); runCurrent() }
    }

    @Test fun exhaustedRecordingQuotaMarksDroppedReceiptsAndAllowsExportAndAnotherDiagnosis() = runTest {
        Harness(this, maximumSessionBytes = 65_536).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            for (ignored in 0 until 300) {
                if (h.diagnosis.state.value.phase == DiagnosisPhase.INTERRUPTED) break
                h.elapse()
            }
            assertEquals(DiagnosisPhase.INTERRUPTED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertFalse(h.diagnosis.state.value.busy)
            assertFalse(h.controller.state.value.liveHealthEnabled)
            val record = h.diagnosis.state.value.current!!
            val saved = h.store.read(record.id)
            assertEquals(DiagnosisStatus.INTERRUPTED, saved.status)
            assertTrue(saved.segments.single().sampleCount > 0)
            assertTrue(saved.segments.single().droppedSamples > 0, "Quota loss must be explicit")
            val out = ByteArrayOutputStream(); h.diagnosis.export(record.id, out)
            val exported = zipText(out.toByteArray())
            assertContains(exported.getValue("report.txt"), "samples not retained: ${saved.segments.single().droppedSamples}")
            assertEquals(saved.segments.single().sampleCount,
                (exported.getValue("readings.csv").lineSequence().count { it.isNotBlank() } - 1).toLong())
            h.diagnosis.refresh(); h.elapse()
            assertNull(h.diagnosis.state.value.error)
            h.diagnosis.start(); h.elapse(2_000)
            assertEquals(DiagnosisPhase.RECORDING, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertNotEquals(record.id, h.diagnosis.state.value.current!!.id,
                "A quota failure cannot leave an unretryable pending queue blocking the next diagnosis")
        }
    }

    @Test fun finishWaitsForDelayedDriveWriterBeforePreservingTerminalAssessmentAndCharts() = runTest {
        val gate = DiskGate(StandardTestDispatcher(testScheduler))
        try { Harness(this, historyDispatcher = gate).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            val id = h.diagnosis.state.value.current!!.id
            val driveId = h.diagnosis.state.value.current!!.segments.single().driveId!!
            assertEquals(SavedDriveStatus.CHECKPOINT, h.drives.read(driveId).status)
            gate.held = true
            h.elapse(2_000)
            h.diagnosis.finish(); h.elapse(2_000)
            assertTrue(gate.waiting > 0, "The terminal drive write is held independently of diagnosis persistence")
            assertEquals(DiagnosisPhase.FINISHING, h.diagnosis.state.value.phase)
            assertTrue(h.diagnosis.state.value.busy)
            assertEquals(SavedDriveStatus.CHECKPOINT, h.drives.read(driveId).status)
            val terminalMonitor = h.controller.state.value.liveHealthSession!!
            val terminalReview = terminalMonitor.driveReview!!
            assertNotNull(terminalMonitor.stoppedAtMillis)

            gate.releaseAll(); h.elapse()
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            val diagnosis = h.store.read(id)
            assertEquals(DiagnosisStatus.COMPLETED, diagnosis.status)
            assertEquals(terminalMonitor.receiptCount, diagnosis.segments.single().sampleCount)
            val archived = assertNotNull(h.diagnosis.readDrive(id, driveId))
            assertEquals(SavedDriveStatus.COMPLETED, archived.status)
            assertEquals(terminalReview.elapsedMillis, archived.review.elapsedMillis)
            assertEquals(terminalReview.signals, archived.review.signals,
                "Every final per-signal sample count and range must survive the delayed terminal write")
            assertEquals(terminalReview.assessment, archived.review.assessment)
        } } finally { gate.releaseAll(); runCurrent() }
    }

    @Test fun driveWriterTimeoutLabelsPartialAnalysisAndLaterReloadFindsTerminalEvidence() = runTest {
        val gate = DiskGate(StandardTestDispatcher(testScheduler))
        try { Harness(this, historyDispatcher = gate).use { h ->
            runCurrent(); h.connect(); h.diagnosis.start(); h.elapse(3_000)
            val id = h.diagnosis.state.value.current!!.id
            val driveId = h.diagnosis.state.value.current!!.segments.single().driveId!!
            gate.held = true
            h.diagnosis.finish(); h.elapse(12_000)
            assertEquals(DiagnosisPhase.COMPLETED, h.diagnosis.state.value.phase, h.diagnosis.state.value.error)
            assertContains(h.store.read(id).note.orEmpty(), "partial checkpoint")
            assertEquals(SavedDriveStatus.CHECKPOINT, h.diagnosis.readDrive(id, driveId)!!.status)
            val terminal = h.controller.state.value.liveHealthSession!!.driveReview!!
            gate.releaseAll(); h.elapse()
            val reloaded = assertNotNull(h.diagnosis.readDrive(id, driveId))
            assertEquals(SavedDriveStatus.COMPLETED, reloaded.status)
            assertEquals(terminal.elapsedMillis, reloaded.review.elapsedMillis)
            assertEquals(terminal.signals, reloaded.review.signals)
        } } finally { gate.releaseAll(); runCurrent() }
    }
}
