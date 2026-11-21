package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.core.SignalValue
import org.openwrench.frontend.android.DemoScenario
import org.openwrench.frontend.android.demoRows

class ReferenceWorkspaceTest {
    @Test fun measuredGridWidthsStayNonnegativeThroughRetainedResize() {
        for (width in 0..1200) for (columns in 1..3) for (scale in listOf(1, 2, 3)) {
            val sizes = referenceGridWidths(width, columns, 8 * scale, 176 * scale)
            assertTrue(listOf(sizes.gap, sizes.main, sizes.left, sizes.middle, sizes.rail).all { it in 0..width })
            if (sizes.columns > 1) assertEquals(sizes.main, sizes.left + sizes.gap + sizes.middle)
            if (sizes.columns == 3) assertEquals(width, sizes.main + sizes.gap + sizes.rail)
            if (sizes.columns == 2) assertEquals(width, sizes.main)
        }
        assertEquals(1, referenceGridWidths(0, 3, 8, 176).columns)
        assertEquals(1, referenceGridWidths(7, 3, 8, 176).columns)
        assertEquals(2, referenceGridWidths(180, 3, 8, 176).columns)
    }

    @Test fun ordinaryReferenceGridGeometryIsUnchanged() {
        assertEquals(ReferenceGridWidths(3, 8, 1078, 594, 476, 176), referenceGridWidths(1262, 3, 8, 176))
        assertEquals(ReferenceGridWidths(2, 8, 782, 387, 387, 387), referenceGridWidths(782, 2, 8, 176))
        assertEquals(referenceGridWidths(0, 3, 8, 176), referenceGridWidths(-1, 3, 8, 176))
    }

