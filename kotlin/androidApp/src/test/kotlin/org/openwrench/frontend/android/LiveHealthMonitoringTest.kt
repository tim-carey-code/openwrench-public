package org.openwrench.frontend.android

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import kotlin.test.*

class LiveHealthMonitoringTest {
    private val supported = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    private fun scalar(pid: Int, value: Double) = NativeReading(pid, DecodedReading.Scalar(value))
    private fun fuel(value: String) = NativeReading(0x03, DecodedReading.Structured(mapOf("system_1" to value, "system_2" to "unused")))
    private fun accumulator() = LiveHealthAccumulator(7, "7E8", 0, supported, supported)
    private fun state(a: LiveHealthAccumulator) = NativeUiState(connected = true, liveRunning = true,
        liveHealthEnabled = true, sessionGeneration = 7, liveHealthSession = a.snapshot)

    private fun monitor(mil: Boolean, count: Int) = NativeReading(0x01, DecodedReading.Structured(mapOf(
        "mil_on" to mil.toString(), "dtc_count" to count.toString(),
        "monitors.0.name" to "oxygen sensor", "monitors.0.available" to "true", "monitors.0.complete" to "false")))

    @Test fun stoppedReportKeepsTimestampedRecordedFactsWithoutRevivingCurrentEvidence() {
        val a = accumulator()
        a.offer(scalar(0x0C, 800.0), 100)
        a.offer(scalar(0x0D, 0.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(monitor(true, 2), 400)
        val live = LiveHealthDelivery.present(state(a), 500)
        assertNull(live.recordedSummary)
        assertEquals(2, live.flags.size)
        a.stop(600)
        val pausedState = state(a).copy(liveHealthEnabled = false, liveRunning = false)
        val paused = LiveHealthDelivery.present(pausedState, 100_000)
        assertEquals(LiveHealthPhase.PAUSED, paused.phase)
        assertEquals("Unknown", paused.currentCondition)
        assertTrue(paused.flags.isEmpty())
        assertTrue(paused.monitors.isEmpty())
        assertTrue(paused.signals.all { it.latestValue == null })
        val recorded = paused.recordedSummary!!
        assertEquals(600, recorded.frozenAtOffsetMillis)
        assertEquals("Stationary with engine running", recorded.condition)
        assertTrue(recorded.fuelSystemStatus.contains("closed loop"))
        assertEquals(400, recorded.ecuReceiptOffsetMillis)
        assertEquals(200, recorded.ecuAgeAtEndMillis)
        assertTrue(recorded.ecuStatus.contains("MIL ON"))
        assertEquals(2, recorded.flags.size)
        assertEquals(1, recorded.monitors.size)
        assertEquals(recorded, LiveHealthDelivery.present(pausedState, 1_000_000).recordedSummary)
        val report = paused.reportText()
        assertTrue(report.contains("Report format: live-health/2"))
        assertTrue(report.contains("Recorded ECU status:"))
        assertFalse(report.contains("Current observed condition: Unknown"))
        assertFalse(report.contains("Monitoring stopped; latest=unknown"))
        assertFalse(report.contains("Current condition window"))
        assertFalse(paused.coverage.any { it.startsWith("Motion/engine context unavailable") || it.startsWith("Fuel-loop context unavailable") })
        assertTrue(report.indexOf("Earlier/session context aggregates") < report.indexOf("Drive review: monitor instance"))
    }

    @Test fun staleRecordedEcuFactsRemainHistoricalAndMissingEndContextIsExplicit() {
        val a = accumulator()
        a.offer(scalar(0x0C, 800.0), 100)
        a.offer(scalar(0x0D, 0.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(monitor(true, 1), 400)
        a.stop(50_000)
        val view = LiveHealthDelivery.present(state(a).copy(liveHealthEnabled = false), 100_000)
        val recorded = view.recordedSummary!!
        assertEquals("Unknown", recorded.condition)
        assertEquals("Unknown", recorded.fuelSystemStatus)
        assertEquals(49_600, recorded.ecuAgeAtEndMillis)
        assertTrue(recorded.ecuStatus.contains("Stale by the frozen point"))
        assertTrue(recorded.ecuStatus.contains("MIL ON"))
        assertTrue(view.flags.isEmpty())
        assertTrue(view.coverage.any { it.startsWith("Recorded motion context at the frozen point was unavailable") })
    }

    @Test fun recordedLatestNoDataCannotFallBackToAnOlderValidEcuStatus() {
        val a = accumulator()
        a.offer(monitor(true, 1), 100)
        a.offer(NativeReading(0x01, null), 200)
        a.stop(300)
        val view = LiveHealthDelivery.present(state(a).copy(liveHealthEnabled = false), 400)
        val recorded = view.recordedSummary!!
        assertEquals(200, recorded.ecuReceiptOffsetMillis)
        assertTrue(recorded.ecuStatus.contains("NO DATA"))
        assertFalse(recorded.ecuStatus.contains("MIL ON"))
        assertTrue(recorded.flags.isEmpty())
        assertTrue(recorded.monitors.isEmpty())
    }

    @Test fun disconnectedAndReconnectedSessionsKeepOldRecordedEvidenceFrozen() {
        val a = accumulator()
        a.offer(scalar(0x0C, 800.0), 100)
        a.offer(scalar(0x0D, 30.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(monitor(false, 0), 400)
        // Even without a terminal callback, an inactive presentation freezes at the last receipt.
        val disconnected = state(a).copy(connected = false, liveRunning = false, liveHealthEnabled = false)
        val first = LiveHealthDelivery.present(disconnected, 10_000)
        assertEquals(LiveHealthPhase.HISTORICAL, first.phase)
        assertEquals(400, first.elapsedMillis)
        assertEquals("Moving", first.recordedSummary!!.condition)
        assertEquals(first.recordedSummary, LiveHealthDelivery.present(disconnected, 1_000_000).recordedSummary)
        val reconnected = disconnected.copy(connected = true, sessionGeneration = 8)
        val later = LiveHealthDelivery.present(reconnected, 2_000_000)
        assertEquals(LiveHealthPhase.HISTORICAL, later.phase)
        assertEquals(7, later.sessionGeneration)
        assertEquals(first.recordedSummary, later.recordedSummary)
        assertTrue(later.flags.isEmpty())
        assertEquals("Unknown", later.currentCondition)
    }

    @Test fun notStartedReportDoesNotInventMissingHardwareEvidence() {
        val view = LiveHealthDelivery.present(NativeUiState(), 1_000)
        assertEquals(LiveHealthPhase.NOT_STARTED, view.phase)
        assertNull(view.recordedSummary)
        assertTrue(view.reportText().contains("No monitoring run has started"))
        assertFalse(view.reportText().contains("Current condition window"))
        assertFalse(view.reportText().contains("latest=unknown"))
    }

    @Test fun recordedSummaryRejectsReceiptsOutsideItsFrozenClockWindow() {
        val a = LiveHealthAccumulator(7, "7E8", 1_000, supported, supported)
        a.offer(scalar(0x0C, 800.0), 1_200)
        a.offer(scalar(0x0D, 0.0), 1_250)
        a.offer(fuel("closed loop"), 1_300)
        a.offer(monitor(true, 2), 1_400)
        a.stop(1_500)
        val invalid = a.snapshot.copy(latest = a.snapshot.latest + mapOf(
            0x01 to LiveHealthReceipt(monitor(true, 9), 1_600),
            0x0D to LiveHealthReceipt(scalar(0x0D, 30.0), 900)))
        val view = LiveHealthDelivery.present(state(a).copy(liveHealthEnabled = false, liveHealthSession = invalid), 100_000)
        val recorded = view.recordedSummary!!
        assertEquals("Unknown", recorded.condition)
        assertNull(recorded.ecuReceiptOffsetMillis)
        assertNull(recorded.ecuAgeAtEndMillis)
        assertTrue(recorded.flags.isEmpty())
        assertTrue(recorded.ecuStatus.contains("No monitor-status receipt"))
        assertEquals(1_400, a.snapshot.latest.getValue(0x01).receivedAtMillis)
    }

    @Test fun planReusesDashboardRatesAndCapacityNeverEvictsUserSelection() {
        val dashboard = mapOf(0x0C to WatchRate.LOW, 0x05 to WatchRate.NORMAL)
        val plan = LiveHealthInputs.plan(dashboard, supported)
        assertEquals(dashboard, plan.dashboard)
        assertEquals(WatchRate.LOW, plan.acquisition[0x0C])
        assertEquals(WatchRate.NORMAL, plan.acquisition[0x05])
        assertEquals(LiveHealthInputs.scalarPids.toSet(), plan.acquisition.keys)
        assertEquals(setOf(0x01, 0x03), plan.structured.keys)
        val full = ObdDecoder.scalarPids.sortedDescending().take(MAX_LIVE_SIGNALS).associateWith { WatchRate.NORMAL }
        val fullPlan = LiveHealthInputs.plan(full, ObdDecoder.catalog.keys)
        assertEquals(full, fullPlan.acquisition)
        assertEquals(ObdDecoder.scalarPids.size, fullPlan.acquisition.size)
        val a = LiveHealthAccumulator(7, "7E8", 0, ObdDecoder.catalog.keys,
            fullPlan.acquisition.keys + fullPlan.structured.keys)
        assertFalse(LiveHealthDelivery.present(state(a)).coverage.any { it.startsWith("Not admitted") })
        assertTrue(LiveHealthInputs.plan(emptyMap(), setOf(0x05)).structured.isEmpty())
    }

    @Test fun currentEvidenceSeparatesMotionAndFuelLoopTransitions() {
        val a = accumulator()
        a.offer(scalar(0x0C, 700.0), 100)
        a.offer(scalar(0x0D, 0.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(scalar(0x05, 78.0), 400)
        var view = LiveHealthDelivery.present(state(a), 400)
        assertEquals("Stationary with engine running", view.currentCondition)
        assertEquals(78.0, view.signals.single { it.pid == 0x05 }.latestValue)
        assertTrue(view.fuelSystemStatus.contains("system 1: closed loop"))
        a.offer(scalar(0x0D, 30.0), 500)
        view = LiveHealthDelivery.present(state(a), 500)
        assertEquals("Moving", view.currentCondition)
        assertNull(view.signals.single { it.pid == 0x05 }.latestValue)
        assertTrue(view.contexts.any { it.condition == "Stationary with engine running" && it.signals.any { row -> row.pid == 0x05 } })
        a.offer(scalar(0x05, 79.0), 600)
        a.offer(fuel("open loop: engine load or fuel cut"), 700)
        view = LiveHealthDelivery.present(state(a), 700)
        assertNull(view.signals.single { it.pid == 0x05 }.latestValue)
        assertTrue(view.contexts.any { it.condition == "Moving" && it.fuelSystemStatus.contains("closed loop") })
        // A hybrid/coasting engine at zero RPM remains moving when speed is positive.
        a.offer(scalar(0x0C, 0.0), 800)
        assertEquals("Moving", LiveHealthDelivery.present(state(a), 800).currentCondition)
        a.offer(scalar(0x0D, 0.0), 900)
        assertEquals("Engine stopped", LiveHealthDelivery.present(state(a), 900).currentCondition)
    }

    @Test fun unavailableAndExpiredContextCannotSupportCurrentFindings() {
        val a = accumulator()
        a.offer(scalar(0x0C, 700.0), 100)
        a.offer(scalar(0x0D, 0.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(scalar(0x06, -2.0), 400)
        a.offer(NativeReading(0x06, null), 500)
        var row = LiveHealthDelivery.present(state(a), 500).signals.single { it.pid == 0x06 }
        assertEquals("NO DATA", row.availability)
        assertNull(row.latestValue)
        assertEquals(0, row.sampleCount)
        a.offer(scalar(0x06, -1.0), 600)
        row = LiveHealthDelivery.present(state(a), 600).signals.single { it.pid == 0x06 }
        assertEquals(1, row.sampleCount)
        assertEquals(-1.0, row.minimum)
        var view = LiveHealthDelivery.present(state(a), 10_301)
        assertEquals("Unknown", view.currentCondition)
        assertEquals("Unknown", view.fuelSystemStatus)
        assertTrue(view.signals.all { it.latestValue == null })
        val ecu = NativeReading(0x01, DecodedReading.Structured(mapOf("mil_on" to "true", "dtc_count" to "2",
            "monitors.0.name" to "oxygen sensor", "monitors.0.available" to "true", "monitors.0.complete" to "false")))
        a.offer(ecu, 11_000)
        view = LiveHealthDelivery.present(state(a), 11_001)
        assertEquals(2, view.flags.size)
        assertEquals(1, view.monitors.size)
        assertTrue(LiveHealthDelivery.present(state(a), 56_001).flags.isEmpty())
        assertTrue(LiveHealthDelivery.present(state(a), 56_001).ecuStatus.startsWith("Unknown"))
        a.stop(12_000)
        view = LiveHealthDelivery.present(state(a).copy(liveHealthEnabled = false), 13_000)
        assertEquals(LiveHealthPhase.PAUSED, view.phase)
        assertTrue(view.flags.isEmpty())
        assertEquals("Unknown", view.currentCondition)
    }

    @Test fun refreshingExpiredPrerequisiteCannotResurrectPriorConditionWindow() {
        val a = accumulator()
        a.offer(scalar(0x0C, 700.0), 100)
        a.offer(scalar(0x0D, 20.0), 200)
        a.offer(fuel("closed loop"), 300)
        a.offer(scalar(0x05, 75.0), 400)
        // Speed expires before fuel/RPM. A new speed receipt restores the same moving key,
        // but the unseen gap must still break the prior evidence window.
        a.offer(fuel("closed loop"), 9_900)
        a.offer(scalar(0x0D, 20.0), 10_201)
        val view = LiveHealthDelivery.present(state(a), 10_201)
        assertEquals("Moving", view.currentCondition)
        assertNull(view.signals.single { it.pid == 0x05 }.latestValue)
        assertTrue(a.snapshot.segment.none { it.receivedAtMillis < 10_201 })
        assertTrue(view.contexts.any { it.signals.any { row -> row.pid == 0x05 } })
    }

    @Test fun longSessionsBoundRawWindowsAndContextAggregates() {
        val a = accumulator()
        repeat(10_000) { index ->
            val at = index.toLong() * 100
            a.offer(scalar(0x0C, 700.0), at)
            a.offer(scalar(0x0D, if (index % 2 == 0) 0.0 else 40.0), at + 1)
            a.offer(fuel("synthetic-state-${index % 30}"), at + 2)
            a.offer(scalar(0x05, 80.0), at + 3)
        }
        assertTrue(a.snapshot.contexts.size <= 16)
        assertTrue(a.snapshot.segment.groupBy { it.reading.pid }.values.all { it.size <= 64 })
        assertTrue(a.snapshot.latest.size <= supported.size)
        val b = accumulator()
        repeat(1_000) { index -> b.offer(scalar(0x05, 80.0), index.toLong() * 10) }
        assertEquals(64, b.snapshot.segment.size)
        assertEquals(1_000, b.snapshot.receiptCount)
    }

    @Test fun defaultDashboardPlusMonitorKeepsFastRpmAndBoundsStatusTrafficOnScriptedAdapter() {
        val dashboard = defaultLiveWatch(supported)
        val plan = LiveHealthInputs.plan(dashboard, supported)
        val rates = plan.acquisition + plan.structured
        val schedule = LivePollSchedule(0)
        val receipts = rates.keys.associateWith { mutableListOf<Long>() }
        var now = 0L
        while (now < 60_000) {
            val pid = schedule.next(rates, now, LiveHealthInputs.intervalOverrides, LiveHealthInputs.freshnessGuards)
            if (pid == null) { now += schedule.waitMillis(rates, now, LiveHealthInputs.intervalOverrides); continue }
            val at = now + 80
            schedule.completed(pid, now, at, true)
            receipts.getValue(pid) += at
            now = at + 20
        }
        val rpm = receipts.getValue(0x0C)
        assertTrue(rpm.size >= 110, "RPM receipts ${rpm.size}")
        assertTrue(rpm.zipWithNext().all { (a, b) -> b - a <= 600 }, "RPM max gap ${rpm.zipWithNext().maxOf { (a,b) -> b-a }}")
        assertTrue(receipts.filterKeys { it != 0x01 }.values.all { times -> times.isNotEmpty() && times.zipWithNext().all { (a,b) -> b-a < 5_000 } })
        assertTrue(receipts.getValue(0x01).size in 2..3)
        assertTrue(receipts.getValue(0x01).zipWithNext().all { (a,b) -> b-a >= 30_000 })
        println("SCRIPTED live health 60s/80ms: RPM=${rpm.size}; max gap=${rpm.zipWithNext().maxOf { (a,b) -> b-a }}; status reads=${receipts.getValue(0x01).size}")
    }
}
