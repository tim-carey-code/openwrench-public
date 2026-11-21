package org.openwrench.nativecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic ELM transcripts only; no adapter enumeration, socket, or vehicle access. */
class AddressedNativeSessionTest {
    private data class Step(val command: String, val reply: String)

    private class Transport(script: List<Step>) : NativeTransport {
        val pending = script.toMutableList()
        val requests = mutableListOf<String>()
        val failures = mutableListOf<Pair<NativeFailureStage, ErrorKind>>()
        var open = false
        var closes = 0
        var afterExchange: (String) -> Unit = {}

        override fun open() { open = true }
        override fun close() { open = false; closes++ }
        override fun reportSessionFailure(stage: NativeFailureStage, kind: ErrorKind) {
            failures += stage to kind
        }
        override fun exchange(command: String, timeoutMillis: Long): String {
            assertTrue(open)
            assertTrue(pending.isNotEmpty(), "Unscripted command: $command")
            val next = pending.removeAt(0)
            assertEquals(next.command, command)
            assertEquals(if (command == "ATZ") 10_000L else 3_000L, timeoutMillis)
            requests += command
            afterExchange(command)
            return next.reply
        }
    }

    private fun initialization() = listOf(
        Step("ATZ", "ELM327 v1.5\r>"), Step("ATE0", "OK\r>"), Step("ATL0", "OK\r>"),
        Step("ATS0", "OK\r>"), Step("ATH0", "OK\r>"), Step("ATSP0", "OK\r>"),
        Step("ATI", "ELM327 v1.5\r>"),
    )

    private fun bitmap(base: Int = 0, vararg pids: Int): String {
        var bits = 0L
        for (pid in pids) {
            require(pid - base in 1..32)
            bits = bits or (1L shl (32 - (pid - base)))
        }
        return "41${base.toString(16).uppercase().padStart(2, '0')}" +
            bits.toString(16).uppercase().padStart(8, '0')
    }

    private fun reply(vararg lines: String) = lines.joinToString("\r") + "\r>"

    private fun frame(source: String, payload: String): String {
        require(payload.length % 2 == 0 && payload.length in 2..14)
        return source + (payload.length / 2).toString(16).padStart(2, '0') + payload
    }

    private fun recovery(protocol: String = "A6", vararg sources: Pair<String, String>): List<Step> {
        require(sources.size > 1)
        return initialization() + listOf(
            Step("0100", reply("SEARCHING...", *sources.map { it.second }.toTypedArray())),
            Step("ATDPN", reply(protocol)), Step("ATCAF1", "OK\r>"),
            Step("ATD0", "OK\r>"), Step("ATH1", "OK\r>"),
            Step("0100", reply(*sources.map { frame(it.first, it.second) }.toTypedArray())),
        )
    }

    private fun assertFaulted(session: NativeSession, transport: Transport) {
        assertEquals(NativeSessionState.FAULTED, session.state)
        assertNull(session.capabilities)
        assertNull(session.identity)
        assertFalse(transport.open)
        assertEquals(1, transport.closes)
    }

