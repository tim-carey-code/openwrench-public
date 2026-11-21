package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.*

class DriveHealthAssessmentTest {
    private val supported = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    private fun recorder(support: Set<Int> = supported, admitted: Set<Int> = support) =
        DriveHealthAssessmentRecorder(0, support, admitted)
    private fun scalar(pid: Int, value: Double) = NativeReading(pid, DecodedReading.Scalar(value))
    private fun fuel(first: String = CLOSED, second: String = CLOSED) = NativeReading(0x03,
        DecodedReading.Structured(mapOf("system_1" to first, "system_2" to second)))
    private fun status(mil: Boolean = false, count: Int = 0, complete: String = "true", available: String = "true") =
        NativeReading(0x01, DecodedReading.Structured(mapOf("mil_on" to mil.toString(), "dtc_count" to count.toString(),
            "monitors.0.name" to "oxygen sensor", "monitors.0.available" to available, "monitors.0.complete" to complete)))
    private fun DriveHealthAssessment.find(id: String) = findings.single { it.checkId == id }
    private fun DriveHealthAssessmentRecorder.find(id: String) = snapshot().find(id)
    private fun cycle(r: DriveHealthAssessmentRecorder, at: Long, value: Double = -15.0, speed: Double = 40.0,
        firstFuel: String = CLOSED, secondFuel: String = CLOSED, bank: Int = 1) {
        r.offer(scalar(0x0C, 1_500.0), at)
        r.offer(scalar(0x0D, speed), at + 1)
        r.offer(fuel(firstFuel, secondFuel), at + 2)
        r.offer(scalar(if (bank == 1) 0x07 else 0x09, value), at + 3)
    }
    private fun qualify(r: DriveHealthAssessmentRecorder, value: Double = -15.0, speed: Double = 40.0,
        secondFuel: String = CLOSED, bank: Int = 1, start: Long = 100) {
        repeat(11) { cycle(r, start + it * 3_000L, value, speed, secondFuel = secondFuel, bank = bank) }
    }