    @Test fun wideCompositionRequiresLegibleFourCardsAndRail() {
        assertTrue(referenceWorkspaceWide(1176f, 1f))
        assertTrue(referenceWorkspaceWide(1432f, 1.2f))
        assertFalse(referenceWorkspaceWide(780f, 1f))
        assertEquals(2, referenceWorkspaceColumns(800f - 18, 1f))
        for (viewport in listOf(960f, 1024f)) assertEquals(3, referenceWorkspaceColumns(viewport - 18f, 1f))
        assertEquals(1, referenceWorkspaceColumns(1176f, 2f))
        assertEquals(1, referenceWorkspaceColumns(366f, 1f))
        assertFalse(referenceWorkspaceWide(1176f, 2f))
        assertFalse(referenceWorkspaceWide(Float.NaN, 1f))
        assertFalse(referenceWorkspaceWide(Float.POSITIVE_INFINITY, 1f))
    }
    @Test fun referenceRolesMatchTheVerifiedFigmaSite() {
        assertEquals(listOf("rpm", "coolant", "speed", "control-module-voltage"), overviewRoles.map { it.id })
        val catalog = demoDashboardSignals().map { it.definition.id }
        val layout = GraphFirstLayout.fromSensorIds(catalog)
        val roles = referenceOverviewRoles(layout, layout, demoDashboardSignals())
        assertEquals(overviewRoles.filter { it.id in catalog }, roles.take(3))
        assertTrue(roles.all { it.id in catalog })
        assertFalse(roles.any { it.id == "control-module-voltage" })
        assertFalse("fuel-level" in catalog)
        assertFalse(layout.modules.any { it.sensorId == "fuel-level" })
    }
    @Test fun nonReferenceCardsCanPinOrMoveBeforeRpmAcrossOverviewBoundary() {
        val rows = syntheticWorkspaceFixture()
        val ids = rows.map { it.definition.id }
        val defaults = GraphFirstLayout.fromSensorIds(ids)
        val pinned = defaults.update("COMPACT_VALUE:maf") { it.copy(pinned = true) }
        assertEquals("maf", referenceOverviewRoles(pinned, defaults, rows).first().id)
        var moved = defaults
        repeat(3) { moved = moved.move("COMPACT_VALUE:speed", -1) }
        assertEquals("speed", referenceOverviewRoles(moved, defaults, rows).first().id)
        val reloaded = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encode(moved), ids).layout
        assertEquals(referenceOverviewRoles(moved, defaults, rows), referenceOverviewRoles(reloaded, defaults, rows))
        assertEquals(overviewRoles, referenceOverviewRoles(defaults, defaults, rows))
    }
    @Test fun hidingCardsRecoversOtherVisibleCardsAndAllHiddenHasNoFakeSlots() {
        val rows = syntheticWorkspaceFixture()
        val defaults = GraphFirstLayout.fromSensorIds(rows.map { it.definition.id })
        val hidden = defaults.update("COMPACT_VALUE:rpm") { it.copy(visible = false) }
        assertFalse(referenceOverviewRoles(hidden, defaults, rows).any { it.id == "rpm" })
        val allHidden = defaults.copy(modules = defaults.modules.map { if (it.kind == WorkspaceModuleKind.COMPACT_VALUE) it.copy(visible = false) else it })
        assertTrue(referenceOverviewRoles(allHidden, defaults, rows).isEmpty())
        assertEquals(defaults.ordered(WorkspaceModuleKind.GRAPH), allHidden.ordered(WorkspaceModuleKind.GRAPH))
    }
    @Test fun actualReceiptSummaryCountsSegmentsAndRealZeroWithoutInventingQuality() {
        val trace = InstrumentTracePresentation(60000, listOf(listOf(TrendPoint(100, 0.0, null, 300), TrendPoint(200, 2.0, null, 400)),
            listOf(TrendPoint(500, 3.0, null, 700))), 0.0 to 3.0)
        assertEquals(ReceiptSummary(3, 2, 0.0, 3.0), receiptSummary(trace))
        assertEquals(ReceiptSummary(0, 0, null, null), receiptSummary(trace.copy(segments = emptyList())))
    }
    @Test fun overviewHeightsAreBoundedAndExpansionRemainsDistinct() {
        val heights = WorkspaceGraphHeight.entries.map { overviewPlotHeightDp(it, false) }
        assertEquals(listOf(80f, 120f, 220f), heights)
        assertEquals(280f, overviewPlotHeightDp(WorkspaceGraphHeight.COMPACT, true))
        assertEquals(180f, workspaceModuleHeightDp(WorkspaceGraphHeight.STANDARD, false, 1f))
    }
    @Test fun timedFixtureIsExplicitBoundedAndDoesNotChangeDefaultStaticDemo() {
        val rows = demoRows(DemoScenario.TIMED_WORKSPACE)
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertEquals(SYNTHETIC_TRACE_REASON, row.reason)
            assertEquals(61, row.trend.points.size)
            assertEquals(60000L, row.trendNowMillis)
            assertEquals(0L, row.trend.points.first().atMillis)
            assertTrue(row.trend.points.zipWithNext().all { (a, b) -> b.atMillis - a.atMillis == 1000L })
            assertEquals(4, row.trend.points.count { it.value == null })
            assertTrue(row.trend.points.mapNotNull { it.value }.all { it.isFinite() })
            assertEquals(2, row.trend.segments(60000).size)
            assertEquals(57, receiptSummary(graphFocusTrace(row, null, false, GraphTimeRange.SIXTY, false)).count)
        }
        assertFalse(rows.any { it.definition.id == "fuel-level" })
        assertTrue(demoRows(DemoScenario.COMPLETE).all { it.trend.points.isEmpty() })
        assertTrue(demoRows(DemoScenario.LOAD_ERROR).isEmpty())
    }
    @Test fun fixtureUsesExistingPidIdentitiesOnly() {
        syntheticWorkspaceFixture().forEach { row ->
            assertEquals(row.definition.id, nativeSignalIds[row.definition.pid!!.takeLast(2).toInt(16)])
        }
    }
    @Test fun pausePreservesReceiptSummaryAndNewPidDoesNotAcquireHistory() {
        val old = syntheticWorkspaceFixture()
        val current = old.first().copy(trend = SignalTrend(), value = SignalValue.Number(0.0))
        val frozen = graphFocusTrace(current, old, false, GraphTimeRange.SIXTY, false)
        assertEquals(57, receiptSummary(frozen).count)
        assertEquals("0", liveValuePresentation(current, false).value)
        assertEquals(0, receiptSummary(graphFocusTrace(old.last(), old.dropLast(1), false, GraphTimeRange.SIXTY, false)).count)
    }
    @Test fun unitsConvertReceiptRangesAndStaleCurrentValuesRemainUnavailable() {
        val coolant = syntheticWorkspaceFixture().first { it.definition.id == "coolant" }
        val metric = receiptSummary(graphFocusTrace(coolant, null, false, GraphTimeRange.SIXTY, true))
        val imperial = receiptSummary(graphFocusTrace(coolant, null, true, GraphTimeRange.SIXTY, true))
        assertEquals(metric.minimum!! * 1.8 + 32, imperial.minimum!!, .0001)
        val stale = coolant.copy(value = null, availability = SignalAvailability.STALE)
        assertFalse(liveValuePresentation(stale, false).available)
        assertEquals("Stale", liveValuePresentation(stale, false).status)
        assertEquals(57, receiptSummary(graphFocusTrace(stale, null, false, GraphTimeRange.SIXTY, false)).count)
    }
    @Test fun overviewAndSupplementalReadoutsKeepEveryVisiblePidWithoutDuplicates() {
        val rows = syntheticWorkspaceFixture()
        val ids = rows.map { it.definition.id }
        val defaults = GraphFirstLayout.fromSensorIds((overviewRoles.map { it.id } + ids).distinct().filter { it in ids })
        for (layout in listOf(defaults, defaults.update("COMPACT_VALUE:maf") { it.copy(pinned = true) },
            defaults.update("COMPACT_VALUE:speed") { it.copy(visible = false) })) {
            val visible = layout.visible(WorkspaceModuleKind.COMPACT_VALUE, rows.map { it.definition.id })
            val roles = referenceOverviewRoles(layout, defaults, rows)
            val top = roles.map { it.id }.filter { id -> visible.any { it.sensorId == id } }
            val rest = referenceSupplementalModules(visible, roles).map { it.sensorId }
            assertEquals(visible.map { it.sensorId }, top + rest)
            assertEquals((top + rest).distinct(), top + rest)
        }
    }
    @Test fun supplementalPanelBoundDoesNotDropOverflowOrCollapsePreferences() {
        val rows = syntheticWorkspaceFixture()
        val ids = rows.map { it.definition.id } + (1..30).map { "extra-$it" }
        val layout = GraphFirstLayout.fromSensorIds(ids).update("COMPACT_VALUE:maf") { it.copy(expanded = false) }
        val visible = layout.visible(WorkspaceModuleKind.COMPACT_VALUE, ids)
        val rest = referenceSupplementalModules(visible, overviewRoles)
        assertEquals(rest, rest.take(8) + rest.drop(8))
        assertFalse(rest.first { it.sensorId == "maf" }.expanded)
        assertEquals(visible.size, visible.count { it.sensorId in overviewRoles.map { role -> role.id } } + rest.size)
    }

}
