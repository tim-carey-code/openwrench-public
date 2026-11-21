package org.openwrench.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openwrench.core.*
import org.openwrench.frontend.android.HealthObservationBridge
import org.openwrench.nativecore.*

/** Synthetic transcripts only. This driver never discovers or connects a physical adapter. */
class AssessmentScriptedDriverTest {
    private class Clock(var time: Long = 1_000) : AssessmentClock { override fun nowMillis() = time }
    private class ScriptedTransport(private val fault: ErrorKind? = null) : NativeTransport {
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()
        private var open = false
        override fun open() { open = true }
        override fun close() { open = false }
        override fun exchange(command: String, timeoutMillis: Long): String {
            assertTrue(open)
            commands += command
            timeouts += timeoutMillis
            val responses = mapOf(
                "ATZ" to "ELM327 v1.5\r>", "ATE0" to "OK\r>", "ATL0" to "OK\r>",
                "ATS0" to "OK\r>", "ATH0" to "OK\r>", "ATSP0" to "OK\r>",
                "ATI" to "ELM327 v1.5\r>",
                "0103" to "41030200\r>", "0104" to "410480\r>", "0105" to "41055A\r>",
                "0106" to "410680\r>", "0107" to "410780\r>", "010C" to "410C1AF8\r>",
            )
            if (command == "0100") {
                val pids = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0C)
                val bitmap = pids.fold(0L) { bits, pid -> bits or (1L shl (32 - pid)) }
                return "4100${bitmap.toString(16).uppercase().padStart(8, '0')}\r>"
            }
            if (command.startsWith("01") && fault != null) {
                if (fault == ErrorKind.TIMEOUT) throw NativeDiagnosticException(ErrorKind.TIMEOUT, "synthetic timeout")
                return "410300\r>" // malformed length on the first structured read
            }
            return assertNotNull(responses[command])
        }
    }

    private val inputs = listOf(
        Triple("fuel-system-status", 0x03, SignalType.STRUCTURED),
        Triple("stft", 0x06, SignalType.NUMBER),
        Triple("ltft", 0x07, SignalType.NUMBER),
        Triple("rpm", 0x0C, SignalType.NUMBER),
        Triple("coolant", 0x05, SignalType.NUMBER),
        Triple("load", 0x04, SignalType.NUMBER),
    )
    private fun profile() = AssessmentProfile(
        AssessmentProfileIdentity("synthetic-mixed-six", "1"), AssessmentMode.SYNTHETIC_MIXED_READ_ONLY,
        inputs.map { (id, pid, type) ->
            AssessmentInput(InputRequirement(id, 1, 0, 1_000, "01${pid.toString(16).uppercase().padStart(2, '0')}",
                expectedUnit = ObdDecoder.catalog.getValue(pid).unit, expectedType = type))
        }, emptyList(), totalBudgetMillis = 1_000, commandCap = 6, maximumCrossSignalGapMillis = 20,
    )

    private fun drive(fault: ErrorKind? = null): Triple<AssessmentCollector, ScriptedTransport, NativeSession> {
        val transport = ScriptedTransport(fault)
        val session = NativeSession(transport)
        val capabilities = session.connect()
        val clock = Clock()
        val collector = AssessmentCollector(profile(), "synthetic-run", 9, ObservationSource.HARDWARE,
            capabilities.pids, emptyMap(), clock)
        while (collector.snapshot().state == AcquisitionState.COLLECTING) {
            val action = assertNotNull(collector.nextAction())
            try {
                val reading = when (action) {
                    is AssessmentReadAction.Scalar -> session.read(action.pid)
                    is AssessmentReadAction.FuelSystemStatus -> session.readStructured(0x03)
                }
                clock.time++ // completed-read receipt, not ECU sample time
                val observation = HealthObservationBridge.normalize(reading, SignalSupport.SUPPORTED,
                    ObservationSource.HARDWARE, 9, clock.time)
                assertTrue(collector.complete(action, observation))
            } catch (error: NativeDiagnosticException) {
                assertTrue(collector.fail(action, "${error.kind}: ${error.message}"))
            }
        }
        return Triple(collector, transport, session)
    }

    @Test fun syntheticMixedNativeReadsProduceQualifiedEvidenceWithoutDiagnosis() {
        val (collector, transport, session) = drive()
        val ready = collector.snapshot()
        assertEquals(AcquisitionState.EVIDENCE_READY, ready.state)
        assertEquals(6, ready.attemptsDispatched)
        assertEquals(inputs.map { it.first }.toSet(), ready.selectedEvidence.keys)
        assertTrue(ready.inputs.all { it.qualified && it.reasons.isEmpty() })
        val profile = profile()
        for (input in profile.required) {
            val evidence = ready.selectedEvidence.getValue(input.requirement.signalId)
            assertEquals(1, evidence.size)
            val receipt = evidence.single()
            assertEquals(input.requirement.expectedPid, receipt.definition.pid)
            assertEquals(input.requirement.expectedType, receipt.definition.type)
            assertEquals(input.requirement.expectedUnit, receipt.definition.unit)
            assertEquals(ObservationSource.HARDWARE, receipt.source)
            assertEquals(9, receipt.sessionGeneration)
            assertNull(receipt.definition.module) // unaddressed ELM response has unknown ECU identity
            assertTrue(receipt.ageMillis(ready.elapsedMillis + 1_000) <= input.requirement.maximumAgeMillis)
        }
        val fields = assertIs<SignalValue.Structured>(ready.selectedEvidence.getValue("fuel-system-status")
            .single().value).fields
        assertTrue("system_1" in fields && "system_2" in fields)
        assertEquals(6, transport.commands.count { it in setOf("0103", "0104", "0105", "0106", "0107", "010C") })
        assertEquals(List(6) { NATIVE_STANDARD_READ_TIMEOUT_MILLIS }, transport.timeouts.takeLast(6))
        assertEquals(NativeSessionState.CONNECTED, session.state)
        // The acquisition result contains observations only; no rule pack or HealthAssessment is invoked.
        session.disconnect()
    }

    @Test fun scriptedTimeoutAndProtocolFaultsTerminateWithoutRetry() {
        for (kind in listOf(ErrorKind.TIMEOUT, ErrorKind.MALFORMED_RESPONSE)) {
            val (collector, transport, session) = drive(kind)
            val result = collector.snapshot()
            assertEquals(AcquisitionState.FAILED, result.state)
            assertEquals(1, result.attemptsDispatched)
            assertTrue(result.selectedEvidence.isEmpty())
            assertFalse(result.failure.isNullOrBlank())
            assertNull(collector.nextAction())
            assertEquals(1, transport.commands.count { it == "0103" })
            assertEquals(NativeSessionState.FAULTED, session.state)
        }
    }
}
