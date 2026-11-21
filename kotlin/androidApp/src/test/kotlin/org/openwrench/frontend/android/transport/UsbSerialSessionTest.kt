package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UsbSerialSessionTest {
    @Test
    fun automaticConnectionFindsHighBaudAndSendsOnlyIdentificationBeforeNormalReset() {
        val fixture = Fixture()
        fixture.io.reply = { command ->
            if (fixture.io.rate != 500000) emptyList() else when (command) {
                "ATI" -> chunks("ATI\r\rELM327 v1.5\r>")
                "ATZ" -> chunks("ATZ\rELM327 v1.5\r>")
                "010C" -> chunks("41 0C 1A F8\r>")
                else -> error("Unexpected command")
            }
        }
        fixture.open()
        assertEquals(500000, fixture.io.rate)
        assertTrue(fixture.io.writes.all { it.command == "ATI" })
        assertTrue(fixture.io.writes.all { it.at >= 2000 })
        assertTrue(fixture.exchange("ATZ").contains("ELM327"))
        assertEquals("41 0C 1A F8\r>", fixture.exchange("010C", 3000))
        assertEquals(1, fixture.io.writes.count { it.command == "ATZ" })
        assertEquals(1, fixture.io.writes.count { it.command == "010C" })
        assertTrue(fixture.diagnostics.snapshot().contains("Current host baud=500000"))
    }

    @Test
    fun manualModeDoesNotProbeOrShortenTheTenSecondResetBudget() {
        val fixture = Fixture(230400)
        fixture.open()
        assertEquals(2000L, fixture.io.clock)
        assertTrue(fixture.io.writes.isEmpty())
        fixture.io.reply = { command ->
            assertEquals("ATZ", command)
            fixture.io.readDelay = 9000
            chunks("ELM327 v1.5\r>")
        }
        assertEquals("ELM327 v1.5\r>", fixture.exchange("ATZ"))
        assertEquals(listOf("ATZ"), fixture.io.writes.map { it.command })
        assertTrue(fixture.io.writes.single().timeout > 9000)
        assertEquals(listOf(230400), fixture.io.configured)
    }

    @Test
    fun residualWrongBaudCommandCanBeClearedByOneMoreIdentification() {
        val fixture = Fixture()
        var correctAttempts = 0
        fixture.io.reply = { command ->
            assertEquals("ATI", command)
            if (fixture.io.rate != 115200) emptyList()
            else if (++correctAttempts == 1) chunks("?\r>") else chunks("ELM327 v1.5\r>")
        }
        fixture.open()
        assertEquals(115200, fixture.io.rate)
        assertEquals(2, correctAttempts)
        assertTrue(fixture.diagnostics.snapshot().contains("result=non-identity"))
    }

    @Test
    fun emptyAndFragmentedReadsAreAssembledWithoutStrippingPayloadBytes() {
        val fixture = Fixture()
        fixture.io.reply = { listOf(byteArrayOf(), "ATI\rELM".toByteArray(), "327 v1.5\r".toByteArray(), "> \r\n".toByteArray()) }
        fixture.open()
        assertEquals(38400, fixture.io.rate)
        assertEquals(1, fixture.io.writes.size)
        assertTrue(fixture.diagnostics.snapshot().contains("emptyReads=1 prompt=true"))
        assertTrue(UsbSerialSession.isElmIdentity("ATI\rELM327 v1.5\r> \r\n"))
    }

    @Test
    fun echoPromptGarbageAndAmbiguousFramesCannotEstablishAnAdapterIdentity() {
        listOf("ATI\r>", ">", "?\r>", "ELM327 v1.5", "ELM327 v1.5>>", "ELM327 v1.5\rOK\r>",
            "ELM327garbage>", "ELM327\u0000>", "ELM327\u0080>", "ELM327 " + "A".repeat(4096) + ">")
            .forEach { assertFalse(UsbSerialSession.isElmIdentity(it), "Accepted an invalid identification frame") }
        assertTrue(UsbSerialSession.isElmIdentity("ELM327/ELM-USB v1.0\r>"))
        assertTrue(UsbSerialSession.isElmIdentity("ATZ\rELM327 v1.5\r>", "ATZ"))
    }

    @Test
    fun malformedCandidateBytesDoNotLeakOrStopOtherBaudCandidates() {
        val fixture = Fixture()
        fixture.io.reply = {
            if (fixture.io.rate == 115200) chunks("ELM327 v1.5\r>")
            else listOf(byteArrayOf(0xff.toByte(), 0))
        }
        fixture.open()
        assertEquals(115200, fixture.io.rate)
        assertTrue(fixture.diagnostics.snapshot().contains("result=malformed"))
        assertFalse(fixture.diagnostics.snapshot().contains("ELM327 v1.5"))
    }

    @Test
    fun allSilentRatesFinishWithinTheOpenBudgetWithSpecificFailure() {
        val fixture = Fixture()
        val error = assertFailsWith<NativeDiagnosticException> { fixture.open() }
        assertEquals(ErrorKind.ADAPTER, error.kind)
        assertTrue(error.message!!.contains("no ELM-compatible reply"))
        assertTrue(fixture.io.clock <= 15000)
        assertEquals(UsbConnectionSettings.SUPPORTED_BAUD_RATES.toSet(), fixture.io.writes.map { it.rate }.toSet())
        assertTrue(fixture.io.writes.all { it.command == "ATI" })
        assertTrue(fixture.diagnostics.snapshot().contains("result=no-payload"))
    }

    @Test
    fun shortenedOverallBudgetCannotBeExtendedByCandidateRetries() {
        val fixture = Fixture()
        val error = assertFailsWith<NativeDiagnosticException> { fixture.session.initialize(2500) }
        assertEquals(ErrorKind.TIMEOUT, error.kind)
        assertEquals(2500L, fixture.io.clock)
        assertEquals(1, fixture.io.writes.size)
    }

    @Test
    fun transportFailureDoesNotAdvanceToAnotherRateOrRetryIdentification() {
        val fixture = Fixture()
        fixture.io.reply = { throw IOException("private device data must not be logged") }
        assertFailsWith<IOException> { fixture.open() }
        assertEquals(1, fixture.io.writes.size)
        assertFalse(fixture.diagnostics.snapshot().contains("private device data"))
    }

    @Test
    fun unsupportedHostRateCanBeSkippedButConfigurationIoFailureIsFatal() {
        val fixture = Fixture()
        fixture.io.onConfigure = { if (it == 115200) throw UnsupportedOperationException() }
        fixture.io.reply = { if (fixture.io.rate == 500000) chunks("ELM327 v1.5\r>") else emptyList() }
        fixture.open()
        assertEquals(500000, fixture.io.rate)
        assertFalse(fixture.io.writes.any { it.rate == 115200 })

        val broken = Fixture()
        broken.io.onConfigure = { if (it == 115200) throw IOException("disconnected") }
        assertFailsWith<IOException> { broken.open() }
        assertFalse(broken.io.configured.any { it == 500000 })
    }

    @Test
    fun driversRejectingHighRatesWithIllegalArgumentCanStillFindConventionalBaud() {
        val fixture = Fixture()
        fixture.io.onConfigure = { if (it >= 230400) throw IllegalArgumentException("Unsupported baud") }
        fixture.io.reply = { if (fixture.io.rate == 57600) chunks("ELM327 v1.5\r>") else emptyList() }
        fixture.open()
        assertEquals(57600, fixture.io.rate)
        assertFalse(fixture.io.writes.any { it.rate >= 230400 })

        val manual = Fixture(500000)
        manual.io.onConfigure = { throw IllegalArgumentException("Unsupported baud") }
        assertFailsWith<IllegalArgumentException> { manual.open() }
        assertTrue(manual.io.writes.isEmpty())
    }

    @Test
    fun cancellationDuringSettlePreventsEveryIdentificationWrite() {
        val fixture = Fixture()
        fixture.io.cancelAt = 100
        assertFailsWith<CancellationException> { fixture.open() }
        assertTrue(fixture.io.writes.isEmpty())
        assertEquals(100L, fixture.io.clock)
    }

    @Test
    fun cancellationAfterReadPreventsPublishingIdentityOrTryingAnotherRate() {
        val fixture = Fixture()
        fixture.io.reply = { chunks("ELM327 v1.5\r>") }
        fixture.io.afterRead = { fixture.io.active = false }
        assertFailsWith<CancellationException> { fixture.open() }
        assertEquals(1, fixture.io.writes.size)
        assertFalse(fixture.diagnostics.snapshot().contains("result=identified"))
    }

    @Test
    fun baudResetRecoveryReturnsARealSecondResetResponseAtTheSavedRate() {
        val fixture = Fixture()
        var adapterRate = 500000
        var resets = 0
        fixture.io.reply = { command ->
            if (fixture.io.rate != adapterRate) emptyList() else when (command) {
                "ATI" -> chunks("ELM327 identification\r>")
                "ATZ" -> {
                    resets++
                    adapterRate = 38400
                    if (fixture.io.rate == adapterRate) chunks("ELM327 reset\r>") else emptyList()
                }
                else -> error("Unexpected command")
            }
        }
        fixture.open()
        val started = fixture.io.clock
        assertEquals("ELM327 reset\r>", fixture.exchange("ATZ"))
        assertEquals(2, resets)
        assertEquals(38400, fixture.io.rate)
        assertTrue(fixture.io.clock - started < 10000)
        assertEquals(listOf(500000, 38400), fixture.io.writes.filter { it.command == "ATZ" }.map { it.rate })
    }

    @Test
    fun asciiGarbageAfterResetTriggersRecoveryInsteadOfBeingReturnedAsReset() {
        val fixture = Fixture()
        var resets = 0
        fixture.io.reply = { command ->
            when (command) {
                "ATI" -> chunks("ELM327 identity\r>")
                "ATZ" -> if (++resets == 1) chunks("?\r>") else chunks("ELM327 reset\r>")
                else -> error("Unexpected command")
            }
        }
        fixture.open()
        assertEquals("ELM327 reset\r>", fixture.exchange("ATZ"))
        assertEquals(2, resets)
    }

    @Test
    fun postResetDiscoveryFailureCannotExtendResetDeadlineOrFabricateSuccess() {
        val fixture = Fixture()
        var resetSent = false
        fixture.io.reply = { command ->
            if (command == "ATZ") resetSent = true
            if (!resetSent) chunks("ELM327 identity\r>") else emptyList()
        }
        fixture.open()
        val started = fixture.io.clock
        assertFailsWith<NativeDiagnosticException> { fixture.exchange("ATZ") }
        assertTrue(fixture.io.clock - started <= 10000)
        assertEquals(1, fixture.io.writes.count { it.command == "ATZ" })
    }

    @Test
    fun manualSilenceAndNormalObdSilenceNeverTriggerBaudScanOrReplay() {
        val manual = Fixture(38400)
        manual.open()
        val manualStart = manual.io.clock
        assertFailsWith<NativeDiagnosticException> { manual.exchange("ATZ") }
        assertEquals(10000L, manual.io.clock - manualStart)
        assertEquals(listOf("ATZ"), manual.io.writes.map { it.command })

        val automatic = Fixture()
        automatic.io.reply = { if (it == "ATI") chunks("ELM327 identity\r>") else emptyList() }
        automatic.open()
        val writesBeforeObd = automatic.io.writes.size
        val obdStart = automatic.io.clock
        assertFailsWith<NativeDiagnosticException> { automatic.exchange("010C", 3000) }
        assertEquals(3000L, automatic.io.clock - obdStart)
        assertEquals(listOf("010C"), automatic.io.writes.drop(writesBeforeObd).map { it.command })
    }

    @Test
    fun fallbackDrainIsBoundedAndCannotDiscardAnUnboundedStream() {
        val fixture = Fixture(38400)
        fixture.open()
        fixture.io.hardwarePurge = false
        repeat(66) { fixture.io.pending.add(ByteArray(64) { 'x'.code.toByte() }) }
        val error = assertFailsWith<NativeDiagnosticException> { fixture.exchange("ATZ") }
        assertEquals(ErrorKind.MALFORMED_RESPONSE, error.kind)
        assertTrue(fixture.io.writes.isEmpty())
    }

    @Test
    fun diagnosticKeepsSettingsBoundedMetadataAndOnlyTheLatestAttempt() {
        val diagnostics = UsbDiagnostics { 0L }
        assertTrue(diagnostics.snapshot().startsWith("No USB"))
        val old = diagnostics.begin(1, 38400, UsbControlLines.ASSERTED)
        old.device("FtdiSerialDriver", 0x0403, 0x6001)
        old.baud(38400)
        repeat(1000) { old.event("command=ATI received=0") }
        assertTrue(diagnostics.snapshot().lines().size <= 185)
        assertTrue(diagnostics.snapshot().contains("VID=0403 PID=6001"))
        assertTrue(diagnostics.snapshot().contains("DTR=true RTS=true"))
        val latest = diagnostics.begin(2, 0, UsbControlLines.DEASSERTED)
        old.event("OLD RELEASE")
        old.device("OldDriver", 1, 2)
        old.baud(9600)
        latest.event("current attempt")
        val snapshot = diagnostics.snapshot()
        assertFalse(snapshot.contains("OLD"))
        assertFalse(snapshot.contains("OldDriver"))
        assertFalse(snapshot.contains("Current host baud"))
        assertFalse(snapshot.contains("deviceId=1"))
        assertTrue(snapshot.contains("deviceId=2 baud=AUTO"))
        assertTrue(snapshot.contains("DTR=false RTS=false"))
    }

    @Test
    fun liveCommandsWaitForFragmentedPromptThenUseShortGapWithoutChangingDiscovery() {
        val fixture = Fixture(500000)
        fixture.open()
        fixture.io.reply = { command ->
            fixture.io.readDelay = 80
            when (command) {
                "010C" -> chunks("410C1A", "F8\r", ">")
                "010D" -> chunks("410D00\r", ">")
                "0100" -> chunks("410000180000\r>")
                else -> error("Unexpected command")
            }
        }
        assertEquals("410C1AF8\r>", fixture.exchange("010C", 3000))
        val rpmCompleted = fixture.io.clock
        assertEquals("410D00\r>", fixture.exchange("010D", 3000))
        assertEquals(rpmCompleted + 20, fixture.io.writes.last().at)
        val speedCompleted = fixture.io.clock
        fixture.exchange("0100", 3000)
        assertEquals(speedCompleted + 200, fixture.io.writes.last().at)
        val copied = fixture.diagnostics.snapshot()
        assertTrue(copied.contains("command=010C completed=1 meanExchangeMs=83"))
        assertTrue(copied.contains("command=010D completed=1 meanExchangeMs=102"))
        assertFalse(copied.contains("command=0100 completed="))
    }

    private class Fixture(requestedBaud: Int = UsbConnectionSettings.AUTO_BAUD) {
        val io = FakeIo()
        val diagnostics = UsbDiagnostics { io.clock }
        val session = UsbSerialSession(io, requestedBaud,
            diagnostics.begin(1, requestedBaud, UsbControlLines.ASSERTED),
            { io.clock }, { io.clock += it }, 64)
        fun open() = session.initialize(io.clock + 15000)
        fun exchange(command: String, budget: Long = 10000): String = session.exchange(command, io.clock + budget)
    }

    private data class Write(val rate: Int, val command: String, val at: Long, val timeout: Int)

    private class FakeIo : UsbSerialIo {
        var clock = 0L
        var rate = 0
        var active = true
        var cancelAt = Long.MAX_VALUE
        var hardwarePurge = true
        var readDelay = 0L
        val configured = mutableListOf<Int>()
        val writes = mutableListOf<Write>()
        val pending = ArrayDeque<ByteArray>()
        var reply: (String) -> List<ByteArray> = { emptyList() }
        var onConfigure: (Int) -> Unit = { }
        var afterRead: () -> Unit = { }

        override fun checkActive() {
            if (!active || clock >= cancelAt) throw CancellationException("Owner cancelled")
        }
        override fun setBaudRate(rate: Int) {
            onConfigure(rate)
            this.rate = rate
            configured += rate
        }
        override fun purgeInput(): Boolean {
            if (hardwarePurge) pending.clear()
            return hardwarePurge
        }
        override fun write(bytes: ByteArray, timeoutMillis: Int) {
            assertTrue(timeoutMillis > 0)
            assertTrue(bytes.last() == '\r'.code.toByte())
            val command = bytes.toString(Charsets.US_ASCII).dropLast(1)
            writes += Write(rate, command, clock, timeoutMillis)
            pending.addAll(reply(command))
        }
        override fun read(bytes: ByteArray, timeoutMillis: Int): Int {
            assertTrue(timeoutMillis > 0)
            if (readDelay >= timeoutMillis) {
                readDelay -= timeoutMillis
                clock += timeoutMillis
                afterRead()
                return 0
            }
            clock += readDelay
            readDelay = 0
            val next = pending.removeFirstOrNull()
            val count = if (next == null) {
                clock += timeoutMillis
                0
            } else {
                clock++
                val length = minOf(bytes.size, next.size)
                next.copyInto(bytes, endIndex = length)
                if (length < next.size) pending.addFirst(next.copyOfRange(length, next.size))
                length
            }
            afterRead()
            return count
        }
    }

    private companion object {
        fun chunks(vararg text: String): List<ByteArray> = text.map { it.toByteArray(Charsets.US_ASCII) }
    }
}
