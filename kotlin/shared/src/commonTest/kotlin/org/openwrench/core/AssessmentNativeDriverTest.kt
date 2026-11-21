package org.openwrench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.openwrench.nativecore.*

/** Synthetic, scripted NativeSession driver. It opens no physical adapter. */
class AssessmentNativeDriverTest {
    private class Clock(var time: Long = 500) : AssessmentClock { override fun nowMillis() = time }
    private class Transcript : NativeTransport {
        var connected = false
        val reads = mutableListOf<String>()
        override fun open() { connected = true }
        override fun close() { connected = false }
        override fun exchange(command: String, timeoutMillis: Long): String {
            assertTrue(connected)
            val startup = mapOf("ATZ" to "ELM327 v1.5\r>", "ATE0" to "OK\r>",
                "ATL0" to "OK\r>", "ATS0" to "OK\r>", "ATH0" to "OK\r>",
                "ATSP0" to "OK\r>", "ATI" to "ELM327 v1.5\r>")
            if (command in startup) return startup.getValue(command)
            if (command == "0100") {
                val bitmap = listOf(3, 4, 5, 6, 7, 12).fold(0L) { bits, pid -> bits or (1L shl (32 - pid)) }
                return "4100${bitmap.toString(16).uppercase().padStart(8, '0')}\r>"
            }
            assertEquals(NATIVE_STANDARD_READ_TIMEOUT_MILLIS, timeoutMillis)
            reads += command
            return mapOf("0103" to "41030200\r>", "0104" to "410480\r>",
                "0105" to "41055A\r>", "0106" to "410680\r>",
                "0107" to "410780\r>", "010C" to "410C1AF8\r>").getValue(command)
        }
    }

    @Test fun scriptedMixedReadActionsReachEvidenceReady() {
        val specs = listOf("fuel-system-status" to 0x03, "stft" to 0x06, "ltft" to 0x07,
            "rpm" to 0x0C, "coolant" to 0x05, "load" to 0x04)
        val inputs = specs.map { (id, pid) ->
            AssessmentInput(InputRequirement(id, 1, 0, 100, "01${pid.toString(16).uppercase().padStart(2, '0')}",
                expectedUnit = ObdDecoder.catalog.getValue(pid).unit,
                expectedType = if (pid == 3) SignalType.STRUCTURED else SignalType.NUMBER))
        }
        val profile = AssessmentProfile(AssessmentProfileIdentity("synthetic-native-driver", "1"),
            AssessmentMode.SYNTHETIC_MIXED_READ_ONLY, inputs, emptyList(), 100, 6, 10)
        val transcript = Transcript()
        val session = NativeSession(transcript)
        val capabilities = session.connect()
        val clock = Clock()
        val collector = AssessmentCollector(profile, "synthetic-instance", 3, ObservationSource.HARDWARE,
            capabilities.pids, emptyMap(), clock)
        while (collector.snapshot().state == AcquisitionState.COLLECTING) {
            val action = assertNotNull(collector.nextAction())
            val reading = when (action) {
                is AssessmentReadAction.Scalar -> session.read(action.pid)
                is AssessmentReadAction.FuelSystemStatus -> session.readStructured(0x03)
            }
            clock.time++
            val metadata = ObdDecoder.catalog.getValue(reading.pid)
            val requirement = inputs.single { it.requirement.signalId == action.signalId }.requirement
            val value = when (val decoded = reading.value) {
                is DecodedReading.Structured -> SignalValue.Structured(decoded.fields)
                is DecodedReading.Scalar -> SignalValue.Number(decoded.value)
                null -> error("The synthetic transcript supplies a value")
            }
            val observation = Observation(SignalDefinition(requirement.signalId, metadata.name, metadata.unit,
                requirement.expectedType!!, SignalSupport.SUPPORTED, requirement.expectedPid), value,
                SignalAvailability.AVAILABLE, ObservationSource.HARDWARE, 3, clock.time)
            assertTrue(collector.complete(action, observation))
        }
        val ready = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
        assertEquals(specs.map { it.first }.toSet(), ready.selectedEvidence.keys)
        assertEquals(6, transcript.reads.size)
        assertEquals("closed loop: oxygen-sensor feedback", (ready.selectedEvidence.getValue("fuel-system-status")
            .single().value as SignalValue.Structured).fields["system_1"])
        assertTrue(ready.selectedEvidence.values.flatten().all { it.definition.module == null })
        session.disconnect()
    }
}
