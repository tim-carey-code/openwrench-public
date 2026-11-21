package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class DashboardPresentationTest {
    private val speed = SignalDefinition("speed", "Vehicle speed", "km/h", SignalType.NUMBER, SignalSupport.SUPPORTED)
    @Test fun fuelTrimPresetPromotesOnlySupportedTrimsWithoutDroppingExistingPins() {
        val rows = listOf(0x06, 0x07, 0x08, 0x09).map { pid ->
            val d = nativeSignalDefinition(pid, if (pid == 0x09) SignalSupport.UNSUPPORTED else SignalSupport.SUPPORTED)
            DashboardSignal(d, if (pid == 0x09) SignalAvailability.NOT_SAMPLED else SignalAvailability.AVAILABLE,
                if (pid == 0x09) null else SignalValue.Number(-12.5))
        }
        val pins = fuelTrimPins(rows, listOf("rpm", "speed", "coolant"))
        assertEquals(listOf("rpm", "stft", "ltft", "stft-bank-2", "speed", "coolant"), pins)
        assertEquals(listOf("stft", "ltft", "stft-bank-2"), instrumentSlots(rows, pins, true).take(3).map { it.first })
        assertEquals("-12.5", rows.first().instrumentValue(false))
        assertEquals("Short-term trim · Bank 1", compactSignalLabel(rows.first()))
    }
    @Test fun unitSwitchConvertsOnlyPresentationAndNeverCreatesUnavailableValues() {
        val supplied = DashboardSignal(speed, SignalAvailability.AVAILABLE, SignalValue.Number(100.0))
        assertEquals("100", supplied.instrumentValue(false))
        assertEquals("62.137", supplied.instrumentValue(true))
        assertEquals(SignalValue.Number(100.0), supplied.value)
        assertEquals("mph", displayUnit("km/h", true))
        assertEquals("°F", displayUnit("°C", true))
        assertEquals("psi", displayUnit("kPa", true))
        assertEquals("lb/min", displayUnit("g/s", true))
        assertEquals(32.0, convertDisplayValue(0.0, "°C", true))
        assertEquals("2,450", groupInstrumentNumber("2450"))
        assertEquals("-12,000.125", groupInstrumentNumber("-12000.125"))
        assertEquals("—", groupInstrumentNumber("—"))
        for (state in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            assertEquals("—", DashboardSignal(speed, state).instrumentValue(true))
        }
    }
    @Test fun plotSelectionCanExceedFourIndependentOfPinsAndPreservesUserChoices() {
        val selected = listOf("rpm", "speed", "load", "coolant")
        assertEquals(selected + "maf", togglePlot(selected, "maf"))
        assertEquals(listOf("rpm", "load", "coolant"), togglePlot(selected, "speed"))
        assertEquals(listOf("rpm", "maf"), togglePlot(listOf("rpm"), "maf"))
        val originalPins = listOf("speed")
        val rows = listOf(DashboardSignal(speed, SignalAvailability.AVAILABLE, SignalValue.Number(0.0)))
        assertEquals(listOf("speed"), defaultPlotIds(rows, originalPins))
        assertEquals(listOf("speed"), originalPins)
    }
    @Test fun manyPlotsCanBeSelectedRemovedAndSelectedAgainWithoutTruncation() {
        val ids = (1..64).map { "signal-$it" }
        var selected = emptyList<String>()
        ids.forEach { selected = togglePlot(selected, it) }
        assertEquals(ids, selected)
        selected = togglePlot(selected, "signal-32")
        assertEquals(ids - "signal-32", selected)
        assertEquals((ids - "signal-32") + "signal-32", togglePlot(selected, "signal-32"))
        ids.filter { it != "signal-32" }.forEach { selected = togglePlot(selected, it) }
        assertTrue(selected.isEmpty())
    }
    @Test fun initialPlotsStillUseFourNumericPinnedDefaults() {
        val rows = (1..8).map { DashboardSignal(speed.copy(id = "signal-$it")) }
        val pins = rows.map { it.definition.id }
        assertEquals(pins.take(4), defaultPlotIds(rows, pins))
    }
    @Test fun plotScaleIncludesOutOfRangeReadingsWithoutHidingThem() {
        val rpm = speed.copy(id = "rpm", unit = "rpm")
        val supplied = DashboardSignal(rpm, SignalAvailability.AVAILABLE, SignalValue.Number(12000.0))
        assertTrue(plotBounds(supplied, listOf(-20.0, 12000.0), false).first <= -20)
        assertTrue(plotBounds(supplied, listOf(-20.0, 12000.0), false).second >= 12000)
        assertEquals("12000", supplied.visibleValue())
        assertEquals("Age unknown", DashboardSignal(speed).shortAge())
        assertEquals("Rate unknown", DashboardSignal(speed).observedFrequency())
    }
    @Test fun explicitlyReorderedPinsKeepTheirChosenSlotOrder() {
        val rows = listOf(DashboardSignal(speed), DashboardSignal(speed.copy(id = "runtime", label = "Runtime", unit = "s")))
        assertEquals(listOf("runtime", "speed"), instrumentSlots(rows, listOf("runtime", "speed"), true).take(2).map { it.first })
        assertEquals("speed", instrumentSlots(rows, listOf("runtime", "speed"), false).first().first)
        assertEquals(8, instrumentSlots(rows, emptyList(), false).size)
    }
    @Test fun unpinnedSlotsStillExposeDisconnectionStalenessAndUnsupportedStatus() {
        assertEquals("Disconnected", instrumentStatus(DashboardSignal(speed, SignalAvailability.DISCONNECTED), false))
        assertEquals("Stale", instrumentStatus(DashboardSignal(speed, SignalAvailability.STALE), false))
        assertEquals("Unsupported", instrumentStatus(DashboardSignal(speed.copy(support = SignalSupport.UNSUPPORTED)), false))
        assertEquals("Not pinned", instrumentStatus(DashboardSignal(speed, SignalAvailability.AVAILABLE, SignalValue.Number(0.0)), false))
        assertEquals("Not supplied", instrumentStatus(null, true))
    }
}
