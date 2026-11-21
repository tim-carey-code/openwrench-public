package org.openwrench.nativecore

import kotlin.test.*

/** Synthetic wire transcripts only, no adapter discovery. */
class DiagnosticContextTest {
    private class Script(val replies: MutableList<Pair<String, String>>) : NativeTransport {
        val commands = mutableListOf<String>()
        var closed = 0
        override fun open() {}
        override fun close() { closed++ }
        override fun exchange(command: String, timeoutMillis: Long): String {
            commands += command
            assertEquals(3_000L, timeoutMillis.takeUnless { command == "ATZ" } ?: 3_000)
            val next = replies.removeAt(0)
            assertEquals(next.first, command)
            return next.second + "\r>"
        }
    }
    private fun connected(vararg replies: Pair<String, String>): Pair<NativeSession, Script> {
        val script = mutableListOf("ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS0" to "OK",
            "ATH0" to "OK", "ATSP0" to "OK", "ATI" to "ELM327 v1.5", "0100" to "410000100000")
        script += replies
        val transport = Script(script)
        return NativeSession(transport).also { it.connect() } to transport
    }

    @Test fun legacyServicesAndIndependentlyAdvertisedFreezePidsAreDecoded() {
        val (s, t) = connected("ATDPN" to "A3", "03" to "430133", "07" to "470000", "0A" to "7F0A11",
            "020000" to "42000048100000", "020200" to "4202000133", "020500" to "42050070", "020C00" to "420C001F40")
        val result = s.readDiagnosticContext()
        assertEquals(listOf("P0133"), result.codes[0].codes)
        assertEquals(emptyList(), result.codes[1].codes)
        assertEquals(DiagnosticReplyStatus.UNSUPPORTED, result.codes[2].status)
        assertEquals("P0133", result.freezeFrame.triggerCode)
        assertEquals(72.0, assertIs<DecodedReading.Scalar>(result.freezeFrame.readings.first().value).value)
        assertEquals(2_000.0, assertIs<DecodedReading.Scalar>(result.freezeFrame.readings.last().value).value)
        assertFalse(0x05 in s.capabilities!!.pids) // Mode 02 support is not guessed from Mode 01.
        assertTrue(t.replies.isEmpty())
        assertFalse(t.commands.any { it in setOf("04", "08", "0902") })
    }

    @Test fun canContextPinsOneSourceAndRestoresHeadersBeforeLiveRead() {
        val (s, t) = connected("ATDPN" to "A6", "ATCAF1" to "OK", "ATD0" to "OK", "ATH1" to "OK",
            "03" to "7E80443010133000000", "07" to "7E80247000000000000", "0A" to "NO DATA",
            "020000" to "7E80742000048100000", "020200" to "7E80542020001330000",
            "020500" to "7E80442050070000000", "020C00" to "7E805420C001F400000", "ATH0" to "OK", "010C" to "410C0C80")
        val result = s.readDiagnosticContext()
        assertEquals("7E8", result.freezeFrame.sourceEcu)
        assertTrue(result.freezeFrame.readings.all { it.sourceEcu == "7E8" })
        assertEquals(DiagnosticReplyStatus.NO_DATA, result.codes[2].status)
        assertEquals(800.0, assertIs<DecodedReading.Scalar>(s.read(0x0C).value).value)
        assertTrue(t.replies.isEmpty())
    }

    @Test fun cannotSubstituteAnotherEcuInTheMiddleOfContext() {
        val (s, t) = connected("ATDPN" to "6", "ATCAF1" to "OK", "ATD0" to "OK", "ATH1" to "OK",
            "03" to "7E80243000000000000", "07" to "7E90247000000000000", "ATH0" to "OK")
        assertEquals(ErrorKind.MALFORMED_RESPONSE, assertFailsWith<NativeDiagnosticException> { s.readDiagnosticContext() }.kind)
        assertEquals(NativeSessionState.FAULTED, s.state)
        assertTrue(t.closed > 0)
        assertFalse(t.commands.contains("0A"))
    }

    @Test fun noDataAndRejectedServicesAreUnknownRatherThanEmptyCodeLists() {
        val (s, t) = connected("ATDPN" to "3", "03" to "NO DATA", "07" to "7F0711", "0A" to "7F0A22", "020000" to "NO DATA")
        val result = s.readDiagnosticContext()
        assertEquals(listOf(DiagnosticReplyStatus.NO_DATA, DiagnosticReplyStatus.UNSUPPORTED, DiagnosticReplyStatus.UNAVAILABLE), result.codes.map { it.status })
        assertEquals(DiagnosticReplyStatus.NO_DATA, result.freezeFrame.status)
        assertTrue(result.freezeFrame.readings.isEmpty())
        assertEquals(NativeSessionState.CONNECTED, s.state)
        assertTrue(t.replies.isEmpty())
    }

    @Test fun noTriggerMeansNoFreezeMeasurementsAreRequested() {
        val (s, t) = connected("ATDPN" to "3", "03" to "430000", "07" to "470000", "0A" to "4A0000",
            "020000" to "42000048100000", "020200" to "4202000000")
        val result = s.readDiagnosticContext()
        assertNull(result.freezeFrame.triggerCode)
        assertTrue(result.freezeFrame.readings.isEmpty())
        assertFalse(t.commands.contains("020500"))
    }

