package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Synthetic retained evidence and manual reports only; no vehicle or adapter access. */
class InvestigationStoreTest {
    private fun case() = DiagnosticCaseBuilder.create(caseScanFixture(), caseDriveFixture(), true, 4_000)
    private fun saved(c: DiagnosticCase = case()) = SavedInvestigation(DiagnosticInvestigation("investigation", listOf(
        InvestigationRevision("r1", 1, 5_000, "Reporter", "Start", InvestigationStep.Complaint(
            "Reported cold-start complaint and roughly -16% corrections, unverified; synthetic scenario")))), listOf(c))
    private fun SavedInvestigation.add(id: String, step: InvestigationStep) = copy(journal = journal.append(id, 6_000, "Analyst", "Evidence update", step))
    private fun scan(c: DiagnosticCase, pid: Int = 5) = InvestigationStep.Observation(InvestigationObservationKind.CASE_SCAN_READING,
        "Retained scan reading; not continuous or current", "Historical capture; measurement clock unknown",
        InvestigationEvidenceRef(c.id, InvestigationObservationKind.CASE_SCAN_READING, pid))
    private fun interval(c: DiagnosticCase, index: Int = 0): InvestigationStep.Observation {
        val finding = c.drive.review.assessment!!.findings.indexOfFirst { it.fuelIntervals.isNotEmpty() }
        check(finding >= 0)
        return InvestigationStep.Observation(InvestigationObservationKind.CASE_FUEL_INTERVAL,
            "Retained qualified interval; original bank/policy/receipts preserved", "Synthetic case fixture",
            InvestigationEvidenceRef(c.id, InvestigationObservationKind.CASE_FUEL_INTERVAL, findingIndex = finding, intervalIndex = index))
    }
    private fun workflow(c: DiagnosticCase = case()): SavedInvestigation = saved(c)
        .add("reported", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Both banks allegedly negative", "Unverified narrative"))
        .add("scan", scan(c)).add("before", interval(c))
        .add("manual", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Click heard", "Synthetic manual entry; sealing/flow unverified"))
        .add("h1", InvestigationStep.Hypothesis("Purge leakage may contribute; unproven", listOf("reported", "before")))
        .add("h2", InvestigationStep.Hypothesis("Measurement/fueling alternative remains open", listOf("scan")))
        .add("test", InvestigationStep.ProposedTest(InvestigationTestKind.REVIEW_RETAINED_EVIDENCE, "Does evidence distinguish alternatives?", listOf("h1", "h2"), "A click is not sealing or flow evidence"))
        .add("result", InvestigationStep.TestResult("test", InvestigationOutcome.INCONCLUSIVE, "No sealing or flow evidence", "Manual synthetic review", listOf("manual"),
            listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "Click alone cannot resolve causality"))))

    @Test fun negativeScanSamplesKeepProvenanceWithoutBecomingAQualifiedInterval() {
        val scan = caseScanFixture()
        val capture = scan.capture!!
        val readings = capture.readings + listOf(
            NativeReading(6, DecodedReading.Scalar(-5.0), "7E8"),
            NativeReading(7, DecodedReading.Scalar(-16.0), "7E8"))
        val drive = caseDriveFixture().let { it.copy(review = it.review.copy(assessment = null),
            report = "OPENWRENCH live health monitoring\nSynthetic historical drive; no retained qualified assessment") }
        val c = DiagnosticCaseBuilder.create(scan.copy(capture = capture.copy(
            eligiblePids = capture.eligiblePids + setOf(6, 7), readings = readings)), drive, true, 4_000)
        val original = saved(c)
            .add("reported", InvestigationStep.Observation(InvestigationObservationKind.REPORTED,
                "Cold-start symptom reported; resolution unknown", "Synthetic reporter narrative"))
            .add("short", scan(c, 6)).add("long", scan(c, 7))
        val reopened = InvestigationCodec.decode(InvestigationCodec.encode(original))
        assertEquals(original, reopened)
        assertEquals(readings, reopened.cases.single().scan.capture!!.readings)
        assertEquals(original.journal.revisions.first(), reopened.journal.revisions.first())
        val symptom = reopened.journal.revisions.single { it.id == "reported" }.step as InvestigationStep.Observation
        assertEquals(InvestigationObservationKind.REPORTED, symptom.kind)
        assertEquals("Synthetic reporter narrative", symptom.provenance)
        // Available negative samples remain exact historical measurements, not a sustained interval.
        val ref = InvestigationEvidenceRef(c.id, InvestigationObservationKind.CASE_FUEL_INTERVAL,
            findingIndex = 0, intervalIndex = 0)
        assertFails { InvestigationCodec.encode(reopened.add("invented-interval", InvestigationStep.Observation(
            InvestigationObservationKind.CASE_FUEL_INTERVAL, "Attempt to promote scan samples", "Synthetic test", ref))) }
    }

