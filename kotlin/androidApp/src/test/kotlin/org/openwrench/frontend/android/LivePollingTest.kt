package org.openwrench.frontend.android

import kotlin.test.*
import org.openwrench.nativecore.ObdDecoder

class LivePollingTest {
    private val pids = listOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0F, 0x10, 0x0B, 0x42, 0x06, 0x07, 0x1F)
    private val twentyFourPids = (pids + ObdDecoder.scalarPids.sorted()).distinct().take(24)

    @Test
    fun defaultWatchUsesEveryAdvertisedNumericSignalWithSlowerTemperatureAndVoltageRates() {
        val watch = defaultLiveWatch(pids.toSet() + 0x01 + 0xFF)
        assertEquals(pids.toSet(), watch.keys)
        assertEquals(pids.take(9), watch.keys.take(9))
        assertEquals(WatchRate.HIGH, watch[0x0C])
        assertEquals(WatchRate.NORMAL, watch[0x11])
        assertEquals(WatchRate.LOW, watch[0x05])
        assertEquals(WatchRate.LOW, watch[0x42])
        listOf(0x07, 0x09, 0x21, 0x30, 0x31, 0x3C, 0x4D, 0x4E).forEach {
            assertEquals(WatchRate.LOW, liveWatchRate(it), "Slow sensor/counter PID $it")
        }
        assertTrue(defaultLiveWatch(setOf(0x01, 0xFF)).isEmpty())
        assertEquals(setOf(0x0C), defaultLiveWatch(setOf(0x0C)).keys)
    }

    @Test
    fun fullCatalogIsSelectedWithoutTruncationAndUnknownOrStructuredPidsStayExcluded() {
        val watch = defaultLiveWatch((0x00..0xFF).toSet())
        assertTrue(watch.size > 24)
        assertEquals(ObdDecoder.scalarPids, watch.keys)
        assertEquals(ObdDecoder.scalarPids.size, MAX_LIVE_SIGNALS)
        assertEquals(watch, defaultLiveWatch((0xFF downTo 0x00).toSet()))
        assertTrue(watch.keys.none { ObdDecoder.catalog.getValue(it).structured })
        assertTrue(watch.keys.none { it in ObdDecoder.rangeBases })
    }

    @Test
    fun fullCatalogRemainsFairAtFastAndSlowAdapterSpeedsWithoutPromisingFreshness() {
        val rates = defaultLiveWatch(ObdDecoder.scalarPids)
        for (responseMillis in listOf(80L, 700L, 3_000L)) {
            val schedule = LivePollSchedule(0)
            val receipts = rates.keys.associateWith { mutableListOf<Long>() }
            // Enough time for multiple bounded sweeps even at the transport timeout.
            val duration = 4L * rates.size * (responseMillis + 20)
            var now = 0L
            while (now < duration) {
                val pid = schedule.next(rates, now)
                if (pid == null) { now += schedule.waitMillis(rates, now); continue }
                val receipt = now + responseMillis
                schedule.completed(pid, now, receipt, true)
                receipts.getValue(pid).add(receipt)
                now = receipt + 20
            }
            assertTrue(receipts.values.all { it.size >= 2 }, "Every PID is serviced: $responseMillis ms")
            assertTrue(receipts.getValue(0x0C).size > receipts.getValue(0x05).size)
            val rpmGaps = receipts.getValue(0x0C).zipWithNext().map { (a, b) -> b - a }
            assertTrue(rpmGaps.all { it <= maxOf(600L, 2 * (responseMillis + 20)) })
            val maxGap = receipts.values.maxOf { it.zipWithNext().maxOf { (a, b) -> b - a } }
            println("SCRIPTED full catalog / $responseMillis ms: signals=${rates.size}, " +
                "reads=${receipts.values.sumOf { it.size }}, rpmMaxGapMs=${rpmGaps.max()}, maxGapMs=$maxGap")
            if (responseMillis >= 700) assertTrue(maxGap > 5_000,
                "A full watch does not fabricate a five-second freshness guarantee")
        }
    }

    @Test
    fun fullCatalogNoDataBackoffDoesNotStarveOtherSensorsAndRecovers() {
        val rates = defaultLiveWatch(ObdDecoder.scalarPids)
        val schedule = LivePollSchedule(0)
        val receipts = rates.keys.associateWith { mutableListOf<Long>() }
        var now = 0L
        while (now < 120_000) {
            val pid = schedule.next(rates, now)
            if (pid == null) { now += schedule.waitMillis(rates, now); continue }
            schedule.completed(pid, now, now + 80, pid != 0x0C || now >= 60_000)
            receipts.getValue(pid).add(now)
            now += 100
        }
        assertTrue(receipts.values.all { it.size >= 2 })
        val rpm = receipts.getValue(0x0C)
        assertTrue(rpm.count { it < 60_000 } < 12, "Repeated NO DATA must back off")
        assertTrue(rpm.count { it >= 60_000 } > 100, "Success restores priority")
    }

    @Test
    fun fullCatalogHealthContextStaysFreshWith200msRepliesAndOtherSensorsStillReceiveService() {
        val plan = LiveHealthInputs.plan(defaultLiveWatch(ObdDecoder.scalarPids), ObdDecoder.catalog.keys)
        val rates = plan.acquisition + plan.structured
        val schedule = LivePollSchedule(0)
        val receipts = rates.keys.associateWith { mutableListOf<Long>() }
        var now = 0L
        while (now < 180_000) {
            val pid = schedule.next(rates, now, LiveHealthInputs.intervalOverrides, LiveHealthInputs.freshnessGuards)
            if (pid == null) { now += schedule.waitMillis(rates, now, LiveHealthInputs.intervalOverrides); continue }
            val receipt = now + 200
            schedule.completed(pid, now, receipt, true)
            receipts.getValue(pid).add(receipt)
            now = receipt + 20
        }
        assertTrue(receipts.values.all { it.size >= 2 }, "Every scalar and status PID gets service")
        for ((pid, freshness) in mapOf(0x0D to LiveHealthInputs.CONTEXT_AGE_MILLIS,
            0x03 to LiveHealthInputs.CONTEXT_AGE_MILLIS, 0x01 to LiveHealthInputs.ECU_AGE_MILLIS)) {
            val samples = receipts.getValue(pid)
            assertTrue(samples.first() < freshness, "PID $pid initial context")
            assertTrue(samples.zipWithNext().all { (a, b) -> b - a < freshness }, "PID $pid context expires")
            assertTrue(now - samples.last() < freshness, "PID $pid final context")
            println("SCRIPTED full health / 200ms / PID $pid: maxGapMs=" + samples.zipWithNext().maxOf { (a, b) -> b - a })
        }
        assertTrue(receipts.getValue(0x0C).size > receipts.getValue(0x0D).size)
    }

    @Test
    fun contextGuardsRespectNoDataBackoffAndCannotStarveTheCatalogAtTimeoutSpeed() {
        val plan = LiveHealthInputs.plan(defaultLiveWatch(ObdDecoder.scalarPids), ObdDecoder.catalog.keys)
        val rates = plan.acquisition + plan.structured
        val schedule = LivePollSchedule(0)
        val receipts = rates.keys.associateWith { mutableListOf<Long>() }
        var now = 0L
        // At 3s per read, context cannot be guaranteed. All PIDs must still get a turn.
        while (now < 16L * rates.size * 3_020) {
            val pid = schedule.next(rates, now, LiveHealthInputs.intervalOverrides, LiveHealthInputs.freshnessGuards)!!
            schedule.completed(pid, now, now + 3_000, pid != 0x03)
            receipts.getValue(pid).add(now)
            now += 3_020
        }
        assertTrue(receipts.values.all { it.size >= 2 }, "Context priority cannot starve ordinary reads")
        val fuel = receipts.getValue(0x03)
        assertTrue(fuel.drop(5).zipWithNext().all { (a, b) -> b - a >= 13_000 },
            "Priority must respect ten-second retry backoff after a three-second failed read")
    }

    @Test
    fun adapterResponseTimeDoesNotAddAnotherWholeRpmInterval() {
        val schedule = LivePollSchedule(0)
        val rates = mapOf(0x0C to WatchRate.HIGH)
        assertEquals(0x0C, schedule.next(rates, 0))
        schedule.completed(0x0C, 0, 180, true)
        assertNull(schedule.next(rates, 199))
        assertEquals(0x0C, schedule.next(rates, 200))
        schedule.completed(0x0C, 200, 700, true)
        assertEquals(0x0C, schedule.next(rates, 720))
    }

    @Test
    fun twelveSignalsStayFreshAndRpmResponsiveWithAn80msAdapterFor60Seconds() {
        val rates = pids.associateWith(::liveWatchRate)
        val schedule = LivePollSchedule(0)
        val receipts = pids.associateWith { mutableListOf<Long>() }
        var now = 0L
        while (now < 60_000) {
            val pid = schedule.next(rates, now)
            if (pid == null) { now += schedule.waitMillis(rates, now); continue }
            val receipt = now + 80
            schedule.completed(pid, now, receipt, true)
            receipts.getValue(pid).add(receipt)
            now = receipt + 20
        }
        assertEquals(12, receipts.count { it.value.isNotEmpty() })
        receipts.forEach { (pid, samples) ->
            assertTrue(samples.first() < 3_000, "PID $pid first sample")
            assertTrue(samples.zipWithNext().all { (a, b) -> b - a < 5_000 }, "PID $pid expired")
        }
        val rpm = receipts.getValue(0x0C)
        assertTrue(rpm.size >= 120, "At least two RPM updates/sec on average; got ${rpm.size}")
        assertTrue(rpm.zipWithNext().all { (a, b) -> b - a <= 600 }, "RPM must not wait behind a full sweep")
        assertTrue(receipts.values.sumOf { it.size } >= 550, "Remove the former <5 reads/sec ceiling")
    }

    @Test
    fun twentyFourSignalsKeepRpmAndEverySelectedValueFreshWithAn80msAdapter() {
        val rates = twentyFourPids.associateWith(::liveWatchRate)
        val schedule = LivePollSchedule(0)
        val receipts = twentyFourPids.associateWith { mutableListOf<Long>() }
        var now = 0L
        while (now < 60_000) {
            val pid = schedule.next(rates, now)
            if (pid == null) { now += schedule.waitMillis(rates, now); continue }
            val receipt = now + 80
            schedule.completed(pid, now, receipt, true)
            receipts.getValue(pid).add(receipt)
            now = receipt + 20
        }
        assertEquals(24, receipts.count { it.value.isNotEmpty() })
        receipts.forEach { (pid, samples) ->
            assertTrue(samples.first() < 5_000, "PID $pid first sample at ${samples.first()}")
            assertTrue(samples.zipWithNext().all { (a, b) -> b - a < 5_000 }, "PID $pid stale gap")
            assertTrue(60_000 - samples.last() < 5_000, "PID $pid stale at end")
        }
        val rpm = receipts.getValue(0x0C)
        val rpmGaps = rpm.zipWithNext().map { (a, b) -> b - a }
        println("SCRIPTED 60s / 24 signals / 80ms replies: reads=${receipts.values.sumOf { it.size }}, " +
            "rpmReads=${rpm.size}, rpmMaxGapMs=${rpmGaps.max()}, " +
            "maxOtherGapMs=${receipts.filterKeys { it != 0x0C }.values.maxOf { it.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: 0 }}")
        assertTrue(rpm.size >= 110, "RPM should keep priority; got ${rpm.size} reads")
        assertTrue(rpmGaps.all { it <= 600 }, "RPM max gap ${rpmGaps.max()}")
        assertTrue(receipts.values.sumOf { it.size } >= 550)
    }

    @Test
    fun twentyFourSignalsRemainFairWhenTheAdapterCannotMeetFreshnessTargets() {
        val rates = twentyFourPids.associateWith(::liveWatchRate)
        val schedule = LivePollSchedule(0)
        val counts = twentyFourPids.associateWith { 0 }.toMutableMap()
        var now = 0L
        while (now < 60_000) {
            val pid = schedule.next(rates, now) ?: error("A selected signal should be due under overload")
            counts[pid] = counts.getValue(pid) + 1
            schedule.completed(pid, now, now + 700, true)
            now += 720
        }
        assertTrue(counts.values.all { it >= 1 }, counts.toString())
        assertTrue(counts.getValue(0x0C) > counts.getValue(0x0D), counts.toString())
    }

    @Test
    fun noDataBackoffIsBoundedAndSuccessRestoresFastPolling() {
        val schedule = LivePollSchedule(0)
        val rates = mapOf(0x0C to WatchRate.HIGH)
        var start = 0L
        for (gap in listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L)) {
            assertEquals(0x0C, schedule.next(rates, start))
            schedule.completed(0x0C, start, start + 80, false)
            assertNull(schedule.next(rates, start + 80 + gap - 1))
            start += 80 + gap
        }
        schedule.completed(0x0C, start, start + 80, true)
        assertEquals(0x0C, schedule.next(rates, start + 200))
        assertTrue(pids.all { it in ObdDecoder.scalarPids })
    }

    @Test
    fun overloadStillServicesEverySignalWithoutSendingBurstsForMissedSlots() {
        val schedule = LivePollSchedule(0)
        val rates = pids.associateWith(::liveWatchRate)
        val counts = pids.associateWith { 0 }.toMutableMap()
        var now = 0L
        while (now < 60_000) {
            val pid = schedule.next(rates, now)!!
            counts[pid] = counts.getValue(pid) + 1
            schedule.completed(pid, now, now + 700, true)
            now += 720
        }
        assertTrue(counts.values.all { it >= 2 }, counts.toString())
        assertTrue(counts.getValue(0x0C) > counts.getValue(0x0D))
        assertTrue(counts.getValue(0x0D) > counts.getValue(0x05))
    }
}
