package org.openwrench.frontend

import kotlin.test.*
import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

class NativeDashboardStateTest {
    private val state = NativeUiState(connected = true, liveRunning = true, supported = setOf(0x0C, 0x1F),
        watched = mapOf(0x0C to WatchRate.HIGH), readings = mapOf(0x0C to TimedReading(NativeReading(0x0C, DecodedReading.Scalar(0.0)), 1000)))
    private fun List<DashboardSignal>.rpm() = single { it.definition.id == "rpm" }

    @Test fun usbRotationWithoutPickerSelectionKeepsDisconnectAvailableDuringRecordingAndScan() {
        // USB permission has completed, so pendingUsbId/Activity selection are absent
        // on recreation. The process-owned controller remains connected and refresh
        // deliberately does not enumerate adapters during the active session.
        for (busy in listOf(false, true)) {
            val retained = state.copy(busy = busy, liveHealthEnabled = true)
            assertTrue(retained.watchPresentation().canUseConnectionAction(adapterSelected = false))
        }
    }

    @Test fun openingCanBeCanceledWithoutSelectionButReconnectStillRequiresOne() {
        for (connection in listOf(HardwareConnection.CONNECTING, HardwareConnection.DISCOVERING)) {
            assertTrue(WatchUiState(connection = connection, busy = true).canUseConnectionAction(false))
        }
        for (connection in listOf(HardwareConnection.DISCONNECTED, HardwareConnection.FAULTED)) {
            val idle = WatchUiState(connection = connection)
            assertFalse(idle.canUseConnectionAction(false))
            assertTrue(idle.canUseConnectionAction(true))
            assertFalse(idle.copy(busy = true).canUseConnectionAction(true))
        }
    }

    @Test fun releaseRetryNeedsNoSelectionButCannotBeRepeatedWhileBusy() {
        val failed = WatchUiState(connection = HardwareConnection.FAULTED, cleanupUncertain = true)
        assertTrue(failed.canUseConnectionAction(false))
        assertFalse(failed.copy(busy = true).canUseConnectionAction(false))
        assertFalse(failed.copy(busy = true).canUseConnectionAction(true))
    }

    @Test fun currentCatalogKeepsAllStableIdentifiersAndValueTypes() {
        assertEquals(ObdDecoder.scalarPids, nativeSignalIds.keys)
        assertEquals(nativeSignalIds.size, nativeSignalIds.values.distinct().size)
        assertEquals("runtime", nativeSignalIds[0x1F])
        assertEquals("control-module-voltage", nativeSignalIds[0x42])
        val rows = state.dashboardSignals(1000)
        assertEquals(55, rows.size)
        assertEquals(SignalType.INTEGER, rows.single { it.definition.pid == "011F" }.definition.type)
        assertEquals(SignalType.NUMBER, rows.rpm().definition.type)
    }

    @Test fun realZeroSurvivesButExpiryAndStopImmediatelyHideIt() {
        val row = state.dashboardSignals(5999).rpm()
        assertEquals("0", row.value?.display())
        assertEquals("rpm", row.definition.unit)
        assertEquals(4999L, row.ageMillis)
        assertNull(row.observedAt) // Native transport supplied a monotonic receipt, never a UTC/ECU clock.
        assertEquals("Receipt age", row.ageLabel)
        val expired = state.dashboardSignals(6000).rpm()
        assertNull(expired.value)
        assertEquals(SignalAvailability.STALE, expired.availability)
        val stopped = state.copy(liveRunning = false).dashboardSignals(1000).rpm()
        assertNull(stopped.value)
        assertEquals(SignalAvailability.STALE, stopped.availability)
    }

    @Test fun unsupportedUnselectedNoDataAndDisconnectedRemainDistinct() {
        val rows = state.dashboardSignals(1000)
        assertEquals("Unsupported", rows.single { it.definition.id == "speed" }.status)
        assertEquals("Not sampled", rows.single { it.definition.id == "runtime" }.status)
        val noData = state.copy(readings = mapOf(0x0C to TimedReading(NativeReading(0x0C, null), 1000))).dashboardSignals(1000).rpm()
        assertEquals("Unavailable", noData.status)
        assertNull(noData.value)
        val disconnected = state.copy(connected = false).dashboardSignals(1000)
        assertTrue(disconnected.all { it.value == null && it.definition.support == SignalSupport.UNKNOWN })
        assertEquals("Disconnected", disconnected.rpm().status)
    }

    @Test fun disconnectedAndReleaseUncertaintyCannotPresentSupportedCountsOrEditSelection() {
        assertEquals(2, state.watchPresentation().availableSignalCount())
        assertNull(state.copy(connected = false).watchPresentation().availableSignalCount())
        assertFalse(state.watchPresentation().canEditSelection())
        assertTrue(state.copy(liveRunning = false).watchPresentation().canEditSelection())
        assertFalse(state.copy(liveRunning = false, cleanupUncertain = true).watchPresentation().canEditSelection())
        assertEquals(Acquisition.STOPPING, state.copy(liveRunning = false, stopPending = true).watchPresentation().acquisition)
    }

    @Test fun adapterFailureShowsReadErrorAndUnknownSupportWithoutRetainingNumbers() {
        val failed = state.copy(connected = false, liveRunning = false, faulted = true, detail = "Adapter timed out")
        val row = failed.dashboardSignals(1000).rpm()
        assertEquals("Read error", row.status)
        assertEquals(SignalSupport.UNKNOWN, row.definition.support)
        assertEquals("Adapter timed out", row.reason)
        assertNull(row.value)
        assertEquals(HardwareConnection.FAULTED, failed.watchPresentation().connection)
        assertNull(failed.watchPresentation().availableSignalCount())
        assertEquals("Disconnected", failed.copy(faulted = false).dashboardSignals(1000).rpm().status)
    }

