package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*
import org.openwrench.frontend.android.DiagnosisPhase

class FullscreenTelemetryTest {
    private val definition = SignalDefinition("coolant", "Coolant temperature", "°C", SignalType.NUMBER, SignalSupport.SUPPORTED, "0105")
    private val row = DashboardSignal(definition, SignalAvailability.AVAILABLE, SignalValue.Number(20.0), ageMillis = 1_500)
    private val active = WatchUiState(connection = HardwareConnection.CONNECTED, acquisition = Acquisition.RUNNING)

    @Test fun fullscreenIsOffByDefaultAndDoesNotAlterOtherDisplayChoices() {
        val state = LiveDisplayState().apply {
            mode = LiveDisplayMode.BOTH; frozen = listOf(row); plotSelection = listOf("coolant")
            imperial = true; range = GraphTimeRange.FIFTEEN; expanded = true; fitScale = true; selectedValuesOnly = true
        }
        assertFalse(state.fullScreen)
        repeat(5) { state.fullScreen = true; state.fullScreen = false }
        assertEquals(LiveDisplayMode.BOTH, state.mode)
        assertEquals(listOf(row), state.frozen)
        assertEquals(listOf("coolant"), state.plotSelection)
        assertEquals(GraphTimeRange.FIFTEEN, state.range)
        assertTrue(state.imperial && state.expanded && state.fitScale && state.selectedValuesOnly)
    }

    @Test fun historicalAndSimulatedSourcesNeverClaimALiveHardwareConnection() {
        mapOf(DashboardSource.DEMO to "Fictional", DashboardSource.SCRIPTED to "Fictional",
            DashboardSource.SNAPSHOT to "Historical", DashboardSource.CIVIC_REPLAY to "Recorded").forEach { (source, prefix) ->
            val status = telemetryStatus(source, active, listOf(row))
            assertTrue(status.sourceAndConnection.contains("no adapter"))
            assertTrue(status.freshness.startsWith(prefix))
        }
    }

    @Test fun simulatedSnapshotRetainsItsFictionalProvenance() {
        val status = telemetryStatus(DashboardSource.SNAPSHOT, active, listOf(row), snapshotSimulated = true)
        assertEquals("Simulator snapshot · no adapter", status.sourceAndConnection)
        assertEquals("Fictional historical values · capture age unknown", status.freshness)
    }

    @Test fun currentReceiptsShowOldestAgeAndStaleCountWithoutInferringHealth() {
        val rows = listOf(row, row.copy(ageMillis = 4500), row.copy(availability = SignalAvailability.STALE, value = null))
        val status = telemetryStatus(DashboardSource.HARDWARE, active, rows)
        assertEquals("Hardware · Connected · reading", status.sourceAndConnection)
        assertEquals("2 current · oldest receipt 4 s · 1 stale", status.freshness)
        assertEquals("1 current · oldest receipt <1 s", telemetryStatus(DashboardSource.HARDWARE, active, listOf(row.copy(ageMillis = 0))).freshness)
    }

    @Test fun missingUnknownInvalidExpiredOrUnsupportedReceiptsAreNeverCurrent() {
        assertFailsWith<IllegalArgumentException> { row.copy(ageMillis = -1) }
        val rows = listOf(row.copy(value = null, availability = SignalAvailability.NOT_SAMPLED), row.copy(ageMillis = null), row.copy(ageMillis = 5000),
            DashboardSignal(definition.copy(support = SignalSupport.UNKNOWN)),
            DashboardSignal(definition.copy(support = SignalSupport.UNSUPPORTED)),
            row.copy(availability = SignalAvailability.ERROR, value = null))
        assertEquals("No current receipts", telemetryStatus(DashboardSource.HARDWARE, active, rows).freshness)
        assertEquals("No current receipts", telemetryStatus(DashboardSource.HARDWARE, active, emptyList()).freshness)
    }

    @Test fun disconnectedAndCleanupStatesCannotShowCurrentEvenWithRetainedRows() {
        HardwareConnection.entries.filter { it != HardwareConnection.CONNECTED }.forEach {
            assertEquals("No current receipts", telemetryStatus(DashboardSource.HARDWARE, active.copy(connection = it), listOf(row)).freshness)
        }
        val cleanup = telemetryStatus(DashboardSource.HARDWARE, active.copy(cleanupUncertain = true), listOf(row))
        assertTrue(cleanup.sourceAndConnection.contains("Cleanup unconfirmed"))
        assertEquals("No current receipts", cleanup.freshness)
    }

    @Test fun stoppedFaultedAndTransitioningAcquisitionNeverClaimsCurrentReceipts() {
        Acquisition.entries.filter { it != Acquisition.RUNNING }.forEach {
            assertEquals("No current receipts", telemetryStatus(DashboardSource.HARDWARE, active.copy(acquisition = it), listOf(row)).freshness)
        }
        assertTrue(telemetryStatus(DashboardSource.HARDWARE, active.copy(acquisition = Acquisition.STOP_UNCONFIRMED), emptyList())
            .sourceAndConnection.contains("stop unconfirmed"))
    }

    @Test fun chartPauseAndDiagnosisRecordingRemainIndependentAndExplicit() {
        assertEquals("Diagnosis recording · Charts paused", telemetryActivity(DiagnosisPhase.RECORDING, true))
        assertEquals("Diagnosis recording", telemetryActivity(DiagnosisPhase.RECORDING, false))
        assertEquals("Charts paused", telemetryActivity(null, true))
        assertEquals("Diagnosis paused", telemetryActivity(DiagnosisPhase.PAUSED, false))
        assertNull(telemetryActivity(null, false))
    }
}
