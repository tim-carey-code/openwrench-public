package org.openwrench.frontend.android.transport

import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElmFramingTest {
    @Test
    fun fragmentedResponseRetainsPrompt() {
        val frame = ElmFrameAccumulator()
        assertNull(frame.append("41 0C 1A".toByteArray(), 8))
        assertEquals("41 0C 1A F8\r\n>", frame.append(" F8\r\n>\r\n".toByteArray(), 8))
    }

    @Test
    fun nonAsciiControlAndSecondFrameFailClosed() {
        listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0), "OK>41 0C".toByteArray()).forEach { bytes ->
            val error = assertFailsWith<NativeDiagnosticException> { ElmFrameAccumulator().append(bytes, bytes.size) }
            assertEquals(ErrorKind.MALFORMED_RESPONSE, error.kind)
        }
    }

    @Test
    fun exactLimitNeedsPrompt() {
        val full = ("A".repeat(4095) + ">").toByteArray()
        assertEquals(4096, ElmFrameAccumulator().append(full, full.size)?.length)
        assertFailsWith<NativeDiagnosticException> { ElmFrameAccumulator().append(ByteArray(4096) { 65 }, 4096) }
    }

    @Test
    fun commandsHaveOneTerminatorAndRejectInjection() {
        assertContentEquals("010C\r".toByteArray(), frameElmCommand("010C"))
        listOf("", " ", "010C\r04", "010C\n", "AT\u0080", "A".repeat(65)).forEach {
            assertFailsWith<NativeDiagnosticException> { frameElmCommand(it) }
        }
    }

    @Test
    fun pacingMeasuresFromCompletionAndRechecksEarlyWakeups() {
        var clock = 1_000L
        val waits = mutableListOf<Long>()
        val pacer = ExchangePacer({ clock }) { wait ->
            waits += wait
            clock += minOf(wait, 50)
        }
        pacer.beforeWrite()
        assertTrue(waits.isEmpty())
        pacer.completed()
        clock += 75
        pacer.beforeWrite()
        assertEquals(listOf(125L, 75L, 25L), waits)
        assertEquals(1_200L, clock)
    }

    @Test
    fun onlyCompletedLiveRepliesEnableShortGaps() {
        var clock = 0L
        val pacer = ExchangePacer({ clock }) { clock += it }
        pacer.completed("010C", prompt = true)
        pacer.beforeWrite("010D")
        assertEquals(20L, clock)
        pacer.completed("010D", prompt = true)
        pacer.beforeWrite("0100") // Capability discovery retains startup pacing.
        assertEquals(220L, clock)
        pacer.completed("0100", prompt = true)
        pacer.beforeWrite("010C")
        assertEquals(420L, clock)
        pacer.completed("010C", prompt = false)
        pacer.beforeWrite("010D")
        assertEquals(620L, clock)
        pacer.completed("010C", prompt = true)
        pacer.beforeWrite("ATZ")
        assertEquals(820L, clock)
    }

    @Test
    fun monitorAndFuelStatusUseLivePacingOnlyAfterCompletePrompts() {
        var clock = 0L
        val pacer = ExchangePacer({ clock }) { clock += it }
        pacer.completed("010C", prompt = true)
        pacer.beforeWrite("0101")
        assertEquals(20L, clock)
        pacer.completed("0101", prompt = true)
        pacer.beforeWrite("0103")
        assertEquals(40L, clock)
        pacer.completed("0103", prompt = false)
        pacer.beforeWrite("0101")
        assertEquals(240L, clock)
    }

    @Test
    fun expiredDeadlineAbortsBlockedOperation() {
        val aborted = CountDownLatch(1)
        val deadline = IoDeadline(30, "Test operation") { aborted.countDown() }
        assertTrue(aborted.await(2, TimeUnit.SECONDS))
        val error = assertFailsWith<NativeDiagnosticException> { deadline.finish() }
        assertEquals(ErrorKind.TIMEOUT, error.kind)
    }

    @Test
    fun finishedDeadlineDoesNotAbortConnection() {
        val aborted = CountDownLatch(1)
        val deadline = IoDeadline(100, "Test operation") { aborted.countDown() }
        deadline.finish()
        assertTrue(!aborted.await(150, TimeUnit.MILLISECONDS))
    }
}
