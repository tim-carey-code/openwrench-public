package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import java.nio.file.Files
import kotlin.test.*

class ContextualFuelCorrectionTest {
    private val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
    private fun cycle(r: DriveHealthAssessmentRecorder, at: Long, short: Double, long: Double, speed: Double, rpm: Double) {
        fun scalar(pid: Int, v: Double, offset: Long) = r.offer(NativeReading(pid, DecodedReading.Scalar(v)), at + offset)
        scalar(12, rpm, 0); scalar(13, speed, 1); scalar(4, 30.0, 2); scalar(5, 90.0, 3)
        r.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at + 4)
        scalar(6, short, 5); scalar(7, long, 6)
    }
    private fun assessed(): SavedDrive {
        val r = DriveHealthAssessmentRecorder(0, inputs - 8 - 9, inputs - 8 - 9)
        repeat(11) { cycle(r, 100 + 3000L * it, 5.0, 15.0, 0.0, 900.0) }
        repeat(11) { cycle(r, 40_100 + 3000L * it, -5.0, 15.0, 40.0, 1800.0) }
        r.stop(71_000)
        val d = historyTestDrive()
        val review = d.review.copy(elapsedMillis = 71_000, assessment = r.snapshot())
        return d.copy(review = review, report = "OPENWRENCH live health monitoring\nSynthetic test evidence\n" + review.reportText())
    }
    @Test fun thirtySecondBoundarySurvivesReplayAndRepeatedHistoryImportsWithoutBecomingAPass() {
        for (span in listOf(29_999L, 30_000L)) {
            val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
            val times = (0..9).map { 100 + it * 3000L } + (100 + span)
            times.forEach { cycle(r, it, -5.0, -16.0, 40.0, 1500.0) }
            val originalAssessment = r.snapshot()
            // Replaying the same receipt clock cannot add pairs or extend the observed span.
            repeat(3) { times.forEach { cycle(r, it, -5.0, -16.0, 40.0, 1500.0) } }
            assertEquals(originalAssessment, r.snapshot())
            val original = historyTestDrive()
            val d = original.copy(review = original.review.copy(assessment = r.snapshot()), origin = DriveEvidenceOrigin.SYNTHETIC)
            val dir = Files.createTempDirectory("ow-short-fuel-import").toFile()
            try {
                repeat(3) { DriveHistoryStore(dir).upsert(DriveHistoryCodec.decode(DriveHistoryCodec.encode(d))) }
                assertEquals(listOf(d.id), DriveHistoryStore(dir).list().map { it.id })
                val reopened = DriveHistoryStore(dir).read(d.id)
                assertEquals(d, reopened)
                val a = reopened.historicalReview().assessment!!
                val f = a.findings.single { it.checkId == "fuel-pattern-bank-1" }
                if (span < 30_000) {
                    assertEquals(DriveHealthDisposition.INSUFFICIENT, f.disposition)
                    assertTrue(f.fuelIntervals.isEmpty())
                    assertContains(f.summary, "30 continuous seconds")
                    assertEquals(DriveHealthDisposition.INSUFFICIENT, a.findings.single { it.checkId == "trim-bank-1" }.disposition)
                } else {
                    assertEquals(DriveHealthDisposition.REVIEW, f.disposition)
                    val i = f.fuelIntervals.single()
                    assertEquals(11, i.pairs.size)
                    assertEquals(30_000, i.pairs.last().lastOffsetMillis - i.pairs.first().lastOffsetMillis)
                    assertTrue(i.pairs.all { it.short.value == -5.0 && it.long.value == -16.0 })
                }
                assertContains(f.limitations.first(), "Mechanical cause remains unestablished")
                assertFalse(f.disposition == DriveHealthDisposition.REASSURING)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun importRejectsShortIntervalsIndividuallyInsteadOfStitchingTheirCombinedSpan() {
        val d = assessed()
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(d)).jsonObject
        val review = root.getValue("review").jsonObject
        val assessment = review.getValue("assessment").jsonObject
        val findings = assessment.getValue("findings").jsonArray
        val finding = findings.single { it.jsonObject["check"]?.jsonPrimitive?.content == "fuel-pattern-bank-1" }.jsonObject
        val intervals = finding.getValue("fuel_intervals").jsonArray
        // Each has ten actual independent pairs, but only 27 seconds. Together they span >30 s.
        val shortIntervals = intervals.map { element ->
            val i = element.jsonObject
            JsonObject(i + ("pairs" to JsonArray(i.getValue("pairs").jsonArray.dropLast(1))))
        }
        shortIntervals.forEach { element ->
            val i = FuelCorrectionArchive.decode(element)
            assertEquals(10, i.pairs.size)
            assertEquals(27_000, i.pairs.last().lastOffsetMillis - i.pairs.first().lastOffsetMillis)
            assertContains(FuelCorrectionRecorder.qualificationIssue(i.pairs)!!, "30 continuous seconds")
        }
        for (rows in listOf(shortIntervals.take(1), shortIntervals, listOf(shortIntervals.first(), shortIntervals.first()))) {
            val typed = rows.map(FuelCorrectionArchive::decode)
            assertFails { FuelCorrectionArchive.validate(typed, 1, 71_000) }
            val changedFinding = JsonObject(finding + mapOf(
                "fuel_intervals" to JsonArray(rows),
                "samples" to JsonPrimitive(typed.sumOf { it.pairs.size }),
                "first_ms" to JsonPrimitive(typed.first().firstOffsetMillis),
                "last_ms" to JsonPrimitive(typed.last().lastOffsetMillis)))
            val changed = JsonObject(root + ("review" to JsonObject(review + ("assessment" to JsonObject(assessment +
                ("findings" to JsonArray(findings.map { if (it == finding) changedFinding else it })))))))
            assertFails { DriveHistoryCodec.decode(changed.toString()) }
        }
    }

    @Test fun reportDescribesConditionsDifferentRelationshipsAndTargetedNextMeasurement() {
        val d = assessed(); val a = d.review.assessment!!
        assertEquals("3", a.packVersion)
        val f = a.findings.single { it.checkId == "fuel-pattern-bank-1" }
        assertEquals(2, f.fuelIntervals.size); assertEquals(22, f.sampleCount)
        assertTrue(f.summary.contains("differs across")); assertTrue(f.summary.contains("opposes"))
        assertTrue(f.nextSteps.first().contains("continues to oppose"))
        assertTrue(f.limitations.any { it.contains("Mechanical cause remains unestablished") })
        assertTrue(d.copyText().contains("STFT 5.00..5.00%"))
        assertTrue(d.copyText().contains("LTFT 15.00..15.00%"))
        assertTrue(a.findings.single { it.checkId == "fuel-pattern-bank-2" }.summary.contains("not advertised"))
        assertEquals(DriveHealthDisposition.REVIEW, a.findings.single { it.checkId == "trim-bank-1" }.disposition)
        assertEquals(a.findings.size, a.findings.map { it.checkId }.distinct().size)
    }
    @Test fun restartAndHistoricalReopenPreserveExactPairsAndOriginalConcern() {
        val directory = Files.createTempDirectory("ow-paired-history").toFile()
        try {
            val d = assessed(); DriveHistoryStore(directory).upsert(d)
            val reopened = DriveHistoryStore(directory).read(d.id)
            assertEquals(d, reopened); assertEquals(d.review.assessment, reopened.historicalReview().assessment)
            assertTrue(DriveHistoryCodec.encode(d).contains("openwrench.drive-history/6"))
            assertEquals(d.copyText(), reopened.copyText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun schemaFourPreservesOriginalPairedEvidenceWithoutAddingMafCharts() {
        val assessed = assessed()
        val original = assessed.copy(review = assessed.review.copy(series = assessed.review.series.filterNot { it.pid == 0x10 }))
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(original)).jsonObject
        val oldFormat = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/4"))).toString()
        val reopened = DriveHistoryCodec.decode(oldFormat)
        assertEquals(original, reopened)
        assertTrue(reopened.review.series.none { it.pid == 0x10 })
        assertEquals(original.review.assessment, reopened.historicalReview().assessment)
    }

    @Test fun legacySchemaThreePreservesOriginalFindingsAndDoesNotInventIntervals() {
        val d = assessed()
        val original = d.review.assessment!!.copy(packVersion = "2", findings = d.review.assessment.findings.filterNot { it.checkId.startsWith("fuel-pattern") })
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(d.copy(review = d.review.copy(assessment = original)))).jsonObject
        val review = root.getValue("review").jsonObject; val a = review.getValue("assessment").jsonObject
        val legacy = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/3")) + ("review" to JsonObject(review +
            ("assessment" to JsonObject(a + ("findings" to JsonArray(a.getValue("findings").jsonArray.map { JsonObject(it.jsonObject - "fuel_intervals") })))))))
        val reopened = DriveHistoryCodec.decode(legacy.toString())
        assertEquals(original, reopened.review.assessment)
        assertTrue(reopened.review.assessment!!.findings.all { it.fuelIntervals.isEmpty() })
    }
    @Test fun malformedReceiptEvidenceAndDuplicateIntervalsAreRejected() {
        val d = assessed(); val a = d.review.assessment!!; val f = a.findings.single { it.checkId == "fuel-pattern-bank-1" }
        val interval = f.fuelIntervals.first(); val first = interval.pairs.first()
        fun malformed(i: FuelCorrectionInterval): SavedDrive {
            val changed = f.copy(fuelIntervals = listOf(i) + f.fuelIntervals.drop(1))
            return d.copy(review = d.review.copy(assessment = a.copy(findings = a.findings.map { if (it.checkId == f.checkId) changed else it })))
        }
        for (bad in listOf(first.copy(short = first.short.copy(offsetMillis = -1)),
            first.copy(long = first.long.copy(offsetMillis = first.short.offsetMillis + 2001)),
            first.copy(short = first.short.copy(value = -5.0)), first.copy(rpm = first.rpm.copy(value = Double.NaN)),
            first.copy(fuelOffsetMillis = 999_999), first.copy(rpm = first.rpm.copy(value = 16_384.0)),
            first.copy(speed = first.speed.copy(value = 256.0)), first.copy(short = first.short.copy(value = 100.0)))) {
            assertFails { DriveHistoryCodec.encode(malformed(interval.copy(pairs = listOf(bad) + interval.pairs.drop(1)))) }
        }
        assertFails { DriveHistoryCodec.encode(malformed(interval.copy(policyVersion = 100))) }
        assertFails { FuelCorrectionArchive.validate(listOf(interval, interval), 1, 71_000) }
        val encoded = DriveHistoryCodec.encode(d)
        assertFails { DriveHistoryCodec.decode(encoded.replaceFirst("\"policy\":1", "\"policy\":100")) }
    }
    @Test fun newAndMixedVersionComparisonsUseOnlyCommonTypedEvidence() {
        val a = assessed(); val b = assessed()
        val comparison = DriveComparisonBuilder.compare(a, b, true)
        assertTrue(comparison.observations.any { it.contains("same STFT/LTFT relationship") })
        assertTrue(comparison.observations.any { it.contains("Bank 2: no compatible") })
        val mixed = b.copy(review = b.review.copy(assessment = b.review.assessment!!.copy(packVersion = "2", findings =
            b.review.assessment.findings.filterNot { it.checkId.startsWith("fuel-pattern") })))
        val old = DriveComparisonBuilder.compare(a, mixed, true)
        assertTrue(old.observations.first().contains("Mixed pack 2/3"))
        assertTrue(old.observations.any { it.contains("sustained correction that added") })
        val missing = b.copy(review = b.review.copy(assessment = b.review.assessment!!.copy(findings =
            b.review.assessment.findings.map { it.copy(fuelIntervals = emptyList()) })))
        assertTrue(DriveComparisonBuilder.compare(a, missing, true).observations.any { it.contains("Bank 1: no compatible") })
        val incompatible = b.copy(review = b.review.copy(assessment = b.review.assessment!!.copy(findings = b.review.assessment.findings.map { f ->
            f.copy(fuelIntervals = f.fuelIntervals.map { i -> i.copy(pairs = i.pairs.map { p -> p.copy(coolant = p.coolant.copy(value = 60.0)) },
                contextBounds = i.contextBounds.copy(coolant = FuelCorrectionBounds(FuelObservation(i.contextBounds.coolant.minimum.offsetMillis, 60.0), FuelObservation(i.contextBounds.coolant.maximum.offsetMillis, 60.0)))) }) })))
        assertTrue(DriveComparisonBuilder.compare(a, incompatible, true).observations.any { it.contains("Bank 1: no compatible") })
    }
    @Test fun admissionCoverageNamesMissingInputAndDoesNotReplaceOldLtftFinding() {
        val r = DriveHealthAssessmentRecorder(0, inputs, inputs - 6)
        repeat(11) { cycle(r, 100 + 3000L * it, 5.0, 15.0, 0.0, 900.0) }
        val a = r.snapshot()
        assertTrue(a.findings.single { it.checkId == "fuel-pattern-bank-1" }.summary.contains("not admitted"))
        assertEquals(DriveHealthDisposition.REVIEW, a.findings.single { it.checkId == "trim-bank-1" }.disposition)
    }
    @Test fun archiveRejectsCrossIntervalReceiptReuseAndContradictoryCachedContext() {
        val first = assessed().review.assessment!!.findings.single { it.checkId == "fuel-pattern-bank-1" }.fuelIntervals.first()
        // Zero-separation pairs make a boundary duplicate otherwise look non-overlapping.
        val a = first.copy(pairs = first.pairs.map { it.copy(short = it.short.copy(offsetMillis = it.long.offsetMillis)) })
        fun shiftBounds(bounds: FuelCorrectionContextBounds, delta: Long): FuelCorrectionContextBounds {
            fun shift(b: FuelCorrectionBounds) = FuelCorrectionBounds(b.minimum.copy(offsetMillis = b.minimum.offsetMillis + delta), b.maximum.copy(offsetMillis = b.maximum.offsetMillis + delta))
            return FuelCorrectionContextBounds(shift(bounds.rpm), shift(bounds.load), shift(bounds.coolant))
        }
        val b = a.copy(contextBounds = shiftBounds(a.contextBounds, 30_000), pairs = a.pairs.map { p ->
            fun shift(o: FuelObservation) = o.copy(offsetMillis = o.offsetMillis + 30_000)
            p.copy(short = shift(p.short), long = shift(p.long), rpm = shift(p.rpm), load = shift(p.load),
                coolant = shift(p.coolant), speed = shift(p.speed), fuelOffsetMillis = p.fuelOffsetMillis + 30_000)
        })
        assertFails { FuelCorrectionArchive.validate(listOf(a, b), 1, 71_000) }
        val later = first.copy(contextBounds = shiftBounds(first.contextBounds, 33_000), pairs = first.pairs.map { p ->
            fun shift(o: FuelObservation) = o.copy(offsetMillis = o.offsetMillis + 33_000)
            p.copy(short = shift(p.short), long = shift(p.long), rpm = shift(p.rpm), load = shift(p.load),
                coolant = shift(p.coolant), speed = shift(p.speed), fuelOffsetMillis = p.fuelOffsetMillis + 33_000)
        })
        FuelCorrectionArchive.validate(listOf(first, later), 1, 71_000)
        val reused = later.pairs.first().copy(coolant = first.pairs.last().coolant.copy(value = 91.0))
        assertFails { FuelCorrectionArchive.validate(listOf(first, later.copy(pairs = listOf(reused) + later.pairs.drop(1))), 1, 71_000) }
    }

    @Test fun conditionDifferenceAndCorrectionDifferenceMustBelongToTheSameIntervalPair() {
        val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
        for ((index, rpm) in listOf(1000.0, 1300.0, 1600.0).withIndex()) {
            repeat(11) { n -> cycle(r, 100 + index * 40_000L + n * 3000L,
                if (index == 1) -5.0 else 5.0, 15.0, 40.0, rpm) }
        }
        val finding = r.snapshot().findings.single { it.checkId == "fuel-pattern-bank-1" }
        assertEquals(3, finding.fuelIntervals.size)
        assertTrue(finding.summary.contains("changed between qualified intervals"))
        assertFalse(finding.summary.contains("differs across"))
    }
    @Test fun unpairedExtremaSurviveSavedReportAndHistoricalReopening() {
        val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
        repeat(11) { n ->
            val at = 100 + n * 3000L
            cycle(r, at, 5.0, 15.0, 40.0, 1500.0)
            if (n == 4) r.offer(NativeReading(12, DecodedReading.Scalar(1600.0)), at + 100)
        }
        val d = historyTestDrive().let { it.copy(review = it.review.copy(assessment = r.snapshot())) }
        val reopened = DriveHistoryCodec.decode(DriveHistoryCodec.encode(d))
        val interval = reopened.review.assessment!!.findings.single { it.checkId == "fuel-pattern-bank-1" }.fuelIntervals.single()
        assertEquals(FuelObservation(12_200, 1600.0), interval.contextBounds.rpm.maximum)
        assertTrue(interval.evidenceText().contains("1600.00 at +12200 ms"))
        assertEquals(d.review.assessment, reopened.review.assessment)
        val f = d.review.assessment!!.findings.single { it.checkId == "fuel-pattern-bank-1" }
        val bad = f.copy(fuelIntervals = listOf(interval.copy(contextBounds = interval.contextBounds.copy(rpm =
            interval.contextBounds.rpm.copy(maximum = FuelObservation(12_200, 2000.0))))))
        assertFails { DriveHistoryCodec.encode(d.copy(review = d.review.copy(assessment = d.review.assessment.copy(findings = d.review.assessment.findings.map { if (it.checkId == f.checkId) bad else it })))) }
    }

    @Test fun firstPendingNumericEvidenceSurvivesSaveAndStoreRecreation() {
        val cases = listOf(Triple(12, 2700.0, 2500.0), Triple(4, 20.0, 15.0), Triple(5, 87.0, 85.0))
        for ((pid, allowed, excursion) in cases) for (reverse in listOf(false, true)) {
            val normal = when (pid) { 12 -> 3000.0; 4 -> 30.0; else -> 90.0 }
            for (value in listOf(excursion, allowed, normal)) {
                val directory = Files.createTempDirectory("ow-first-pending-context").toFile()
                try {
                    val r = DriveHealthAssessmentRecorder(0, inputs, inputs)
                    fun scalar(p: Int, v: Double, at: Long) = r.offer(NativeReading(p, DecodedReading.Scalar(v)), at)
                    fun refresh(at: Long) {
                        scalar(12, 3000.0, at); scalar(4, 30.0, at); scalar(5, 90.0, at); scalar(13, 40.0, at)
                        r.offer(NativeReading(3, DecodedReading.Structured(mapOf(
                            "system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at)
                    }
                    fun pair(end: Long) {
                        refresh(end - 10)
                        scalar(if (reverse) 7 else 6, if (reverse) 15.0 else 5.0, end - 1)
                        scalar(if (reverse) 6 else 7, if (reverse) 5.0 else 15.0, end)
                    }
                    refresh(0)
                    scalar(if (reverse) 7 else 6, if (reverse) 15.0 else 5.0, 100)
                    scalar(pid, value, 500); scalar(pid, normal, 1000)
                    scalar(if (reverse) 6 else 7, if (reverse) 5.0 else 15.0, 1500)
                    repeat(10) { pair(4500 + it * 3000L) }
                    val original = historyTestDrive()
                    fun saveAndReopen(): SavedDrive {
                        val d = original.copy(review = original.review.copy(assessment = r.snapshot()))
                        DriveHistoryStore(directory).upsert(d)
                        val reopened = DriveHistoryStore(directory).read(d.id)
                        assertEquals(d, reopened)
                        assertEquals(d.review.assessment, reopened.historicalReview().assessment)
                        return reopened
                    }
                    val reopened = saveAndReopen()
                    val finding = reopened.review.assessment!!.findings.single { it.checkId == "fuel-pattern-bank-1" }
                    if (value == excursion) {
                        assertTrue(finding.fuelIntervals.isEmpty(), "PID $pid, reverse=$reverse")
                        pair(34_500)
                        val recovered = saveAndReopen().review.assessment!!.findings.single { it.checkId == finding.checkId }.fuelIntervals.single()
                        assertEquals(4499, recovered.firstOffsetMillis)
                        assertEquals(11, recovered.pairs.size)
                    } else {
                        val interval = finding.fuelIntervals.single()
                        val bounds = when (pid) { 12 -> interval.contextBounds.rpm; 4 -> interval.contextBounds.load; else -> interval.contextBounds.coolant }
                        assertEquals(FuelObservation(if (value == normal) 0 else 500, value), bounds.minimum)
                        assertEquals(FuelObservation(0, normal), bounds.maximum)
                        assertEquals(100, interval.firstOffsetMillis)
                        assertTrue(interval.evidenceText().contains("at +${bounds.minimum.offsetMillis} ms"))
                    }
                } finally { directory.deleteRecursively() }
            }
        }
    }

    @Test fun archiveBoundsCannotPrecedeInitialContextFreshnessOrFollowInterval() {
        val i = assessed().review.assessment!!.findings.single { it.checkId == "fuel-pattern-bank-1" }.fuelIntervals.last()
        val earliest = i.firstOffsetMillis - FuelCorrectionPolicy.CONTEXT_AGE_MS
        for (at in listOf(earliest - 1, i.lastOffsetMillis + 1)) {
            val bad = i.copy(contextBounds = i.contextBounds.copy(rpm = i.contextBounds.rpm.copy(
                minimum = i.contextBounds.rpm.minimum.copy(offsetMillis = at))))
            assertFails { FuelCorrectionArchive.validate(listOf(bad), 1, 71_000) }
        }
    }

}
