package org.openwrench.frontend

import kotlin.test.*
import org.junit.Test
import org.openwrench.core.SignalValue

class TemperatureOverlayTest {
    private val rows = syntheticWorkspaceFixture()
    private val modules = overviewTemperatureModules(GraphFirstLayout.fromSensorIds(rows.map { it.definition.id })
        .ordered(WorkspaceModuleKind.GRAPH), rows)
    private fun overlay(rows: List<DashboardSignal> = this.rows, modules: List<WorkspaceModule> = this.modules,
        frozen: List<DashboardSignal>? = null, imperial: Boolean = false, fit: Boolean = false) =
        workspaceTemperatureOverlay(rows, modules, frozen, imperial, GraphTimeRange.SIXTY, fit)

    @Test fun threeChannelsShareOneAxisWithoutChangingSamplesOrGapBoundaries() {
        val result = overlay()
        assertEquals(listOf("coolant", "intake-air", "ambient-air-temperature"), result.layers.map { it.id })
        assertTrue(result.compatibleClock)
        assertEquals(60000L, result.axis.nowMillis)
        result.layers.forEach { layer ->
            val original = rows.first { it.definition.id == layer.id }.trend.segments(60000)
            assertEquals(original, layer.trace.segments)
            assertEquals(57, original.sumOf { it.size })
            assertEquals(2, original.size)
            assertFalse(original.flatten().any { it.atMillis in 35000..38000 })
        }
    }

    @Test fun unequalWindowClocksNeverProduceAnApparentlyAlignedOverlay() {
        val shifted = rows.map { if (it.definition.id == "coolant") it.copy(trendNowMillis = 61000) else it }
        val result = overlay(shifted)
        assertFalse(result.compatibleClock)
        assertTrue(result.layers.all { it.trace.segments.isEmpty() })
        assertTrue(overlay(emptyList()).layers.isEmpty())
    }

    @Test fun hiddenCollapsedAndWrongUnitModulesCannotAddTraces() {
        val changed = modules.mapIndexed { index, m -> when (index) {
            0 -> m.copy(visible = false)
            1 -> m.copy(expanded = false)
            else -> m
        } }
        assertEquals(listOf("ambient-air-temperature"), overlay(modules = changed).layers.map { it.id })
        assertTrue(overlay(rows.map { it.copy(definition = it.definition.copy(unit = "rpm")) }).layers.isEmpty())
    }

    @Test fun unitsAndFitBoundsApplyToEveryChannelAndRealZeroRemainsAReceipt() {
        val metric = overlay(fit = true); val fahrenheit = overlay(imperial = true, fit = true)
        metric.layers.zip(fahrenheit.layers).forEach { (c, f) ->
            c.trace.segments.flatten().zip(f.trace.segments.flatten()).forEach { (cp, fp) ->
                assertEquals(cp.atMillis, fp.atMillis)
                assertEquals(checkNotNull(cp.value) * 1.8 + 32, checkNotNull(fp.value), .00001)
            }
        }
        val values = fahrenheit.layers.flatMap { it.trace.segments.flatten() }.map { checkNotNull(it.value) }
        assertTrue(fahrenheit.axis.bounds.first < values.min())
        assertTrue(fahrenheit.axis.bounds.second > values.max())
        val coolant = rows.first { it.definition.id == "coolant" }.copy(value = SignalValue.Number(0.0),
            trend = SignalTrend(listOf(TrendPoint(0, 0.0, "zero", 3000))), trendNowMillis = 0)
        assertEquals(32.0, overlay(listOf(coolant), imperial = true).layers.single().trace.segments.single().single().value)
    }

    @Test fun pauseUsesFrozenHistoriesAndNeverAddsANewChannelOrCurrentValue() {
        val frozen = rows.filterNot { it.definition.id == "intake-air" }
        val current = rows.map { it.copy(value = null, availability = SignalAvailability.STALE,
            trend = SignalTrend(), trendNowMillis = 120000) }
        val result = overlay(current, frozen = frozen)
        assertTrue(result.compatibleClock)
        assertEquals(60000L, result.axis.nowMillis)
        assertTrue(result.layers.first { it.id == "intake-air" }.trace.segments.isEmpty())
        assertEquals(57, result.layers.first { it.id == "coolant" }.trace.segments.flatten().size)
        assertFalse(liveValuePresentation(current.first { it.definition.id == "coolant" }, false).available)
        assertTrue(overlay(current).layers.all { it.trace.segments.isEmpty() })
    }
}
