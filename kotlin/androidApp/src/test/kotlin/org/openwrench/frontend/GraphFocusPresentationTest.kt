package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class GraphFocusPresentationTest {
    @Test fun compactPlotsStaySmallAndExpandKeepsTheFullHeight() {
        assertEquals(72f, graphFocusPlotHeightDp(expanded = false, fontScale = 1f))
        assertEquals(280f, graphFocusPlotHeightDp(expanded = true, fontScale = 1f))
    }

    @Test fun bothPlotSizesGrowWithTextWithoutShrinkingBelowTheirBaseline() {
        assertEquals(144f, graphFocusPlotHeightDp(expanded = false, fontScale = 2f))
        assertEquals(560f, graphFocusPlotHeightDp(expanded = true, fontScale = 2f))
        assertEquals(72f, graphFocusPlotHeightDp(expanded = false, fontScale = .85f))
        assertEquals(280f, graphFocusPlotHeightDp(expanded = true, fontScale = .85f))
    }

    private val rpm = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED, "010C")
    private fun row(definition: SignalDefinition = rpm, now: Long = 60_000) = DashboardSignal(
        definition, SignalAvailability.AVAILABLE, SignalValue.Number(1200.0),
        trend = SignalTrend(points = listOf(
            TrendPoint(1_000, 6000.0, "first", 6_000),
            TrendPoint(40_000, 1100.0, "second", 45_000),
            TrendPoint(58_000, 1000.0, "third", 63_000),
            TrendPoint(59_000, 1200.0, "fourth", 64_000),
        )), trendNowMillis = now,
    )

    @Test fun rangeFiltersReceiptsWithoutJoiningGapsOrInventingPoints() {
        val original = row()
        val all = graphFocusTrace(original, null, false, GraphTimeRange.SIXTY, false)
        assertEquals(listOf(1, 1, 2), all.segments.map { it.size })
        val short = graphFocusTrace(original, null, false, GraphTimeRange.FIFTEEN, false)
        assertEquals(listOf(58_000L, 59_000L), short.segments.single().map { it.atMillis })
        assertEquals(4, original.trend.points.size)
        assertEquals(60_000, short.nowMillis)
    }

    @Test fun pausedTraceAndScaleRemainFrozenWhileCurrentReadingChanges() {
        val original = row()
        val updated = original.copy(value = SignalValue.Number(9000.0), trendNowMillis = 80_000)
        assertEquals(graphFocusTrace(original, null, false, GraphTimeRange.SIXTY, true),
            graphFocusTrace(updated, listOf(original), false, GraphTimeRange.SIXTY, true))
        assertEquals("9000", updated.instrumentValue(false))
        assertEquals("—", updated.copy(availability = SignalAvailability.STALE, value = null).instrumentValue(false))
    }

    @Test fun newPidAfterPauseDoesNotFallBackToLiveTrace() {
        val original = row()
        val added = row(rpm.copy(id = "added"))
        assertTrue(graphFocusTrace(added, listOf(original), false, GraphTimeRange.SIXTY, false).segments.isEmpty())
        assertTrue(graphFocusTrace(added, emptyList(), false, GraphTimeRange.SIXTY, true).segments.isEmpty())
    }

    @Test fun fitScaleUsesOnlyVisibleObservationsAndLeavesDefaultsAvailable() {
        val original = row()
        val fitted = graphFocusTrace(original, null, false, GraphTimeRange.FIFTEEN, true)
        assertEquals(980.0 to 1220.0, fitted.bounds)
        val standard = graphFocusTrace(original, null, false, GraphTimeRange.FIFTEEN, false)
        assertEquals(0.0 to 6000.0, standard.bounds)
    }

    @Test fun fitConvertsValuesAndBoundsTogetherAndHandlesSingleObservation() {
        val temperature = row(rpm.copy(id = "coolant", unit = "°C")).copy(
            value = SignalValue.Number(20.0),
            trend = SignalTrend(points = listOf(TrendPoint(59_000, 20.0, "temperature", 64_000))),
        )
        val trace = graphFocusTrace(temperature, null, true, GraphTimeRange.THIRTY, true)
        assertEquals(68.0, trace.segments.single().single().value)
        assertEquals(67.32, trace.bounds.first, .00001)
        assertEquals(68.68, trace.bounds.second, .00001)
        assertEquals(20.0, temperature.trend.points.single().value)
    }

    @Test fun noHistoryRemainsEmptyAndDoesNotTurnCurrentValueIntoAChart() {
        val current = row().copy(trend = SignalTrend())
        GraphTimeRange.entries.forEach { range ->
            assertTrue(graphFocusTrace(current, null, false, range, true).segments.isEmpty())
        }
        assertTrue(graphFocusTrace(row(now = 121_000), null, false, GraphTimeRange.SIXTY, true).segments.isEmpty())
    }

    @Test fun captionsKeepSourceAndPauseMeaningExplicit() {
        assertTrue(graphFocusCaption(DashboardSource.DEMO, false).contains("Simulated"))
        assertTrue(graphFocusCaption(DashboardSource.SNAPSHOT, false).contains("Historical"))
        assertTrue(graphFocusCaption(DashboardSource.CIVIC_REPLAY, false).contains("Recorded"))
        assertTrue(graphFocusCaption(DashboardSource.HARDWARE, true).contains("acquisition unchanged"))
    }
}
