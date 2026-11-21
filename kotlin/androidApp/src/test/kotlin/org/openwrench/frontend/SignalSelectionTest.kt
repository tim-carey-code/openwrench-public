package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class SignalSelectionTest {
    @Test fun categoriesDoNotAlterSuppliedDefinitionsOrSearch() {
        val rows = demoDashboardSignals()
        assertEquals(SignalCategory.TEMPERATURE, rows.single { it.definition.id == "coolant" }.definition.category())
        assertEquals(SignalCategory.ENGINE, rows.single { it.definition.id == "rpm" }.definition.category())
        assertEquals(SignalCategory.OTHER, rows.single { it.definition.id == "example_structure" }.definition.category())
        assertEquals(listOf("rpm"), filterSignals(rows, "RPM", SignalSupport.SUPPORTED, null).map { it.definition.id })
    }
    @Test fun liveDefaultsFollowSuppliedSelectionRatherThanRpmOrVehicleAssumptions() {
        val definition = SignalDefinition("control-module-voltage", "Supplied voltage", "V", SignalType.NUMBER, SignalSupport.SUPPORTED, "0142")
        val state = WatchUiState(catalog = listOf(definition), selection = listOf("rpm", definition.id))
        assertEquals(listOf(definition.id), state.defaultLivePins())
        assertTrue(WatchUiState().defaultLivePins().isEmpty())
    }
    @Test fun currentAvailableCountBecomesUnknownAfterDisconnectOrFault() {
        val d = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED)
        val state = WatchUiState(connection = HardwareConnection.CONNECTED, catalog = listOf(d))
        assertEquals(1, state.availableSignalCount())
        assertNull(state.copy(connection = HardwareConnection.DISCONNECTED).availableSignalCount())
        assertNull(state.copy(connection = HardwareConnection.FAULTED).availableSignalCount())
        assertNull(WatchUiState().availableSignalCount())
    }
    @Test fun sessionCatalogSummaryDoesNotPresentRetainedSupportAsCurrent() {
        val d = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED)
        val connected = WatchUiState(connection = HardwareConnection.CONNECTED, catalog = listOf(d), selection = listOf("rpm"))
        assertEquals("1 signals · 1 supported · 1 selected", connected.sessionCatalogSummary())
        for (state in listOf(HardwareConnection.DISCONNECTED, HardwareConnection.FAULTED)) {
            val summary = connected.copy(connection = state).sessionCatalogSummary()
            assertEquals("1 signals · support unknown until connected · 1 selected", summary)
            assertFalse("supported" in summary)
        }
        assertEquals("Support not checked", WatchUiState().sessionCatalogSummary())
    }
    @Test fun cleanupUncertaintyBlocksSelectionEvenWhenStopped() {
        val state = WatchUiState(connection = HardwareConnection.CONNECTED)
        assertTrue(state.canEditSelection())
        assertFalse(state.copy(cleanupUncertain = true).canEditSelection())
        assertFalse(state.copy(acquisition = Acquisition.STOP_UNCONFIRMED).canEditSelection())
    }
}
