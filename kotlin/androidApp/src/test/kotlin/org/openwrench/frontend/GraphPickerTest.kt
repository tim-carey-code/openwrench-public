package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class GraphPickerTest {
    private fun row(id: String, support: SignalSupport = SignalSupport.SUPPORTED,
        type: SignalType = SignalType.NUMBER, availability: SignalAvailability = SignalAvailability.NOT_SAMPLED) =
        DashboardSignal(SignalDefinition(id, "Sensor $id", "kPa", type, support, pid = id.toIntOrNull()?.let { "01" + it.toString(16).uppercase().padStart(2, '0') }), availability)

    @Test fun everyNumericPidCanBeFoundIncludingUnsampledAndUnknown() {
        val rows = (1..40).map { row(it.toString()) } + row("unknown", SignalSupport.UNKNOWN) +
            row("unsupported", SignalSupport.UNSUPPORTED) + row("text", type = SignalType.TEXT)
        assertEquals(42, graphPickerRows(rows, "", false).size)
        assertEquals(40, graphPickerRows(rows, "", true).size)
        assertEquals(listOf("39"), graphPickerRows(rows, "0127", false).map { it.definition.id })
        assertEquals(42, graphPickerRows(rows, "kPa", false).size)
        assertEquals(listOf("unknown"), graphPickerRows(rows, "UNKNOWN", false).map { it.definition.id })
        assertTrue(graphPickerRows(rows, "unknown", true).isEmpty())
        assertTrue(graphPickerRows(rows, "missing", false).isEmpty())
        assertTrue(rows.all { it.value == null })
    }

    @Test fun graphChoiceNeverImpliesAcquisitionEligibility() {
        for (support in listOf(SignalSupport.UNKNOWN, SignalSupport.UNSUPPORTED)) {
            val row = row("unavailable", support)
            assertTrue(row.definition.isGraphable())
            assertFalse(row.definition.isAcquirable())
            assertEquals(listOf("unavailable"), togglePlot(emptyList(), row.definition.id))
        }
    }
    @Test fun friendlyNamesAreSearchableWithoutChangingProtocolMetadata() {
        val rows = listOf(row("rpm"), row("speed"), row("map"))
        assertEquals("RPM (engine speed)", rows[0].definition.graphLabel())
        assertEquals("Vehicle speed", rows[1].definition.graphLabel())
        assertEquals("MAP pressure (intake manifold)", rows[2].definition.graphLabel())
        assertEquals(listOf("map"), graphPickerRows(rows, "manifold", false).map { it.definition.id })
        assertEquals(listOf("speed"), graphPickerRows(rows, "vehicle", false).map { it.definition.id })
        assertEquals("Sensor rpm", rows[0].definition.label)
    }

    @Test fun filteringAndRepeatedTogglesPreserveOtherSelectionsAndAvailability() {
        val rows = listOf(row("rpm"), row("speed"), row("map", SignalSupport.UNKNOWN))
        var selected = listOf("rpm")
        val map = graphPickerRows(rows, "MAP pressure", false).single()
        selected = togglePlot(selected, map.definition.id)
        assertEquals(listOf("rpm", "map"), selected)
        selected = togglePlot(selected, map.definition.id)
        assertEquals(listOf("rpm"), selected)
        assertEquals(3, graphPickerRows(rows, "", false).size)
        assertEquals(SignalAvailability.NOT_SAMPLED, map.availability)
        assertNull(map.value)
        assertFalse(map.definition.isAcquirable())
    }

}
