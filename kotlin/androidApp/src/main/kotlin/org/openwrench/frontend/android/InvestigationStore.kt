package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import java.io.File
import java.io.FileOutputStream

/** Separate storage: frozen cases and their original assessment semantics are never rewritten. */
data class SavedInvestigation(val journal: DiagnosticInvestigation, val cases: List<DiagnosticCase>) {
    fun validate() {
        journal.validate()
        require(cases.isNotEmpty())
        DiagnosticCaseCodec.encode(cases)
        val byId = cases.associateBy { it.id }
        journal.revisions.forEach { r ->
            val s = r.step as? InvestigationStep.Observation ?: return@forEach
            val e = s.evidence ?: return@forEach
            val case = requireNotNull(byId[e.caseId]) { "Referenced case evidence is missing" }
            when (e.kind) {
                InvestigationObservationKind.CASE_SCAN_READING -> {
                    val capture = requireNotNull(case.scan.capture)
                    require(e.pid in capture.eligiblePids)
                    require(capture.readings.singleOrNull { it.pid == e.pid }?.value != null) { "No measured reading at this reference" }
                }
                InvestigationObservationKind.CASE_FUEL_INTERVAL -> {
                    val finding = case.drive.review.assessment?.findings?.getOrNull(e.findingIndex!!)
                    require(finding?.fuelIntervals?.getOrNull(e.intervalIndex!!) != null) { "No retained qualified interval at this reference" }
                }
                else -> error("Not a retained-evidence reference")
            }
        }
    }

    /** Only these two selected retained intervals are compared; never substitutes other banks/windows. */
    fun compareRepairReview(revisionId: String, sameVehicleConfirmed: Boolean): InvestigationRepairComparison {
        validate()
        val review = journal.revisions.single { it.id == revisionId }.step as? InvestigationStep.RepairReview
            ?: error("Not a repair review")
        fun selected(id: String): Pair<DiagnosticCase, FuelCorrectionInterval> {
            val observation = journal.revisions.single { it.id == id }.step as InvestigationStep.Observation
            require(observation.kind == InvestigationObservationKind.CASE_FUEL_INTERVAL) { "Comparison needs retained paired evidence" }
            val ref = observation.evidence!!
            val case = cases.single { it.id == ref.caseId }
            return case to case.drive.review.assessment!!.findings[ref.findingIndex!!].fuelIntervals[ref.intervalIndex!!]
        }
        val (before, a) = selected(review.beforeObservationId)
        val (after, b) = selected(review.afterObservationId)
        fun overlap(x: FuelCorrectionBounds, y: FuelCorrectionBounds) =
            maxOf(x.minimum.value, y.minimum.value) <= minOf(x.maximum.value, y.maximum.value)
        val compatible = sameVehicleConfirmed && before.drive.id != after.drive.id &&
            before.drive.origin == DriveEvidenceOrigin.HARDWARE && after.drive.origin == DriveEvidenceOrigin.HARDWARE &&
            before.drive.sourceEcu != null && before.drive.sourceEcu == after.drive.sourceEcu &&
            before.drive.review.assessment!!.packId == "standard-obd-drive" &&
            after.drive.review.assessment!!.packId == "standard-obd-drive" &&
            before.drive.review.assessment.packVersion == "3" && after.drive.review.assessment.packVersion == "3" &&
            a.bank == b.bank && a.policyVersion == FuelCorrectionPolicy.VERSION && a.policyVersion == b.policyVersion && a.condition == b.condition &&
            a.system1 == b.system1 && a.system2 == b.system2 &&
            overlap(a.contextBounds.rpm, b.contextBounds.rpm) && overlap(a.contextBounds.load, b.contextBounds.load) &&
            overlap(a.contextBounds.coolant, b.contextBounds.coolant)
        return InvestigationRepairComparison(compatible, if (compatible)
            "Selected same-bank intervals have compatible recorded conditions under policy 1. Matching ranges do not establish equal histories."
            else "Comparison unavailable: distinct drives, explicit same-vehicle confirmation, hardware origin, matching observed source, assessment policy, bank, fuel state and overlapping RPM/load/coolant ranges are required.")
    }

}

data class InvestigationRepairComparison(val compatibleRecordedConditions: Boolean, val explanation: String) {
    val repairStatus: String get() = "NOT_ESTABLISHED"
    val limitation: String get() = "Trim improvement alone does not prove a repair or symptom resolution; symptom follow-up remains user-reported."
}