    @Test fun unsupportedAndUnadmittedCoverageIsNotAHealthyCheck() {
        val unsupported = recorder(emptySet()).snapshot()
        assertEquals(10, unsupported.findings.size)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, unsupported.find("ecu-warning").disposition)
        assertTrue(unsupported.find("trim-bank-1").summary.contains("not advertised"))
        val deferred = recorder(supported, supported - 0x07).find("trim-bank-1")
        assertEquals(DriveHealthDisposition.INSUFFICIENT, deferred.disposition)
        assertTrue(deferred.summary.contains("not admitted"))
        assertEquals(DriveHealthDisposition.NOT_ASSESSED, unsupported.find("module-coverage").disposition)
    }

    @Test fun ecuWarningSurvivesNoDataLaterClearAndAge() {
        val r = recorder()
        r.offer(status(true, 2), 1_000)
        r.offer(NativeReading(0x01, null), 31_000)
        r.offer(status(false, 0), 61_000)
        val warning = r.snapshot().present(200_000).find("ecu-warning")
        assertEquals(DriveHealthDisposition.REVIEW, warning.disposition)
        assertEquals(1_000, warning.firstOffsetMillis)
        assertTrue(warning.evidence.single().contains("DTC count 2"))
        assertTrue(warning.limitations.any { it.contains("later clear", ignoreCase = true) })
    }

    @Test fun clearStatusAgesWithoutCreatingEvidenceAndCanRecoverOnANewReceipt() {
        val r = recorder()
        r.offer(status(), 100)
        val fresh = r.snapshot()
        assertEquals(DriveHealthDisposition.REASSURING, fresh.find("ecu-warning").disposition)
        val stale = fresh.present(45_101)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, stale.find("ecu-warning").disposition)
        assertEquals(1, stale.find("ecu-warning").sampleCount)
        assertEquals(100, stale.find("ecu-warning").lastOffsetMillis)
        assertEquals(DriveHealthDisposition.REASSURING, fresh.find("ecu-warning").disposition)
        r.offer(status(), 60_000)
        assertEquals(DriveHealthDisposition.REASSURING, r.find("ecu-warning").disposition)
    }

    @Test fun latestNoDataDoesNotReuseOlderClearStatusOrReadiness() {
        val r = recorder()
        r.offer(status(), 100)
        r.offer(NativeReading(0x01, null), 200)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("ecu-warning").disposition)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("readiness").disposition)
    }

    @Test fun readinessIncompleteIsUnfinishedCheckRatherThanAFailedPart() {
        val r = recorder()
        r.offer(status(complete = "false"), 100)
        val readiness = r.find("readiness")
        assertEquals(DriveHealthDisposition.REVIEW, readiness.disposition)
        assertTrue(readiness.summary.contains("not completed"))
        assertTrue(readiness.nextSteps.single().contains("not a component-failure"))
        assertTrue(readiness.evidence.any { it.contains("oxygen sensor") })
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.snapshot().present(45_101).find("readiness").disposition)
        r.offer(status(complete = "true"), 200)
        assertEquals(DriveHealthDisposition.REASSURING, r.find("readiness").disposition)
        r.offer(status(complete = "unknown"), 300)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("readiness").disposition)
        r.offer(status(available = "false"), 400)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("readiness").disposition)
    }

    @Test fun exactFeedbackFaultStateIsReviewAndCannotQualifyATrimWindow() {
        val r = recorder()
        repeat(15) { cycle(r, 100 + it * 3_000L, firstFuel = "closed loop: oxygen-sensor feedback fault") }
        assertEquals(DriveHealthDisposition.REVIEW, r.find("fuel-control").disposition)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        cycle(r, 50_000)
        assertEquals(DriveHealthDisposition.REVIEW, r.find("fuel-control").disposition)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun loadOrDecelerationFuelCutIsNotAFaultOrTrimEvidence() {
        val r = recorder()
        repeat(15) { cycle(r, 100 + it * 3_000L, firstFuel = "open loop: load or deceleration fuel cut",
            secondFuel = "open loop: load or deceleration fuel cut") }
        assertEquals(DriveHealthDisposition.REASSURING, r.find("fuel-control").disposition)
        assertTrue(r.find("fuel-control").summary.contains("open-loop operation alone is not a fault"))
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun sustainedCorrectionHasActualQualifiedBoundsDirectionAndDisclosedLimits() {
        val r = recorder()
        qualify(r)
        val finding = r.find("trim-bank-1")
        assertEquals(DriveHealthDisposition.REVIEW, finding.disposition)
        assertEquals(103, finding.firstOffsetMillis)
        assertEquals(30_103, finding.lastOffsetMillis)
        assertEquals(11, finding.sampleCount)
        assertTrue(finding.context.startsWith("Moving"))
        assertTrue(finding.summary.contains("removing fuel"))
        assertTrue(finding.limitations.any { it.contains("not a vehicle-specific normal range") })
        assertTrue(finding.limitations.any { it.contains("currently rich") })
        assertTrue(finding.nextSteps.single().contains("fully warm"))
        r.offer(NativeReading(0x07, null), 31_000)
        qualify(r, value = 0.0, start = 40_000)
        assertEquals(finding, r.find("trim-bank-1"))
    }

    @Test fun positiveStationaryCorrectionIsScreenedWithoutClaimingIdleOrWarmth() {
        val r = recorder()
        qualify(r, value = 14.0, speed = 0.0)
        val finding = r.find("trim-bank-1")
        assertEquals(DriveHealthDisposition.REVIEW, finding.disposition)
        assertTrue(finding.context.startsWith("Stationary with engine running"))
        assertTrue(finding.summary.contains("adding fuel"))
        assertFalse(finding.context.contains("idle", ignoreCase = true))
        assertTrue(r.snapshot().notes.any { it.contains("No warmed-engine state is inferred") })
    }

    @Test fun withinCueAndBoundaryObservationsNeverBecomeAFuelSystemPass() {
        for (value in listOf(-10.0, -2.0, 0.0, 10.0)) {
            val r = recorder()
            qualify(r, value)
            val finding = r.find("trim-bank-1")
            assertEquals(DriveHealthDisposition.OBSERVED, finding.disposition)
            assertTrue(finding.summary.contains("No sustained learned correction"))
            assertFalse(finding.summary.contains("healthy", ignoreCase = true))
            assertFalse(finding.summary.contains("normal", ignoreCase = true))
        }
    }

    @Test fun tooShortAndTooFewSamplesCannotEarnATrimFinding() {
        val r = recorder()
        repeat(30) { cycle(r, 100 + it * 500L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        val sparse = recorder()
        repeat(7) { cycle(sparse, 100 + it * 5_500L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, sparse.find("trim-bank-1").disposition)
    }

    @Test fun motionChangesDoNotJoinSeparateShortWindows() {
        val r = recorder()
        repeat(6) { cycle(r, 100 + it * 3_000L) }
        repeat(6) { cycle(r, 18_100 + it * 3_000L, speed = 0.0) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        repeat(6) { cycle(r, 36_100 + it * 3_000L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun noDataAndLongReceiptGapRestartRequiredTrimRun() {
        val r = recorder()
        repeat(8) { cycle(r, 100 + it * 3_000L) }
        r.offer(NativeReading(0x07, null), 22_000)
        repeat(7) { cycle(r, 24_100 + it * 3_000L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        repeat(7) { cycle(r, 51_100 + it * 3_000L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun restoringSameContextAfterStalePrerequisiteCannotResurrectWindow() {
        val r = recorder()
        repeat(8) { cycle(r, 100 + it * 3_000L) }
        // Previous motion context expires, then the next sequence restores the identical key.
        repeat(7) { cycle(r, 32_200 + it * 3_000L) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun systemFieldsAreNotAssumedToBeBankAssignments() {
        val r = recorder()
        qualify(r, secondFuel = "engine off")
        assertEquals(DriveHealthDisposition.REVIEW, r.find("trim-bank-1").disposition)
        qualify(r, secondFuel = "engine off", bank = 2, start = 40_000)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-2").disposition)
        qualify(r, bank = 2, start = 80_000)
        assertEquals(DriveHealthDisposition.REVIEW, r.find("trim-bank-2").disposition)
        assertTrue(r.find("trim-bank-2").limitations.any { it.contains("bank-to-system mapping") })
    }

    @Test fun differentCompatibleFuelSystemStatesDoNotJoinATrimWindow() {
        val r = recorder()
        repeat(7) { cycle(r, 100 + it * 3_000L, secondFuel = "engine off") }
        repeat(7) { cycle(r, 21_100 + it * 3_000L, secondFuel = CLOSED) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        repeat(7) { cycle(r, 42_100 + it * 3_000L, secondFuel = "engine off") }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
    }

    @Test fun duplicateAndOutOfOrderReceiptsCannotQualify() {
        val r = recorder()
        cycle(r, 100)
        repeat(100) { r.offer(scalar(0x07, -15.0), 103) }
        repeat(100) { r.offer(scalar(0x07, -15.0), 102) }
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("trim-bank-1").disposition)
        r.offer(status(true, 4), 101) // Future monitor receipt 103 already observed: reject this old reply.
        assertEquals(DriveHealthDisposition.INSUFFICIENT, r.find("ecu-warning").disposition)
    }

    @Test fun longRunsBoundEvidenceAndDoNotRequireChartRetention() {
        val r = recorder()
        repeat(5_000) { cycle(r, 100 + it * 3_000L, value = 0.0) }
        val assessment = r.snapshot()
        assertEquals(10, assessment.findings.size)
        assertTrue(assessment.find("trim-bank-1").sampleCount <= 512)
        assertTrue(assessment.findings.all { it.evidence.size <= 16 && it.limitations.size <= 16 })
        assertTrue(assessment.find("trim-bank-1").lastOffsetMillis!! > 20 * 60_000)
    }

    @Test fun fastWatchRatesCanStillQualifyThirtySecondWindows() {
        val r = recorder()
        repeat(201) { cycle(r, 100 + it * 200L, value = 0.0) }
        assertEquals(DriveHealthDisposition.OBSERVED, r.find("trim-bank-1").disposition)
        val negative = recorder()
        repeat(201) { cycle(negative, 100 + it * 200L) }
        assertEquals(DriveHealthDisposition.REVIEW, negative.find("trim-bank-1").disposition)
        assertTrue(negative.find("trim-bank-1").lastOffsetMillis!! - negative.find("trim-bank-1").firstOffsetMillis!! >= 30_000)
    }

    @Test fun newSustainedTailCanQualifyWithoutWaitingForPrecedingInBandValuesToExpire() {
        val r = recorder()
        qualify(r, value = 0.0)
        assertEquals(DriveHealthDisposition.OBSERVED, r.find("trim-bank-1").disposition)
        qualify(r, start = 33_100)
        val review = r.find("trim-bank-1")
        assertEquals(DriveHealthDisposition.REVIEW, review.disposition)
        assertEquals(33_103, review.firstOffsetMillis)
        assertEquals(63_103, review.lastOffsetMillis)
    }

    @Test fun stopFreezesAssessmentAndNewMonitorDoesNotInheritIt() {
        val r = recorder()
        qualify(r)
        r.stop(40_000)
        val frozen = r.snapshot()
        r.offer(status(true, 5), 50_000)
        cycle(r, 60_000)
        assertEquals(frozen, r.snapshot())
        assertEquals(DriveHealthDisposition.INSUFFICIENT, recorder().find("trim-bank-1").disposition)
        assertEquals(DriveHealthDisposition.INSUFFICIENT, recorder().find("ecu-warning").disposition)
    }

    @Test fun accumulatorPublishesAssessmentWithoutChangingAcquisitionOrRevivingCurrentFacts() {
        val a = LiveHealthAccumulator(7, "7E8", 0, supported, supported)
        a.offer(status(true, 1), 100)
        a.stop(200)
        val state = NativeUiState(connected = false, liveRunning = false, liveHealthEnabled = false,
            sessionGeneration = 8, liveHealthSession = a.snapshot)
        val view = LiveHealthDelivery.present(state, 100_000)
        assertTrue(view.flags.isEmpty())
        val assessment = view.driveReview!!.assessment!!
        assertEquals(DriveHealthDisposition.REVIEW, assessment.find("ecu-warning").disposition)
        assertEquals(200, assessment.evaluatedAtOffsetMillis)
        assertEquals(1, view.receiptCount)
        val text = view.reportText()
        assertEquals(1, Regex("Vehicle health assessment:").findAll(text).count())
        assertTrue(text.indexOf("Vehicle health assessment:") > text.indexOf("Drive review: monitor instance"))
    }

    companion object { private const val CLOSED = "closed loop: oxygen-sensor feedback" }
}
