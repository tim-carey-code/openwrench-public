package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.core.*

class WorkspaceValuePresentationTest {
    private fun reading(value: SignalValue, unit: String, type: SignalType = SignalType.NUMBER): DashboardSignal {
        val base = syntheticWorkspaceFixture().first()
        return base.copy(definition = base.definition.copy(unit = unit, type = type), value = value)
    }

    @Test fun overviewUsesSensibleGroupedPrecisionWithoutChangingSamples() {
        for ((number, unit, expected) in listOf(Triple(2345.6, "rpm", "2,346"), Triple(91.048, "°C", "91"),
            Triple(14.105, "V", "14.1"), Triple(-.78125, "%", "-0.8"), Triple(.98123, "ratio", "0.98"))) {
            val row = reading(SignalValue.Number(number), unit)
            val before = row.copy()
            assertEquals(expected, workspaceValuePresentation(row, false).value)
            assertEquals(before, row)
            assertEquals(number, (row.value as SignalValue.Number).value)
        }
    }

    @Test fun conversionsZeroAndLargeIntegersStayTruthful() {
        assertEquals("196", workspaceValuePresentation(reading(SignalValue.Number(91.048), "°C"), true).value)
        assertEquals("°F", workspaceValuePresentation(reading(SignalValue.Number(91.048), "°C"), true).unit)
        assertEquals("43.5", workspaceValuePresentation(reading(SignalValue.Number(300.0), "kPa"), true).value)
        for (zero in listOf(0.0, -0.0, -.0001)) assertEquals("0", workspaceValuePresentation(reading(SignalValue.Number(zero), "V"), false).value)
        assertEquals("9,007,199,254,740,993", workspaceValuePresentation(reading(SignalValue.Integer(9007199254740993L), "s", SignalType.INTEGER), false).value)
    }

    @Test fun missingStaleAndUnsupportedStatesAreUnchanged() {
        val row = reading(SignalValue.Number(91.048), "°C")
        for (state in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            val stale = row.copy(availability = state, value = null)
            assertEquals(liveValuePresentation(stale, false), workspaceValuePresentation(stale, false))
        }
        val unsupported = row.copy(definition = row.definition.copy(support = SignalSupport.UNSUPPORTED), availability = SignalAvailability.NOT_SAMPLED, value = null)
        assertEquals(liveValuePresentation(unsupported, false), workspaceValuePresentation(unsupported, false))
        val missing = row.copy(value = null, availability = SignalAvailability.NOT_SAMPLED)
        assertEquals(liveValuePresentation(missing, false), workspaceValuePresentation(missing, false))
    }
}
