package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

class MechanicPresentationTest {
    private fun row(pid: Int, value: Double = 0.0) = DashboardSignal(nativeSignalDefinition(pid, SignalSupport.SUPPORTED),
        SignalAvailability.AVAILABLE, if (ObdDecoder.isIntegerScalar(pid)) SignalValue.Integer(value.toLong()) else SignalValue.Number(value), ageMillis = 40)
    private fun layout(rows: List<DashboardSignal>) = GraphFirstLayout.fromSensorIds(
        (mechanicTopIds + rows.map { it.definition.id }).distinct().filter { id -> rows.any { it.definition.id == id } })
    private fun scan(status: DiagnosticScanStatus = DiagnosticScanStatus.CAPTURED, captured: Long? = 1000,
        codes: StoredDtcRead? = null, count: Int? = null, rows: List<DiagnosticScanRow> = emptyList()) =
        DiagnosticScanView(status, false, rows.size, rows.size, 0, captured, null, count, codes, emptyList(), rows)

    @Test fun bankValuesStayIndependentIncludingRealZeroAndMissingBankTwo() {
        val rows = listOf(row(0x06), row(0x07, 2.34375), row(0x08, -1.5625), row(0x09, .78125))
        val banks = mechanicTrimBanks(rows, rows, layout(rows), false)
        assertEquals(listOf(1, 2), banks.map { it.bank })
        assertEquals("0", banks[0].short.value); assertTrue(banks[0].short.available)
        assertEquals("2.3", banks[0].long.value); assertEquals("-1.6", banks[1].short.value)
        assertEquals("0.8", banks[1].long.value)
        val absent = mechanicTrimBanks(rows.take(2), rows.take(2), layout(rows.take(2)), false)[1]
        assertEquals("Not supplied", absent.short.status); assertEquals("—", absent.long.value)
        assertFalse(absent.short.available)
    }

    @Test fun staleDisconnectedErrorAndUnsupportedNeverBecomeZeroOrAnotherBanksValue() {
        for (state in listOf(SignalAvailability.STALE, SignalAvailability.DISCONNECTED, SignalAvailability.ERROR, SignalAvailability.UNAVAILABLE, SignalAvailability.NOT_SAMPLED)) {
            val rows = listOf(row(0x06, 3.0), row(0x08, 5.0).copy(availability = state, value = null))
            val banks = mechanicTrimBanks(rows, rows, layout(rows), false)
            assertEquals("3", banks[0].short.value); assertEquals("—", banks[1].short.value)
            assertFalse(banks[1].short.available); assertEquals(rows[1].status + " · 40 ms ago", banks[1].short.status); assertTrue(banks[1].short.needsDataAttention)
        }
        val unsupported = row(0x08).copy(definition = nativeSignalDefinition(0x08, SignalSupport.UNSUPPORTED), availability = SignalAvailability.NOT_SAMPLED, value = null)
        val cell = mechanicCell("stft-bank-2", listOf(unsupported), listOf(unsupported), layout(listOf(unsupported)), false)
        assertEquals("Unsupported · 40 ms ago", cell.status); assertFalse(cell.available)
    }

    @Test fun hiddenAndUnselectedModulesRemainRecoverableWithoutShowingTheirValues() {
        val rows = listOf(row(0x06, 2.0), row(0x08, 9.0))
        val hidden = layout(rows).update("COMPACT_VALUE:stft-bank-2") { it.copy(visible = false) }
        assertEquals("Hidden · 40 ms ago", mechanicTrimBanks(rows, rows, hidden, false)[1].short.status)
        assertEquals("Not selected for display · 40 ms ago", mechanicTrimBanks(rows, rows.take(1), layout(rows), false)[1].short.status)
        assertEquals("9", mechanicTrimBanks(rows, rows, layout(rows), false)[1].short.value)
    }

    @Test fun sixDefaultRolesNeverInventChannelsAndCustomizationWins() {
        val rows = mechanicDemoRows(DemoScenario.COMPLETE)
        val initial = layout(rows)
        assertEquals(mechanicTopIds, mechanicCardIds(initial, rows))
        val pinned = initial.update("COMPACT_VALUE:maf") { it.copy(pinned = true) }
        assertEquals("maf", mechanicCardIds(pinned, rows).first())
        val hidden = pinned.update("COMPACT_VALUE:rpm") { it.copy(visible = false) }
        assertFalse("rpm" in mechanicCardIds(hidden, rows))
        val stale = rows.map { if (it.definition.id == "coolant") it.copy(availability = SignalAvailability.STALE, value = null) else it }
        assertFalse("coolant" in mechanicCardIds(initial.copy(hideUnavailable = true), stale))
        assertEquals(listOf("rpm"), mechanicCardIds(layout(listOf(row(0x0C))), listOf(row(0x0C))))
    }