/** Strict versioned journal envelope; unknown versions/fields and duplicate JSON keys fail closed. */
object InvestigationCodec {
    const val MAX_BYTES = 25_000_000
    private fun arr(vararg values: JsonElement) = JsonArray(values.toList())
    private fun str(value: String) = JsonPrimitive(value)
    private fun strings(values: List<String>) = JsonArray(values.map(::str))
    private fun number(value: Int?) = value?.let(::JsonPrimitive) ?: JsonNull
    private fun evidence(e: InvestigationEvidenceRef?) = e?.let {
        arr(str(it.caseId), str(it.kind.name), number(it.pid), number(it.findingIndex), number(it.intervalIndex))
    } ?: JsonNull
    private fun step(s: InvestigationStep): JsonArray = when (s) {
        is InvestigationStep.Complaint -> arr(str("complaint"), str(s.reported))
        is InvestigationStep.Observation -> arr(str("observation"), str(s.kind.name), str(s.description), str(s.provenance), evidence(s.evidence))
        is InvestigationStep.Hypothesis -> arr(str("hypothesis"), str(s.explanation), strings(s.observationIds))
        is InvestigationStep.ProposedTest -> arr(str("test"), str(s.kind.name), str(s.question), strings(s.hypothesisIds), str(s.distinguishingOutcomes))
        is InvestigationStep.TestResult -> arr(str("result"), str(s.testId), str(s.outcome.name), str(s.result), str(s.provenance), strings(s.observationIds),
            JsonArray(s.effects.map { arr(str(it.hypothesisId), str(it.effect.name), str(it.rationale)) }), s.supersedesResultId?.let(::str) ?: JsonNull)
        is InvestigationStep.RepairReview -> arr(str("repair_review"), str(s.beforeObservationId), str(s.afterObservationId), str(s.repairReported), str(s.symptomReported), str(s.provenance))
    }
    internal fun encodeRevision(r: InvestigationRevision): JsonArray =
        arr(str(r.id), JsonPrimitive(r.sequence), JsonPrimitive(r.recordedAtEpochMillis), str(r.author), str(r.reason), step(r.step))
    internal fun decodeRevision(value: JsonElement): InvestigationRevision = value.row(6).let { a ->
        InvestigationRevision(a[0].text(), a[1].int(), a[2].long(), a[3].text(), a[4].text(), parseStep(a[5]))
    }
    fun encode(saved: SavedInvestigation): String {
        saved.validate()
        return buildJsonObject {
            put("schema", "openwrench.investigation/1"); put("policy", saved.journal.policyVersion); put("id", saved.journal.id)
            put("cases", Json.parseToJsonElement(DiagnosticCaseCodec.encode(saved.cases)))
            put("revisions", JsonArray(saved.journal.revisions.map(::encodeRevision)))
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Investigation storage limit reached" } }
    }
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonElement.long() = jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonElement.int(): Int = long().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonElement.texts() = jsonArray.also { require(it.size <= 32) }.map { it.text() }
    private fun JsonElement.row(size: Int) = jsonArray.also { require(it.size == size) }
    private fun parseEvidence(e: JsonElement): InvestigationEvidenceRef? {
        if (e == JsonNull) return null
        val a = e.row(5)
        fun optional(i: Int) = if (a[i] == JsonNull) null else a[i].int()
        return InvestigationEvidenceRef(a[0].text(), enumValueOf(a[1].text()), optional(2), optional(3), optional(4))
    }
    private fun parseStep(e: JsonElement): InvestigationStep {
        val a = e.jsonArray
        return when (a.first().text()) {
            "complaint" -> { e.row(2); InvestigationStep.Complaint(a[1].text()) }
            "observation" -> { e.row(5); InvestigationStep.Observation(enumValueOf(a[1].text()), a[2].text(), a[3].text(), parseEvidence(a[4])) }
            "hypothesis" -> { e.row(3); InvestigationStep.Hypothesis(a[1].text(), a[2].texts()) }
            "test" -> { e.row(5); InvestigationStep.ProposedTest(enumValueOf(a[1].text()), a[2].text(), a[3].texts(), a[4].text()) }
            "result" -> {
                e.row(8)
                InvestigationStep.TestResult(a[1].text(), enumValueOf(a[2].text()), a[3].text(), a[4].text(), a[5].texts(),
                    a[6].jsonArray.also { require(it.size <= 32) }.map { effect -> effect.row(3).let {
                        InvestigationEffect(it[0].text(), enumValueOf(it[1].text()), it[2].text())
                    } }, if (a[7] == JsonNull) null else a[7].text())
            }
            "repair_review" -> { e.row(6); InvestigationStep.RepairReview(a[1].text(), a[2].text(), a[3].text(), a[4].text(), a[5].text()) }
            else -> error("Unsupported investigation step")
        }
    }
    fun decode(text: String): SavedInvestigation {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "policy", "id", "cases", "revisions"))
        require(root.getValue("schema").text() == "openwrench.investigation/1")
        val revisions = root.getValue("revisions").jsonArray.also { require(it.size <= DiagnosticInvestigation.MAX_REVISIONS) }.map(::decodeRevision)
        return SavedInvestigation(DiagnosticInvestigation(root.getValue("id").text(), revisions, root.getValue("policy").int()),
            DiagnosticCaseCodec.decode(root.getValue("cases").toString())).also { it.validate() }
    }
}

