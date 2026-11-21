package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

class FigmaWorkspaceTest {
    @Test fun unavailableOrHiddenDefaultCardsCannotReappearInOverview() {
        val base = syntheticWorkspaceFixture().filter { it.definition.id in overviewRoles.map { role -> role.id } }
        val defaults = GraphFirstLayout.fromSensorIds(base.map { it.definition.id })
        for (state in SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }) {
            val rows = base.map { if (it.definition.id == "coolant") it.copy(value = null, availability = state) else it }
            val layout = defaults.copy(hideUnavailable = true)
            assertEquals(listOf("rpm", "speed", "control-module-voltage"), referenceOverviewRoles(layout, defaults, rows).map { it.id })
            val hidden = layout.update("COMPACT_VALUE:coolant") { it.copy(visible = false) }
            assertFalse(referenceOverviewRoles(hidden, defaults, rows).any { it.id == "coolant" })
            assertTrue(referenceOverviewRoles(defaults, defaults, rows).any { it.id == "coolant" })
        }
        assertTrue(referenceOverviewRoles(defaults, defaults, emptyList()).isEmpty())
        assertEquals(listOf("speed"), referenceOverviewRoles(defaults, defaults, base.filter { it.definition.id == "speed" }).map { it.id })
    }

    @Test fun nonDefaultSelectedPidsFillOnlyEligibleOverviewSlots() {
        val rows = syntheticWorkspaceFixture()
        val defaults = GraphFirstLayout.fromSensorIds(rows.map { it.definition.id })
        for (selected in listOf(listOf("maf"), listOf("throttle"), listOf("rpm", "maf", "throttle"))) {
            val shown = referenceOverviewRoles(defaults, defaults, rows.filter { it.definition.id in selected })
            assertEquals(selected.toSet(), shown.map { it.id }.toSet())
            assertEquals(shown.size, shown.map { it.id }.distinct().size)
        }
        val hidden = defaults.update("COMPACT_VALUE:maf") { it.copy(visible = false) }
        assertTrue(referenceOverviewRoles(hidden, defaults, rows.filter { it.definition.id == "maf" }).isEmpty())
    }

    @Test fun valuesHeaderAndRowsSharePaddedWidthAndToolbarUsesFigmaBoundary() {
        assertFalse(workspaceValuesShowLastSample(640f, 1f))
        assertFalse(workspaceValuesShowLastSample(665f, 1f))
        assertTrue(workspaceValuesShowLastSample(666f, 1f))
        assertFalse(workspaceValuesShowLastSample(1280f, 2f))
        assertFalse(workspaceValuesShowLastSample(2560f, 2f))
        assertTrue(workspaceValuesShowLastSample(1118f, 1f))
        assertFalse(workspaceValuesShowLastSample(Float.NaN, 1f))
        for (width in listOf(640f, 641f, 900f)) assertTrue(workspaceToolbarCompactActions(width, 1f))
        assertFalse(workspaceToolbarCompactActions(901f, 1f))
        assertTrue(workspaceToolbarCompactActions(1800f, 2f))
        assertFalse(workspaceToolbarCompactActions(1802f, 2f))
    }

    @Test fun appliedPidSelectionPersistsWithLayoutAndRetainsDormantSourceChoices() {
        val rows = syntheticWorkspaceFixture()
        val ids = rows.map { it.definition.id }
        val original = GraphFirstLayout.fromSensorIds(ids).withDisplaySelection(ids, ids - "rpm")
            .copy(cardDensity = WorkspaceCardDensity.LARGE)
        val raw = WorkspaceLayoutCodec.encode(original)
        assertFalse("rpm" in persistedWorkspacePlotIds(rows, listOf("rpm"), raw))
        val speedOnly = rows.filter { it.definition.id == "speed" }
        val partial = WorkspaceLayoutCodec.decode(raw, listOf("speed"))
        val edited = partial.layout.withDisplaySelection(listOf("speed"), emptyList())
        val merged = mergeWorkspaceLayout(partial.stored, edited)
        val saved = WorkspaceLayoutCodec.encode(merged)
        val restored = persistedWorkspacePlotIds(rows, listOf("rpm", "speed"), saved)
        assertFalse("rpm" in restored); assertFalse("speed" in restored); assertTrue("coolant" in restored)
        assertTrue(persistedWorkspacePlotIds(speedOnly, emptyList(), saved).isEmpty())
        assertEquals(WorkspaceCardDensity.LARGE, WorkspaceLayoutCodec.decode(saved, ids).layout.cardDensity)
        assertEquals(defaultWorkspacePlotIds(rows, emptyList()).toSet(), persistedWorkspacePlotIds(rows, emptyList(), null).toSet())
        assertEquals(raw, WorkspaceLayoutCodec.encode(original)) // Cancelled drafts never mutate saved input.
    }

    @Test fun batchedNativeUpdatesCannotBecomeFakeReceiptPairs() {
        val state = NativeUiState(connected = true, liveRunning = true, supported = setOf(0x0C, 0x11),
            watched = mapOf(0x0C to WatchRate.HIGH, 0x11 to WatchRate.HIGH), readings = mapOf(
                0x0C to TimedReading(NativeReading(0x0C, DecodedReading.Scalar(1500.0)), 1000),
                0x11 to TimedReading(NativeReading(0x11, DecodedReading.Scalar(25.0)), 1200)))
        val rows = NativeTrendHistory { 1500 }.present(state)
        val rpm = rows.first { it.definition.id == "rpm" }; val throttle = rows.first { it.definition.id == "throttle" }
        assertEquals(rpm.trend.points.single().atMillis, throttle.trend.points.single().atMillis)
        assertNotEquals(rpm.trend.points.single().observedAt, throttle.trend.points.single().observedAt)
        for (source in listOf(DashboardSource.HARDWARE, DashboardSource.SNAPSHOT, DashboardSource.SCRIPTED, DashboardSource.DEMO))
            assertTrue(workspaceSourceScatter(source, rows, null, false, GraphTimeRange.SIXTY, false).isEmpty())
        val fixture = syntheticWorkspaceFixture()
        assertEquals(57, workspaceSourceScatter(DashboardSource.DEMO, fixture, null, false, GraphTimeRange.SIXTY, false).size)
        assertTrue(workspaceSourceScatter(DashboardSource.HARDWARE, fixture, null, false, GraphTimeRange.SIXTY, false).isEmpty())
        assertEquals(57, workspaceSourceScatter(DashboardSource.CIVIC_REPLAY, fixture, fixture, true, GraphTimeRange.SIXTY, false).size)
    }

    @Test fun scatterUsesUniqueExactReceiptPairsAndPreservesMissingData() {
        fun trace(now: Long, vararg values: Pair<Long, Double>) = InstrumentTracePresentation(now,
            values.map { (at, value) -> listOf(TrendPoint(at, value, null, at + 3000)) }, 0.0 to 4000.0)
        val rpm = trace(60000, 0L to 0.0, 1000L to 900.0, 2000L to 1500.0, 60000L to 2200.0)
        val throttle = trace(60000, 0L to 0.0, 1001L to 10.0, 60000L to 30.0)
        assertEquals(listOf(WorkspaceScatterPoint(0, 0.0, 0.0), WorkspaceScatterPoint(60000, 30.0, 2200.0)), workspaceScatterPoints(rpm, throttle, 60000))
        assertEquals(listOf(WorkspaceScatterPoint(60000, 30.0, 2200.0)), workspaceScatterPoints(rpm, throttle, 15000))
        assertTrue(workspaceScatterPoints(rpm, null, 60000).isEmpty())
        assertTrue(workspaceScatterPoints(rpm, throttle.copy(nowMillis = 130000), 60000).isEmpty())
        val duplicates = throttle.copy(segments = throttle.segments + listOf(throttle.segments.last()))
        assertEquals(listOf(WorkspaceScatterPoint(0, 0.0, 0.0)), workspaceScatterPoints(rpm, duplicates, 60000))
        assertTrue(workspaceScatterPoints(rpm, trace(60000, 60000L to 101.0), 60000).isEmpty())
    }
    @Test fun scatterFixtureGapsPauseAndUnitsRemainRealReceipts() {
        val rows = syntheticWorkspaceFixture()
        fun points(frozen: List<DashboardSignal>?, imperial: Boolean) = workspaceScatterPoints(
            graphFocusTrace(rows.first { it.definition.id == "rpm" }, frozen, imperial, GraphTimeRange.SIXTY, false),
            graphFocusTrace(rows.first { it.definition.id == "throttle" }, frozen, imperial, GraphTimeRange.SIXTY, false), 60000)
        assertEquals(57, points(null, false).size)
        assertFalse(points(null, false).any { it.atMillis in 35000..38000 })
        assertEquals(points(null, false), points(rows, true))
        assertTrue(points(rows.filterNot { it.definition.id == "throttle" }, false).isEmpty())
        assertEquals(rows.size, defaultWorkspacePlotIds(rows, emptyList()).size)
        assertTrue(defaultWorkspacePlotIds(emptyList(), listOf("rpm")).isEmpty())
    }

    @Test fun temperatureHeightPresetsRemainMonotonicAndScaleForAxisLabels() {
        assertEquals(listOf(60f, 70f, 145f), WorkspaceGraphHeight.entries.map { temperaturePlotHeightDp(it, false, 1f) })
        assertEquals(140f, temperaturePlotHeightDp(WorkspaceGraphHeight.STANDARD, false, 2f))
        assertEquals(280f, temperaturePlotHeightDp(WorkspaceGraphHeight.COMPACT, true, 1f))
    }
    @Test fun replayLabelsNeverAssertHardwareCapability() {
        val row = syntheticWorkspaceFixture().first()
        assertEquals("Retained replay channel · ECU capability unverified", workspaceSupportLabel(row, true))
        assertEquals("supported", workspaceSupportLabel(row, false))
    }
    @Test fun tabletGridAndCardDensityReflowWithoutAssumingPhysicalPixelDensity() {
        assertEquals(3, referenceWorkspaceColumns(944f, 1f))
        assertEquals(2, referenceWorkspaceColumns(900f, 1f))
        assertEquals(3, referenceWorkspaceColumns(901f, 1f))
        assertEquals(2, referenceWorkspaceColumns(641f, 1f))
        assertEquals(1, referenceWorkspaceColumns(640f, 1f))
        assertEquals(2, referenceWorkspaceColumns(787f, 1f))
        assertEquals(1, referenceWorkspaceColumns(590f, 1f))
        assertEquals(1, referenceWorkspaceColumns(960f, 2f))
        assertEquals(4, workspaceCardColumns(950f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, workspaceCardColumns(640f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(4, workspaceCardColumns(641f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, workspaceCardColumns(1280f, 2f, WorkspaceCardDensity.COMPACT))
        assertEquals(4, workspaceCardColumns(1282f, 2f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, workspaceCardColumns(950f, 1f, WorkspaceCardDensity.STANDARD))
        assertEquals(1, workspaceCardColumns(950f, 1f, WorkspaceCardDensity.LARGE))
        assertEquals(2, workspaceCardColumns(590f, 1f, WorkspaceCardDensity.COMPACT))
        assertEquals(1, workspaceCardColumns(390f, 2f, WorkspaceCardDensity.COMPACT))
        assertEquals(2, workspaceGraphColumns(1170f, 1f, false))
        assertEquals(1, workspaceGraphColumns(1170f, 1f, true))
        assertEquals(1, workspaceGraphColumns(590f, 1f, false))
    }
    @Test fun densityAndAvailabilityPreferencesSurviveSourceSwitchWithoutDroppingDormantModules() {
        val initial = GraphFirstLayout.fromSensorIds(listOf("rpm", "coolant", "speed"))
            .copy(cardDensity = WorkspaceCardDensity.LARGE, hideUnavailable = true)
            .update("GRAPH:coolant") { it.copy(pinned = true, height = WorkspaceGraphHeight.TALL) }
        val saved = WorkspaceLayoutCodec.encode(initial)
        val partial = WorkspaceLayoutCodec.decode(saved, listOf("speed"))
        val edited = partial.layout.copy(cardDensity = WorkspaceCardDensity.STANDARD)
        val merged = mergeWorkspaceLayout(partial.stored, edited)
        val restored = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(merged), listOf("rpm", "coolant", "speed")).layout
        assertEquals(WorkspaceCardDensity.STANDARD, restored.cardDensity)
        assertTrue(restored.hideUnavailable)
        assertEquals(initial.modules.first { it.key == "GRAPH:coolant" }, restored.modules.first { it.key == "GRAPH:coolant" })
        assertEquals(WorkspaceCardDensity.COMPACT, mergeWorkspaceLayout(restored, GraphFirstLayout.fromSensorIds(listOf("speed")), true).cardDensity)
    }
    @Test fun oldLayoutDefaultsRemainReadableAndBadNewPreferencesFallBackSafely() {
        val layout = GraphFirstLayout.fromSensorIds(listOf("rpm"))
        val raw = WorkspaceLayoutCodec.encode(layout)
        val old = raw.replace("\"cardDensity\":\"COMPACT\",", "").replace("\"hideUnavailable\":false,", "")
        assertEquals(layout, WorkspaceLayoutCodec.decode(old, listOf("rpm")).layout)
        for (invalid in listOf(raw.replace("COMPACT\"", "HUGE\""), raw.replace("\"hideUnavailable\":false", "\"hideUnavailable\":\"false\"")))
            assertNotNull(WorkspaceLayoutCodec.decode(invalid, listOf("rpm")).notice)
    }
    @Test fun everySelectedGraphSurvivesTemperaturePartitionAndPinnedTemperatureDoesNotDuplicate() {
        val seed = syntheticWorkspaceFixture().first { it.definition.id == "coolant" }
        val rows = (0..5).map { i -> seed.copy(definition = seed.definition.copy(id = "temperature-$i")) }
        val modules = GraphFirstLayout.fromSensorIds(rows.map { it.definition.id }).ordered(WorkspaceModuleKind.GRAPH)
        val displayed = modules.take(1) + overviewTemperatureModules(modules, rows) + overviewRemainingGraphs(modules, rows)
        assertEquals(modules, displayed)
        assertEquals(displayed.size, displayed.map { it.key }.distinct().size)
        assertEquals(2, overviewRemainingGraphs(modules, rows).size)
    }
    @Test fun integrityBinsCountActualReceiptsIncludingRealZeroWithoutImplyingExpectedSamples() {
        val points = listOf(TrendPoint(0, 0.0, null, 1000), TrendPoint(15000, 3.0, null, 16000), TrendPoint(60000, 4.0, null, 61000))
        val trace = InstrumentTracePresentation(60000, points.map { listOf(it) }, 0.0 to 4.0)
        assertEquals(listOf(1, 0, 1, 0, 0, 0, 0, 1), receiptBins(trace, 60000))
        assertEquals(List(8) { 0 }, receiptBins(trace.copy(segments = emptyList()), 60000))
        val shifted = trace.copy(nowMillis = 160000, segments = trace.segments.map { segment -> segment.map { it.copy(atMillis = it.atMillis + 100000) } })
        assertEquals(receiptBins(trace, 60000), receiptBins(shifted, 60000))
    }
}