    @Test fun snapshotRemainsHistoricalWithNoInventedClockOrTrend() {
        val snapshot = NativeUiState(origin = ReadingOrigin.REPLAY, snapshot = listOf(
            NativeReading(0x0C, DecodedReading.Scalar(42.0)),
            NativeReading(0x01, DecodedReading.Structured(mapOf("mil_on" to "false")))))
        val rows = NativeTrendHistory { 1000 }.present(snapshot)
        assertEquals("42", rows.rpm().value?.display())
        assertTrue(rows.all { it.observedAt == null && it.ageMillis == null && it.trend.points.isEmpty() })
        assertTrue(rows.all { "Historical" in it.reason })
        assertTrue(rows.last().value is SignalValue.Structured)
    }

    private fun importedSnapshotRow(pid: Int, value: JsonPrimitive, type: String): DashboardSignal {
        val reference = checkNotNull(javaClass.getResourceAsStream("/native-snapshot-reference.json"))
            .bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(reference).jsonObject
        val original = root.getValue("readings").jsonArray.single { it.jsonObject.getValue("pid").jsonPrimitive.int == pid }.jsonObject
        val reading = JsonObject(original + ("value" to value) + ("value_type" to JsonPrimitive(type)))
        val imported = SnapshotCodec.decode(JsonObject(root + ("readings" to JsonArray(listOf(reading)))).toString())
        val state = NativeUiState(origin = ReadingOrigin.REPLAY, snapshot = imported.readings, replaySimulated = imported.simulated)
        return NativeTrendHistory { 1000 }.present(state).single()
    }

    @Test fun snapshotImportDisplaysIntegerRpmUsingItsSuppliedType() {
        val row = importedSnapshotRow(0x0C, JsonPrimitive(1000), "integer")
        assertEquals(SignalType.INTEGER, row.definition.type)
        assertEquals(SignalValue.Integer(1000), row.value)
        assertEquals(SignalAvailability.AVAILABLE, row.availability)
        assertEquals(SignalType.NUMBER, nativeSignalDefinition(0x0C, SignalSupport.SUPPORTED).type)
    }

    @Test fun snapshotImportDisplaysNumberRuntimeUsingItsSuppliedType() {
        val row = importedSnapshotRow(0x1F, JsonPrimitive(842.0), "number")
        assertEquals(SignalType.NUMBER, row.definition.type)
        assertEquals(SignalValue.Number(842.0), row.value)
        assertEquals(SignalAvailability.AVAILABLE, row.availability)
        assertEquals(SignalType.INTEGER, nativeSignalDefinition(0x1F, SignalSupport.SUPPORTED).type)
    }

    @Test fun clockTicksDoNotFabricateSamplesAndNewReadsComputeObservedCadence() {
        var now = 1000L
        val history = NativeTrendHistory { now }
        val first = history.present(state).rpm()
        assertEquals(1, first.trend.points.size)
        now = 1500
        assertEquals(first.trend, history.present(state).rpm().trend)
        val next = state.copy(readings = mapOf(0x0C to TimedReading(NativeReading(0x0C, DecodedReading.Scalar(2.0)), 1600)))
        now = 1600
        val row = history.present(next).rpm()
        assertEquals(2, row.trend.points.size)
        assertEquals(600L, row.cadenceMillis)
        now = 6600
        val expired = history.present(next).rpm()
        assertNull(expired.trend.points.last().value)
        now = 6700
        history.present(next.copy(liveRunning = false, readings = emptyMap()))
        now = 1000
        assertEquals(1, history.present(state).rpm().trend.points.count { it.value != null })
    }

    @Test fun staticDemoMatchesCurrentDashboardAndDoesNotInventTiming() {
        val rows = demoRows(DemoScenario.COMPLETE)
        assertEquals("715", rows.rpm().value?.display())
        assertEquals("82", rows.single { it.definition.id == "coolant" }.value?.display())
        assertEquals("0", rows.single { it.definition.id == "speed" }.value?.display())
        assertEquals("184", rows.single { it.definition.id == "runtime" }.value?.display())
        assertTrue(rows.all { it.observedAt == null && it.ageMillis == null })
        assertEquals(SignalAvailability.STALE, demoRows(DemoScenario.MIXED).rpm().availability)
        assertTrue(demoRows(DemoScenario.LOAD_ERROR).isEmpty())
    }

    @Test fun pinPreferencesAreSourceSeparatedAndValidated() {
        val store = MemoryDashboardPinStore()
        store.save(DashboardSource.DEMO, listOf("rpm", "coolant"))
        store.save(DashboardSource.HARDWARE, listOf("control-module-voltage"))
        assertEquals(listOf("rpm", "coolant"), store.load(DashboardSource.DEMO))
        assertEquals(listOf("control-module-voltage"), store.load(DashboardSource.HARDWARE))
        assertNull(store.load(DashboardSource.SNAPSHOT))
        assertFailsWith<IllegalArgumentException> { store.save(DashboardSource.DEMO, listOf("rpm", "rpm")) }
    }

    @Test fun compactFormattingAndResponsiveGridKeepCurrentDashboardRules() {
        assertEquals("50.196", SignalValue.Number(50.19607843137255).compactDisplay())
        assertEquals("1.0E-5", SignalValue.Number(0.00001).compactDisplay())
        assertEquals(1, dashboardColumns(320f, 1f))
        assertEquals(2, dashboardColumns(760f, 1f))
        assertEquals(4, dashboardColumns(1200f, 1f))
        assertEquals(1, dashboardColumns(760f, 2f))
    }
}
