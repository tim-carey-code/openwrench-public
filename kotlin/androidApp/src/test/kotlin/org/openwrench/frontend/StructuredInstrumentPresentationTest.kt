package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

class StructuredInstrumentPresentationTest {
    @Test fun importedStructuredTemperaturesKeepTheirValuesAndCelsiusUnitsInImperialMode() {
        val reference = checkNotNull(javaClass.getResourceAsStream("/native-snapshot-reference.json"))
            .bufferedReader().use { it.readText() }
        val imported = SnapshotCodec.decode(reference)
        val rows = NativeUiState(origin = ReadingOrigin.REPLAY, snapshot = imported.readings)
            .dashboardSignals(1000).filter { it.definition.id in setOf("pid-67", "pid-68") }

        assertEquals(2, rows.size)
        for (row in rows) {
            assertEquals(SignalAvailability.AVAILABLE, row.availability)
            assertEquals("Value supplied", row.status)
            assertEquals(SignalType.STRUCTURED, row.definition.type)
            assertEquals("sensor_1_celsius: 171.0\nsensor_2_celsius: 215.0", row.instrumentValue(true))
            assertEquals(row.instrumentValue(false), row.instrumentValue(true))
            assertEquals("°C", row.instrumentUnit(true))
            assertEquals("°C", row.instrumentUnit(false))
        }
    }

    @Test fun structuredOptionalFieldsRemainExplicitAndUnavailableRowsNeverCreateReadings() {
        val reading = NativeReading(0x67, ObdDecoder.decode(0x67, "4167015000"))
        val row = NativeUiState(origin = ReadingOrigin.REPLAY, snapshot = listOf(reading))
            .dashboardSignals(1000).single()
        assertEquals("sensor_1_celsius: 40.0\nsensor_2_celsius: unavailable", row.instrumentValue(true))
        assertEquals("°C", row.instrumentUnit(true))
        for (availability in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            val unavailable = row.copy(availability = availability, value = null)
            assertEquals("—", unavailable.instrumentValue(true))
            assertEquals("°C", unavailable.instrumentUnit(true))
        }
        for (support in listOf(SignalSupport.UNKNOWN, SignalSupport.UNSUPPORTED)) {
            val unavailable = DashboardSignal(row.definition.copy(support = support))
            assertEquals("—", unavailable.instrumentValue(true))
            assertEquals("°C", unavailable.instrumentUnit(true))
        }
    }

    @Test fun scalarValuesAndUnitsConvertTogetherWithoutChangingSourceReadings() {
        val cases = listOf(
            Triple("°C", "212", "°F"), Triple("km/h", "62.137", "mph"),
            Triple("kPa", "14.504", "psi"), Triple("g/s", "13.228", "lb/min"),
        )
        for ((unit, expectedValue, expectedUnit) in cases) {
            for (type in listOf(SignalType.NUMBER, SignalType.INTEGER)) {
                val value = if (type == SignalType.INTEGER) SignalValue.Integer(100) else SignalValue.Number(100.0)
                val row = DashboardSignal(SignalDefinition("scalar", "Scalar reading", unit, type, SignalSupport.SUPPORTED),
                    SignalAvailability.AVAILABLE, value)
                assertEquals(expectedValue, row.instrumentValue(true))
                assertEquals(expectedUnit, row.instrumentUnit(true))
                assertEquals("100", row.instrumentValue(false))
                assertEquals(unit, row.instrumentUnit(false))
                assertEquals(value, row.value)
                val unavailable = row.copy(availability = SignalAvailability.UNAVAILABLE, value = null)
                assertEquals("—", unavailable.instrumentValue(true))
                assertEquals(expectedUnit, unavailable.instrumentUnit(true))
            }
        }
    }
}
