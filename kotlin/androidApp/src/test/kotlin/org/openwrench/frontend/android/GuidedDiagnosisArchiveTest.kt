package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.nativecore.*
import java.util.UUID
import kotlin.test.*

class GuidedDiagnosisArchiveTest {
    private fun proof(): GuidedDiagnosisProgress {
        val inputs = setOf(3, 4, 5, 6, 7, 12, 13)
        val recorder = GuidedDiagnosisRecorder(0, inputs, inputs, true)
        repeat(11) { index ->
            val at = 100 + index * 3_000L
            for ((n, entry) in listOf(5 to 72.0, 12 to 1500.0, 13 to 40.0, 4 to 30.0).withIndex())
                recorder.offer(NativeReading(entry.first, DecodedReading.Scalar(entry.second)), at + n)
            recorder.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED,
                "system_2" to "engine off"))), at + 4)
            recorder.offer(NativeReading(6, DecodedReading.Scalar(5.0)), at + 5)
            recorder.offer(NativeReading(7, DecodedReading.Scalar(15.0)), at + 6)
        }
        return recorder.snapshot()
    }
    private fun record() = DiagnosisRecord(UUID.randomUUID().toString(), 1_000_000, 1_000_000,
        DiagnosisStatus.COMPLETED, listOf(DiagnosisSegment(UUID.randomUUID().toString(), 7, "7E8", 1_000_000,
            0, endedAtOffsetMillis = 40_000, guidance = proof())), guidedPolicyVersion = 1)

    @Test fun newProofRoundTripsAndLegacyMetadataRemainsUnqualifiedAndUnchanged() {
        val r = record()
        assertEquals(r, DiagnosisCodec.decode(DiagnosisCodec.encode(r)))
        assertContains(DiagnosisCodec.encode(r), "openwrench.diagnosis/2")
        val legacy = r.copy(guidedPolicyVersion = null, segments = r.segments.map { it.copy(guidance = null) })
        val original = DiagnosisCodec.encode(legacy)
        assertContains(original, "openwrench.diagnosis/1")
        assertEquals(original, DiagnosisCodec.encode(DiagnosisCodec.decode(original)))
        assertNull(DiagnosisCodec.decode(original).segments.single().guidance)
    }

    @Test fun rejectsInvalidPolicyColdProofReusedReceiptsAndInconsistentMilestones() {
        val p = proof()
        assertFails { GuidedDiagnosisArchive.encode(p.copy(policyVersion = 2)) }
        assertFails { GuidedDiagnosisArchive.encode(p.copy(initialAssessmentOffsetMillis = 1)) }
        assertFails { GuidedDiagnosisArchive.encode(p.copy(scanSucceeded = false)) }
        val interval = p.banks.first().interval!!
        val cold = interval.copy(contextBounds = interval.contextBounds.copy(coolant = FuelCorrectionBounds(
            FuelObservation(100, 71.0), FuelObservation(30_100, 72.0))))
        assertFails { GuidedDiagnosisArchive.encode(p.copy(banks = listOf(p.banks[0].copy(interval = cold), p.banks[1]))) }
        val reused = interval.copy(pairs = interval.pairs.map { it.copy(short = interval.pairs.first().short) })
        assertFails { GuidedDiagnosisArchive.encode(p.copy(banks = listOf(p.banks[0].copy(interval = reused), p.banks[1]))) }
        assertFails { DiagnosisCodec.encode(record().copy(guidedPolicyVersion = 2)) }
        val r = record()
        assertFails { DiagnosisCodec.encode(r.copy(segments = r.segments.map { it.copy(endedAtOffsetMillis = 1) })) }
        val encoded = GuidedDiagnosisArchive.encode(p).jsonObject
        assertFails { GuidedDiagnosisArchive.decode(JsonObject(encoded + ("extra" to JsonPrimitive(true)))) }
    }
}