    @Test fun allStepsAndOriginalEvidenceRoundTripWithoutChangingHistoricCases() {
        val c = case(); val original = DiagnosticCaseCodec.encode(listOf(c))
        val j = workflow(c).add("after", interval(c)).add("repair", InvestigationStep.RepairReview("before", "after",
            "No verified repair", "Symptom follow-up unavailable", "Synthetic manual entry"))
        val reopened = InvestigationCodec.decode(InvestigationCodec.encode(j))
        assertEquals(j, reopened)
        assertEquals(original, DiagnosticCaseCodec.encode(reopened.cases))
        assertFalse(reopened.compareRepairReview("repair", true).compatibleRecordedConditions)
        assertEquals("NOT_ESTABLISHED", reopened.compareRepairReview("repair", true).repairStatus)
        assertContains(reopened.compareRepairReview("repair", true).limitation, "does not prove a repair")
    }
    @Test fun absentUnmeasuredUnadvertisedAndMissingBankEvidenceCannotQualifyObservations() {
        val c = case()
        for (pid in listOf(12, 255)) assertFails { InvestigationCodec.encode(saved(c).add("missing", scan(c, pid))) }
        assertFails { InvestigationCodec.encode(saved(c).add("missing-bank", interval(c, 999))) }
        val absent = scan(c).copy(evidence = scan(c).evidence!!.copy(caseId = "absent"))
        assertFails { InvestigationCodec.encode(saved(c).add("unknown-case", absent)) }
    }
    @Test fun processRecreationAndSourceRemovalPreserveEvidenceAndRejectStaleWriter() {
        val dir = Files.createTempDirectory("ow-investigation").toFile()
        try {
            val s = saved(); val c = s.cases.single()
            DiagnosticCaseStore(dir).add(c)
            val store = InvestigationStore(dir); store.create(s)
            val next = store.append("investigation", "r1", "scan", 6_000, "Analyst", "Observation", scan(c))
            DiagnosticCaseStore(dir).delete(c.id)
            assertEquals(next, InvestigationStore(dir).load("investigation"))
            assertFails { InvestigationStore(dir).append("investigation", "r1", "stale", 6_001, "Analyst", "Stale", scan(c)) }
            assertFails { store.append("investigation", "scan", "replace", 6_002, "Analyst", "Replace case", scan(c), listOf(c)) }
            assertFails { store.create(s) }
            assertEquals(next, store.load("investigation"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedWriteAndInvalidAppendLeavePriorDurableBytesUntouched() {
        val dir = Files.createTempDirectory("ow-investigation-failure").toFile()
        try {
            val s = saved(); val store = InvestigationStore(dir); store.create(s)
            val file = File(dir, "investigations-v1/investigation.json"); val original = file.readBytes()
            val temporary = File(dir, "investigations-v1/investigation.tmp")
            temporary.mkdirs(); File(temporary, "block").writeText("Synthetic failure")
            assertFails { store.append("investigation", "r1", "next", 6_000, "Analyst", "Failed write", scan(s.cases.single())) }
            assertContentEquals(original, file.readBytes()); temporary.deleteRecursively()
            assertFails { store.append("investigation", "r1", "bad", 6_000, "Analyst", "Invalid", scan(s.cases.single(), 12)) }
            assertContentEquals(original, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptFutureDuplicateAndWrongTypedFilesBlockMutationAndStayByteExact() {
        val dir = Files.createTempDirectory("ow-investigation-corrupt").toFile()
        try {
            val s = saved(); val store = InvestigationStore(dir); store.create(s)
            val file = File(dir, "investigations-v1/investigation.json"); val text = file.readText()
            val malformed = listOf("{", text.replace("openwrench.investigation/1", "openwrench.investigation/2"),
                text.replace("\"policy\":1", "\"policy\":1,\"policy\":1"), text.replace("\"policy\":1", "\"policy\":2"),
                text.replace("\"policy\":1", "\"policy\":\"1\""), text.replace("\"policy\":1", "\"extra\":0,\"policy\":1"))
            for (bad in malformed) {
                file.writeText(bad)
                assertFails { store.load("investigation") }
                assertFails { store.append("investigation", "r1", "bad", 6_000, "Analyst", "Must fail", scan(s.cases.single())) }
                assertEquals(bad, file.readText())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun quotaAndTraversalDoNotEvictOrEscape() {
        val dir = Files.createTempDirectory("ow-investigation-quota").toFile()
        try {
            val store = InvestigationStore(dir); val s = saved()
            repeat(InvestigationStore.MAX_INVESTIGATIONS) { store.create(s.copy(journal = s.journal.copy(id = "investigation-$it"))) }
            assertFails { store.create(s) }
            assertEquals(s.journal.revisions, store.load("investigation-0").journal.revisions)
            assertFails { store.load("../outside") }
        } finally { dir.deleteRecursively() }
    }
    @Test fun repairComparisonNeedsExactBankWindowsSourceOriginAndExplicitVehicleConfirmation() {
        fun review(a: DiagnosticCase, b: DiagnosticCase) = saved(a).copy(cases = listOf(a, b))
            .add("before", interval(a)).add("after", interval(b))
            .add("repair", InvestigationStep.RepairReview("before", "after", "Reported repair", "Reported symptom change", "Manual report"))
        val first = case(); val second = case()
        assertFalse(review(first, second).compareRepairReview("repair", true).compatibleRecordedConditions)
        val a = first.copy(drive = first.drive.copy(origin = DriveEvidenceOrigin.HARDWARE))
        val b = second.copy(drive = second.drive.copy(origin = DriveEvidenceOrigin.HARDWARE))
        assertTrue(review(a, b).compareRepairReview("repair", true).compatibleRecordedConditions)
        assertFalse(review(a, b).compareRepairReview("repair", false).compatibleRecordedConditions)
        assertFalse(review(a, b.copy(drive = b.drive.copy(sourceEcu = "7E9"))).compareRepairReview("repair", true).compatibleRecordedConditions)
        val assessment = b.drive.review.assessment!!
        val shifted = assessment.copy(findings = assessment.findings.map { f -> f.copy(fuelIntervals = f.fuelIntervals.map { i ->
            fun FuelObservation.shift() = copy(value = value + 10)
            i.copy(pairs = i.pairs.map { it.copy(coolant = it.coolant.shift()) }, contextBounds = i.contextBounds.copy(
                coolant = FuelCorrectionBounds(i.contextBounds.coolant.minimum.shift(), i.contextBounds.coolant.maximum.shift())))
        }) })
        val incompatible = b.copy(drive = b.drive.copy(review = b.drive.review.copy(assessment = shifted)))
        assertFalse(review(a, incompatible).compareRepairReview("repair", true).compatibleRecordedConditions)
        assertEquals("NOT_ESTABLISHED", review(a, b).compareRepairReview("repair", true).repairStatus)
    }
}