    @Test fun defaultDisplaySelectionUsesOnlyTheCatalogAndDoesNotMutateIt() {
        val rows = listOf(row(0x06), row(0x08), row(0x0C))
        val before = rows.toList()
        assertEquals(listOf("rpm", "stft", "stft-bank-2"), mechanicDefaultSelection(rows, emptyList()))
        assertEquals(before, rows)
        val stored = WorkspaceLayoutCodec.encode(layout(rows).withDisplaySelection(rows.map { it.definition.id }, listOf("stft-bank-2")))
        assertEquals(listOf("stft-bank-2"), persistedWorkspacePlotIds(rows, emptyList(), stored, mechanicDefaultSelection(rows, emptyList())))
    }

    @Test fun rpmGaugePreservesExactReadoutAndDoesNotInventAReadingWhenStale() {
        val rpm = row(0x0C, 2345.75)
        assertEquals("2,345.75", mechanicCell("rpm", listOf(rpm), listOf(rpm), layout(listOf(rpm)), false).value)
        assertEquals(2345.75f / 8000f, mechanicRpmGauge(rpm).fraction)
        assertEquals(0f, mechanicRpmGauge(row(0x0C)).fraction)
        assertNull(mechanicRpmGauge(rpm.copy(availability = SignalAvailability.STALE, value = null)).fraction)
        assertNull(mechanicRpmGauge(null).fraction)
        assertTrue(mechanicRpmGauge(row(0x0C, 9001.0)).maximum > 9001)
    }

    @Test fun fuelSystemNumbersAreNotMappedToBanksAndStaleFieldsDoNotLeak() {
        val definition = nativeSignalDefinition(0x03, SignalSupport.SUPPORTED)
        val row = DashboardSignal(definition, SignalAvailability.AVAILABLE,
            SignalValue.Structured(mapOf("system_1" to "closed loop: oxygen-sensor feedback", "system_2" to "engine off")))
        val text = mechanicFuelStatus(DashboardSource.HARDWARE, listOf(row), null)
        assertTrue(text[0].startsWith("Supplied fuel system 1:")); assertTrue(text[1].endsWith("engine off"))
        assertFalse(text.any { it.contains("Bank") })
        val stale = row.copy(availability = SignalAvailability.STALE, value = null)
        assertEquals(listOf("Fuel system: Stale"), mechanicFuelStatus(DashboardSource.HARDWARE, listOf(stale), null))
    }

    @Test fun liveScanNeverMasqueradesAsReplayOrDemoFaultAndFuelData() {
        val live = scan(codes = StoredDtcRead(listOf("P0171"), "7E8"), count = 1,
            rows = listOf(DiagnosticScanRow(3, "Fuel system", null, DecodedReading.Structured(mapOf("system_1" to "closed loop")))))
        for (source in listOf(DashboardSource.CIVIC_REPLAY, DashboardSource.DEMO, DashboardSource.SNAPSHOT)) {
            assertNull(mechanicSourceScan(source, live))
            assertEquals("Code list unknown", mechanicFaultSummary(source, live, 2000).count)
            assertFalse(mechanicFuelStatus(source, emptyList(), live).any { it.contains("closed loop") })
        }
        assertEquals(live, mechanicSourceScan(DashboardSource.HARDWARE, live))
        assertTrue(mechanicFuelStatus(DashboardSource.HARDWARE, emptyList(), live).first().startsWith("Last scan fuel system 1:"))
    }

    @Test fun scanCountsAreNotCodeListsAndEmptyResponsesDoNotDeclareHealth() {
        val counted = mechanicFaultSummary(DashboardSource.HARDWARE, scan(count = 2), 3000)
        assertTrue(counted.count.contains("2 (not a code list)"))
        assertEquals(listOf("Stored: Not captured"), counted.replies)
        assertEquals("Last scan MIL: unknown", counted.mil)
        val empty = mechanicFaultSummary(DashboardSource.HARDWARE, scan(codes = StoredDtcRead(emptyList(), null)), 3000)
        assertEquals(listOf("Stored: No codes returned in this response"), empty.replies)
        assertFalse(empty.toString().contains("healthy", ignoreCase = true))
    }

    @Test fun scanTimeIsExplicitAndFutureOrImportedClockIsUnknown() {
        assertEquals("captured · 2 s since capture", mechanicScanAge(scan(), 3000))
        assertTrue(mechanicScanAge(scan(captured = 4000), 3000).contains("age unknown"))
        assertTrue(mechanicScanAge(scan(DiagnosticScanStatus.IMPORTED), 3000).contains("age unknown"))
        assertEquals("No scan supplied", mechanicScanAge(null, 3000))
    }

    @Test fun retainedReplayLabelNeverAdvertisesVehicleSupport() {
        val replay = row(0x08).copy(retainedReplay = true)
        val cell = mechanicCell(replay.definition.id, listOf(replay), listOf(replay), layout(listOf(replay)), false)
        assertTrue(cell.available); assertEquals("0", cell.value)
        assertTrue(cell.reason.contains("ECU capability unverified"))
    }

