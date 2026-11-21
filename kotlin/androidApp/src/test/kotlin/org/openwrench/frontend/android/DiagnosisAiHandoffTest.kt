package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.UUID

class DiagnosisAiHandoffTest {
    private fun id() = UUID.randomUUID().toString()
    private fun drive(origin: DriveEvidenceOrigin, status: SavedDriveStatus) = SavedDrive(
        id(), 1_000, 2_000, status, null, null, 1, "Fictional fixture",
        LiveHealthDriveReview(1, 0, 1_000, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), 0, null, null), origin)

    @Test fun missingDriveHasNoInventedPathsOriginOrAssessment() {
        val ref = DiagnosisAiHandoff.driveReference(id(), DiagnosisDriveEvidence(null, "Unavailable"))
        assertFalse(ref.getValue("available").jsonPrimitive.boolean)
        for (key in listOf("archive", "charts", "origin", "status", "assessment_location"))
            assertEquals(JsonNull, ref[key])
        assertFalse(ref.getValue("assessment_present").jsonPrimitive.boolean)
        assertEquals("Unavailable", ref.getValue("warning").jsonPrimitive.content)
    }

    @Test fun partialAndUnverifiedOriginsArePreservedWithoutNewAssessments() {
        for (origin in DriveEvidenceOrigin.entries) {
            val drive = drive(origin, SavedDriveStatus.CHECKPOINT)
            val ref = DiagnosisAiHandoff.driveReference(drive.id, DiagnosisDriveEvidence(drive, "Partial checkpoint"))
            assertEquals(origin.name, ref.getValue("origin").jsonPrimitive.content)
            assertEquals("CHECKPOINT", ref.getValue("status").jsonPrimitive.content)
            assertEquals("Partial checkpoint", ref.getValue("warning").jsonPrimitive.content)
            assertEquals("drives/${drive.id}.json", ref.getValue("archive").jsonPrimitive.content)
            assertFalse(ref.getValue("assessment_present").jsonPrimitive.boolean)
        }
    }

    @Test fun indexPreservesSeparateClocksLostSamplesAndMissingLinks() {
        val driveId = id()
        val a = DiagnosisSegment(id(), 1, null, 1_000, 0, 400, driveId, "Disconnected", 5, 2)
        val b = DiagnosisSegment(id(), 2, null, 3_000, 50, null, null, null, 0, 0)
        val record = DiagnosisRecord(id(), 1_000, 3_000, DiagnosisStatus.INTERRUPTED, listOf(a, b))
        val index = Json.parseToJsonElement(DiagnosisAiHandoff.index(record,
            listOf(DiagnosisAiHandoff.driveReference(driveId, DiagnosisDriveEvidence(null))))).jsonObject
        assertEquals("openwrench.ai-evidence/1", index.getValue("schema").jsonPrimitive.content)
        assertEquals("INTERRUPTED", index.getValue("status").jsonPrimitive.content)
        assertFalse(index.getValue("cloud_upload_performed").jsonPrimitive.boolean)
        val segments = index.getValue("segments").jsonArray
        assertEquals(a.id, segments[0].jsonObject.getValue("segment_id").jsonPrimitive.content)
        assertEquals(2, segments[0].jsonObject.getValue("dropped_receipts").jsonPrimitive.int)
        assertEquals(JsonNull, segments[1].jsonObject["drive_id"])
        assertEquals(JsonNull, segments[1].jsonObject["ended_offset_ms"])
        assertEquals(1, index.getValue("drives").jsonArray.size)
    }

    @Test fun instructionsRequireEvidenceAndDiscriminatingChecksNotPartsVerdicts() {
        val text = DiagnosisAiHandoff.requestText
        for (term in listOf("Competing explanations", "Next distinguishing checks",
            "positive/negative/inconclusive", "NO_DATA/null/missing is not zero",
            "untrusted evidence", "high voltage", "review.assessment", "context limits"))
            assertContains(text, term)
        assertContains(text, "No cloud response is automatically accepted")
        assertContains(text, "symptom-free baseline, not")
        assertContains(text, "Missing or uncertain symptom status is not symptom-free")
        assertContains(text, "timing is unknown, do not assign it")
        assertContains(text, "an incomplete monitor and a reported failure")
        assertContains(text, "is not measured component behavior")
        assertContains(text, "is not measured mixture error")
    }
}
