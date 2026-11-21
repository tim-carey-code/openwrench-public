package org.openwrench.nativecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure protocol transcripts: these tests never enumerate or connect a hardware adapter. */
class NativeSessionTest {
    @Test
    fun cancellationDuringTransportNegotiationStopsBeforeAnotherProbeOrInitialization() {
        var active = true
        var probes = 0
        var closes = 0
        var exchanges = 0
        val cancellation = IllegalStateException("Owner cancelled")
        val transport = object : NativeTransport {
            override fun open() = error("Negotiating transports require the owner callback")
            override fun open(checkActive: () -> Unit) {
                repeat(3) {
                    checkActive()
                    probes++
                    active = false // Owner cancels while the first probe is in flight.
                }
            }
            override fun close() { closes++ }
            override fun exchange(command: String, timeoutMillis: Long): String {
                exchanges++
                error("Initialization must not start after cancellation")
            }
        }
        val session = NativeSession(transport)
        val error = assertFailsWith<NativeDiagnosticException> {
            session.connect { if (!active) throw cancellation }
        }
        assertEquals(cancellation, error.cause)
        assertEquals(1, probes)
        assertEquals(0, exchanges)
        assertEquals(1, closes)
        assertEquals(NativeSessionState.FAULTED, session.state)
        assertNull(session.capabilities)
    }

    private data class Step(val command: String, val response: String = "", val error: Exception? = null)

    private class ScriptedTransport(script: List<Step>) : NativeTransport {
        val remaining = script.toMutableList()
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()
        var openCalls = 0
        var closeCalls = 0
        var isOpen = false
        var closeFailures = 0
        var openError: Exception? = null

        override fun open() {
            openCalls++
            openError?.let { throw it }
            isOpen = true
        }

        override fun close() {
            closeCalls++
            if (closeFailures > 0) {
                closeFailures--
                throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Scripted close failure")
            }
            isOpen = false
        }

        override fun exchange(command: String, timeoutMillis: Long): String {
            assertTrue(isOpen, "Exchange requires an open adapter")
            assertTrue(remaining.isNotEmpty(), "Unexpected request $command")
            val next = remaining.removeAt(0)
            assertEquals(next.command, command)
            commands.add(command)
            timeouts.add(timeoutMillis)
            next.error?.let { throw it }
            return next.response
        }
    }

    private fun initialization() = listOf(
        Step("ATZ", "ATZ\r\rELM327 v1.5\r>"),
        Step("ATE0", "ATE0\rOK\r>"),
        Step("ATL0", "OK\r>"), Step("ATS0", "OK\r>"), Step("ATH0", "OK\r>"),
        Step("ATSP0", "OK\r>"), Step("ATI", "ELM327 v1.5\r>"),
    )

    private fun bitmap(base: Int, vararg pids: Int): Step {
        var value = 0L
        pids.forEach {
            require(it - base in 1..32)
            value = value or (1L shl (32 - (it - base)))
        }
        val hex = base.toString(16).uppercase().padStart(2, '0')
        return Step("01$hex", "41$hex${value.toString(16).uppercase().padStart(8, '0')}\r>")
    }

    private fun connected(vararg script: Step, pids: IntArray = intArrayOf(0x0C)): Pair<NativeSession, ScriptedTransport> {
        val transport = ScriptedTransport(initialization() + bitmap(0, *pids) + script)
        val session = NativeSession(transport)
        session.connect()
        return session to transport
    }

    private fun expectKind(kind: ErrorKind, action: () -> Unit): NativeDiagnosticException {
        val error = assertFailsWith<NativeDiagnosticException> { action() }
        assertEquals(kind, error.kind)
        return error
    }

    private fun assertFaulted(session: NativeSession, transport: ScriptedTransport) {
        assertEquals(NativeSessionState.FAULTED, session.state)
        assertNull(session.capabilities)
        assertNull(session.identity)
        assertFalse(transport.isOpen)
    }

    @Test
    fun initializationUsesResetTimeoutAndConnectionIsIdempotent() {
        val (session, transport) = connected()
        assertEquals("ELM327v1.5", session.identity)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        assertEquals(setOf(0x0C), session.connect().pids)
        assertEquals(listOf(0), session.capabilities?.ranges)
        assertEquals(1, transport.openCalls)
        assertEquals(listOf(10_000L) + List(7) { 3_000L }, transport.timeouts)
        assertTrue(transport.remaining.isEmpty())
        session.disconnect()
        assertEquals(NativeSessionState.DISCONNECTED, session.state)
        assertNull(session.identity)
        assertNull(session.capabilities)
        assertFalse(transport.isOpen)
    }

