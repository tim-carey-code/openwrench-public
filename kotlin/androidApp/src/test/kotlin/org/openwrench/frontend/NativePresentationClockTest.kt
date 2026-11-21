package org.openwrench.frontend

import kotlin.test.*
import org.openwrench.core.SignalValue
import org.openwrench.frontend.android.NativeUiState
import org.openwrench.frontend.android.TimedReading
import org.openwrench.frontend.android.WatchRate
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading

class NativePresentationClockTest {
    private fun reading(receivedAt: Long, rpm: Double = 700.0) = NativeUiState(
        connected = true,
        liveRunning = true,
        supported = setOf(0x0C),
        watched = mapOf(0x0C to WatchRate.HIGH),
        readings = mapOf(0x0C to TimedReading(NativeReading(0x0C, DecodedReading.Scalar(rpm)), receivedAt)),
    )

    private fun List<DashboardSignal>.rpm() = single { it.definition.id == "rpm" }

    @Test fun arrivalsBetweenRedrawTicksStayAvailableAndRetainEveryTrendSample() {
        var elapsedRealtime = 1000L
        val history = NativeTrendHistory { elapsedRealtime }
        val first = history.present(reading(1000, 700.0)).rpm()
        assertEquals(SignalAvailability.AVAILABLE, first.availability)

        // Both state updates arrive before the next 250 ms idle redraw tick.
        elapsedRealtime = 1107L
        val second = history.present(reading(1100, 710.0)).rpm()
        assertEquals(SignalAvailability.AVAILABLE, second.availability)
        assertEquals(SignalValue.Number(710.0), second.value)
        assertEquals(7L, second.ageMillis)
        assertEquals(100L, second.cadenceMillis)

        elapsedRealtime = 1203L
        val latestState = reading(1200, 720.0)
        val third = history.present(latestState).rpm()
        assertEquals(SignalAvailability.AVAILABLE, third.availability)
        assertEquals(SignalValue.Number(720.0), third.value)
        assertEquals(3L, third.ageMillis)
        assertEquals(listOf(700.0, 710.0, 720.0), third.trend.points.map { it.value })
        assertEquals(1, third.trend.segments(elapsedRealtime).size)
        assertEquals(listOf(6000L, 6100L, 6200L), third.trend.points.map { it.expiresAtMillis })

        // The redraw ages the latest receipt without inventing another sample.
        elapsedRealtime = 1250L
        val tick = history.present(latestState).rpm()
        assertEquals(SignalAvailability.AVAILABLE, tick.availability)
        assertEquals(50L, tick.ageMillis)
        assertEquals(third.trend, tick.trend)
    }

    @Test fun unchangedReadingStillExpiresAtFiveSecondsOnIdleRedraws() {
        var elapsedRealtime = 1107L
        val history = NativeTrendHistory { elapsedRealtime }
        val state = reading(1100)
        history.present(state)

        elapsedRealtime = 6099L
        val fresh = history.present(state).rpm()
        assertEquals(SignalAvailability.AVAILABLE, fresh.availability)
        assertEquals(4999L, fresh.ageMillis)
        assertEquals(1, fresh.trend.points.size)

        elapsedRealtime = 6100L
        val expired = history.present(state).rpm()
        assertEquals(SignalAvailability.STALE, expired.availability)
        assertEquals(5000L, expired.ageMillis)
        assertNull(expired.value)
        assertEquals(2, expired.trend.points.size)
        assertNull(expired.trend.points.last().value)

        elapsedRealtime = 6350L
        assertEquals(expired.trend, history.present(state).rpm().trend)
    }

    @Test fun genuinelyFutureReceiptStillHasNoDisplayableValueOrTrendSample() {
        val history = NativeTrendHistory { 1250L }
        val future = history.present(reading(1300)).rpm()
        assertEquals(SignalAvailability.STALE, future.availability)
        assertNull(future.ageMillis)
        assertNull(future.value)
        assertTrue(future.reason.contains("Receipt clock is incompatible"))
        assertTrue(future.trend.points.all { it.value == null })
    }
}
