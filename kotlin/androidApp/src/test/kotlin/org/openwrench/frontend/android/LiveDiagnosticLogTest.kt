package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDiagnosticLogTest {
    @Test
    fun distinguishesValuesFromNoDataAndKeepsTimingAcrossEventRollover() {
        var clock = 1_000L
        val log = LiveDiagnosticLog { clock }
        log.connected(7, "7E8")
        log.start(7, mapOf(0x0C to WatchRate.HIGH))
        clock = 1_080
        log.completed(7, NativeReading(0x0C, DecodedReading.Scalar(714.75)), 1_000, clock)
        clock = 1_300
        log.completed(7, NativeReading(0x0C, null), 1_260, clock)
        val first = log.snapshot()
        assertTrue(first.contains("selectedEcu=7E8"))
        assertTrue(first.contains("selected=010C:200ms"))
        assertTrue(first.contains("command=010C values=1 noData=1 meanReadMs=60 maxReadMs=80 meanReceiptIntervalMs=220"))
        assertTrue(first.contains("read command=010C result=VALUE value=714.75 elapsedMs=80"))
        assertTrue(first.contains("read command=010C result=NO_DATA value=— elapsedMs=40"))
        repeat(400) {
            clock += 200
            log.completed(7, NativeReading(0x0C, DecodedReading.Scalar(700.0)), clock - 80, clock)
        }
        val copied = log.snapshot()
        assertTrue(copied.contains("command=010C values=401 noData=1"))
        assertFalse(copied.contains("value=714.75 elapsedMs=80"), "Old events roll over")
        assertTrue(copied.lines().size < 335)
        log.stop(7, "user")
        assertTrue(log.snapshot().contains("running=false"))
        assertTrue(log.snapshot().contains("live stop reason=user"))
    }

    @Test
    fun liveSelectionUpdateKeepsPriorReceiptsAndStartTime() {
        var clock = 0L
        val log = LiveDiagnosticLog { clock }
        log.connected(2, "7E8")
        log.start(2, mapOf(0x0C to WatchRate.HIGH))
        clock = 100
        log.completed(2, NativeReading(0x0C, DecodedReading.Scalar(700.0)), 0, clock)
        clock = 1_000
        log.selectionChanged(1, mapOf(0x07 to WatchRate.LOW))
        assertFalse(log.snapshot().contains("live selection updated"))
        log.selectionChanged(2, mapOf(0x0C to WatchRate.HIGH, 0x07 to WatchRate.LOW))
        assertTrue(log.snapshot().contains("elapsedMs=1000"))
        assertTrue(log.snapshot().contains("command=010C values=1"))
        assertTrue(log.snapshot().contains("selected=0107:2000ms,010C:200ms"))
        log.stop(2, "user")
        log.selectionChanged(2, emptyMap())
        assertTrue(log.snapshot().contains("selected=0107:2000ms,010C:200ms"))
    }

    @Test
    fun olderSessionsAndVinReadsCannotEnterTheLatestLog() {
        var clock = 0L
        val log = LiveDiagnosticLog { clock }
        log.connected(2, null)
        log.start(2, mapOf(0x0C to WatchRate.HIGH))
        log.completed(2, NativeReading(0x0C, DecodedReading.Scalar(700.0)), 0, 20)
        clock = 100
        log.connected(3, "7E8")
        log.start(3, mapOf(0x0D to WatchRate.NORMAL))
        log.completed(2, NativeReading(0x0C, DecodedReading.Scalar(888.0)), 80, 100)
        log.completed(3, NativeReading(0xFF, DecodedReading.Scalar(123.0)), 80, 100)
        val copied = log.snapshot()
        assertFalse(copied.contains("command=010C"))
        assertFalse(copied.contains("888.0"))
        assertFalse(copied.contains("command=01FF"))
        assertTrue(copied.contains("selected=010D:500ms"))
        clock = 200
        log.opening(4)
        assertFalse(log.snapshot().contains("selectedEcu=7E8"))
        assertFalse(log.snapshot().contains("command=010D"))
        assertTrue(log.snapshot().contains("connection opening"))
    }
}
