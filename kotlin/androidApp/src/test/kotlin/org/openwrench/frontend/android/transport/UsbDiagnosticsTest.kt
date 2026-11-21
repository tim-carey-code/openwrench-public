package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import org.openwrench.nativecore.NativeFailureStage
import org.openwrench.nativecore.NativeSession
import org.openwrench.nativecore.NativeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbDiagnosticsTest {
    @Test
    fun aggregateTimingSurvivesEventRolloverAndResetsWithTheConnection() {
        var clock = 0L
        val diagnostics = UsbDiagnostics { clock }
        val old = diagnostics.begin(1, 500000, UsbControlLines.ASSERTED)
        repeat(600) {
            clock += 100
            old.completed("010C", 80)
            old.event("command=010C result=complete")
        }
        val copied = diagnostics.snapshot()
        assertTrue(copied.contains("command=010C completed=600 meanExchangeMs=80 maxExchangeMs=80 meanCompletionIntervalMs=100"))
        assertTrue(copied.lines().size < 190)
        diagnostics.begin(2, 500000, UsbControlLines.ASSERTED)
        old.completed("010C", 20)
        assertFalse(diagnostics.snapshot().contains("meanExchangeMs"))
    }

    @Test
    fun timingSummaryAcceptsOnlyAllowlistedPidMetadata() {
        val diagnostics = UsbDiagnostics { 0 }
        val attempt = diagnostics.begin(1, 500000, UsbControlLines.ASSERTED)
        listOf("0902", "PRIVATE_VIN", "0100", "01FF").forEach { attempt.completed(it, 80) }
        assertFalse(diagnostics.snapshot().contains("meanExchangeMs"))
    }

    @Test
    fun capabilityFailureSurvivesCleanupWithoutCopyingTheVehicleReply() {
        val diagnostics = UsbDiagnostics { 0L }
        val attempt = diagnostics.begin(1, 0, UsbControlLines.ASSERTED)
        var closes = 0
        val transport = object : NativeTransport {
            override fun open() = Unit
            override fun close() { closes++; attempt.event("CLOSE complete") }
            override fun exchange(command: String, timeoutMillis: Long): String = when (command) {
                "ATZ", "ATI" -> "ELM327 v1.5\r>"
                "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0" -> "OK\r>"
                "0100" -> "CAN ERROR PRIVATE_VEHICLE_DATA\r>"
                else -> error("Unexpected command $command")
            }

            override fun reportSessionFailure(stage: NativeFailureStage, kind: ErrorKind) =
                attempt.sessionFailure(stage, kind)
        }

        val error = assertFailsWith<NativeDiagnosticException> { NativeSession(transport).connect() }
        assertEquals(ErrorKind.ADAPTER, error.kind)
        assertTrue(error.message!!.contains("PRIVATE_VEHICLE_DATA"))
        assertEquals(1, closes)
        val copied = diagnostics.snapshot()
        assertTrue(copied.contains("session result=failed stage=CAPABILITY_DISCOVERY kind=ADAPTER"))
        assertTrue(copied.indexOf("session result=failed") < copied.indexOf("CLOSE complete"))
        assertFalse(copied.contains("PRIVATE_VEHICLE_DATA"))
        assertFalse(copied.contains("ELM327 v1.5"))
        assertEquals(copied, diagnostics.snapshot(), "Copying a diagnostic must not consume the failure")
    }

    @Test
    fun lateFailureFromPreviousOwnerCannotReplaceTheLatestAttempt() {
        val diagnostics = UsbDiagnostics { 0L }
        val previous = diagnostics.begin(1, 38400, UsbControlLines.ASSERTED)
        previous.sessionFailure(NativeFailureStage.INITIALIZATION, ErrorKind.ADAPTER)
        val latest = diagnostics.begin(2, 500000, UsbControlLines.DEASSERTED)
        latest.event("command=OPEN result=ready")
        previous.sessionFailure(NativeFailureStage.READING, ErrorKind.TIMEOUT)
        previous.event("CLOSE complete")

        val copied = diagnostics.snapshot()
        assertTrue(copied.contains("deviceId=2 baud=500000"))
        assertTrue(copied.contains("command=OPEN result=ready"))
        assertFalse(copied.contains("session result=failed"))
        assertFalse(copied.contains("CLOSE complete"))
    }

    @Test
    fun failureMetadataRemainsBoundedAfterLongAttempts() {
        val diagnostics = UsbDiagnostics { 0L }
        val attempt = diagnostics.begin(1, 500000, UsbControlLines.ASSERTED)
        repeat(500) { attempt.event("command=010C result=complete") }
        attempt.sessionFailure(NativeFailureStage.READING, ErrorKind.MALFORMED_RESPONSE)
        attempt.event("CLOSE complete")

        val copied = diagnostics.snapshot()
        assertTrue(copied.lines().size <= 185)
        assertTrue(copied.contains("session result=failed stage=READING kind=MALFORMED_RESPONSE"))
        assertTrue(copied.endsWith("CLOSE complete"))
    }
}
