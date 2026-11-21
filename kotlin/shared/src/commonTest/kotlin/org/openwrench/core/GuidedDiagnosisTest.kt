package org.openwrench.core

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.*

class GuidedDiagnosisTest {
    private val inputs = setOf(3, 4, 5, 6, 7, 8, 9, 12, 13)
    private fun recorder(supported: Set<Int> = inputs, admitted: Set<Int> = supported, scan: Boolean = true) =
        GuidedDiagnosisRecorder(0, supported, admitted, scan)
    private fun scalar(pid: Int, value: Double?) = NativeReading(pid, value?.let { DecodedReading.Scalar(it) })
    private fun cycle(r: GuidedDiagnosisRecorder, at: Long, coolant: Double = 72.0,
        rpm: Double = 1500.0, speed: Double = 40.0, closed: Boolean = true, banks: List<Int> = listOf(1),
        long: Double = 15.0, short: Double = 5.0,
    ) {
        r.offer(scalar(5, coolant), at)
        r.offer(scalar(12, rpm), at + 1); r.offer(scalar(13, speed), at + 2); r.offer(scalar(4, 30.0), at + 3)
        r.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to
            if (closed) FuelCorrectionRecorder.CLOSED else "open loop: load or deceleration fuel cut",
            "system_2" to FuelCorrectionRecorder.CLOSED))), at + 4)
        banks.forEach { bank ->
            r.offer(scalar(if (bank == 1) 6 else 8, short), at + 5 + bank * 2)
            r.offer(scalar(if (bank == 1) 7 else 9, long), at + 6 + bank * 2)
        }
    }
    private fun qualify(r: GuidedDiagnosisRecorder, start: Long = 100, coolant: Double = 72.0, banks: List<Int> = listOf(1)) {
        repeat(11) { cycle(r, start + it * 3_000L, coolant = coolant, banks = banks) }
    }

    @Test fun boundaryAndWarmAtConnectionNeedNoColdRise() {
        for (temperature in listOf(71.0, 72.0)) {
            val r = recorder(); qualify(r, coolant = temperature)
            assertEquals(temperature == 72.0, r.snapshot().initialAssessmentAvailable)
        }
        val warm = recorder(); qualify(warm, coolant = 97.0)
        assertTrue(warm.snapshot().initialAssessmentAvailable)
        assertEquals(1, warm.snapshot().policyVersion)
    }

    @Test fun coldIntervalPlusWarmSampleCannotQualifyAndWarmDipRequiresWholeNewWindow() {
        val r = recorder(); qualify(r, coolant = 71.0)
        cycle(r, 33_100, coolant = 72.0)
        assertFalse(r.snapshot().initialAssessmentAvailable)
        val dip = recorder(); repeat(6) { cycle(dip, 100 + it * 3_000L) }
        dip.offer(scalar(5, 71.0), 16_000)
        repeat(6) { cycle(dip, 18_100 + it * 3_000L) }
        assertFalse(dip.snapshot().initialAssessmentAvailable)
        qualify(dip, start = 40_100)
        assertTrue(dip.snapshot().initialAssessmentAvailable)
        assertTrue(dip.snapshot().banks.first().interval!!.firstOffsetMillis >= 18_100)
    }

    @Test fun contextFailuresNeverJoinShortRuns() {
        for (pid in listOf(3, 4, 5, 12, 13, 6, 7)) {
            val r = recorder(); repeat(6) { cycle(r, 100 + it * 3_000L) }
            r.offer(NativeReading(pid, null), 16_000)
            repeat(6) { cycle(r, 18_100 + it * 3_000L) }
            assertFalse(r.snapshot().initialAssessmentAvailable, "PID $pid")
        }
        for (transition in listOf("stop", "loop", "motion", "gap")) {
            val r = recorder(); repeat(6) { cycle(r, 100 + it * 3_000L) }
            when (transition) {
                "stop" -> cycle(r, 18_100, rpm = 0.0)
                "loop" -> cycle(r, 18_100, closed = false)
                "motion" -> cycle(r, 18_100, speed = 0.0)
                else -> Unit
            }
            repeat(6) { cycle(r, 33_100 + it * 3_000L) }
            assertFalse(r.snapshot().initialAssessmentAvailable, transition)
        }
    }

    @Test fun missingStaleAndUnadmittedInputsRemainExplicit() {
        for (pid in inputs) {
            val r = recorder(admitted = inputs - pid); qualify(r, banks = listOf(1, 2))
            if (pid !in setOf(8, 9)) assertEquals(GuidedBankStatus.COLLECTING, r.snapshot().banks.first().status)
            assertTrue(r.snapshot().banks.any { it.requirement.contains("not admitted") })
        }
        val stale = recorder(); cycle(stale, 100)
        assertContains(stale.snapshot(11_000).banks.first().requirement, "10000")
        val missing = recorder(supported = inputs - 5); qualify(missing)
        assertFalse(missing.snapshot().initialAssessmentAvailable)
        assertContains(missing.snapshot().banks.first().requirement, "not advertised")
    }

    @Test fun unsupportedBankIsUnavailableButSupportedSilentBankStaysIncomplete() {
        val one = recorder(supported = inputs - 8 - 9); qualify(one)
        assertTrue(one.snapshot().initialAssessmentAvailable)
        assertEquals(GuidedBankStatus.UNAVAILABLE, one.snapshot().banks[1].status)
        val two = recorder(); qualify(two)
        assertTrue(two.snapshot().initialAssessmentAvailable)
        assertEquals(GuidedBankStatus.COLLECTING, two.snapshot().banks[1].status)
        val both = recorder(); qualify(both, banks = listOf(1, 2))
        assertTrue(both.snapshot().banks.all { it.status == GuidedBankStatus.QUALIFIED })
        val none = recorder(supported = inputs - 6 - 7 - 8 - 9); qualify(none)
        assertFalse(none.snapshot().initialAssessmentAvailable)
        assertTrue(none.snapshot().banks.all { it.status == GuidedBankStatus.UNAVAILABLE })
    }

    @Test fun cachedOrDuplicateReceiptsCannotCreateEvidence() {
        val r = recorder(); cycle(r, 100)
        repeat(100) {
            r.offer(scalar(6, 5.0), 107); r.offer(scalar(7, 15.0), 108)
        }
        assertFalse(r.snapshot().initialAssessmentAvailable)
        val onlyLong = recorder()
        repeat(15) { cycle(onlyLong, 100 + it * 3_000L, banks = emptyList())
            onlyLong.offer(scalar(7, 15.0), 110 + it * 3_000L) }
        assertFalse(onlyLong.snapshot().initialAssessmentAvailable)
    }

    @Test fun failedScanBlocksMilestoneAndStopFreezesEvidenceWithoutJoiningRestart() {
        val partial = recorder(scan = false); qualify(partial)
        assertFalse(partial.snapshot().initialAssessmentAvailable)
        assertEquals(GuidedBankStatus.QUALIFIED, partial.snapshot().banks.first().status)
        val r = recorder(); repeat(6) { cycle(r, 100 + it * 3_000L) }
        r.stop(16_000); qualify(r, start = 18_100)
        assertFalse(r.snapshot().initialAssessmentAvailable)
        val restart = recorder(); repeat(6) { cycle(restart, 18_100 + it * 3_000L) }
        assertFalse(restart.snapshot().initialAssessmentAvailable)
    }

    @Test fun milestoneIsStableWhileFurtherEvidenceContinuesAndWarningProofSurvives() {
        val r = recorder(); qualify(r)
        val proof = r.snapshot().banks.first().interval!!
        val milestone = r.snapshot().initialAssessmentOffsetMillis
        assertTrue(proof.screened)
        repeat(30) { cycle(r, 40_100 + it * 3_000L, long = 0.0, short = 0.0) }
        assertEquals(milestone, r.snapshot().initialAssessmentOffsetMillis)
        assertEquals(proof, r.snapshot().banks.first().interval)
        assertTrue(r.snapshot().evaluatedAtOffsetMillis > milestone!!)
        assertContains(r.snapshot().reportText(), "No problem is ruled out")
    }
}
