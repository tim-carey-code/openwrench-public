package org.openwrench.core

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.*

class FuelCorrectionTest {
    private val inputs = setOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0C, 0x0D)
    private fun recorder(supported: Set<Int> = inputs, admitted: Set<Int> = supported) = FuelCorrectionRecorder(0, supported, admitted)
    private fun scalar(pid: Int, value: Double?) = NativeReading(pid, value?.let { DecodedReading.Scalar(it) })
    private fun fuel(closed: Boolean = true, second: String = FuelCorrectionRecorder.CLOSED) = NativeReading(3,
        DecodedReading.Structured(mapOf("system_1" to if (closed) FuelCorrectionRecorder.CLOSED else "open loop: load or deceleration fuel cut", "system_2" to second)))
    private fun context(r: FuelCorrectionRecorder, at: Long, rpm: Double = 1500.0, load: Double = 30.0, coolant: Double = 90.0, speed: Double = 40.0, closed: Boolean = true, second: String = FuelCorrectionRecorder.CLOSED) {
        r.offer(scalar(12, rpm), at); r.offer(scalar(13, speed), at + 1); r.offer(scalar(4, load), at + 2)
        r.offer(scalar(5, coolant), at + 3); r.offer(fuel(closed, second), at + 4)
    }
    private fun cycle(r: FuelCorrectionRecorder, at: Long, short: Double = 5.0, long: Double = 15.0, speed: Double = 40.0, rpm: Double = 1500.0, bank: Int = 1) {
        context(r, at, rpm = rpm, speed = speed)
        r.offer(scalar(if (bank == 1) 6 else 8, short), at + 5)
        r.offer(scalar(if (bank == 1) 7 else 9, long), at + 6)
    }
    private fun qualify(r: FuelCorrectionRecorder, start: Long = 100, short: Double = 5.0, long: Double = 15.0, speed: Double = 40.0, rpm: Double = 1500.0, bank: Int = 1) {
        repeat(11) { cycle(r, start + 3000L * it, short, long, speed, rpm, bank) }
    }

    @Test fun originalEvidencePolicyStillQualifiesDecoderValidColdContextWithoutGuidedWarmth() {
        val r = recorder()
        repeat(11) { index ->
            val at = 100 + index * 3_000L
            context(r, at, coolant = 71.0)
            r.offer(scalar(6, 5.0), at + 5); r.offer(scalar(7, 15.0), at + 6)
        }
        val interval = r.intervals(1).single()
        assertEquals(FuelCorrectionPolicy.VERSION, interval.policyVersion)
        assertEquals(71.0, interval.contextBounds.coolant.minimum.value)
    }

    @Test fun actualPairsClassifyBothDirectionsAndSmallBandsWithReceiptTimes() {
        for ((short, long, pattern) in listOf(Triple(5.0, 15.0, FuelCorrectionPattern.REINFORCING),
            Triple(-5.0, -15.0, FuelCorrectionPattern.REINFORCING), Triple(-5.0, 15.0, FuelCorrectionPattern.OPPOSING),
            Triple(5.0, -15.0, FuelCorrectionPattern.OPPOSING), Triple(3.0, 15.0, FuelCorrectionPattern.LEARNED_WITH_SMALL_SHORT),
            Triple(15.0, 3.0, FuelCorrectionPattern.SMALL_LEARNED))) {
            val r = recorder(); qualify(r, short = short, long = long)
            val interval = r.intervals(1).single()
            assertEquals(pattern, interval.pattern); assertEquals(11, interval.pairs.size)
            assertEquals(105, interval.pairs.first().short.offsetMillis); assertEquals(106, interval.pairs.first().long.offsetMillis)
            assertEquals(1, interval.pairs.first().separationMillis)
            assertEquals(short, interval.pairs.first().short.value); assertEquals(long, interval.pairs.first().long.value)
        }
    }
    @Test fun timingLimitIsInclusiveAndLargerSeparationsCannotPair() {
        for (separation in listOf(2000L, 2001L)) {
            val r = recorder()
            repeat(11) { n -> val at = 100 + 3000L * n; context(r, at)
                r.offer(scalar(6, 5.0), at + 5); r.offer(scalar(7, 15.0), at + 5 + separation) }
            assertEquals(if (separation == 2000L) 1 else 0, r.intervals(1).size)
        }
    }
    @Test fun cachedTrimCannotManufacturePairsAndPendingReplacementIsChronological() {
        val r = recorder(); context(r, 0); r.offer(scalar(6, -5.0), 5)
        r.offer(scalar(6, 5.0), 6); r.offer(scalar(7, 15.0), 7)
        repeat(15) { n -> val at = 100 + n * 3000L; context(r, at); r.offer(scalar(7, 15.0), at + 6) }
        assertTrue(r.intervals(1).isEmpty())
        // The replaced opposing receipt is not selected for a stronger/different finding.
        qualify(r, start = 50_000)
        assertEquals(FuelCorrectionPattern.REINFORCING, r.intervals(1).single().pattern)
    }
    @Test fun noDataOnEitherTrimBreaksRunAndCannotFallBackToOlderAvailableReceipt() {
        for (pid in listOf(6, 7)) {
            val r = recorder(); repeat(7) { cycle(r, 100 + 3000L * it) }
            r.offer(scalar(pid, null), 19_000)
            repeat(7) { cycle(r, 21_100 + 3000L * it) }
            assertTrue(r.intervals(1).isEmpty())
        }
        val r = recorder(); context(r, 0); r.offer(scalar(6, 5.0), 5)
        r.offer(scalar(6, null), 6); r.offer(scalar(7, 15.0), 7)
        assertTrue(r.missingRequirement(1).contains("Latest 0106"))
    }
    @Test fun duplicateOutOfOrderAndInvalidValuesDoNotCreateEvidence() {
        val r = recorder(); cycle(r, 100)
        repeat(100) { r.offer(scalar(6, 5.0), 105); r.offer(scalar(7, 15.0), 106) }
        assertTrue(r.intervals(1).isEmpty())
        r.offer(scalar(6, Double.NaN), 107); r.offer(scalar(7, Double.POSITIVE_INFINITY), 108)
        assertTrue(r.intervals(1).isEmpty())
    }
    @Test fun shortInterruptedMotionFuelAndStaleRunsNeverJoin() {
        val r = recorder()
        repeat(6) { cycle(r, 100 + it * 3000L) }
        repeat(6) { cycle(r, 18_100 + it * 3000L, speed = 0.0) }
        repeat(6) { cycle(r, 36_100 + it * 3000L) }
        assertTrue(r.intervals(1).isEmpty())
        val f = recorder(); repeat(7) { cycle(f, 100 + it * 3000L) }
        f.offer(fuel(false), 19_000); repeat(7) { cycle(f, 21_100 + it * 3000L) }
        assertTrue(f.intervals(1).isEmpty())
        val stale = recorder(); repeat(7) { cycle(stale, 100 + it * 3000L) }
        repeat(7) { cycle(stale, 32_100 + it * 3000L) }; assertTrue(stale.intervals(1).isEmpty())
    }
    @Test fun everyContextNoDataBoundaryAndTransientNumericChangeBreaksContinuity() {
        for (pid in listOf(3, 4, 5, 12, 13)) {
            val r = recorder(); repeat(7) { cycle(r, 100 + it * 3000L) }
            r.offer(NativeReading(pid, null), 19_000)
            repeat(7) { cycle(r, 21_100 + it * 3000L) }; assertTrue(r.intervals(1).isEmpty(), "PID $pid")
        }
        for ((pid, spike) in listOf(12 to 2000.0, 4 to 60.0, 5 to 95.0)) {
            val r = recorder(); repeat(7) { cycle(r, 100 + it * 3000L) }
            r.offer(scalar(pid, spike), 19_000)
            repeat(7) { cycle(r, 21_100 + it * 3000L) }; assertTrue(r.intervals(1).isEmpty(), "PID $pid")
        }
    }
    @Test fun firstPairCannotStraddleMotionTransition() {
        val r = recorder(); context(r, 100); r.offer(scalar(6, 5.0), 105)
        r.offer(scalar(13, 0.0), 106); r.offer(scalar(7, 15.0), 107)
        repeat(10) { cycle(r, 3100 + it * 3000L, speed = 0.0) }
        assertTrue(r.intervals(1).isEmpty())
    }
    @Test fun contextReuseCannotEstablishStabilityOrSurviveExpiry() {
        val r = recorder(); context(r, 100)
        repeat(40) { n -> r.offer(scalar(6, 5.0), 105 + n * 1000L); r.offer(scalar(7, 15.0), 106 + n * 1000L) }
        assertTrue(r.intervals(1).isEmpty()); assertTrue(r.missingRequirement(1).contains("10000"))
        // Even fresh-but-sparse supporting observations must meet independent count/span policy.
        val pairs = (0..10).map { n -> val at = n * 3000L
            FuelCorrectionPair(FuelObservation(at, 5.0), FuelObservation(at + 1, 15.0),
                FuelObservation(0, 1500.0), FuelObservation(0, 30.0), FuelObservation(0, 90.0), FuelObservation(0, 40.0), 0) }
        assertTrue(FuelCorrectionRecorder.qualificationIssue(pairs)!!.contains("distinct RPM"))
    }
    @Test fun banksAndAdmissionRemainIndependent() {
        val absent = recorder(inputs - 8 - 9); qualify(absent, bank = 2)
        assertTrue(absent.intervals(2).isEmpty()); assertTrue(absent.missingRequirement(2).contains("not advertised"))
        val unadmitted = recorder(inputs, inputs - 6); qualify(unadmitted)
        assertTrue(unadmitted.intervals(1).isEmpty()); assertTrue(unadmitted.missingRequirement(1).contains("not admitted"))
        val r = recorder()
        repeat(11) { n -> val at = 100 + n * 3000L; context(r, at, second = "engine off")
            r.offer(scalar(8, 5.0), at + 5); r.offer(scalar(9, 15.0), at + 6) }
        assertTrue(r.intervals(2).isEmpty()); qualify(r, start = 40_000, bank = 2)
        assertEquals(1, r.intervals(2).size); assertTrue(r.intervals(1).isEmpty())
    }
    @Test fun boundedIntervalsPreserveFirstConcernAndLaterDifferentBehavior() {
        val r = recorder(); qualify(r)
        val original = r.intervals(1).first()
        repeat(12) { n -> qualify(r, start = 40_000 + n * 40_000L, short = if (n % 2 == 0) -5.0 else 0.0, speed = if (n % 2 == 0) 0.0 else 40.0) }
        assertEquals(8, r.intervals(1).size); assertEquals(original, r.intervals(1).first())
        assertEquals(FuelCorrectionPattern.LEARNED_WITH_SMALL_SHORT, r.intervals(1).last().pattern)
        assertTrue(r.intervals(1).zipWithNext().all { (a, b) -> a.lastOffsetMillis < b.firstOffsetMillis })
        r.stop(600_000); qualify(r, start = 700_000)
        assertEquals(8, r.intervals(1).size); assertTrue(recorder().intervals(1).isEmpty())
    }
    @Test fun reverseArrivalAndSharedMillisecondReceiptsRemainIndependent() {
        val r = FuelCorrectionRecorder(10_000, inputs, inputs)
        repeat(11) { n ->
            val at = 10_100 + n * 3000L
            context(r, at)
            r.offer(scalar(7, 15.0), at + 5)
            r.offer(scalar(6, -5.0), at + 5)
        }
        val interval = r.intervals(1).single()
        assertEquals(FuelCorrectionPattern.OPPOSING, interval.pattern)
        assertEquals(105, interval.firstOffsetMillis)
        assertTrue(interval.pairs.all { it.separationMillis == 0L })
    }
    @Test fun decoderDomainViolationsBreakEvidenceAndNeverQualify() {
        for ((pid, value) in listOf(6 to 100.0, 7 to 100.0, 12 to 16_384.0, 13 to 256.0, 4 to 101.0, 5 to 216.0)) {
            val r = recorder()
            repeat(7) { cycle(r, 100 + it * 3000L) }
            r.offer(scalar(pid, value), 19_000)
            repeat(7) { cycle(r, 21_100 + it * 3000L) }
            assertTrue(r.intervals(1).isEmpty(), "PID $pid invalid value $value must break continuity")
        }
    }
    @Test fun longContinuousRunIsSplitIntoIndependentlyQualifiedBoundedIntervals() {
        val r = recorder()
        repeat(52) { cycle(r, 100 + it * 3000L) }
        val intervals = r.intervals(1)
        assertEquals(3, intervals.size)
        assertTrue(intervals.all { it.lastOffsetMillis - it.firstOffsetMillis <= FuelCorrectionPolicy.MAX_SPAN_MS })
        val shorts = intervals.flatMap { it.pairs }.map { it.short.offsetMillis }
        assertEquals(shorts.size, shorts.distinct().size)
    }

    @Test fun oppositeUnpairedContextExcursionsCannotHideFullObservedVariation() {
        for ((pid, low, high) in listOf(Triple(12, 1200.0, 1800.0), Triple(4, 20.0, 40.0), Triple(5, 87.0, 93.0))) {
            val r = recorder()
            repeat(7) { cycle(r, 100 + it * 3000L) }
            r.offer(scalar(pid, low), 19_000)
            r.offer(scalar(pid, high), 19_100)
            repeat(7) { cycle(r, 21_100 + it * 3000L) }
            assertTrue(r.intervals(1).isEmpty(), "PID $pid full variation must break the candidate")
        }
    }
    @Test fun boundedUnpairedContextExtremaRetainTheirOriginalReceipts() {
        val r = recorder()
        repeat(11) { n ->
            val at = 100 + n * 3000L
            cycle(r, at)
            if (n == 4) { r.offer(scalar(12, 1400.0), at + 100); r.offer(scalar(12, 1600.0), at + 200) }
        }
        val b = r.intervals(1).single().contextBounds.rpm
        assertEquals(FuelObservation(12_200, 1400.0), b.minimum)
        assertEquals(FuelObservation(12_300, 1600.0), b.maximum)
    }

    @Test fun zeroLearnedCorrectionHasItsOwnDirection() {
        val r = recorder()
        qualify(r, long = 0.0)
        qualify(r, start = 33_100, long = -2.0)
        assertEquals(listOf(0, -1), r.intervals(1).map { FuelCorrectionRecorder.direction(it.pairs.first().long.value) })
    }

}
