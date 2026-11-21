package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.nativecore.*
import java.util.UUID
import kotlin.test.*

internal fun syntheticGuidedProgress(coolantCelsius: Double = 72.0): GuidedDiagnosisProgress {
    val inputs = setOf(3, 4, 5, 6, 7, 12, 13)
    val recorder = GuidedDiagnosisRecorder(0, inputs, inputs, true)
    syntheticFuelWindow(coolantCelsius, offer = recorder::offer)
    return recorder.snapshot()
}

internal fun syntheticFuelWindow(
    coolantCelsius: Double, start: Long = 100, rpm: Double = 1500.0, speed: Double = 40.0,
    load: Double = 30.0, shortTrim: Double = 5.0, longTrim: Double = 15.0,
    offer: (NativeReading, Long) -> Unit,
) {
    repeat(11) { index ->
        val at = start + index * 3_000L
        for ((n, entry) in listOf(5 to coolantCelsius, 12 to rpm, 13 to speed, 4 to load).withIndex())
            offer(NativeReading(entry.first, DecodedReading.Scalar(entry.second)), at + n)
        offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED,
            "system_2" to "engine off"))), at + 4)
        offer(NativeReading(6, DecodedReading.Scalar(shortTrim)), at + 5)
        offer(NativeReading(7, DecodedReading.Scalar(longTrim)), at + 6)
    }
}

class GuidedDiagnosisArchiveTest {
    private fun record() = DiagnosisRecord(UUID.randomUUID().toString(), 1_000_000, 1_000_000,
        DiagnosisStatus.COMPLETED, listOf(DiagnosisSegment(UUID.randomUUID().toString(), 7, "7E8", 1_000_000,
            0, endedAtOffsetMillis = 40_000, guidance = syntheticGuidedProgress())), guidedPolicyVersion = 1)

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
        val p = syntheticGuidedProgress()
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
