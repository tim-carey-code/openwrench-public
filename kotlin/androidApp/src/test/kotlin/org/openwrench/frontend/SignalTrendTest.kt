package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.*

class SignalTrendTest {
    private fun SignalTrend.sample(at: Long, id: String, value: Double = 0.0, age: Long = 0) =
        receive(at, SignalAvailability.AVAILABLE, SignalValue.Number(value), id, age)

    @Test fun repeatedCachePollsDoNotCreateSamplesOrRenewExpiry() {
        val first = SignalTrend().sample(1000, "original", age = 200)
        val repeated = first.sample(2000, "original", age = 1200)
        assertEquals(first, repeated)
        assertEquals(5800, repeated.points.single().expiresAtMillis)
        assertEquals(0.0, repeated.points.single().value)
        assertEquals(2, repeated.sample(3000, "new", 0.0).points.size)
    }

    @Test fun unavailableAndExpiredIntervalsBreakLinesWithoutCreatingValues() {
        val trace = SignalTrend().sample(0, "a", 700.0)
            .receive(100, SignalAvailability.UNAVAILABLE, null, null, null)
            .sample(200, "b", 700.0).sample(5300, "c", 800.0)
        assertEquals(listOf(1, 1, 1), trace.segments(5400).map { it.size })
        assertNull(trace.points[1].value)
        assertEquals(trace, trace.sample(5500, "c", 800.0, 200))
    }

    @Test fun noTimelineOrNonNumericDataProducesNoFabricatedTrend() {
        val unknown = SignalTrend().receive(0, SignalAvailability.AVAILABLE, SignalValue.Number(1.0), null, null)
        assertTrue(unknown.segments(0).isEmpty())
        val text = SignalTrend().receive(0, SignalAvailability.AVAILABLE, SignalValue.Text("test"), "a", 0)
        assertTrue(text.segments(0).isEmpty())
    }

    @Test fun retainedHistoryIsBoundedAndScrollsOutOfTheWindow() {
        var trace = SignalTrend()
        repeat(1000) { trace = trace.sample(it * 100L, "id:$it", it.toDouble()) }
        assertTrue(trace.points.size <= 240)
        assertTrue(trace.segments(160_000).isEmpty())
    }

    @Test fun replayLoopsAndRestartCannotJoinHistoryFromThePriorPass() {
        val definition = SignalDefinition("rpm", "Engine speed", "rpm", SignalType.NUMBER, SignalSupport.SUPPORTED, "010C")
        val recording = CivicRecording(CivicCaptureMetadata("Honda Civic", null, "client_receipt", "Synthetic test only"),
            1000, listOf(definition), listOf(
                RecordedObservation(100, "rpm", SignalAvailability.AVAILABLE, SignalValue.Number(1.0), "2020-01-01T00:00:00Z", 0, null),
                RecordedObservation(200, "rpm", SignalAvailability.AVAILABLE, SignalValue.Number(2.0), "2020-01-01T00:00:01Z", 0, null)))
        val end = CivicReplayState(recording = recording, positionMillis = 900, observations = recording.observations)
        assertEquals(2, end.dashboardSignals().single().trend.points.size)
        val loop = end.copy(positionMillis = 0, loop = 1, generation = 1, observations = emptyList())
        assertTrue(loop.dashboardSignals().single().trend.points.isEmpty())
    }

}
