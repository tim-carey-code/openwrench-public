package org.openwrench.frontend.android

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriveComparisonTest {
    private val supported = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    private val condition = "Moving; qualified reported closed-loop context; system 1: closed loop: oxygen-sensor feedback; system 2: closed loop: oxygen-sensor feedback"
    private fun window(trim: Double = -15.0, coolant: Double = 90.0, context: String = condition) =
        DriveHealthWindow(1_000, 31_000, 11, context, trim, trim, 1_500.0, 2_000.0, 20.0, 40.0, coolant, coolant + 2)
    private fun drive(trim: Double = -15.0, coolant: Double = 90.0): SavedDrive {
        val d = historyTestDrive()
        val w = window(trim, coolant)
        val finding = DriveHealthFinding("trim-bank-1", "Bank 1 correction",
            if (trim < -10 || trim > 10) DriveHealthDisposition.REVIEW else DriveHealthDisposition.OBSERVED,
            "Synthetic recorded finding", listOf("Actual synthetic receipts"), emptyList(), emptyList(), 1_000, 31_000, 11, condition,
            comparisonWindow = w)
        return d.copy(review = d.review.copy(assessment = DriveHealthAssessment("standard-obd-drive", "2", 36_000,
            listOf(finding), emptyList(), emptyList())))
    }
    private fun recorder() = DriveHealthAssessmentRecorder(0, supported, supported)
    private fun cycle(r: DriveHealthAssessmentRecorder, at: Long, metadata: Boolean = true, trim: Double = -15.0) {
        fun scalar(pid: Int, value: Double, offset: Long) = r.offer(NativeReading(pid, DecodedReading.Scalar(value)), at + offset)
        scalar(0x0C, 1_700.0, 0); scalar(0x0D, 40.0, 1)
        r.offer(NativeReading(0x03, DecodedReading.Structured(mapOf("system_1" to "closed loop: oxygen-sensor feedback", "system_2" to "closed loop: oxygen-sensor feedback"))), at + 2)
        if (metadata) { scalar(0x04, 30.0, 3); scalar(0x05, 90.0, 4) }
        scalar(0x07, trim, 5)
    }

    @Test fun comparisonRequiresExplicitVehicleConfirmationCompletedDrivesAndMatchingSources() {
        val a = drive(); val b = drive()
        assertFails { DriveComparisonBuilder.compare(a, b, false) }
        assertFails { DriveComparisonBuilder.compare(a, a, true) }
        assertFails { DriveComparisonBuilder.compare(a, b.copy(status = SavedDriveStatus.INTERRUPTED), true) }
        val mismatch = DriveComparisonBuilder.compare(a, b.copy(sourceEcu = "7E9"), true)
        assertTrue(mismatch.observations.single().contains("matching observed ECU"))
        assertTrue(DriveComparisonBuilder.compare(a.copy(sourceEcu = null), b, true).observations.single().contains("unavailable"))
    }

    @Test fun recurringCueNeedsMatchingContextAndOverlappingActualOperatingBounds() {
        val comparison = DriveComparisonBuilder.compare(drive(), drive(), true)
        assertTrue(comparison.observations.first().contains("sustained correction that removed fuel"))
        assertTrue(comparison.observations.any { it.contains("not a confirmed fault") })
        assertTrue(comparison.reportText().contains("explicitly confirmed by the user"))
        val cold = DriveComparisonBuilder.compare(drive(), drive(coolant = 60.0), true)
        assertTrue(cold.observations.first().contains("non-overlapping"))
        assertFalse(cold.observations.any { it.contains("beyond the screening cue was recorded in both drives") })
        val other = drive().let { d -> val a = d.review.assessment!!; val f = a.findings.single(); d.copy(review = d.review.copy(
            assessment = a.copy(findings = listOf(f.copy(comparisonWindow = f.comparisonWindow!!.copy(condition = "Stationary")))))) }
        assertTrue(DriveComparisonBuilder.compare(drive(), other, true).observations.first().contains("different motion"))
    }

    @Test fun changingCueDoesNotBecomeARepairOrHealthVerdictAndOlderEvidenceIsNotRebuilt() {
        val comparison = DriveComparisonBuilder.compare(drive(), drive(trim = 2.0), true)
        assertTrue(comparison.observations.first().contains("does not confirm improvement"))
        assertFalse(comparison.observations.first().contains("beyond the screening cue was recorded in both drives"))
        val old = drive().let { it.copy(review = it.review.copy(assessment = it.review.assessment!!.copy(packVersion = "1"))) }
        assertTrue(DriveComparisonBuilder.compare(old, drive(), true).observations.first().contains("not reevaluated"))
        val missing = drive().let { d -> d.copy(review = d.review.copy(assessment = d.review.assessment!!.copy(
            findings = d.review.assessment.findings.map { it.copy(comparisonWindow = null) }))) }
        assertTrue(DriveComparisonBuilder.compare(missing, drive(), true).observations.first().contains("not enough"))
    }

    @Test fun recorderStoresOnlyFreshContextAtTrimReceiptsAndCanUpgradeAStartupWindow() {
        val r = recorder()
        repeat(12) { cycle(r, 100 + it * 3_000L, metadata = it != 0) }
        val f = r.snapshot().findings.single { it.checkId == "trim-bank-1" }
        assertEquals(105, f.firstOffsetMillis) // Original first warning window remains intact.
        val w = f.comparisonWindow!!
        assertEquals(3_105, w.firstOffsetMillis)
        assertEquals(33_105, w.lastOffsetMillis)
        assertEquals(30.0, w.loadMinimum)
        assertEquals(90.0, w.coolantMinimum)
        val absent = recorder()
        repeat(12) { cycle(absent, 100 + it * 3_000L, metadata = false) }
        assertNull(absent.snapshot().findings.single { it.checkId == "trim-bank-1" }.comparisonWindow)
    }

    @Test fun missingOrExpiredComparisonMetadataCannotBeBridgedOnRecovery() {
        val r = recorder()
        repeat(6) { cycle(r, 100 + it * 3_000L) }
        repeat(5) { cycle(r, 18_100 + it * 3_000L, metadata = false) }
        cycle(r, 33_100)
        assertNull(r.snapshot().findings.single { it.checkId == "trim-bank-1" }.comparisonWindow)
        repeat(10) { cycle(r, 36_100 + it * 3_000L) }
        assertNotNull(r.snapshot().findings.single { it.checkId == "trim-bank-1" }.comparisonWindow)
        assertTrue(r.snapshot().findings.single { it.checkId == "trim-bank-1" }.comparisonWindow!!.firstOffsetMillis >= 33_100)
    }

    @Test fun schemaThreeRoundTripsTypedWindowsAndReadsSchemaTwoWithoutInventingThem() {
        val d = drive()
        assertEquals(d, DriveHistoryCodec.decode(DriveHistoryCodec.encode(d)))
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(d)).jsonObject
        val review = root.getValue("review").jsonObject
        val a = review.getValue("assessment").jsonObject
        val legacyFindings = JsonArray(a.getValue("findings").jsonArray.map { JsonObject(it.jsonObject - "comparison_window" - "fuel_intervals") })
        val old = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/2")) +
            ("review" to JsonObject(review + ("assessment" to JsonObject(a + ("findings" to legacyFindings))))))
        assertNull(DriveHistoryCodec.decode(old.toString()).review.assessment!!.findings.single().comparisonWindow)
        val malformed = d.copy(review = d.review.copy(assessment = d.review.assessment!!.copy(findings =
            d.review.assessment.findings.map { it.copy(comparisonWindow = it.comparisonWindow!!.copy(loadMaximum = Double.NaN)) })))
        assertFails { DriveHistoryCodec.encode(malformed) }
    }

    @Test fun comparisonResultsCanBeClosedAndDeletedWithoutResurrectingOldWork() = runTest {
        val dir = Files.createTempDirectory("ow-drive-compare").toFile()
        try {
            val store = DriveHistoryStore(dir); val a = drive(); val b = drive()
            store.upsert(a); store.upsert(b)
            val history = DriveHistoryCoordinator(store, backgroundScope, StandardTestDispatcher(testScheduler))
            runCurrent()
            history.compare(listOf(a.id, b.id), true)
            history.closeComparison(); runCurrent()
            assertNull(history.state.value.comparison)
            assertFalse(history.state.value.comparing)
            history.compare(listOf(a.id, b.id), true); runCurrent()
            assertNotNull(history.state.value.comparison)
            history.delete(a.id); runCurrent()
            assertNull(history.state.value.comparison)
            assertEquals(listOf(b.id), history.state.value.drives.map { it.id })
        } finally { dir.deleteRecursively() }
    }
}
