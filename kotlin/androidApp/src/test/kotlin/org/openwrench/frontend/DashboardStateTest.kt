package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

class DashboardStateTest {
    @Test fun compactValuesDoNotWrapAtDecoderPrecisionOrTurnSmallNonzeroIntoZero() {
        val value = SignalValue.Number(50.19607843137255)
        assertEquals("50.196", value.compactDisplay())
        assertEquals("50.19607843137255", value.display())
        assertEquals("0", SignalValue.Number(0.0).compactDisplay())
        assertEquals("1.0E-5", SignalValue.Number(0.00001).compactDisplay())
        assertEquals("-2.344", SignalValue.Number(-2.34375).compactDisplay())
    }
    private val rpm = uncheckedRpm.copy(support = SignalSupport.SUPPORTED)

    @Test fun realZeroRetainsUnitAndReceiptAgeButLossAndExpiryHideValue() {
        val observation = TimedReading(NativeReading(0x0C, DecodedReading.Scalar(0.0)), 1000)
        val state = NativeUiState(connected = true, liveRunning = true, supported = setOf(0x0C), readings = mapOf(0x0C to observation))
        val row = state.dashboardSignals(4500).single { it.definition.id == "rpm" }
        assertEquals("0", row.value?.display())
        assertEquals("rpm", row.definition.unit)
        assertEquals(3500L, row.ageMillis)
        assertNull(row.observedAt)
        val expired = state.dashboardSignals(6000).single { it.definition.id == "rpm" }
        assertNull(expired.value); assertEquals(SignalAvailability.STALE, expired.availability)
        val disconnected = state.copy(connected = false).dashboardSignals(4500).single { it.definition.id == "rpm" }
        assertNull(disconnected.value)
        assertEquals(SignalAvailability.DISCONNECTED, disconnected.availability)
    }

    @Test fun catalogIsGenericAndSupportDoesNotImplyReadingAvailability() {
        val newSignal = SignalDefinition("future_signal", "New catalog signal", "kPa", SignalType.NUMBER, SignalSupport.SUPPORTED, "017F")
        val unsupported = newSignal.copy(id = "not_supported", support = SignalSupport.UNSUPPORTED)
        val rows = listOf(rpm, newSignal, unsupported).map(::DashboardSignal)
        assertNull(rows[1].value); assertEquals("Not sampled", rows[1].status)
        assertEquals("Unsupported", rows[2].status)
        assertEquals(listOf(rows[1]), filterSignals(rows, "017f", SignalSupport.SUPPORTED, SignalAvailability.NOT_SAMPLED))
        assertEquals("Support not checked", DashboardSignal(uncheckedRpm).status)
    }

    @Test fun demoHasNoFictionalTimeAndHardwareNeverFallsBackToDemo() {
        val demo = demoDashboardSignals()
        assertTrue(demo.all { it.observedAt == null && it.ageMillis == null })
        assertEquals("0", demo.single { it.definition.id == "speed" }.value?.display())
        assertEquals("No", demo.single { it.definition.id == "example_boolean" }.value?.display())
        assertTrue(demo.any { it.value is SignalValue.Structured })
        assertTrue(demo.any { it.value is SignalValue.TroubleCodes })
        assertTrue(NativeUiState(faulted = true).dashboardSignals(1000).all { it.value == null })
    }

    @Test fun valuesMustMatchCatalogTypeAndAvailability() {
        assertFailsWith<IllegalArgumentException> { DashboardSignal(rpm, SignalAvailability.STALE, SignalValue.Number(0.0)) }
        assertFailsWith<IllegalArgumentException> { DashboardSignal(rpm, SignalAvailability.AVAILABLE, SignalValue.Text("0")) }
        assertFailsWith<IllegalArgumentException> { DashboardSignal(rpm.copy(support = SignalSupport.UNSUPPORTED), SignalAvailability.AVAILABLE, SignalValue.Number(0.0)) }
        assertFailsWith<IllegalArgumentException> { SignalValue.Number(Double.NaN) }
        assertEquals("9223372036854775807", SignalValue.Integer(Long.MAX_VALUE).display())
    }

    @Test fun columnsAdaptToPhoneTabletDesktopAndFontScale() {
        assertEquals(1, dashboardColumns(320f, 1f))
        assertEquals(2, dashboardColumns(760f, 1f))
        assertEquals(4, dashboardColumns(1200f, 1f))
        assertEquals(1, dashboardColumns(760f, 2f))
        assertEquals(2, dashboardColumns(1200f, 2f))
    }
}
