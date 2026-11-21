package org.openwrench.core

import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import kotlin.test.*

class FuelCorrectionPendingContextTest {
    private val inputs = setOf(3, 4, 5, 6, 7, 8, 9, 12, 13)
    private data class Numeric(val pid: Int, val normal: Double, val allowed: Double, val excursion: Double) {
        fun bounds(i: FuelCorrectionInterval) = when (pid) {
            12 -> i.contextBounds.rpm
            4 -> i.contextBounds.load
            else -> i.contextBounds.coolant
        }
    }
    private val numerics = listOf(Numeric(12, 3000.0, 2700.0, 2500.0),
        Numeric(4, 30.0, 20.0, 15.0), Numeric(5, 90.0, 87.0, 85.0))
    private fun scalar(r: FuelCorrectionRecorder, pid: Int, value: Double, at: Long) =
        r.offer(NativeReading(pid, DecodedReading.Scalar(value)), at)
    private fun refreshContext(r: FuelCorrectionRecorder, at: Long) {
        scalar(r, 12, 3000.0, at); scalar(r, 4, 30.0, at); scalar(r, 5, 90.0, at); scalar(r, 13, 40.0, at)
        r.offer(NativeReading(3, DecodedReading.Structured(mapOf(
            "system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to FuelCorrectionRecorder.CLOSED))), at)
    }
    private fun pair(r: FuelCorrectionRecorder, end: Long, reverse: Boolean, bank: Int = 1,
        short: Double = 5.0, long: Double = 15.0) {
        refreshContext(r, end - 10)
        val shortPid = if (bank == 1) 6 else 8
        val longPid = shortPid + 1
        scalar(r, if (reverse) longPid else shortPid, if (reverse) long else short, end - 1)
        scalar(r, if (reverse) shortPid else longPid, if (reverse) short else long, end)
    }
    private fun pendingPair(r: FuelCorrectionRecorder, start: Long, reverse: Boolean, numeric: Numeric,
        value: Double, bank: Int = 1) {
        refreshContext(r, start)
        val shortPid = if (bank == 1) 6 else 8
        val longPid = shortPid + 1
        scalar(r, if (reverse) longPid else shortPid, if (reverse) 15.0 else 5.0, start + 100)
        scalar(r, numeric.pid, value, start + 500)
        scalar(r, numeric.pid, numeric.normal, start + 1000)
        scalar(r, if (reverse) shortPid else longPid, if (reverse) 5.0 else 15.0, start + 1500)
    }

    @Test fun firstPendingPairRejectsReturnedNumericExcursionsInEitherOrderAndBank() {
        for (numeric in numerics) for (reverse in listOf(false, true)) for (bank in 1..2) {
            val r = FuelCorrectionRecorder(0, inputs, inputs)
            pendingPair(r, 0, reverse, numeric, numeric.excursion, bank)
            repeat(10) { pair(r, 4500 + it * 3000L, reverse, bank) }
            assertTrue(r.intervals(bank).isEmpty(), "PID ${numeric.pid}, reverse=$reverse, bank=$bank")
            // Recovery must qualify on its own, without borrowing the rejected pair's time.
            pair(r, 34_500, reverse, bank)
            val interval = r.intervals(bank).single()
            assertEquals(4499, interval.firstOffsetMillis)
            assertEquals(11, interval.pairs.size)
            assertEquals(0.0, numeric.bounds(interval).range)
        }
    }

    @Test fun firstPendingPairRetainsInclusivePolicyExtremaAndStableControl() {
        for (numeric in numerics) for (reverse in listOf(false, true)) for (value in listOf(numeric.allowed, numeric.normal)) {
            val r = FuelCorrectionRecorder(0, inputs, inputs)
            pendingPair(r, 0, reverse, numeric, value)
            repeat(10) { pair(r, 4500 + it * 3000L, reverse) }
            val interval = r.intervals(1).single()
            assertEquals(11, interval.pairs.size)
            assertEquals(100, interval.firstOffsetMillis)
            val bounds = numeric.bounds(interval)
            assertEquals(FuelObservation(if (value == numeric.normal) 0 else 500, value), bounds.minimum)
            assertEquals(FuelObservation(0, numeric.normal), bounds.maximum)
        }
    }

    private enum class Boundary { PATTERN, SCREEN, DIRECTION, SPAN, COUNT, GAP, INVALID, MOTION }
    private fun seed(r: FuelCorrectionRecorder, boundary: Boundary, reverse: Boolean): Long {
        val count = when (boundary) { Boundary.SPAN -> 20; Boundary.COUNT -> FuelCorrectionPolicy.MAX_PAIRS; else -> 1 }
        val step = if (boundary == Boundary.COUNT) 100L else 3000L
        repeat(count) { pair(r, 1500 + it * step, reverse,
            short = if (boundary == Boundary.PATTERN || boundary == Boundary.DIRECTION) -5.0 else 5.0,
            long = when (boundary) { Boundary.SCREEN -> 8.0; Boundary.DIRECTION -> -15.0; else -> 15.0 }) }
        val last = 1500 + (count - 1) * step
        return when (boundary) {
            Boundary.GAP -> last + 6100
            Boundary.INVALID -> { r.offer(NativeReading(6, null), last + 1); last + 100 }
            Boundary.MOTION -> { scalar(r, 13, 0.0, last + 1); last + 100 }
            else -> last + 1500
        }
    }

    @Test fun newCandidateBoundariesKeepOnlyTheirOwnPendingPairExtrema() {
        for (boundary in Boundary.entries) for (numeric in numerics) for (reverse in listOf(false, true)) {
            val r = FuelCorrectionRecorder(0, inputs, inputs)
            val start = seed(r, boundary, reverse)
            val retainedBefore = r.intervals(1)
            pendingPair(r, start, reverse, numeric, numeric.allowed)
            repeat(10) { pair(r, start + 4500 + it * 3000L, reverse) }
            val interval = r.intervals(1).last()
            assertTrue(interval !in retainedBefore, "$boundary, PID ${numeric.pid}, reverse=$reverse")
            assertEquals(start + 100, interval.firstOffsetMillis)
            assertEquals(FuelObservation(start + 500, numeric.allowed), numeric.bounds(interval).minimum)
            assertEquals(FuelObservation(start, numeric.normal), numeric.bounds(interval).maximum)
        }
    }

    @Test fun newCandidateBoundariesCannotHideReturnedNumericExcursions() {
        for (boundary in Boundary.entries) for (numeric in numerics) for (reverse in listOf(false, true)) {
            val r = FuelCorrectionRecorder(0, inputs, inputs)
            val start = seed(r, boundary, reverse)
            val retainedBefore = r.intervals(1)
            pendingPair(r, start, reverse, numeric, numeric.excursion)
            repeat(10) { pair(r, start + 4500 + it * 3000L, reverse) }
            assertEquals(retainedBefore, r.intervals(1), "$boundary, PID ${numeric.pid}, reverse=$reverse")
        }
    }

    @Test fun replacementAndExpiryStartFreshPendingEvidence() {
        for (reverse in listOf(false, true)) for (expired in listOf(false, true)) {
            val r = FuelCorrectionRecorder(0, inputs, inputs)
            refreshContext(r, 0)
            val first = if (reverse) 7 else 6
            val second = if (reverse) 6 else 7
            scalar(r, first, if (reverse) 15.0 else 5.0, 100)
            scalar(r, 12, 2700.0, 500)
            scalar(r, 12, 3000.0, 1000)
            val replacement = if (expired) 2200L else 1200L
            scalar(r, first, if (reverse) 15.0 else 5.0, replacement)
            scalar(r, second, if (reverse) 5.0 else 15.0, replacement + 100)
            repeat(10) { pair(r, replacement + 3100 + it * 3000L, reverse) }
            val interval = r.intervals(1).single()
            assertEquals(replacement, interval.firstOffsetMillis)
            assertEquals(0.0, interval.contextBounds.rpm.range)
            assertEquals(FuelObservation(1000, 3000.0), interval.contextBounds.rpm.minimum)
        }
    }
}
