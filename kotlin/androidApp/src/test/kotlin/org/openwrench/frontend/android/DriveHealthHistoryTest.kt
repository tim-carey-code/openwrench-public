package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DriveHealthHistoryTest {
    private fun assessedDrive(): SavedDrive {
        val drive = historyTestDrive()
        val finding = DriveHealthFinding("synthetic-fuel-trim-bank1", "Bank 1 fuel correction",
            DriveHealthDisposition.REVIEW, "A repeated fuel-correction pattern needs review.",
            listOf("Synthetic qualified receipts from the recorded drive."),
            listOf("Compare a repeat run under similar conditions."), listOf("This does not identify a failed part."),
            2_000, 32_000, 11, "Moving; closed-loop fuel control", 15_000)
        val assessment = DriveHealthAssessment("synthetic-assessment", "test-1", 36_000,
            listOf(finding), listOf("https://example.invalid/synthetic-reference"), listOf("Synthetic test data."))
        val review = drive.review.copy(assessment = assessment)
        return drive.copy(review = review, report = drive.report.substringBefore("Drive review: monitor instance") + review.reportText())
    }

    @Test fun savedAssessmentRoundTripsWithoutReevaluatingHistoricalReceipts() {
        val directory = Files.createTempDirectory("ow-health-history").toFile()
        try {
            val drive = assessedDrive()
            DriveHistoryStore(directory).upsert(drive)
            val reopened = DriveHistoryStore(directory).read(drive.id)
            assertEquals(drive, reopened)
            assertEquals(drive.review.assessment, reopened.historicalReview().assessment)
            assertEquals(DriveHealthDisposition.REVIEW, reopened.historicalReview().assessment!!.findings.single().disposition)
            assertTrue(reopened.copyText().contains("Synthetic qualified receipts"))
            assertEquals(1, Regex("Synthetic qualified receipts").findAll(reopened.copyText()).count())
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyDriveKeepsMeasurementsWithoutInventingAnAssessment() {
        val drive = assessedDrive()
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(drive)).jsonObject
        val legacy = JsonObject((root - "origin") + ("schema" to JsonPrimitive("openwrench.drive-history/1")) +
            ("review" to JsonObject(root.getValue("review").jsonObject - "assessment")))
        val reopened = DriveHistoryCodec.decode(legacy.toString())
        assertNull(reopened.review.assessment)
        assertEquals(drive.review.signals, reopened.review.signals)
        assertEquals(drive.review.series, reopened.review.series)
        assertEquals(drive.review.bookmarks, reopened.review.bookmarks)
        assertTrue(DriveHistoryCodec.encode(reopened).contains("openwrench.drive-history/6"))
    }

    @Test fun observedAssessmentRemainsDistinctFromReassuringAndRequiresEvidence() {
        val drive = assessedDrive()
        val assessment = drive.review.assessment!!
        val finding = assessment.findings.single().copy(disposition = DriveHealthDisposition.OBSERVED)
        val observed = drive.copy(review = drive.review.copy(assessment = assessment.copy(findings = listOf(finding))))
        val reopened = DriveHistoryCodec.decode(DriveHistoryCodec.encode(observed))
        assertEquals(DriveHealthDisposition.OBSERVED, reopened.review.assessment!!.findings.single().disposition)
        assertFails { DriveHistoryCodec.encode(observed.copy(review = observed.review.copy(
            assessment = assessment.copy(findings = listOf(finding.copy(evidence = emptyList())))))) }
    }

    @Test fun malformedAssessmentBoundsCannotBeStoredOrDecoded() {
        val drive = assessedDrive()
        val assessment = drive.review.assessment!!
        fun invalid(value: DriveHealthAssessment) = drive.copy(review = drive.review.copy(assessment = value))
        assertFails { DriveHistoryCodec.encode(invalid(assessment.copy(evaluatedAtOffsetMillis = drive.review.elapsedMillis + 1))) }
        assertFails { DriveHistoryCodec.encode(invalid(assessment.copy(findings = assessment.findings + assessment.findings))) }
        assertFails { DriveHistoryCodec.encode(invalid(assessment.copy(findings = listOf(assessment.findings.single().copy(firstOffsetMillis = 33_000))))) }
        assertFails { DriveHistoryCodec.encode(invalid(assessment.copy(findings = listOf(assessment.findings.single().copy(sampleCount = -1))))) }
        assertFails { DriveHistoryCodec.encode(invalid(assessment.copy(notes = List(21) { "Repeated note" }))) }
        assertFails { DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive).replace("\"REVIEW\"", "\"FUTURE_DISPOSITION\"")) }
        val root = Json.parseToJsonElement(DriveHistoryCodec.encode(drive)).jsonObject
        val review = root.getValue("review").jsonObject
        val extra = JsonObject(root + ("review" to JsonObject(review + ("unexpected" to JsonNull))))
        assertFails { DriveHistoryCodec.decode(extra.toString()) }
    }
}