/** App-private, bounded, append-only writes with optimistic concurrency across recreated store instances.
 * Caller owns background dispatch. Corrupt files block mutations; there is no eviction or migration of old histories.
 */
class InvestigationStore(private val root: File) {
    companion object {
        private val lock = Any()
        const val MAX_INVESTIGATIONS = 10
    }
    private val directory get() = File(root, "investigations-v1")
    private fun file(id: String): File { DiagnosticInvestigation.validId(id); return File(directory, "$id.json") }
    fun load(id: String): SavedInvestigation = synchronized(lock) {
        val f = file(id)
        require(f.isFile && f.length() <= InvestigationCodec.MAX_BYTES) { "Investigation is unavailable or too large" }
        InvestigationCodec.decode(f.readText(Charsets.UTF_8)).also { require(it.journal.id == id) }
    }
    /** A bad archive stays visible with its error; it never hides other saved investigations. */
    fun list(): List<InvestigationListing> = synchronized(lock) {
        if (!directory.exists()) return@synchronized emptyList()
        require(directory.isDirectory) { "Investigation history is unavailable" }
        val files = requireNotNull(directory.listFiles()) { "Cannot list investigation history" }
        require(files.size <= MAX_INVESTIGATIONS * 2) { "Unexpected investigation directory contents; no files were removed" }
        files.sortedBy { it.name }.map { f ->
            val id = f.name.removeSuffix(".json")
            try {
                require(f.name.endsWith(".json")) { "Unfinished or unknown history file" }
                val saved = load(id)
                InvestigationListing(id, (saved.journal.revisions.first().step as InvestigationStep.Complaint).reported,
                    saved.journal.revisions.size, saved.journal.revisions.last().recordedAtEpochMillis, storageKey = f.name)
            } catch (error: Exception) {
                InvestigationListing(f.name, "Unavailable investigation", 0, null, error.message ?: "Cannot read archive", f.name)
            }
        }
    }
    fun find(id: String): SavedInvestigation? = synchronized(lock) {
        if (file(id).exists()) load(id) else null
    }
    fun create(saved: SavedInvestigation): SavedInvestigation = synchronized(lock) {
        require(saved.journal.revisions.size == 1) { "Create from the complaint, then append" }
        require(directory.isDirectory || directory.mkdirs())
        val entries = requireNotNull(directory.listFiles())
        require(entries.size < MAX_INVESTIGATIONS) { "Investigation history is full; existing history was kept" }
        require(!file(saved.journal.id).exists()) { "Investigation already exists" }
        write(saved)
    }
    fun append(id: String, expectedRevisionId: String, revisionId: String, atEpochMillis: Long,
        author: String, reason: String, step: InvestigationStep, additionalCases: List<DiagnosticCase> = emptyList()): SavedInvestigation = synchronized(lock) {
        val old = load(id)
        require(old.journal.revisions.last().id == expectedRevisionId) { "Investigation changed; reload before appending" }
        require(additionalCases.none { new -> old.cases.any { it.id == new.id } }) { "Frozen case cannot be replaced" }
        write(SavedInvestigation(old.journal.append(revisionId, atEpochMillis, author, reason, step), old.cases + additionalCases))
    }
    private fun write(saved: SavedInvestigation): SavedInvestigation {
        val text = InvestigationCodec.encode(saved)
        val destination = file(saved.journal.id)
        val temporary = File(directory, "${saved.journal.id}.tmp")
        try {
            FileOutputStream(temporary).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
            check(temporary.renameTo(destination)) { "Could not replace investigation; prior history was kept" }
        } finally { temporary.delete() }
        // Return a detached, validated snapshot rather than caller-owned mutable lists.
        return InvestigationCodec.decode(text)
    }
}

/** Browsing metadata only; opening always reloads and validates the full archive. */
data class InvestigationListing(val id: String, val complaint: String, val revisionCount: Int,
    val lastRecordedAtEpochMillis: Long?, val error: String? = null, val storageKey: String = id)