    @Test
    fun singleEcuKeepsExistingConnectionPathWithoutAdditionalAdapterCommands() {
        val transport = Transport(initialization() + listOf(
            Step("0100", reply(bitmap(0, 0x0C))), Step("010C", reply("410C1AF8")),
        ))
        val session = NativeSession(transport)
        assertNull(session.connect().sourceEcu)
        val reading = session.read(0x0C)
        assertEquals(1726.0, assertIs<DecodedReading.Scalar>(reading.value).value)
        assertNull(reading.sourceEcu)
        assertFalse("ATDPN" in transport.requests)
        assertFalse("ATH1" in transport.requests)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun selectsRpmAdvertiserAndNeverUnionsAnotherEcusCapabilitiesOrValues() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x05, 0x0D), "7E9" to bitmap(0, 0x0C)) + listOf(
            Step("010C", reply(frame("7E8", "410C0000"), frame("7E9", "410C1AF8"))),
        ))
        val session = NativeSession(transport)
        val capabilities = session.connect()
        assertEquals("7E9", capabilities.sourceEcu)
        assertEquals(setOf(0x0C), capabilities.pids)
        assertEquals(2, transport.requests.count { it == "0100" })
        assertEquals(1, transport.requests.count { it == "ATH1" })
        val reading = session.read(0x0C)
        assertEquals("7E9", reading.sourceEcu)
        assertEquals(1726.0, assertIs<DecodedReading.Scalar>(reading.value).value)
        assertEquals(ErrorKind.UNSUPPORTED, assertFailsWith<NativeDiagnosticException> { session.read(0x05) }.kind)
        assertEquals(NativeSessionState.CONNECTED, session.state)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun responseOrderDoesNotChangeTheExplicitLowestIdTieBreak() {
        for (sources in listOf(listOf("7E9", "7E8"), listOf("7E8", "7E9"))) {
            val transport = Transport(recovery("6", *sources.map { it to bitmap(0, 0x0C) }.toTypedArray()) + listOf(
                Step("010C", reply(frame("7E9", "410C0000"), frame("7E8", "410C1AF8"))),
            ))
            val session = NativeSession(transport)
            assertEquals("7E8", session.connect().sourceEcu)
            assertEquals(1726.0, assertIs<DecodedReading.Scalar>(session.read(0x0C).value).value)
        }
    }

    @Test
    fun selectionWithoutAnRpmAdvertiserStillKeepsExactlyOneSource() {
        val transport = Transport(recovery("A6",
            "7E9" to bitmap(0, 0x0D), "7E8" to bitmap(0, 0x05)))
        val session = NativeSession(transport)
        val capabilities = session.connect()
        assertEquals("7E8", capabilities.sourceEcu)
        assertEquals(setOf(0x05), capabilities.pids)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun capabilityContinuationBelongsOnlyToTheSelectedSource() {
        val transport = Transport(recovery("A6",
            "7E9" to bitmap(0, 0x05, 0x0C, 0x20), "7E8" to bitmap(0, 0x0C, 0x20)) + listOf(
            Step("0120", reply(frame("7E9", bitmap(0x20, 0x40)), frame("7E8", bitmap(0x20, 0x21)))),
        ))
        val session = NativeSession(transport)
        val capabilities = session.connect()
        assertEquals("7E8", capabilities.sourceEcu)
        assertEquals(listOf(0, 0x20), capabilities.ranges)
        assertEquals(setOf(0x0C, 0x20, 0x21), capabilities.pids)
        assertFalse("0140" in transport.requests)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun anotherSourceCannotCauseExtraCapabilityRangeRequests() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x20)))
        assertEquals(listOf(0), NativeSession(transport).connect().ranges)
        assertFalse("0120" in transport.requests)
    }

    @Test
    fun can29AndPaddedFramesRetainSourceThroughStructuredReadAndSnapshot() {
        val selected = "18DAF110"
        val other = "18DAF118"
        val transport = Transport(recovery("A7",
            other to bitmap(0, 0x03, 0x0C, 0x0D), selected to bitmap(0, 0x03, 0x0C)) + listOf(
            Step("0103", reply(frame(other, "41030800"), frame(selected, "41030200") + "000000")),
            Step("0103", reply(frame(selected, "41030200"), frame(other, "41030800"))),
            Step("010C", reply(frame(other, "410C0000"), frame(selected, "410C1AF8") + "000000")),
        ))
        val session = NativeSession(transport)
        assertEquals(selected, session.connect().sourceEcu)
        val structured = session.readStructured(0x03)
        assertEquals(selected, structured.sourceEcu)
        assertIs<DecodedReading.Structured>(structured.value)
        val snapshot = session.snapshot()
        assertEquals(listOf(0x03, 0x0C), snapshot.map { it.pid })
        assertTrue(snapshot.all { it.sourceEcu == selected })
        assertEquals(1726.0, assertIs<DecodedReading.Scalar>(snapshot.last().value).value)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun selectedSourceAbsentFaultsInsteadOfUsingAnotherEcusReading() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + listOf(
            Step("010C", reply(frame("7E9", "410C1AF8"))),
        ))
        val session = NativeSession(transport)
        session.connect()
        val failure = assertFailsWith<NativeDiagnosticException> { session.read(0x0C) }
        assertEquals(ErrorKind.MALFORMED_RESPONSE, failure.kind)
        assertTrue(failure.message!!.contains("Selected ECU 7E8 did not respond"))
        assertEquals(listOf(NativeFailureStage.READING to ErrorKind.MALFORMED_RESPONSE), transport.failures)
        assertFaulted(session, transport)
    }

    @Test
    fun standaloneNoDataRemainsUnavailableWithoutChangingSelectedSource() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + listOf(
            Step("010C", "NO DATA\r>"), Step("010C", reply(frame("7E8", "410C1AF8"))),
        ))
        val session = NativeSession(transport)
        session.connect()
        assertEquals(NativeReading(0x0C, null, "7E8"), session.read(0x0C))
        assertEquals("7E8", session.read(0x0C).sourceEcu)
        assertEquals(NativeSessionState.CONNECTED, session.state)
    }

    @Test
    fun duplicateOrInvalidCanFramesCannotProduceAReading() {
        val good = frame("7E8", "410C1AF8")
        val badReplies = listOf(
            reply(good, good), // Even identical duplicates are ambiguous wire data.
            reply(good, frame("7E8", "410C0000")),
            reply("7E804410C1A"), // PCI says four payload bytes, but only three follow.
            reply("7E800"), reply("7E808410C1AF800000000"),
            reply("7E821410C1AF8"), // Orphan ISO-TP continuation.
            reply("7E804410C1AF8Z0"),
            reply("7E704410C1AF8"), // Not a standard functional OBD response address.
            reply(frame("7E8", "410D2A")), // Valid CAN frame, wrong PID.
            reply(good, "NODATA"),
        )
        for (bad in badReplies) {
            val transport = Transport(recovery("A6",
                "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + Step("010C", bad))
            val session = NativeSession(transport)
            session.connect()
            assertEquals(ErrorKind.MALFORMED_RESPONSE,
                assertFailsWith<NativeDiagnosticException> { session.read(0x0C) }.kind, bad)
            assertFaulted(session, transport)
            assertEquals(1, transport.requests.count { it == "010C" })
        }
    }

    @Test
    fun malformedUnaddressedRepliesDoNotTriggerRecoveryOrAnyRetry() {
        val first = bitmap(0, 0x0C)
        for (bad in listOf("4100ABCD", "410C1AF8", "41000000GG00", "unexpected", "NO DATA")) {
            val transport = Transport(initialization() + Step("0100", reply(first, bad)))
            val session = NativeSession(transport)
            assertFailsWith<NativeDiagnosticException> { session.connect() }
            assertFalse("ATDPN" in transport.requests)
            assertEquals(1, transport.requests.count { it == "0100" })
            assertFaulted(session, transport)
        }
    }

    @Test
    fun unsupportedLegacyProtocolStopsBeforeChangingHeadersOrRetrying() {
        val transport = Transport(initialization() + listOf(
            Step("0100", reply(bitmap(0, 0x0C), bitmap(0, 0x0D))), Step("ATDPN", reply("A3")),
        ))
        val session = NativeSession(transport)
        assertEquals(ErrorKind.UNSUPPORTED, assertFailsWith<NativeDiagnosticException> { session.connect() }.kind)
        assertFalse("ATH1" in transport.requests)
        assertFalse("ATCAF1" in transport.requests)
        assertEquals(1, transport.requests.count { it == "0100" })
        assertFaulted(session, transport)
    }

    @Test
    fun rejectedRecoveryConfigurationNeverSendsAnotherVehicleRequest() {
        val transport = Transport(initialization() + listOf(
            Step("0100", reply(bitmap(0, 0x0C), bitmap(0, 0x0D))), Step("ATDPN", reply("A6")),
            Step("ATCAF1", "?\r>"),
        ))
        val session = NativeSession(transport)
        assertEquals(ErrorKind.ADAPTER, assertFailsWith<NativeDiagnosticException> { session.connect() }.kind)
        assertEquals(1, transport.requests.count { it == "0100" })
        assertFaulted(session, transport)
    }

    @Test
    fun cancellationDuringRecoveryStopsBeforeTheNextCommandAndReleasesOwner() {
        val script = recovery("A6", "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D))
        for (cancelAfter in listOf("ATDPN", "ATCAF1", "ATD0", "ATH1")) {
            var active = true
            val transport = Transport(script)
            transport.afterExchange = { if (it == cancelAfter) active = false }
            val session = NativeSession(transport)
            val cancellation = IllegalStateException("Owner cancelled")
            assertEquals(cancellation, assertFailsWith<IllegalStateException> {
                session.connect { if (!active) throw cancellation }
            })
            assertEquals(cancelAfter, transport.requests.last())
            assertEquals(1, transport.requests.count { it == "0100" })
            assertFaulted(session, transport)
        }
    }

    @Test
    fun reconnectClearsAddressAndHeaderParsingFromThePreviousSession() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + initialization() + listOf(
            Step("0100", reply(bitmap(0, 0x05))), Step("0105", reply("41057D")),
        ))
        val session = NativeSession(transport)
        assertEquals("7E8", session.connect().sourceEcu)
        session.disconnect()
        val next = session.connect()
        assertNull(next.sourceEcu)
        assertEquals(setOf(0x05), next.pids)
        val reading = session.read(0x05)
        assertNull(reading.sourceEcu)
        assertEquals(85.0, assertIs<DecodedReading.Scalar>(reading.value).value)
        assertEquals(1, transport.requests.count { it == "ATDPN" })
        assertTrue(transport.pending.isEmpty())
    }

    private fun vinFrames(source: String, vin: String): List<String> {
        require(vin.length == 17)
        val payload = "490201" + vin.map { it.code.toString(16).uppercase().padStart(2, '0') }.joinToString("")
        return listOf(source + "1014" + payload.take(12),
            source + "21" + payload.substring(12, 26), source + "22" + payload.substring(26))
    }

    @Test
    fun vinAssemblyUsesTheChosenSourceAcrossInterleavedEcus() {
        val selectedVin = "1HGBH41JXMN109186"
        val otherVin = "2HGFA16598H000001"
        val selected = vinFrames("7E8", selectedVin)
        val other = vinFrames("7E9", otherVin)
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + listOf(
            Step("0902", reply(other[0], selected[0], other[1], selected[1], selected[2], other[2])),
            Step("010C", reply(frame("7E8", "410C1AF8"))),
        ))
        val session = NativeSession(transport)
        session.connect()
        assertEquals(selectedVin, session.identifyVin())
        assertEquals("7E8", session.read(0x0C).sourceEcu)
        assertTrue(transport.pending.isEmpty())
    }

    @Test
    fun storedCodesAssembleSelectedEcuAcrossInterleavedCanFrames() {
        val transport = Transport(recovery("A6",
            "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + listOf(
            Step("03", reply(frame("7E9", "4300"),
                "7E81008430301330420", "7E821C123")),
        ))
        val session = NativeSession(transport)
        assertEquals("7E8", session.connect().sourceEcu)
        val result = session.readStoredDtcs()
        assertEquals("7E8", result.sourceEcu)
        assertEquals(listOf("P0133", "P0420", "U0123"), result.codes)
        assertTrue(transport.pending.isEmpty())
        session.disconnect()
    }

    @Test
    fun singleEcuCanScanEnablesSourceHeadersTemporarilyAndRestoresLiveReads() {
        val transport = Transport(initialization() + listOf(
            Step("0100", reply(bitmap(0, 0x0C))),
            Step("ATDPN", reply("A6")), Step("ATCAF1", reply("OK")),
            Step("ATD0", reply("OK")), Step("ATH1", reply("OK")),
            Step("03", reply("7E81008430301330420", "7E821C123")),
            Step("ATH0", reply("OK")),
            Step("010C", reply("410C1AF8")),
        ))
        val session = NativeSession(transport)
        assertNull(session.connect().sourceEcu)
        val codes = session.readStoredDtcs()
        assertEquals("7E8", codes.sourceEcu)
        assertEquals(listOf("P0133", "P0420", "U0123"), codes.codes)
        assertEquals(1726.0, assertIs<DecodedReading.Scalar>(session.read(0x0C).value).value)
        assertTrue(transport.pending.isEmpty())
        session.disconnect()
    }

    @Test
    fun storedCodesRejectMissingSelectedEcuAndCountMismatch() {
        for (bad in listOf(reply(frame("7E9", "4300")),
            reply(frame("7E8", "43020133")))) {
            val transport = Transport(recovery("A6",
                "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + Step("03", bad))
            val session = NativeSession(transport)
            session.connect()
            assertEquals(ErrorKind.MALFORMED_RESPONSE,
                assertFailsWith<NativeDiagnosticException> { session.readStoredDtcs() }.kind)
            assertEquals(listOf(NativeFailureStage.STORED_DTCS to ErrorKind.MALFORMED_RESPONSE), transport.failures)
            assertFaulted(session, transport)
        }
    }

    @Test
    fun missingOrOutOfOrderVinFragmentsFaultWithoutReturningPartialIdentity() {
        val frames = vinFrames("7E8", "1HGBH41JXMN109186")
        for (bad in listOf(reply(frames[0], frames[2], frames[1]), reply(frames[0], frames[1]),
            reply(frames[1], frames[2]), reply(frames[0], frames[1], frames[1], frames[2]),
            reply(frames[0].replace("1014", "1015"), frames[1], frames[2]),
            reply(*vinFrames("7E9", "2HGFA16598H000001").toTypedArray()))) {
            val transport = Transport(recovery("A6",
                "7E8" to bitmap(0, 0x0C), "7E9" to bitmap(0, 0x0D)) + Step("0902", bad))
            val session = NativeSession(transport)
            session.connect()
            assertEquals(ErrorKind.MALFORMED_RESPONSE,
                assertFailsWith<NativeDiagnosticException> { session.identifyVin() }.kind)
            assertEquals(listOf(NativeFailureStage.VIN to ErrorKind.MALFORMED_RESPONSE), transport.failures)
            assertFaulted(session, transport)
        }
    }
}
