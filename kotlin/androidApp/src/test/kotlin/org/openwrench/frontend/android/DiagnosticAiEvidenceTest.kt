package org.openwrench.frontend.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openwrench.nativecore.ObdDecoder
import kotlin.test.*

class DiagnosticAiEvidenceTest {
    private val scan = "OPENWRENCH read-only diagnostic scan\nStored DTC: P0171"

    private fun diagnosis(coolantCelsius: Double): DiagnosisRecord {
        val guidance = syntheticGuidedProgress(coolantCelsius)
        val segment = DiagnosisSegment("a97e1bda-3947-44f5-b849-828c13573f5e", 7, "7E8", 1_000_000,
            0, endedAtOffsetMillis = 31_000, sampleCount = 77, guidance = guidance)
        return DiagnosisRecord("bef17ee3-8c67-42ca-8336-b6faac1e373a", 1_000_000, 1_031_000,
            DiagnosisStatus.COMPLETED, listOf(segment), listOf(scan), guidedPolicyVersion = guidance.policyVersion)
    }

    private fun preview(driverNote: String, report: String): String {
        val evidence = DiagnosticAiEvidence(driverNote, report)
        val body = Json.parseToJsonElement(evidence.body.decodeToString()).jsonObject
        assertEquals(setOf("evidence"), body.keys)
        assertEquals(evidence.text, body.getValue("evidence").jsonPrimitive.content)
        return evidence.text
    }

    private fun chartSeries(pid: Int, points: List<LiveHealthChartPoint>): LiveHealthChartSeries {
        val metadata = ObdDecoder.catalog.getValue(pid)
        return LiveHealthChartSeries(pid, metadata.name, metadata.unit, points, maximumLinkGapMillis = 10_000)
    }

    private fun chartDrive(vararg series: LiveHealthChartSeries): SavedDrive {
        val seed = historyTestDrive("bd351efc-6aa4-4e79-996b-c93dc09061d4")
        val points = series.flatMap { it.points }
        val signals = series.mapNotNull { signal ->
            val valid = signal.points.filter { it.value != null }
            if (valid.isEmpty()) null else LiveHealthDriveSignal(signal.pid, signal.name, signal.unit,
                valid.size.toLong(), valid.first().value!!, valid.last().value!!,
                valid.minOf { it.value!! }, valid.maxOf { it.value!! },
                valid.first().offsetMillis, valid.last().offsetMillis)
        }
        return seed.copy(savedAtEpochMillis = seed.startedAtEpochMillis + 180_000,
            origin = DriveEvidenceOrigin.SYNTHETIC,
            report = "OPENWRENCH live health monitoring\nSynthetic retained-chart timing fixture\n",
            review = seed.review.copy(elapsedMillis = 180_000, highlights = emptyList(), signals = signals,
                conditions = emptyList(), series = series.toList(), bookmarks = emptyList(),
                retentionNotes = emptyList(), droppedBookmarks = 0, assessment = null,
                retainedFromOffsetMillis = points.minOf { it.offsetMillis },
                retainedToOffsetMillis = points.maxOf { it.offsetMillis }))
    }

