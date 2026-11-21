package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.SignalValue

class DenseReplayValuesTest {
    private fun rows(count: Int = 55) = List(count) { index ->
        val sample = syntheticWorkspaceFixture().first()
        sample.copy(definition = sample.definition.copy(id = "retained-$index", pid = "01${index.toString(16).uppercase().padStart(2, '0')}"),
            retainedReplay = true)
    }

    @Test fun tabletHasSixColumnsAndTwentyFourValuesInFourRowsWithoutChangingOrder() {
        val selected = rows()
        assertEquals(6, denseReplayValueColumns(1190f, 1f, selected))
        assertEquals(selected.take(24), selected.chunked(6).take(4).flatten())
        assertEquals(4, denseReplayValueColumns(782f, 1f, selected))
        assertEquals(4, denseReplayValueColumns(1190f, 1.5f, selected))
        assertEquals(selected, selected.chunked(6).flatten())
    }

    @Test fun narrowLargeFontSmallSelectionsAndNonReplayKeepReadableTable() {
        for ((width, scale) in listOf(372f to 1f, 1190f to 2f, 0f to 1f, Float.NaN to 1f,
            1190f to Float.NaN, 1190f to 0f)) assertEquals(0, denseReplayValueColumns(width, scale, rows()))
        assertEquals(0, denseReplayValueColumns(1190f, 1f, rows(23)))
        assertEquals(0, denseReplayValueColumns(1190f, 1f, rows().map { it.copy(retainedReplay = false) }))
    }

    @Test fun tilesRetainIdentityUnitsMissingStatesZeroAndReplayProvenance() {
        val row = rows(1).single().copy(value = SignalValue.Number(0.0), availability = SignalAvailability.AVAILABLE)
        val before = row.copy()
        val zero = denseReplayValueDescription(row, false)
        assertContains(zero, "0 rpm")
        assertContains(zero, row.definition.pid!!)
        assertContains(zero, "Historical replay, not live")
        for (availability in listOf(SignalAvailability.STALE, SignalAvailability.UNAVAILABLE, SignalAvailability.NOT_SAMPLED)) {
            val missing = row.copy(availability = availability, value = null)
            val presentation = workspaceValuePresentation(missing, false)
            val text = denseReplayValueDescription(missing, false)
            assertContains(text, presentation.status)
            assertFalse(text.contains("· 0 rpm"))
        }
        assertEquals(before, row)
    }
}
