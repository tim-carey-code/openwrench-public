package org.openwrench.frontend.android

import java.nio.file.Files
import kotlin.test.*

class SavedEvidenceReviewTest {
    private fun oldDrive(): SavedDrive {
        val d = historyTestDrive()
        val signal = LiveHealthDriveSignal(0x07, "Long-term fuel trim, Bank 1", "%", 12, -15.0, -14.0, -16.0, -13.0, 1_000, 31_000)
        val finding = DriveHealthFinding("trim-bank-1", "Bank 1 learned fuel correction", DriveHealthDisposition.REVIEW,
            "Synthetic original finding", listOf("Original timed evidence"), emptyList(), emptyList(), 1_000, 31_000, 12, "Moving")
        return d.copy(review = d.review.copy(signals = listOf(signal), conditions = listOf(LiveHealthDriveCondition("Moving", 12, listOf(signal))),
            assessment = DriveHealthAssessment("standard-obd-drive", "1", 36_000, listOf(finding), emptyList(), emptyList())))
    }
    @Test fun oldSavedRunCanBeReviewedOfflineWithoutInventingNewWindowsOrChangingIt() {
        val d = oldDrive(); val original = DriveHistoryCodec.encode(d)
        val review = SavedEvidenceReview.build(d)
        assertTrue(review.observations.any { it.contains("12 recorded LTFT") && it.contains("negative") })
        assertTrue(review.observations.any { it.contains("not based solely on a parked idle") })
        assertTrue(review.observations.any { it.contains("Original assessment marked for review") })
        assertTrue(review.limitations.any { it.contains("cannot be reconstructed") })
        assertTrue(review.limitations.any { it.contains("freeze frames are not stored") })
        assertEquals(original, DriveHistoryCodec.encode(d))
        assertNull(d.review.assessment!!.findings.single().comparisonWindow)
        assertEquals(1, Regex("OPENWRENCH offline review").findAll(d.copyText()).count())
    }
    @Test fun olderRecordedReviewCategoriesCanBeComparedWithoutClaimingMatchedOperatingConditions() {
        val a = oldDrive(); val b = oldDrive()
        val comparison = DriveComparisonBuilder.compare(a, b, true)
        assertTrue(comparison.observations.first().contains("matching operating conditions cannot be established"))
        assertTrue(comparison.observations.any { it.contains("Both original assessments marked Bank 1") })
        assertFalse(comparison.observations.any { it.contains("beyond the screening cue was recorded in both drives") })
        assertTrue(comparison.observations.any { it.contains("aggregate ranges do not establish a sustained window") })
        assertFalse(comparison.reportText().contains("Only retained qualified windows are compared"))
        assertTrue(comparison.reportText().contains("Missing operating context is not reconstructed"))
        assertTrue(comparison.reportText().contains("When typed operating windows are available"))
    }
    @Test fun archiveWithoutOriginalAssessmentStillProvidesFactsAndRetainsMissingCoverage() {
        val d = oldDrive().let { it.copy(review = it.review.copy(assessment = null)) }
        val dir = Files.createTempDirectory("ow-offline-old-drive").toFile()
        try {
            DriveHistoryStore(dir).upsert(d)
            val reopened = DriveHistoryStore(dir).read(d.id)
            assertTrue(SavedEvidenceReview.build(reopened).observations.any { it.contains("No original assessment") })
            assertTrue(reopened.copyText().contains("offline review of saved evidence"))
            assertNull(reopened.review.assessment)
        } finally { dir.deleteRecursively() }
    }
}