    private fun bothChartEvidenceReports(drive: SavedDrive): List<String> {
        val reopened = DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive))
        val segment = DiagnosisSegment("a97e1bda-3947-44f5-b849-828c13573f5e", reopened.generation,
            reopened.sourceEcu, reopened.startedAtEpochMillis, 0,
            endedAtOffsetMillis = reopened.review.elapsedMillis, driveId = reopened.id,
            sampleCount = reopened.review.signals.sumOf { it.sampleCount })
        val record = DiagnosisRecord("bef17ee3-8c67-42ca-8336-b6faac1e373a", reopened.startedAtEpochMillis,
            reopened.savedAtEpochMillis, DiagnosisStatus.COMPLETED, listOf(segment), listOf(scan))
        val saved = DiagnosisCodec.decode(DiagnosisCodec.encode(record))
        return listOf(diagnosisAiReport(saved) { id ->
            assertEquals(reopened.id, id)
            DiagnosisDriveEvidence(reopened)
        }, importedDriveAiReport(reopened)).map { preview("The engine stumbled at 90 seconds.", it) }
    }

    @Test fun sameLeanCodeWithQualifiedWarmVersusColdReceiptsHasDifferentEvidence() {
        fun report(temperature: Double): String {
            val record = diagnosis(temperature)
            val saved = DiagnosisCodec.decode(DiagnosisCodec.encode(record))
            return preview("", diagnosisAiReport(saved) { error("No linked drive") })
        }
        val warm = report(85.0)
        val cold = report(35.0)
        for (evidence in listOf(warm, cold)) {
            assertContains(evidence, scan)
            assertContains(evidence, "Driver report: Not supplied")
            assertContains(evidence, "Received samples: 77")
            assertContains(evidence, "Bank 2 · UNAVAILABLE")
            assertFalse(DiagnosticSessionStore.CSV_HEADER in evidence)
        }
        assertContains(warm, "Bank 1 · QUALIFIED")
        assertContains(warm, "11 independent pairs")
        assertContains(warm, "STFT 5.00..5.00%; LTFT 15.00..15.00%")
        assertContains(warm, "coolant °C 85.00")
        assertContains(cold, "Bank 1 · COLLECTING: Coolant must remain at least 72.0 °C")
        assertFalse("Bank 1 · QUALIFIED" in cold)
        assertFalse("STFT 5.00" in cold)
    }

    @Test fun warmLeanCodeRetainsBothStationaryAndMovingCorrectionWindowsFromOneDrive() {
        val pids = setOf(3, 4, 5, 6, 7, 12, 13)
        val health = LiveHealthAccumulator(7, "7E8", 0, pids, pids, 1)
        syntheticFuelWindow(85.0, rpm = 750.0, speed = 0.0, load = 20.0, offer = health::offer)
        syntheticFuelWindow(86.0, start = 40_100, rpm = 2000.0, speed = 65.0, load = 35.0,
            shortTrim = 1.0, longTrim = 2.0, offer = health::offer)
        health.stop(72_000, "Scripted stop")
        val review = assertNotNull(health.snapshot.driveReview)
        val drive = SavedDrive("bd351efc-6aa4-4e79-996b-c93dc09061d4", 1_000_000, 1_072_000,
            SavedDriveStatus.COMPLETED, "Scripted stop", "7E8", 7, "OPENWRENCH live health monitoring\nSynthetic operating-point comparison\n",
            review, DriveEvidenceOrigin.SYNTHETIC)
        val reopened = DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive))
        // This historical fixture has no separate guided milestone. Only the original drive
        // assessment supplies operating conditions; warmth is a measured coolant range.
        val segment = DiagnosisSegment("a97e1bda-3947-44f5-b849-828c13573f5e", 7, "7E8", 1_000_000, 0,
            endedAtOffsetMillis = 72_000, driveId = drive.id, sampleCount = 154)
        val record = DiagnosisRecord("bef17ee3-8c67-42ca-8336-b6faac1e373a", 1_000_000, 1_072_000,
            DiagnosisStatus.COMPLETED, listOf(segment), listOf(scan))
        val report = preview("Not sure when symptoms occurred", diagnosisAiReport(record) { DiagnosisDriveEvidence(reopened) })
        assertContains(report, scan)
        assertContains(report, "Bank 1: 2 stored paired fuel-correction intervals")
        assertContains(report, "Stationary with engine running; 11 independent pairs")
        assertContains(report, "Moving; 11 independent pairs")
        assertContains(report, "STFT 5.00..5.00%; LTFT 15.00..15.00%")
        assertContains(report, "STFT 1.00..1.00%; LTFT 2.00..2.00%")
        assertContains(report, "coolant °C 85.00")
        assertContains(report, "coolant °C 86.00")
        assertContains(report, "No guided screening record stored")
    }

    @Test fun symptomFreeImportedDriveIsAttributedOnlyToTheDriver() {
        val drive = historyTestDrive().copy(origin = DriveEvidenceOrigin.SYNTHETIC)
        val report = importedDriveAiReport(drive)
        val note = "My usual stumble did not occur on this drive."
        val baseline = preview(note, report)
        assertTrue(baseline.startsWith("Driver report: $note\n\nSaved report:\n"))
        assertContains(baseline, "Recorded origin: SYNTHETIC")
        assertContains(baseline, drive.copyText())
        assertFalse(note in report)
        assertFalse(note in preview("", report))
    }

    @Test fun interruptedRecordingPreservesDropsPartialDriveAndMissingAssessmentWithoutStitching() {
        val seed = historyTestDrive()
        val series = seed.review.series.first().copy(points = listOf(LiveHealthChartPoint(0, 1234.5, true),
            LiveHealthChartPoint(20, null, true), LiveHealthChartPoint(50, 9999.0, true)))
        val partial = seed.copy(status = SavedDriveStatus.CHECKPOINT, endReason = null,
            review = seed.review.copy(assessment = null, series = listOf(series)))
        val original = diagnosis(35.0)
        val segment = original.segments.single().copy(driveId = partial.id, guidance = null,
            endReason = "Scripted disconnect", droppedSamples = 9)
        val record = original.copy(status = DiagnosisStatus.INTERRUPTED, segments = listOf(segment))
        val report = preview("Timing unknown", diagnosisAiReport(record) { id ->
            assertEquals(partial.id, id)
            DiagnosisDriveEvidence(partial, "Partial checkpoint only")
        })
        for (fact in listOf("Status: interrupted", "samples not retained: 9", "Scripted disconnect",
            "No guided screening record stored", "Retention warning: Partial checkpoint only", "Recorded origin: UNKNOWN",
            "status: CHECKPOINT", "No original drive assessment stored", "1 unavailable points; 2 marked breaks",
            "not aligned to segment offsets")) assertContains(report, fact)
        assertFalse("1234.5" in report, "A raw chart series is not part of the payload")
        val missing = diagnosisAiReport(record) { DiagnosisDriveEvidence(null) }
        assertContains(missing, "Linked drive evidence unavailable")
        assertFalse("Stored assessment:" in missing)
    }

    @Test fun retainedUnavailableTimingDistinguishesNinetySecondSymptomFromLaterGapInBothReports() {
        fun drive(gapAt: Long): SavedDrive {
            val trim = chartSeries(0x07, (0L..180_000L step 10_000).map { at ->
                LiveHealthChartPoint(at, if (at == gapAt) null else 12.34567, at == 0L || at == gapAt)
            })
            val continuous = chartSeries(0x06, (0L..180_000L step 10_000).map { at ->
                LiveHealthChartPoint(at, 3.0, at == 0L)
            })
            return chartDrive(trim, continuous)
        }
        val early = drive(90_000)
        val late = drive(150_000)
        assertEquals(early.copyText(), late.copyText(), "Aggregate and chart-count reports cannot locate this gap")
        for ((selected, gapAt, otherGapAt) in listOf(Triple(early, 90_000, 150_000), Triple(late, 150_000, 90_000))) {
            for (report in bothChartEvidenceReports(selected)) {
                assertContains(report, "Driver report: The engine stumbled at 90 seconds.")
                val marker = report.lineSequence().single { it.startsWith("Retained chart PID 0107 ") }
                assertContains(marker, "drive-relative")
                assertContains(marker, "unavailable at +$gapAt ms")
                assertFalse("+$otherGapAt ms" in marker)
                assertFalse("unjoined" in marker, "Null receipts are not valid endpoints of a joined interval")
                assertFalse("Retained chart PID 0106 " in report, "First-point breaks and link-limit equality are not gaps")
                assertContains(report, "not establish a vehicle-wide outage or continuous coverage elsewhere")
                assertFalse("12.34567" in marker, "Gap markers must not expand into raw chart readings")
            }
        }
    }

    @Test fun unjoinedValidReceiptTimingDistinguishesNinetyAndOneHundredFiftySecondsInBothReports() {
        for (explicitBreak in listOf(true, false)) {
            fun drive(gapAt: Long): SavedDrive = chartDrive(chartSeries(0x07,
                (0L..180_000L step 10_000).filterNot { !explicitBreak && it == gapAt - 10_000 }.map { at ->
                    LiveHealthChartPoint(at, 15.0, at == 0L || (explicitBreak && at == gapAt))
                }))
            val early = drive(90_000)
            val late = drive(150_000)
            assertEquals(early.copyText(), late.copyText())
            for ((selected, gapAt, otherGapAt) in listOf(Triple(early, 90_000, 150_000), Triple(late, 150_000, 90_000))) {
                for (report in bothChartEvidenceReports(selected)) {
                    val marker = report.lineSequence().single { it.startsWith("Retained chart PID 0107 ") }
                    val previous = gapAt - if (explicitBreak) 10_000 else 20_000
                    assertContains(marker, "unjoined receipts +$previous..+$gapAt ms")
                    assertFalse("+$otherGapAt ms" in marker)
                    assertFalse("unavailable" in marker, "An unjoined interval does not mean either receipt is unavailable")
                    assertEquals(1, Regex("unjoined receipts").findAll(marker).count())
                }
            }
        }
    }

    @Test fun retainedGapMarkerCapIsPerPidAndDisclosesOmissionsInBothReports() {
        val trim = chartSeries(0x07, listOf(
            LiveHealthChartPoint(0, 15.0, true), LiveHealthChartPoint(10_000, null, true),
            LiveHealthChartPoint(20_000, 15.0, true), LiveHealthChartPoint(30_000, 15.0, true),
            LiveHealthChartPoint(40_000, null, true), LiveHealthChartPoint(50_000, 15.0, true),
            LiveHealthChartPoint(60_000, 15.0, true), LiveHealthChartPoint(70_000, null, true),
            LiveHealthChartPoint(80_000, null, true), LiveHealthChartPoint(90_000, 15.0, true)))
        val other = chartSeries(0x06, listOf(
            LiveHealthChartPoint(90_000, null, true), LiveHealthChartPoint(150_000, null, true)))
        for (report in bothChartEvidenceReports(chartDrive(trim, other))) {
            val trimMarker = report.lineSequence().single { it.startsWith("Retained chart PID 0107 ") }
            for (detail in listOf("unavailable at +10000 ms", "unjoined receipts +20000..+30000 ms",
                "unavailable at +40000 ms", "unjoined receipts +50000..+60000 ms",
                "2 additional markers omitted")) assertContains(trimMarker, detail)
            assertFalse("+70000 ms" in trimMarker)
            assertFalse("+80000 ms" in trimMarker)
            val otherMarker = report.lineSequence().single { it.startsWith("Retained chart PID 0106 ") }
            assertContains(otherMarker, "unavailable at +90000 ms")
            assertContains(otherMarker, "unavailable at +150000 ms")
            assertFalse("omitted" in otherMarker, "One PID's cap must not suppress another PID's evidence")
        }
    }

    @Test fun boundAppliesToEncodedUtf8IncludingDriverTextAndJsonEscaping() {
        assertFailsWith<IllegalArgumentException> {
            DiagnosticAiEvidence("温".repeat(DiagnosticAiEvidence.MAX_REQUEST_BYTES / 3), "saved")
        }
        assertFailsWith<IllegalArgumentException> {
            DiagnosticAiEvidence("", "\"".repeat(DiagnosticAiEvidence.MAX_REQUEST_BYTES / 2))
        }
    }
}
