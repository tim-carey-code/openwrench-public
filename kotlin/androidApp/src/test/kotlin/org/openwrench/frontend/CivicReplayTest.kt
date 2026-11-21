package org.openwrench.frontend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import kotlin.test.*

/** Synthetic timing examples only. No physical capture is bundled or claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class CivicReplayTest {
    private val rpm = uncheckedRpm.copy(support = SignalSupport.SUPPORTED)
    private val speed = SignalDefinition("speed", "Vehicle speed", "km/h", SignalType.NUMBER, SignalSupport.SUPPORTED, "010D")
    private val original = "2020-01-01T00:00:00Z"
    private fun sample(at: Long, id: String, value: Double) = RecordedObservation(at, id,
        SignalAvailability.AVAILABLE, SignalValue.Number(value), original, 0, null)
    private fun recording() = CivicRecording(CivicCaptureMetadata("Honda Civic (synthetic test)", original,
        "backend_observation", "SYNTHETIC UNIT TEST; not a real recording"), 1000,
        listOf(rpm, speed), listOf(sample(0, "rpm", 700.0), sample(100, "speed", 0.0), sample(400, "rpm", 900.0), sample(750, "speed", 2.0)))
    private fun clock(scheduler: TestCoroutineScheduler) = object : FrontendClock {
        override fun elapsedMillis() = scheduler.currentTime
        override fun utc() = "MUST NOT BECOME AN ORIGINAL CAPTURE TIMESTAMP"
    }

    @Test fun recordedIntervalsPauseResumeRestartAndLoopReset() = runTest {
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        val capture = recording()
        player.load(ReplayLoadResult.Loaded(capture))
        assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
        assertNull(player.state.value.dashboardSignals().single { it.definition.id == "speed" }.value)
        player.play(); runCurrent()
        advanceTimeBy(99); runCurrent()
        assertNull(player.state.value.dashboardSignals().single { it.definition.id == "speed" }.value)
        advanceTimeBy(1); runCurrent()
        assertEquals("0", player.state.value.dashboardSignals().single { it.definition.id == "speed" }.value!!.display())
        advanceTimeBy(45); player.pause()
        assertEquals(145L, player.state.value.positionMillis)
        val paused = player.state.value
        advanceTimeBy(5000); runCurrent(); assertEquals(paused, player.state.value)
        player.play(); runCurrent(); advanceTimeBy(254); runCurrent()
        assertEquals("700", player.state.value.dashboardSignals().single { it.definition.id == "rpm" }.value!!.display())
        advanceTimeBy(1); runCurrent()
        assertEquals("900", player.state.value.dashboardSignals().single { it.definition.id == "rpm" }.value!!.display())
        assertSame(capture.capture, player.state.value.recording!!.capture)
        assertTrue(player.state.value.dashboardSignals().all { it.observedAt == original })
        player.restart(); runCurrent()
        assertEquals(ReplayPhase.PLAYING, player.state.value.phase)
        assertEquals(0L, player.state.value.positionMillis)
        advanceTimeBy(750); runCurrent()
        assertEquals("2", player.state.value.dashboardSignals().single { it.definition.id == "speed" }.value!!.display())
        val generation = player.state.value.generation
        advanceTimeBy(250); runCurrent()
        assertEquals(1L, player.state.value.loop)
        assertTrue(player.state.value.generation > generation)
        assertEquals(listOf(capture.observations.first()), player.state.value.observations)
        assertNull(player.state.value.dashboardSignals().single { it.definition.id == "speed" }.value)
        assertEquals(original, player.state.value.recording!!.capture.capturedAtUtc)
        player.close()
    }

    @Test fun restartWhilePausedAndLifecycleNeverResumeAutomatically() = runTest {
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        player.play(); player.restart(); assertEquals(ReplayPhase.EMPTY, player.state.value.phase)
        player.load(ReplayLoadResult.Loaded(recording())); player.play(); runCurrent()
        advanceTimeBy(800); runCurrent(); player.setActive(false)
        val paused = player.state.value
        player.setActive(true); advanceTimeBy(4000); runCurrent()
        assertEquals(paused, player.state.value)
        player.restart(); runCurrent()
        assertEquals(ReplayPhase.PAUSED, player.state.value.phase)
        assertEquals(0L, player.state.value.positionMillis)
        assertEquals(1, player.state.value.observations.size)
        player.load(ReplayLoadResult.Invalid("No recording"))
        assertNull(player.state.value.recording)
        assertTrue(player.state.value.observations.isEmpty())
        assertTrue(player.state.value.dashboardSignals().isEmpty())
        player.close()
    }

    @Test fun delayedUiTickSkipsWholeLoopsWithoutRetainingHistory() = runTest {
        var now = 0L
        val player = CivicReplayPlayer(backgroundScope, object : FrontendClock {
            override fun elapsedMillis() = now
            override fun utc() = "unused"
        })
        player.load(ReplayLoadResult.Loaded(recording())); player.play(); runCurrent()
        now = 3500; advanceTimeBy(100); runCurrent()
        assertEquals(3L, player.state.value.loop)
        assertEquals(500L, player.state.value.positionMillis)
        assertEquals(listOf(0L, 100L, 400L), player.state.value.observations.map { it.atMillis })
        assertEquals(original, player.state.value.dashboardSignals().first().observedAt)
        player.close()
    }

    @Test fun recordedMissingStaleAndIntegerValuesAreNotFabricatedOrRetimestamped() = runTest {
        val runtime = SignalDefinition("runtime", "Runtime", "s", SignalType.INTEGER, SignalSupport.SUPPORTED, "011F")
        val events = listOf(
            sample(0, "rpm", 0.0).copy(ageAtCaptureMillis = 6000),
            RecordedObservation(0, "runtime", SignalAvailability.AVAILABLE, SignalValue.Integer(12), original, 0, null),
            RecordedObservation(100, "rpm", SignalAvailability.UNAVAILABLE, null, null, null, "ECU returned NO DATA"),
            RecordedObservation(200, "rpm", SignalAvailability.STALE, null, original, 6000, "Recorded stale observation"),
        )
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        player.load(ReplayLoadResult.Loaded(recording().copy(catalog = listOf(rpm, runtime), observations = events)))
        var rows = player.state.value.dashboardSignals()
        assertEquals(SignalAvailability.STALE, rows.first().availability)
        assertNull(rows.first().value)
        assertEquals(SignalValue.Integer(12), rows.last().value)
        player.play(); runCurrent(); advanceTimeBy(100); runCurrent()
        rows = player.state.value.dashboardSignals()
        assertEquals(SignalAvailability.UNAVAILABLE, rows.first().availability)
        assertNull(rows.first().value); assertNull(rows.first().observedAt)
        assertNull(rows.first().ageMillis)
        assertContains(rows.first().reason, "NO DATA")
        advanceTimeBy(100); runCurrent()
        val stale = player.state.value.dashboardSignals().first()
        assertNull(stale.value); assertEquals(original, stale.observedAt)
        assertEquals(6000L, stale.ageMillis)
        assertEquals("Age on recording timeline", stale.ageLabel)
        player.close()
    }

    @Test fun receiptOffsetsCountSourceAgeOnceAndExpireAtTheOriginalDeadline() {
        val event = sample(1000, "rpm", 700.0).copy(ageAtCaptureMillis = 200)
        val capture = recording().copy(
            capture = recording().capture.copy(clockBasis = "client_receipt"),
            durationMillis = 10000,
            catalog = listOf(rpm),
            observations = listOf(event, sample(9000, "rpm", 700.0)),
        )
        fun row(at: Long) = CivicReplayState(recording = capture, positionMillis = at,
            observations = listOf(event)).dashboardSignals().single()
        assertEquals(200L, row(1000).ageMillis)
        assertEquals(4999L, row(5799).ageMillis)
        assertEquals(SignalValue.Number(700.0), row(5799).value)
        assertEquals(SignalAvailability.STALE, row(5800).availability)
        assertNull(row(5800).value)
        assertEquals(original, row(5800).observedAt)
        assertContains(row(1000).reason, "client_receipt")
    }

    @Test fun firstSeenNoDataClearsPriorValueUntilANewObservation() {
        val events = listOf(sample(0, "rpm", 700.0),
            RecordedObservation(100, "rpm", SignalAvailability.UNAVAILABLE, null, null, null, "ECU returned NO DATA"),
            sample(300, "rpm", 700.0))
        val capture = recording().copy(catalog = listOf(rpm), observations = events)
        fun row(at: Long) = CivicReplayState(recording = capture, positionMillis = at,
            observations = events.takeWhile { it.atMillis <= at }).dashboardSignals().single()
        assertEquals(SignalValue.Number(700.0), row(99).value)
        for (at in listOf(100L, 299L)) {
            assertEquals(SignalAvailability.UNAVAILABLE, row(at).availability)
            assertNull(row(at).value)
            assertNull(row(at).observedAt)
            assertNull(row(at).ageMillis)
            assertContains(row(at).reason, "NO DATA")
        }
        // Equal values are still distinct completed observations, and recovery resets age.
        assertEquals(SignalValue.Number(700.0), row(300).value)
        assertEquals(0L, row(300).ageMillis)
    }

    @Test fun receiptOffsetAndSourceAgeAreCountedOnceAtTheExpiryBoundary() = runTest {
        val events = listOf(sample(1000, "rpm", 700.0).copy(ageAtCaptureMillis = 200), sample(7500, "rpm", 700.0))
        val capture = recording().copy(durationMillis = 8000, catalog = listOf(rpm), observations = events,
            capture = recording().capture.copy(clockBasis = "client_receipt"))
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        player.load(ReplayLoadResult.Loaded(capture)); player.play(); runCurrent()
        advanceTimeBy(999); runCurrent(); assertNull(player.state.value.dashboardSignals().single().value)
        advanceTimeBy(1); runCurrent()
        assertEquals(200L, player.state.value.dashboardSignals().single().ageMillis)
        advanceTimeBy(4799); player.pause()
        val beforeExpiry = player.state.value.dashboardSignals().single()
        assertEquals(4999L, beforeExpiry.ageMillis); assertNotNull(beforeExpiry.value)
        assertContains(beforeExpiry.reason, "client_receipt")
        player.play(); runCurrent(); advanceTimeBy(1); player.pause()
        val expired = player.state.value.dashboardSignals().single()
        assertEquals(5800L, player.state.value.positionMillis)
        assertEquals(5000L, expired.ageMillis); assertNull(expired.value)
        assertEquals(SignalAvailability.STALE, expired.availability)
        assertEquals(original, expired.observedAt)
        player.close()
    }

    @Test fun unavailableRecoveryAndUnknownStaleAgesRemainExplicit() = runTest {
        val events = listOf(sample(0, "rpm", 0.0),
            RecordedObservation(100, "rpm", SignalAvailability.UNAVAILABLE, null, null, null, "NO DATA"),
            sample(200, "rpm", 0.0),
            RecordedObservation(300, "rpm", SignalAvailability.STALE, null, original, null, "Unknown source age"))
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        player.load(ReplayLoadResult.Loaded(recording().copy(catalog = listOf(rpm), observations = events)))
        player.play(); runCurrent(); advanceTimeBy(100); runCurrent()
        val unavailable = player.state.value.dashboardSignals().single()
        assertNull(unavailable.value); assertNull(unavailable.ageMillis)
        assertEquals(SignalAvailability.UNAVAILABLE, unavailable.availability)
        advanceTimeBy(100); runCurrent()
        assertEquals("0", player.state.value.dashboardSignals().single().value?.display())
        advanceTimeBy(100); runCurrent()
        val stale = player.state.value.dashboardSignals().single()
        assertNull(stale.value); assertNull(stale.ageMillis)
        assertEquals(SignalAvailability.STALE, stale.availability)
        assertEquals(original, stale.observedAt)
        player.close()
    }

    @Test fun availableObservationsWithUnknownAgeOrTimeAreRejectedNotTreatedAsFresh() {
        val capture = recording()
        val unknownAge = sample(0, "rpm", 700.0).copy(ageAtCaptureMillis = null)
        val unknownTime = sample(0, "rpm", 700.0).copy(observedAtUtc = null)
        for (bad in listOf(unknownAge, unknownTime, unknownAge.copy(observedAtUtc = null))) {
            assertFailsWith<IllegalArgumentException> {
                capture.copy(observations = listOf(bad, sample(400, "rpm", 900.0)))
            }
            assertFailsWith<IllegalArgumentException> {
                capture.copy(observations = listOf(sample(0, "rpm", 700.0), bad.copy(atMillis = 400)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            capture.copy(observations = listOf(sample(0, "rpm", 700.0).copy(ageAtCaptureMillis = -1), sample(400, "rpm", 900.0)))
        }
        // Unknown age stays legal, and stays unknown, only where no value is claimed.
        val legal = capture.copy(catalog = listOf(rpm), observations = listOf(sample(0, "rpm", 700.0),
            RecordedObservation(100, "rpm", SignalAvailability.UNAVAILABLE, null, null, null, "NO DATA"),
            RecordedObservation(200, "rpm", SignalAvailability.STALE, null, original, null, null)))
        assertEquals(3, legal.observations.size)
    }

    @Test fun anAvailableEventWithoutAgeFailsClosedEvenIfBuiltOutsideTheValidatedRecording() {
        val event = sample(0, "rpm", 700.0).copy(ageAtCaptureMillis = null)
        val row = CivicReplayState(recording = recording().copy(catalog = listOf(rpm),
            observations = listOf(sample(0, "rpm", 1.0), sample(400, "rpm", 2.0))), positionMillis = 0,
            observations = listOf(event)).dashboardSignals().single()
        assertEquals(SignalAvailability.STALE, row.availability)
        assertNull(row.value); assertNull(row.ageMillis)
        assertContains(row.reason, "not supplied")
    }

    @Test fun anAvailableEventWithoutTimestampCannotRenderAsValidInDirectlyConstructedState() {
        val event = sample(0, "rpm", 700.0).copy(observedAtUtc = null)
        val capture = recording().copy(catalog = listOf(rpm),
            observations = listOf(sample(0, "rpm", 1.0), sample(400, "rpm", 2.0)))
        val row = CivicReplayState(recording = capture, positionMillis = 0,
            observations = listOf(event)).dashboardSignals().single()
        assertEquals(SignalAvailability.STALE, row.availability)
        assertNull(row.value)
        assertNull(row.observedAt)
        assertEquals(0L, row.ageMillis)
        assertTrue(row.trend.points.none { it.value != null })
    }

    @Test fun sourceAgeBoundariesExpireExactlyAtFiveSeconds() {
        fun row(age: Long, at: Long): DashboardSignal {
            val event = sample(100, "rpm", 700.0).copy(ageAtCaptureMillis = age)
            val capture = recording().copy(catalog = listOf(rpm), observations = listOf(event, sample(900, "rpm", 710.0)))
            return CivicReplayState(recording = capture, positionMillis = at, observations = listOf(event)).dashboardSignals().single()
        }
        assertEquals(SignalAvailability.AVAILABLE, row(0, 100).availability)
        assertEquals(0L, row(0, 100).ageMillis)
        assertEquals(SignalAvailability.AVAILABLE, row(4999, 100).availability)
        assertEquals(4999L, row(4999, 100).ageMillis)
        assertEquals(SignalAvailability.STALE, row(4999, 101).availability)
        assertEquals(SignalAvailability.STALE, row(5000, 100).availability)
        assertNull(row(5000, 100).value)
        assertEquals(5000L, row(5000, 100).ageMillis)
        // Position before the event never yields a negative elapsed time.
        assertEquals(4999L, row(4999, 50).ageMillis)
        val extreme = row(Long.MAX_VALUE, 900)
        assertEquals(SignalAvailability.STALE, extreme.availability)
        assertEquals(Long.MAX_VALUE, extreme.ageMillis)
    }

    @Test fun offsetAndDurationBoundariesAreInclusiveExclusiveAsDocumented() {
        fun build(duration: Long, vararg at: Long) = recording().copy(durationMillis = duration, catalog = listOf(rpm),
            observations = at.map { sample(it, "rpm", 700.0) })
        assertEquals(2, build(2, 0, 1).observations.size)
        assertEquals(300_000L, build(300_000, 0, 299_999).durationMillis)
        assertFailsWith<IllegalArgumentException> { build(1, 0, 1) }
        assertFailsWith<IllegalArgumentException> { build(1000, 0, 1000) }
        assertFailsWith<IllegalArgumentException> { build(1000, -1, 5) }
        assertFailsWith<IllegalArgumentException> { build(0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { build(300_001, 0, 1) }
        assertFailsWith<IllegalArgumentException> { build(1000, 5, 5) }
    }

    @Test fun samePositionsProduceIdenticalRowsOnEveryLoopRegardlessOfTickGranularity() = runTest {
        val player = CivicReplayPlayer(backgroundScope, clock(testScheduler))
        player.load(ReplayLoadResult.Loaded(recording()))
        fun project() = player.state.value.dashboardSignals().map {
            listOf(it.definition.id, it.availability, it.value, it.observedAt, it.ageMillis, it.reason, it.trend.points)
        }
        val targets = listOf(99L, 100L, 399L, 400L, 749L, 750L, 999L)
        fun pass(step: Long?): List<List<List<Any?>>> = targets.map { target ->
            val delta = (target - player.state.value.positionMillis + 1000) % 1000
            if (step == null) advanceTimeBy(delta) else repeat(delta.toInt()) { advanceTimeBy(step) }
            runCurrent(); player.pause()
            assertEquals(target, player.state.value.positionMillis)
            project().also { player.play(); runCurrent() }
        }
        player.play(); runCurrent()
        val first = pass(null)
        val second = pass(1)
        assertEquals(first, second)
        assertTrue(player.state.value.loop >= 1)
        // Restart reproduces the first pass exactly.
        player.restart(); runCurrent()
        assertEquals(first, pass(null))
        player.close()
    }

    @Test fun aSnapshotOrInventedTimingIsRejected() {
        val capture = recording()
        assertFailsWith<IllegalArgumentException> { capture.copy(observations = listOf(sample(0, "rpm", 700.0))) }
        assertFailsWith<IllegalArgumentException> { capture.copy(observations = listOf(sample(0, "rpm", 700.0), sample(100, "speed", 0.0))) }
        assertFailsWith<IllegalArgumentException> { capture.copy(observations = capture.observations.reversed()) }
        assertFailsWith<IllegalArgumentException> { capture.copy(observations = listOf(sample(0, "rpm", 700.0), sample(1000, "rpm", 710.0))) }
        assertFailsWith<IllegalArgumentException> { capture.copy(observations = listOf(sample(0, "rpm", 700.0), sample(100, "unknown", 1.0))) }
    }
}
