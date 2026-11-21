package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.StoredDtcRead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticScanDeliveryTest {
    @Test
    fun scanRequiresStoppedLiveHardwareAndReportsOnlyAdvertisedEligiblePids() {
        val connected = NativeUiState(connected = true, sessionGeneration = 7,
            supported = setOf(0x01, 0x05, 0x0C, 0x20, 0xA6))
        val ready = DiagnosticScanDelivery.present(connected)
        assertEquals(DiagnosticScanStatus.READY, ready.status)
        assertTrue(ready.canRun)
        assertEquals(3, ready.eligibleCount)
        assertFalse(DiagnosticScanDelivery.present(connected.copy(liveRunning = true)).canRun)
        assertEquals(DiagnosticScanStatus.STOP_LIVE,
            DiagnosticScanDelivery.present(connected.copy(liveRunning = true)).status)
        assertFalse(DiagnosticScanDelivery.present(connected.copy(origin = ReadingOrigin.REPLAY)).canRun)
    }

    @Test
    fun capturedMonitorStatusIsFactualAndOldSessionIsHistorical() {
        val captured = NativeUiState(connected = true, sessionGeneration = 7,
            supported = setOf(0x01, 0x05, 0x0C), snapshotSessionGeneration = 7,
            snapshotCapturedMillis = 1_234, snapshotEligiblePids = setOf(0x01, 0x05, 0x0C),
            storedDtcs = StoredDtcRead(listOf("P0133"), "7E8"),
            snapshot = listOf(
                NativeReading(0x01, DecodedReading.Structured(mapOf(
                    "mil_on" to "true", "dtc_count" to "2",
                    "monitors.0.name" to "misfire", "monitors.0.available" to "true",
                    "monitors.0.complete" to "false"))),
                NativeReading(0x05, null), NativeReading(0x0C, DecodedReading.Scalar(900.0)),
            ))
        val current = DiagnosticScanDelivery.present(captured)
        assertEquals(DiagnosticScanStatus.CAPTURED, current.status)
        assertEquals(true, current.milOn)
        assertEquals(2, current.reportedDtcCount)
        assertEquals(listOf("P0133"), current.storedDtcs?.codes)
        assertTrue(current.reportText().contains("Stored codes: P0133"))
        assertTrue(current.reportText().contains("0105 Engine coolant temperature: NO DATA"))
        assertTrue(current.reportText().contains("VIN omitted"))
        assertEquals(DiagnosticMonitorView("misfire", "true", "false"), current.monitors.single())
        assertEquals(2, current.availableCount)
        assertEquals(1, current.noDataCount)
        val rerun = DiagnosticScanDelivery.present(captured.copy(snapshotInProgress = true, busy = true))
        assertEquals(DiagnosticScanStatus.COLLECTING, rerun.status)
        assertTrue(rerun.rows.isEmpty())
        assertEquals(null, rerun.storedDtcs)
        assertEquals(DiagnosticScanStatus.HISTORICAL,
            DiagnosticScanDelivery.present(captured.copy(sessionGeneration = 8, connected = false)).status)
        assertTrue(DiagnosticScanDelivery.present(captured.copy(storedDtcs = StoredDtcRead(null, null)))
            .reportText().contains("Stored codes: NO DATA (unknown)"))
        val replay = DiagnosticScanDelivery.present(NativeUiState(origin = ReadingOrigin.REPLAY,
            snapshot = captured.snapshot))
        assertEquals(DiagnosticScanStatus.IMPORTED, replay.status)
        assertEquals(3, replay.eligibleCount)
        assertTrue(replay.reportText().contains("Stored codes: not captured"))
    }

    @Test
    fun demoAndImportedSnapshotsAreNotLabeledAsEarlierConnections() {
        val reading = NativeReading(0x0C, DecodedReading.Scalar(900.0))
        val simulator = DiagnosticScanDelivery.present(NativeUiState(
            origin = ReadingOrigin.SIMULATOR, snapshot = listOf(reading)))
        assertEquals(DiagnosticScanStatus.SIMULATED, simulator.status)
        assertEquals(1, simulator.eligibleCount)
        assertFalse(simulator.canRun)

        val imported = DiagnosticScanDelivery.present(NativeUiState(
            origin = ReadingOrigin.REPLAY, snapshot = listOf(reading)))
        assertEquals(DiagnosticScanStatus.IMPORTED, imported.status)
        assertEquals(1, imported.eligibleCount)
        assertFalse(imported.canRun)

        val importedSimulator = DiagnosticScanDelivery.present(NativeUiState(
            origin = ReadingOrigin.REPLAY, replaySimulated = true, snapshot = listOf(reading)))
        assertEquals(DiagnosticScanStatus.IMPORTED_SIMULATED, importedSimulator.status)
        assertTrue(importedSimulator.reportText().contains("imported simulated"))
        assertFalse(importedSimulator.canRun)
    }
}