    @Test
    fun discoversEntireContinuationChainThroughE0() {
        val ranges = (0..0xE0 step 0x20).toList()
        val transport = ScriptedTransport(initialization() + ranges.map { base ->
            if (base < 0xE0) bitmap(base, base + 1, base + 0x20) else bitmap(base, 0xE1, 0xFF, 0x100)
        })
        val session = NativeSession(transport)
        val capabilities = session.connect()
        assertEquals(ranges, capabilities.ranges)
        assertTrue(0xE1 in capabilities.pids)
        assertTrue(0xFF in capabilities.pids)
        assertFalse(0x100 in capabilities.pids)
        assertTrue(transport.remaining.isEmpty())
        session.disconnect()
    }

    @Test
    fun anEmptyBitmapIsAValidConnectedVehicleWithNoEligibleReads() {
        val (session, transport) = connected(pids = intArrayOf())
        assertTrue(session.connect().pids.isEmpty())
        assertTrue(session.snapshot().isEmpty())
        expectKind(ErrorKind.UNSUPPORTED) { session.read(0x0C) }
        assertEquals(NativeSessionState.CONNECTED, session.state)
        assertTrue(transport.remaining.isEmpty())
        session.disconnect()
    }

    @Test
    fun echoAndStandaloneSearchMessagesAreNormalized() {
        val (session, transport) = connected(Step("010C", "01 0C\rSEARCHING...\nBUS INIT: OK\r41 0C 1A F8\r>"))
        assertEquals(1726.0, assertIs<DecodedReading.Scalar>(session.read(0x0C).value).value)
        assertTrue(transport.remaining.isEmpty())
        session.disconnect()
    }

    @Test
    fun onlyExactNoDataIsUnavailableAndNextReadCanRecover() {
        val (session, transport) = connected(Step("010C", "NO DATA\r>"), Step("010C", "410C0B2C\r>"))
        assertNull(session.read(0x0C).value)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        assertEquals(715.0, assertIs<DecodedReading.Scalar>(session.read(0x0C).value).value)
        assertEquals(0, transport.closeCalls)
        session.disconnect()
    }

    @Test
    fun structuredFuelStatusReusesDecoderAndNoDataCanRecover() {
        val (session, transport) = connected(
            Step("0103", "41 03 02 00\r>"), Step("0103", "NO DATA\r>"),
            Step("0103", "41030108\r>"), pids = intArrayOf(0x03))
        val first = assertIs<DecodedReading.Structured>(session.readStructured(0x03).value)
        assertEquals("closed loop: oxygen-sensor feedback", first.fields["system_1"])
        assertEquals("engine off", first.fields["system_2"])
        assertNull(session.readStructured(0x03).value)
        assertEquals("open loop: system fault",
            assertIs<DecodedReading.Structured>(session.readStructured(0x03).value).fields["system_2"])
        assertEquals(List(3) { NATIVE_STANDARD_READ_TIMEOUT_MILLIS }, transport.timeouts.takeLast(3))
        session.disconnect()
    }

    @Test
    fun structuredMonitorStatusReportsEcuFlagsAndCanRecoverFromNoData() {
        val (session, transport) = connected(
            Step("0101", "410185079110\r>"), Step("0101", "NO DATA\r>"),
            Step("0101", "410100000000\r>"), pids = intArrayOf(0x01))
        val first = assertIs<DecodedReading.Structured>(session.readStructured(0x01).value)
        assertEquals("true", first.fields["mil_on"])
        assertEquals("5", first.fields["dtc_count"])
        assertNull(session.readStructured(0x01).value)
        val recovered = assertIs<DecodedReading.Structured>(session.readStructured(0x01).value)
        assertEquals("false", recovered.fields["mil_on"])
        assertEquals("0", recovered.fields["dtc_count"])
        assertEquals(3, transport.commands.count { it == "0101" })
        assertEquals(NativeSessionState.CONNECTED, session.state)
        session.disconnect()
    }