    @Test fun unsupportedTriggerIsNeverProbedAndMissingValuesKeepTheirReplyStatus() {
        val (s, t) = connected("ATDPN" to "3", "03" to "430000", "07" to "470000", "0A" to "4A0000", "020000" to "42000008100000")
        assertEquals(DiagnosticReplyStatus.UNSUPPORTED, s.readDiagnosticContext().freezeFrame.status)
        assertFalse(t.commands.contains("020200"))
        val (other, _) = connected("ATDPN" to "3", "03" to "430000", "07" to "470000", "0A" to "4A0000",
            "020000" to "42000048100000", "020200" to "4202000133", "020500" to "7F0212", "020C00" to "NO DATA")
        val frame = other.readDiagnosticContext().freezeFrame
        assertEquals(DiagnosticReplyStatus.UNSUPPORTED, frame.unavailablePids[0x05])
        assertEquals(DiagnosticReplyStatus.NO_DATA, frame.unavailablePids[0x0C])
        assertTrue(frame.readings.all { it.value == null })
    }

    @Test fun malformedOrMixedRepliesFaultAndCancelStopsBeforeNextRequest() {
        for (reply in listOf("410000000000", "470133", "7F0311FF", "NO DATA\r430000")) {
            val (s, _) = connected("ATDPN" to "3", "03" to reply)
            assertFailsWith<NativeDiagnosticException> { s.readDiagnosticContext() }
            assertEquals(NativeSessionState.FAULTED, s.state)
        }
        val (s, t) = connected()
        assertFailsWith<IllegalStateException> { s.readDiagnosticContext { error("cancelled") } }
        assertFalse(t.commands.contains("ATDPN"))
        assertTrue(t.closed > 0)
    }

    @Test fun pendingAndPermanentCanCodesUseBoundedMultiframeAssembly() {
        val (s, t) = connected("ATDPN" to "6", "ATCAF1" to "OK", "ATD0" to "OK", "ATH1" to "OK",
            "03" to "7E80243000000000000",
            "07" to "7E8100A470401330301\r7E82104201234000000",
            "0A" to "7E8100A4A0401330301\r7E82104201234000000", "020000" to "NO DATA", "ATH0" to "OK")
        val result = s.readDiagnosticContext()
        assertEquals(listOf("P0133", "P0301", "P0420", "P1234"), result.codes[1].codes)
        assertEquals(result.codes[1].codes, result.codes[2].codes)
        assertTrue(t.replies.isEmpty())
        assertNull(result.freezeFrame.sourceEcu) // NO DATA is not an observed reply from the prior source.
    }

    @Test fun twentyNineBitCanUsesItsObservedAddressAndRestoresHeaders() {
        val (s, t) = connected("ATDPN" to "7", "ATCAF1" to "OK", "ATD0" to "OK", "ATH1" to "OK",
            "03" to "18DAF1100243000000000000", "07" to "18DAF1100247000000000000",
            "0A" to "18DAF110024A000000000000", "020000" to "NO DATA", "ATH0" to "OK")
        val result = s.readDiagnosticContext()
        assertTrue(result.codes.all { it.sourceEcu == "18DAF110" && it.status == DiagnosticReplyStatus.AVAILABLE && it.codes.isEmpty() })
        assertEquals("ATH0", t.commands.last())
    }

    @Test fun independentlyAdvertisedBankTwoFrameTrimsAreIncluded() {
        val (s, t) = connected("ATDPN" to "3", "03" to "430000", "07" to "470000", "0A" to "4A0000",
            "020000" to "42000041800000", "020200" to "4202000133", "020800" to "42080080", "020900" to "42090070")
        val frame = s.readDiagnosticContext().freezeFrame
        assertEquals(listOf(0x08, 0x09), frame.readings.map { it.pid })
        assertEquals(0.0, assertIs<DecodedReading.Scalar>(frame.readings[0].value).value)
        assertEquals(-12.5, assertIs<DecodedReading.Scalar>(frame.readings[1].value).value)
        assertTrue(t.replies.isEmpty())
    }

    @Test fun failedHeaderRestorationFaultsBeforeAnyContextCanBePublishedOrLiveReadResumed() {
        val (s, t) = connected("ATDPN" to "6", "ATCAF1" to "OK", "ATD0" to "OK", "ATH1" to "OK",
            "03" to "7E80243000000000000", "07" to "7E80247000000000000",
            "0A" to "7E8024A000000000000", "020000" to "NO DATA", "ATH0" to "?")
        assertEquals(ErrorKind.ADAPTER, assertFailsWith<NativeDiagnosticException> { s.readDiagnosticContext() }.kind)
        assertEquals(NativeSessionState.FAULTED, s.state)
        assertNull(s.capabilities)
        assertFailsWith<NativeDiagnosticException> { s.read(0x0C) }
        assertFalse(t.commands.contains("010C"))
        assertTrue(t.closed > 0)
    }

    @Test fun frameModePidLengthAndFrameNumberAreStrict() {
        for (payload in listOf("41050070", "42050170", "420C0070", "420500", "4205007000", "420500GG"))
            assertFails { FreezeFrameDecoder.reading(payload, 0x05) }
        assertFails { FreezeFrameDecoder.trigger("420200013300") }
        assertFails { StoredDtcDecoder.decode("430000", false, DtcService.PENDING) }
        assertFails { FreezeFrameDecoder.reading("42010000000000", 0x01) }
        assertEquals(listOf("U1234"), StoredDtcDecoder.decode("4AD234", false, DtcService.PERMANENT))
    }
}
