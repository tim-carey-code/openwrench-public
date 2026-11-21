package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class InstrumentTracePresentationTest {
    private val rpm = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED, "010C")

    private fun sample(value: Double, at: Long = 1000, definition: SignalDefinition = rpm) = DashboardSignal(
        definition, SignalAvailability.AVAILABLE, SignalValue.Number(value),
        trend = SignalTrend().receive(at, SignalAvailability.AVAILABLE, SignalValue.Number(value), "sample:$at", 0),
        trendNowMillis = at,
    )

    @Test fun pausingFreezesScaleAndGeometryWhileNewReadingsExtendTheLiveRange() {
        val frozen = sample(1000.0)
        val live = sample(6000.0, 2000)
        val original = instrumentTracePresentation(frozen, strip = true)
        val paused = instrumentTracePresentation(live, frozen, strip = true)
        assertEquals(original, paused)
        assertEquals(0.0 to 4000.0, paused.bounds)
        assertEquals(1000, paused.nowMillis)
        assertEquals(1000.0, paused.segments.single().single().value)
        val resumed = instrumentTracePresentation(live, strip = true)
        assertEquals(0.0 to 6000.0, resumed.bounds)
        assertEquals(2000, resumed.nowMillis)
        assertEquals(6000.0, resumed.segments.single().single().value)
    }

    @Test fun replayScaleExcludesAnExpiredExtremeEvenWithoutAnotherObservation() {
        val recording = CivicRecording(
            CivicCaptureMetadata("Synthetic scale regression", null, "client_receipt", "Synthetic test only"),
            100_000, listOf(rpm), listOf(
                RecordedObservation(0, "rpm", SignalAvailability.AVAILABLE, SignalValue.Number(6000.0), "2020-01-01T00:00:00Z", 0, null),
                RecordedObservation(30_000, "rpm", SignalAvailability.AVAILABLE, SignalValue.Number(1000.0), "2020-01-01T00:00:30Z", 0, null),
            ),
        )
        val state = CivicReplayState(recording = recording, positionMillis = 60_000, observations = recording.observations)
        val before = instrumentTracePresentation(state.dashboardSignals().single(), strip = true)
        assertEquals(0.0 to 6000.0, before.bounds)
        val row = state.copy(positionMillis = 61_000).dashboardSignals().single()
        assertEquals(2, row.trend.points.size, "Replay still retains the earlier extreme")
        val trace = instrumentTracePresentation(row, strip = true)
        assertEquals(listOf(1000.0), trace.segments.flatten().map { it.value })
        assertEquals(0.0 to 4000.0, trace.bounds, "The shared axis/canvas scale uses only visible history")
        assertTrue(instrumentTracePresentation(state.copy(positionMillis = 91_000).dashboardSignals().single(), strip = true).segments.isEmpty())
    }

    @Test fun pausedImperialTraceConvertsTheSameFrozenValuesAndBounds() {
        val temperature = rpm.copy(id = "coolant", unit = "°C", pid = "0105")
        val frozen = sample(20.0, definition = temperature)
        val live = sample(120.0, 2000, temperature)
        val trace = instrumentTracePresentation(live, frozen, strip = true, imperial = true)
        assertEquals(50.0 to 86.0, trace.bounds)
        assertEquals(68.0, trace.segments.single().single().value)
        assertEquals(instrumentTracePresentation(frozen, strip = true, imperial = true), trace)
        assertEquals(20.0, frozen.trend.points.single().value, "Display conversion preserves source observations")
    }
}