    @Test
    fun structuredMonitorStatusRejectsMalformedOrWrongPidReplies() {
        for (response in listOf("410100\r>", "410300000000\r>", "410100000000\rNO DATA\r>")) {
            val (session, transport) = connected(Step("0101", response), pids = intArrayOf(0x01))
            expectKind(ErrorKind.MALFORMED_RESPONSE) { session.readStructured(0x01) }
            assertFaulted(session, transport)
        }
        val (session, transport) = connected(pids = intArrayOf(0x13))
        val before = transport.commands.toList()
        expectKind(ErrorKind.UNSUPPORTED) { session.readStructured(0x13) }
        assertEquals(before, transport.commands)
        session.disconnect()
    }

    @Test
    fun structuredGateRejectsUnadvertisedAndOtherPidsBeforeExchange() {
        val (session, transport) = connected(pids = intArrayOf(0x0C))
        val before = transport.commands.toList()
        expectKind(ErrorKind.UNSUPPORTED) { session.readStructured(0x03) }
        expectKind(ErrorKind.UNSUPPORTED) { session.readStructured(0x01) }
        expectKind(ErrorKind.UNSUPPORTED) { session.read(0x03) }
        assertEquals(before, transport.commands)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        session.disconnect()
    }

    @Test
    fun structuredMalformedAndAmbiguousResponsesFaultAndRelease() {
        for (response in listOf("410302\r>", "41030300\r>", "410C0200\r>",
            "41030200\r41030100\r>", "41030200\rNO DATA\r>", "41030200\r>>")) {
            val (session, transport) = connected(Step("0103", response), pids = intArrayOf(0x03))
            expectKind(ErrorKind.MALFORMED_RESPONSE) { session.readStructured(0x03) }
            assertFaulted(session, transport)
        }
    }

    @Test
    fun structuredTimeoutFaultsWithoutRetry() {
        val (session, transport) = connected(
            Step("0103", error = NativeDiagnosticException(ErrorKind.TIMEOUT, "Scripted slow response")),
            pids = intArrayOf(0x03))
        expectKind(ErrorKind.TIMEOUT) { session.readStructured(0x03) }
        assertEquals(1, transport.commands.count { it == "0103" })
        assertEquals(NATIVE_STANDARD_READ_TIMEOUT_MILLIS, transport.timeouts.last())
        assertFaulted(session, transport)
    }

    @Test
    fun unsupportedAndUnsafeReadsNeverReachTransport() {
        val (session, transport) = connected(pids = intArrayOf(0x01, 0x02, 0x0C))
        val before = transport.commands.toList()
        for (pid in listOf(-1, 0x01, 0x02, 0x05, 0x100, 0x3FFF)) {
            expectKind(ErrorKind.UNSUPPORTED) { session.read(pid) }
        }
        assertEquals(before, transport.commands)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        session.disconnect()
    }

