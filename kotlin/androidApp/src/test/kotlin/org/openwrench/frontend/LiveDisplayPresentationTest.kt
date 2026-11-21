package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class LiveDisplayPresentationTest {
    private val definition = SignalDefinition("coolant", "Coolant temperature", "°C", SignalType.NUMBER, SignalSupport.SUPPORTED, "0105")
    private val supplied = DashboardSignal(definition, SignalAvailability.AVAILABLE, SignalValue.Number(20.0), ageMillis = 1_500)

    @Test fun homeDefaultsToBothAndModesHaveDisjointContent() {
        assertEquals(LiveDisplayMode.BOTH, LiveDisplayState().mode)
        assertTrue(LiveDisplayMode.BOTH.showsValues && LiveDisplayMode.BOTH.showsGraphs)
        assertTrue(LiveDisplayMode.GRAPHS.showsGraphs)
        assertFalse(LiveDisplayMode.GRAPHS.showsValues)
        assertTrue(LiveDisplayMode.VALUES.showsValues)
        assertFalse(LiveDisplayMode.VALUES.showsGraphs)
    }

    @Test fun interruptedRepeatedViewSwitchesKeepSessionPresentation() {
        val state = LiveDisplayState().apply {
            plotSelection = listOf("coolant")
            frozen = listOf(supplied)
            selectedValuesOnly = true
            fullScreen = true
        }
        repeat(10) {
            state.mode = LiveDisplayMode.GRAPHS
            state.fullScreen = false // navigation/background exit preserves the mode and frozen chart
            state.mode = LiveDisplayMode.VALUES
            state.fullScreen = true
            state.mode = LiveDisplayMode.BOTH
        }
        assertEquals(listOf("coolant"), state.plotSelection)
        assertEquals(listOf(supplied), state.frozen)
        assertTrue(state.selectedValuesOnly && state.fullScreen)
        assertEquals(LiveDisplayMode.BOTH, state.mode)
        assertNull(LiveDisplayState().frozen)
    }

    @Test fun selectedValuesPreferenceDoesNotHideBothHomeInstruments() {
        val state = LiveDisplayState().apply { selectedValuesOnly = true; mode = LiveDisplayMode.VALUES }
        val rows = listOf(supplied, supplied.copy(definition = definition.copy(id = "rpm")))
        assertEquals(1, liveValueRows(rows, listOf("coolant"), state.filtersValues()).size)
        state.mode = LiveDisplayMode.BOTH
        assertFalse(state.filtersValues())
        assertSame(rows, liveValueRows(rows, listOf("coolant"), state.filtersValues()))
        state.mode = LiveDisplayMode.GRAPHS
        assertFalse(state.filtersValues())
        state.mode = LiveDisplayMode.VALUES
        assertTrue(state.filtersValues())
    }

    @Test fun valuesShowActualReceivedScalarsAndConvertedUnitsTogether() {
        assertEquals(LiveValuePresentation("20", "°C", "1 s ago", true, true), liveValuePresentation(supplied, false))
        assertEquals(LiveValuePresentation("68", "°F", "1 s ago", true, true), liveValuePresentation(supplied, true))
        assertEquals(SignalValue.Number(20.0), supplied.value)
    }

    @Test fun staleDisconnectedAndFailedReadingsNeverReuseAnOldNumber() {
        val statuses = mapOf(SignalAvailability.STALE to "Stale", SignalAvailability.DISCONNECTED to "Disconnected",
            SignalAvailability.ERROR to "Read error", SignalAvailability.NOT_SAMPLED to "Not sampled",
            SignalAvailability.UNAVAILABLE to "Unavailable")
        statuses.forEach { (availability, status) ->
            val result = liveValuePresentation(supplied.copy(availability = availability, value = null), true)
            assertEquals("—", result.value)
            assertEquals(status, result.status)
            assertFalse(result.available)
        }
    }

    @Test fun unsupportedAndUncheckedCapabilitiesStayDistinct() {
        val unsupported = DashboardSignal(definition.copy(support = SignalSupport.UNSUPPORTED))
        val unchecked = DashboardSignal(definition.copy(support = SignalSupport.UNKNOWN))
        assertEquals("Unsupported", liveValuePresentation(unsupported, false).status)
        assertEquals("Support not checked", liveValuePresentation(unchecked, false).status)
        assertEquals("—", liveValuePresentation(unsupported, false).value)
        assertEquals("—", liveValuePresentation(unchecked, false).value)
    }

    @Test fun unknownAgeDoesNotBecomeAFreshnessClaim() {
        assertEquals("Age unknown", liveValuePresentation(supplied.copy(ageMillis = null), false).status)
    }

    @Test fun categoricalAndStructuredReadingsRemainVisibleWithoutScalarConversion() {
        val category = DashboardSignal(definition.copy(id = "fuel", type = SignalType.CATEGORY, unit = null),
            SignalAvailability.AVAILABLE, SignalValue.Category("Closed loop"))
        val structured = DashboardSignal(definition.copy(id = "temperatures", type = SignalType.STRUCTURED),
            SignalAvailability.AVAILABLE, SignalValue.Structured(mapOf("Bank 1" to "20 °C")))
        assertEquals("Closed loop", liveValuePresentation(category, true).value)
        assertFalse(liveValuePresentation(category, true).scalar)
        assertEquals(structured.visibleValue(), liveValuePresentation(structured, true).value)
        assertEquals("°C", liveValuePresentation(structured, true).unit)
    }

    @Test fun allValuesContainEveryStatusInStableCatalogOrder() {
        val rows = listOf(DashboardSignal(definition.copy(id = "unknown", support = SignalSupport.UNKNOWN)), supplied,
            DashboardSignal(definition.copy(id = "unsupported", support = SignalSupport.UNSUPPORTED)))
        assertSame(rows, liveValueRows(rows, listOf("coolant"), false))
        val expired = rows.map { if (it == supplied) it.copy(availability = SignalAvailability.STALE, value = null) else it }
        assertEquals(rows.map { it.definition.id }, liveValueRows(expired, emptyList(), false).map { it.definition.id })
    }

    @Test fun selectedValuesFollowTheSameSelectionAndEmptyStaysEmpty() {
        val rows = listOf(supplied, supplied.copy(definition = definition.copy(id = "other")))
        assertEquals(listOf("other", "coolant"), liveValueRows(rows, listOf("other", "missing", "coolant"), true).map { it.definition.id })
        assertTrue(liveValueRows(rows, emptyList(), true).isEmpty())
        assertEquals(2, rows.size)
    }

    @Test fun denseGridReflowsForPhonesTabletsAndLargeText() {
        assertEquals(2, liveValueColumns(366f, 1f))
        assertEquals(4, liveValueColumns(760f, 1f))
        assertEquals(4, liveValueColumns(1400f, 1f))
        assertEquals(1, liveValueColumns(366f, 2f))
        assertEquals(1, liveValueColumns(120f, 1f))
        assertEquals(liveValueColumns(366f, 1f), liveValueColumns(366f, .8f))
    }

    @Test fun repeatedModeChangesPreserveSelectionAndChartSettings() {
        val state = LiveDisplayState().apply {
            plotSelection = listOf("coolant", "rpm")
            frozen = listOf(supplied)
            range = GraphTimeRange.FIFTEEN
            fitScale = true
            expanded = true
            imperial = true
        }
        repeat(3) { LiveDisplayMode.entries.forEach { state.mode = it } }
        assertEquals(listOf("coolant", "rpm"), state.plotSelection)
        assertEquals(listOf(supplied), state.frozen)
        assertEquals(GraphTimeRange.FIFTEEN, state.range)
        assertTrue(state.fitScale && state.expanded && state.imperial)
        assertEquals(LiveDisplayMode.BOTH, state.mode)
    }

    @Test fun newSourceStateDoesNotKeepAnotherSessionsFrozenValues() {
        val old = LiveDisplayState().apply { frozen = listOf(supplied); plotSelection = listOf("coolant") }
        val fresh = LiveDisplayState()
        assertNotNull(old.frozen)
        assertNull(fresh.frozen)
        assertNull(fresh.plotSelection)
    }

    @Test fun captionsRetainProvenanceAndExplainDisplayOnlyPause() {
        assertTrue(liveValuesCaption(DashboardSource.DEMO, false).contains("Simulated"))
        assertTrue(liveValuesCaption(DashboardSource.SCRIPTED, true).contains("Simulated"))
        assertTrue(liveValuesCaption(DashboardSource.SNAPSHOT, false).contains("Historical"))
        assertTrue(liveValuesCaption(DashboardSource.CIVIC_REPLAY, false).contains("not live"))
        assertTrue(liveValuesCaption(DashboardSource.HARDWARE, false).contains("acquisition unchanged"))
        assertTrue(liveValuesCaption(DashboardSource.HARDWARE, true).contains("charts remain paused"))
    }
}