    @Test fun densitiesReflowAtOrdinaryTabletPhoneAndLargeTextWidths() {
        assertEquals(6, mechanicCardColumns(1170f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(3, mechanicCardColumns(800f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, mechanicCardColumns(390f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(1, mechanicCardColumns(390f, 2f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, mechanicCardColumns(1536f, 1f, WorkspaceCardDensity.STANDARD))
        assertEquals(1, mechanicCardColumns(1536f, 1f, WorkspaceCardDensity.LARGE))
        assertEquals(1, mechanicCardColumns(Float.NaN, 1f, WorkspaceCardDensity.COMPACT))
    }

    @Test fun syntheticTraceGapsAndPerSignalScalesSurvivePauseAndUnits() {
        val rows = mechanicDemoRows(DemoScenario.TIMED_WORKSPACE)
        assertTrue(rows.all { it.reason == SYNTHETIC_TRACE_REASON })
        for (id in mechanicTopIds + mechanicTrimIds) {
            val row = rows.first { it.definition.id == id }
            assertTrue(row.trend.points.filter { it.atMillis in 35000..38000 }.all { it.value == null })
            val trace = graphFocusTrace(row, rows, true, GraphTimeRange.SIXTY, false)
            assertTrue(trace.segments.size >= 2)
            assertFalse(trace.segments.any { segment -> segment.any { it.atMillis < 35000 } && segment.any { it.atMillis > 38000 } })
        }
        val rpm = graphFocusTrace(rows.first { it.definition.id == "rpm" }, null, false, GraphTimeRange.SIXTY, false)
        val volts = graphFocusTrace(rows.first { it.definition.id == "control-module-voltage" }, null, false, GraphTimeRange.SIXTY, false)
        assertNotEquals(rpm.bounds, volts.bounds)
        assertEquals("°F", rows.first { it.definition.id == "intake-air" }.instrumentUnit(true))
        assertTrue(mechanicDemoRows(DemoScenario.COMPLETE).all { it.trend.points.isEmpty() && it.ageMillis == null })
        assertEquals(demoRows(DemoScenario.MIXED), mechanicDemoRows(DemoScenario.MIXED))
        assertTrue(mechanicDemoRows(DemoScenario.LOAD_ERROR).isEmpty())
    }
    @Test fun adverseSummaryIncludesHiddenSelectedRowsAndKeepsMissingAgeHonest() {
        val rows = listOf(row(0x0C), row(0x05).copy(availability = SignalAvailability.STALE, value = null),
            row(0x06).copy(availability = SignalAvailability.UNAVAILABLE, value = null),
            row(0x07).copy(availability = SignalAvailability.NOT_SAMPLED, value = null),
            row(0x08).copy(availability = SignalAvailability.DISCONNECTED, value = null),
            row(0x09).copy(availability = SignalAvailability.ERROR, value = null))
        val hidden = layout(rows).copy(hideUnavailable = true).update("COMPACT_VALUE:coolant") { it.copy(visible = false) }
        assertEquals(listOf("rpm"), mechanicCardIds(hidden, rows))
        assertEquals(MechanicDataCounts(6, 1, 1, 2, 1, 1), mechanicDataCounts(rows))
        assertFalse(mechanicCell("coolant", rows, rows, hidden, false).needsDataAttention)
        val noTime = rows[1].copy(ageMillis = null)
        assertEquals("Stale", mechanicCell("coolant", listOf(noTime), listOf(noTime), layout(listOf(noTime)), false).status)
        assertEquals(MechanicDataCounts(0, 0, 0, 0, 0, 0), mechanicDataCounts(emptyList()))
    }

    @Test fun provenanceDistinguishesStaticDemoTimedFixtureReplayAndActualAdapterReceipts() {
        assertEquals("Fictional static values · receipt times not supplied", mechanicDataProvenance(DashboardSource.DEMO, mechanicDemoRows(DemoScenario.COMPLETE)))
        assertEquals("Synthetic timed observations · not vehicle receipts", mechanicDataProvenance(DashboardSource.DEMO, mechanicDemoRows(DemoScenario.TIMED_WORKSPACE)))
        assertEquals("Adapter receipt data · ECU sample time unknown", mechanicDataProvenance(DashboardSource.HARDWARE, emptyList()))
        assertEquals("Retained replay · not live · ECU capability unverified", mechanicDataProvenance(DashboardSource.CIVIC_REPLAY, emptyList()))
        assertEquals("Historical snapshot · not live", mechanicDataProvenance(DashboardSource.SNAPSHOT, emptyList()))
        assertEquals("Scripted fictional observations · not a vehicle", mechanicDataProvenance(DashboardSource.SCRIPTED, emptyList()))
    }

    @Test fun mixedFixtureResetCountsEverySelectedFailedChannelBeforeHiding() {
        val rows = mechanicDemoRows(DemoScenario.MIXED)
        val selected = workspaceResetSelection(rows, listOf("rpm", "coolant", "speed", "runtime"), true)
        assertEquals(listOf("rpm", "coolant", "speed", "runtime"), selected)
        assertEquals(MechanicDataCounts(4, 0, 1, 2, 0, 1), mechanicDataCounts(rows.filter { it.definition.id in selected }))
    }

}
