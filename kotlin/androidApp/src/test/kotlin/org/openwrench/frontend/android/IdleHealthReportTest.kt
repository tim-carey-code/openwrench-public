package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdleHealthReportTest {
    @Test
    fun summarisesRepeatedReceiptsAndKeepsUnsupportedAndUnknownExplicit() {
        val report = IdleHealthReportBuilder.build(7, "7E8", 1000, 61_000, 60_000,
            IdleHealthStatus.COMPLETE, setOf(0x0C), listOf(
                IdleHealthSample(NativeReading(0x0C, DecodedReading.Scalar(700.0)), 7, 10),
                IdleHealthSample(NativeReading(0x0C, null), 7, 20),
                IdleHealthSample(NativeReading(0x0C, DecodedReading.Scalar(710.0)), 7, 30),
            ), null)
        val rpm = report.signals.single { it.pid == 0x0C }
        assertEquals(2, rpm.sampleCount)
        assertEquals(1, rpm.noDataCount)
        assertEquals(700.0, rpm.firstValue)
        assertEquals(710.0, rpm.lastValue)
        assertEquals(700.0, rpm.minimum)
        assertEquals(710.0, rpm.maximum)
        assertEquals(listOf(10L, 20L, 30L), rpm.samples.map { it.receivedAtMonotonicMillis })
        assertFalse(report.signals.single { it.pid == 0x05 }.supported)
        assertNull(report.scan)
        assertTrue(report.reportText().contains("MIL, codes and readiness unknown"))
        assertTrue(report.reportText().contains("user-reported idle condition"))
        assertTrue(report.reportText().contains("completed at epoch ms: 61000"))
        assertFalse(report.reportText().contains("VIN:"))
    }

    @Test
    fun foreignSessionEvidenceCannotEnterTheReport() {
        assertFailsWith<IllegalArgumentException> {
            IdleHealthReportBuilder.build(7, null, 1000, 1100, 100,
                IdleHealthStatus.PARTIAL, setOf(0x0C), listOf(
                    IdleHealthSample(NativeReading(0x0C, DecodedReading.Scalar(700.0)), 8, 10),
                ), null)
        }
    }

    @Test
    fun missingMonitorDataKeepsReadinessUnknown() {
        val scan = DiagnosticScanView(DiagnosticScanStatus.CAPTURED, false,
            0, 0, 0, null, null, null, null, emptyList(), emptyList())
        val report = IdleHealthReportBuilder.build(7, null, 1000, 61_000, 60_000,
            IdleHealthStatus.COMPLETE, emptySet(), emptyList(), scan)
        assertTrue(report.reportText().contains("Readiness: unknown (monitor status not returned)"))
        assertFalse(report.reportText().contains("0 advertised monitor(s) incomplete"))
    }
}