    @Test
    fun disconnectedOperationsNeverOpenTransport() {
        val transport = ScriptedTransport(emptyList())
        val session = NativeSession(transport)
        expectKind(ErrorKind.NOT_CONNECTED) { session.read(0x0C) }
        expectKind(ErrorKind.NOT_CONNECTED) { session.identifyVin() }
        expectKind(ErrorKind.NOT_CONNECTED) { session.snapshot() }
        assertEquals(0, transport.openCalls)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun snapshotUsesItsOwnAllowlistAndSkipsUnsupportedAndBitmapPids() {
        val transport = ScriptedTransport(initialization() + listOf(
            bitmap(0, 0x01, 0x02, 0x0A, 0x0C, 0x20), bitmap(0x20, 0x21),
            Step("0101", "410100000000\r>"), Step("010C", "NO DATA\r>"), Step("0121", "41210001\r>"),
        ))
        val session = NativeSession(transport)
        session.connect()
        val readings = session.snapshot()
        assertEquals(listOf(0x01, 0x0C, 0x21), readings.map { it.pid })
        assertIs<DecodedReading.Structured>(readings[0].value)
        assertNull(readings[1].value)
        assertEquals(1.0, assertIs<DecodedReading.Scalar>(readings[2].value).value)
        assertTrue(transport.remaining.isEmpty())
        session.disconnect()
    }

    @Test
    fun snapshotFailureInvalidatesWholeSessionWithoutContinuing() {
        val (session, transport) = connected(
            Step("0105", "41057D\r>"), Step("010C", "BUS ERROR\r>"),
            pids = intArrayOf(0x05, 0x0C, 0x0D),
        )
        expectKind(ErrorKind.ADAPTER) { session.snapshot() }
        assertFaulted(session, transport)
        assertEquals("010C", transport.commands.last())
        expectKind(ErrorKind.NOT_CONNECTED) { session.read(0x0D) }
    }

    @Test
    fun storedCodesUseOnlyMode03AndPreserveNoDataAsUnknown() {
        val (session, transport) = connected(
            Step("ATDPN", "A3\r>"),
            Step("03", "43 01 33 00 00 00 00\r>"),
            Step("03", "NO DATA\r>"),
            Step("03", "43 00 00 00 00 00 00\r>"),
        )
        assertEquals(listOf("P0133"), session.readStoredDtcs().codes)
        assertNull(session.readStoredDtcs().codes)
        assertEquals(emptyList(), session.readStoredDtcs().codes)
        assertEquals(List(3) { "03" }, transport.commands.takeLast(3))
        assertEquals(List(3) { NATIVE_STANDARD_READ_TIMEOUT_MILLIS }, transport.timeouts.takeLast(3))
        session.disconnect()
    }

    @Test
    fun malformedStoredCodesFaultWithoutPublishingIdentifiers() {
        val (session, transport) = connected(Step("ATDPN", "A3\r>"),
            Step("03", "44 01 33 00 00 00 00\r>"))
        expectKind(ErrorKind.MALFORMED_RESPONSE) { session.readStoredDtcs() }
        assertFaulted(session, transport)
        assertEquals("03", transport.commands.last())
    }

    @Test
    fun noDataCapabilityFailureClearsIdentityAndCloses() {
        val transport = ScriptedTransport(initialization() + bitmap(0, 0x0C, 0x20) + Step("0120", "NO DATA\r>"))
        val session = NativeSession(transport)
        val error = expectKind(ErrorKind.ADAPTER) { session.connect() }
        assertTrue(error.message.orEmpty().contains("0120"))
        assertTrue(error.message.orEmpty().contains("0100"))
        assertFaulted(session, transport)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun malformedCapabilityResponseCannotCreatePartiallyConnectedSession() {
        val transport = ScriptedTransport(initialization() + Step("0100", "41000010\r>"))
        val session = NativeSession(transport)
        expectKind(ErrorKind.MALFORMED_RESPONSE) { session.connect() }
        assertFaulted(session, transport)
    }

    @Test
    fun adapterErrorsInvalidateAndReleaseWithoutRetry() {
        for (response in listOf("?", "STOPPED", "BUS ERROR", "CAN ERROR", "UNABLE TO CONNECT", "BUFFER FULL", "DATA ERROR", "NO DATA EXTRA")) {
            val (session, transport) = connected(Step("010C", "$response\r>"))
            expectKind(ErrorKind.ADAPTER) { session.read(0x0C) }
            assertFaulted(session, transport)
            assertEquals(1, transport.commands.count { it == "010C" })
        }
    }

    @Test
    fun malformedFramesAndAmbiguousEcuRepliesAreRejected() {
        for (response in listOf(
            "410C1AF8", "410C1AF8>garbage>", "410C1AF8\r>extra", "é\r>", "\r>",
            "410C1AF8\r410C1AF8\r>", "410D42\r>", "410C1A\r>", "410C1AF800\r>",
            "NO DATA\r410C1AF8\r>", "SEARCHING...410C1AF8\r>", "7E804410C1AF8\r>",
            "410C" + "00".repeat(2050) + ">",
        )) {
            val (session, transport) = connected(Step("010C", response))
            expectKind(ErrorKind.MALFORMED_RESPONSE) { session.read(0x0C) }
            assertFaulted(session, transport)
        }
    }

    @Test
    fun timeoutAndUnexpectedTransportExceptionsAreTypedAndReleaseConnection() {
        for (failure in listOf(
            NativeDiagnosticException(ErrorKind.TIMEOUT, "Scripted timeout") to ErrorKind.TIMEOUT,
            IllegalStateException("Disconnected cable") to ErrorKind.TRANSPORT,
        )) {
            val (session, transport) = connected(Step("010C", error = failure.first))
            expectKind(failure.second) { session.read(0x0C) }
            assertFaulted(session, transport)
        }
    }

    @Test
    fun openFailureStillAttemptsCleanup() {
        val transport = ScriptedTransport(emptyList()).also { it.openError = IllegalStateException("No adapter") }
        val session = NativeSession(transport)
        expectKind(ErrorKind.TRANSPORT) { session.connect() }
        assertFaulted(session, transport)
        assertEquals(1, transport.closeCalls)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun failedInitializationCleanupBlocksReconnectUntilExplicitSuccessfulDisconnect() {
        val transport = ScriptedTransport(listOf(Step("ATZ", "OBDII device\r>")) + initialization() + bitmap(0, 0x0C))
        transport.closeFailures = 1
        val session = NativeSession(transport)
        expectKind(ErrorKind.ADAPTER) { session.connect() }
        expectKind(ErrorKind.TRANSPORT) { session.connect() }
        assertEquals(1, transport.openCalls)
        session.disconnect()
        session.connect()
        assertEquals(2, transport.openCalls)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        session.disconnect()
    }

    @Test
    fun disconnectFailureBlocksReconnectAndClearsCachedVehicle() {
        val (session, transport) = connected()
        transport.closeFailures = 1
        expectKind(ErrorKind.TRANSPORT) { session.disconnect() }
        assertNull(session.capabilities)
        assertNull(session.identity)
        expectKind(ErrorKind.TRANSPORT) { session.connect() }
        assertEquals(1, transport.openCalls)
        session.disconnect()
        assertEquals(NativeSessionState.DISCONNECTED, session.state)
        assertFalse(transport.isOpen)
    }

    @Test
    fun reconnectAfterAProtocolFaultRediscoversCapabilities() {
        val transport = ScriptedTransport(initialization() + listOf(bitmap(0, 0x0C), Step("010C", "BUS ERROR\r>")) +
            initialization() + bitmap(0, 0x0D))
        val session = NativeSession(transport)
        session.connect()
        expectKind(ErrorKind.ADAPTER) { session.read(0x0C) }
        assertEquals(setOf(0x0D), session.connect().pids)
        expectKind(ErrorKind.UNSUPPORTED) { session.read(0x0C) }
        session.disconnect()
    }

    private val fictionalVin = "TESTV1N1234567890"
    private fun vinPayload() = "490201" + fictionalVin.map { it.code.toString(16).padStart(2, '0') }.joinToString("").uppercase()

    @Test
    fun vinAcceptsSingleOrOrderedIndexedPositiveResponses() {
        val payload = vinPayload()
        for (response in listOf("$payload\r>", "0:${payload.take(14)}\r1:${payload.substring(14, 28)}\r2:${payload.drop(28)}\r>")) {
            val (session, transport) = connected(Step("0902", response))
            assertEquals(fictionalVin, session.identifyVin())
            assertTrue(transport.remaining.isEmpty())
            session.disconnect()
        }
    }

    @Test
    fun exactVinNoDataIsUnsupportedWithoutInvalidatingReadSession() {
        val (session, transport) = connected(Step("0902", "NO DATA\r>"), Step("010C", "410C0B2C\r>"))
        assertNull(session.identifyVin())
        assertEquals(715.0, assertIs<DecodedReading.Scalar>(session.read(0x0C).value).value)
        assertEquals(0, transport.closeCalls)
        session.disconnect()
    }

    @Test
    fun vinRejectsAmbiguousMalformedRawAndNonAsciiIdentifiers() {
        val payload = vinPayload()
        for (response in listOf(
            "$payload\r$payload\r>", "0:$payload\r0:$payload\r>", "1:$payload\r>",
            "0:${payload.take(14)}\r${payload.drop(14)}\r>", "0:${payload.take(14)}\r2:${payload.drop(14)}\r>",
            "0:${payload.take(13)}\r>", "0:GG\r>", "x:$payload\r>", "16:$payload\r>",
            "1014490201544553\r215456494E313233\r2234353637383930\r>",
            "${payload.dropLast(2)}\r>", "${payload}00\r>", "490202${payload.drop(6)}\r>",
            "49020100${payload.drop(8)}\r>", "490201FF${payload.drop(8)}\r>",
            "NO DATA\r$payload\r>",
        )) {
            val (session, transport) = connected(Step("0902", response))
            expectKind(ErrorKind.MALFORMED_RESPONSE) { session.identifyVin() }
            assertFaulted(session, transport)
        }
    }
}
