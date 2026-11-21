package org.openwrench.frontend

import org.openwrench.frontend.android.DiagnosisPhase
import kotlin.test.*

class DiagnosisDriveHomePresentationTest {
    private fun present(
        phase: DiagnosisPhase = DiagnosisPhase.IDLE,
        source: DashboardSource = DashboardSource.HARDWARE,
        hardware: WatchUiState = WatchUiState(),
        selected: Boolean = true,
        canStart: Boolean = false,
        busy: Boolean = false,
    ) = diagnosisDriveHomePresentation(phase, source, hardware, selected, canStart, busy)

    @Test fun selectedAdapterConnectsButMissingConfigurationOpensSetup() {
        var connections = 0
        var setups = 0
        performDiagnosisConnectionAction(present().connectionAction, { connections++ }, { setups++ })
        assertEquals(1, connections)
        assertEquals(0, setups)
        val missing = present(selected = false)
        assertEquals("Set up connection", missing.connectionButton)
        performDiagnosisConnectionAction(missing.connectionAction, { connections++ }, { setups++ })
        assertEquals(1, connections)
        assertEquals(1, setups)
    }

    @Test fun existingConnectionOnlyOpensSettingsAndNeverInvokesConnectToggle() {
        val model = present(hardware = WatchUiState(connection = HardwareConnection.CONNECTED), canStart = true)
        assertTrue(model.connected)
        assertTrue(model.startEnabled)
        assertFalse(model.endEnabled)
        assertEquals("Connection settings", model.connectionButton)
        var setup = 0
        performDiagnosisConnectionAction(model.connectionAction, { fail("Must not reconnect an owned session") }, { setup++ })
        assertEquals(1, setup)
    }

    @Test fun scanTransitionsKeepActualConnectionAndExplainProgress() {
        for ((phase, expected) in listOf(
            DiagnosisPhase.STARTING to "initial scan",
            DiagnosisPhase.FINISHING to "final evidence",
            DiagnosisPhase.PAUSING to "saving received readings",
        )) {
            val model = present(phase, hardware = WatchUiState(connection = HardwareConnection.CONNECTED, busy = true),
                canStart = false, busy = true)
            assertTrue(model.connected, phase.name)
            assertEquals("Dongle connected", model.connectionStatus)
            assertContains(model.subtitle, expected)
            assertFalse(model.subtitle.contains("Connect your adapter"))
            assertEquals(DiagnosisConnectionAction.NONE, model.connectionAction)
            assertFalse(model.startEnabled)
            assertFalse(model.endEnabled)
        }
    }

    @Test fun failedCleanupAndUnconfirmedStopNeverAdmitConnect() {
        val states = listOf(
            WatchUiState(cleanupUncertain = true),
            WatchUiState(acquisition = Acquisition.STOP_UNCONFIRMED),
            WatchUiState(acquisition = Acquisition.STOPPING),
            WatchUiState(busy = true),
            WatchUiState(connection = HardwareConnection.CONNECTING),
            WatchUiState(connection = HardwareConnection.DISCOVERING),
        )
        states.forEach { state ->
            val model = present(hardware = state)
            assertNotEquals(DiagnosisConnectionAction.CONNECT, model.connectionAction)
            performDiagnosisConnectionAction(model.connectionAction, { fail("Ownership guard bypassed: $state") }, {})
        }
    }

    @Test fun repeatedConnectWhileBusyDoesNothingAndCancelledAttemptCanRetry() {
        var native = WatchUiState()
        var calls = 0
        fun tap() = performDiagnosisConnectionAction(present(hardware = native).connectionAction, {
            calls++
            native = WatchUiState(connection = HardwareConnection.CONNECTING, busy = true)
        }, { fail("Selected adapter should not open setup") })
        tap(); tap()
        assertEquals(1, calls)
        native = WatchUiState(connection = HardwareConnection.DISCONNECTED)
        tap()
        assertEquals(2, calls)
    }

    @Test fun historicalAndDemoSourcesRequireExplicitSetupWithoutAutoConnection() {
        for (source in listOf(DashboardSource.DEMO, DashboardSource.SNAPSHOT, DashboardSource.CIVIC_REPLAY)) {
            val model = present(source = source)
            assertFalse(model.connected)
            assertFalse(model.startEnabled)
            assertEquals(DiagnosisConnectionAction.SETUP, model.connectionAction)
            assertTrue(model.connectionStatus.contains("Not live") || model.connectionStatus.contains("No dongle"))
            performDiagnosisConnectionAction(model.connectionAction, { fail("Must not replace $source") }, {})
        }
    }

    @Test fun recordingAndLostConnectionDoNotAdmitConnectionChanges() {
        val native = WatchUiState(connection = HardwareConnection.CONNECTED)
        val recording = present(DiagnosisPhase.RECORDING, hardware = native, canStart = true)
        assertTrue(recording.endEnabled)
        assertFalse(recording.startEnabled)
        assertEquals(DiagnosisConnectionAction.NONE, recording.connectionAction)
        val disconnected = present(DiagnosisPhase.RECORDING)
        assertFalse(disconnected.connected)
        assertContains(disconnected.subtitle, "Connection lost")
        assertEquals(DiagnosisConnectionAction.NONE, disconnected.connectionAction)
    }

    @Test fun pausedAndInterruptedOptionsRetainOfflineFinishAndRequireReadinessToContinue() {
        for (phase in listOf(DiagnosisPhase.PAUSED, DiagnosisPhase.INTERRUPTED)) {
            val offline = present(phase)
            assertTrue(offline.retainedFinishEnabled)
            assertFalse(offline.continueEnabled)
            assertFalse(offline.endEnabled, "Top action remains distinct from retained Session options")
            val ready = present(phase, hardware = WatchUiState(connection = HardwareConnection.CONNECTED), canStart = true)
            assertTrue(ready.continueEnabled)
            assertFalse(present(phase, busy = true).retainedFinishEnabled)
        }
        assertFalse(present(DiagnosisPhase.COMPLETED).retainedFinishEnabled)
        assertContains(present(DiagnosisPhase.COMPLETED).subtitle, "historical")
    }

    @Test fun portraitAndEnlargedTextReflowWithoutChangingNormalTabletBoundary() {
        assertFalse(diagnosisDriveHomeWide(390f, 1f))
        assertFalse(diagnosisDriveHomeWide(719f, 1f))
        assertTrue(diagnosisDriveHomeWide(720f, 1f))
        assertTrue(diagnosisDriveHomeWide(1280f - 32f, 1f))
        assertFalse(diagnosisDriveHomeWide(1280f - 32f, 2f))
        assertFalse(diagnosisDriveHomeWide(1439f, 2f))
        assertTrue(diagnosisDriveHomeWide(1440f, 2f))
    }
}
