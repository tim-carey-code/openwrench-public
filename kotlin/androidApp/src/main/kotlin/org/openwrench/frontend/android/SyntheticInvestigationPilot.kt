package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.*
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Versioned, deterministic fictional seed. Never reads scan, drive, case or investigation histories. */
internal object SyntheticInvestigationSeed {
    const val ID = "fictional-pilot-v1"
    val questions = listOf("sealing-first", "sealing-later")
    val legacyJson: String by lazy {
        val capture = ScanCapture("00000000-0000-4000-8000-000000000001", ScanOrigin.SYNTHETIC, null, null,
            null, null, null, 3000, setOf(5), listOf(NativeReading(5, DecodedReading.Scalar(90.0))), emptyList(), null)
        val scan = SavedScan("00000000-0000-4000-8000-000000000002", 3000,
            "OPENWRENCH read-only diagnostic scan\nFICTIONAL PILOT ONLY\nVIN omitted.\n", null, capture)
        val inputs = (LiveHealthInputs.scalarPids + LiveHealthInputs.structuredPids).toSet()
        val recorder = DriveHealthAssessmentRecorder(0, inputs, inputs)
        repeat(11) { index ->
            val at = 100 + index * 3000L
            fun scalar(pid: Int, value: Double, offset: Long) = recorder.offer(NativeReading(pid, DecodedReading.Scalar(value)), at + offset)
            scalar(12, 900.0, 0); scalar(13, 0.0, 1); scalar(4, 30.0, 2); scalar(5, 90.0, 3)
            recorder.offer(NativeReading(3, DecodedReading.Structured(mapOf("system_1" to FuelCorrectionRecorder.CLOSED, "system_2" to "engine off"))), at + 4)
            scalar(6, -5.0, 5); scalar(7, -16.0, 6)
        }
        recorder.stop(36000)
        val review = LiveHealthDriveReview(1, 0, 36000, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf("FICTIONAL PILOT ONLY; no vehicle receipts"), 0, null, null, recorder.snapshot())
        val drive = SavedDrive("00000000-0000-4000-8000-000000000003", 1000, 40000, SavedDriveStatus.COMPLETED,
            "Fictional completed drive", null, 0, "OPENWRENCH live health monitoring\nFICTIONAL PILOT ONLY\n" + review.reportText(), review, DriveEvidenceOrigin.SYNTHETIC)
        val case = DiagnosticCaseBuilder.create(scan, drive, true, 41000).copy(id = "00000000-0000-4000-8000-000000000004")
        var journal = DiagnosticInvestigation(ID, listOf(InvestigationRevision("complaint", 1, 42000, "Fictional seed author", "Immutable pilot seed 1",
            InvestigationStep.Complaint("FICTIONAL illustration inspired by a reported 2013 Legacy 3.6R cold-start/-16% story. This is not Nick's capture, verified symptom, two-bank finding or purge diagnosis."))))
        fun add(id: String, reason: String, step: InvestigationStep) { journal = journal.append(id, 42000, "Fictional seed author", reason, step) }
        add("starting", "Fictional owner account", InvestigationQuestionCatalog.startingReport(StartingBehavior.NORMAL_SPEED_LONG_CRANK, "Fictional owner account, not measured cranking"))
        add("timing", "Different fictional occasions", InvestigationQuestionCatalog.timingReport(SymptomTiming.DIFFERENT_OCCASIONS, "Earlier cold-start story; later synthetic running interval"))
        add("adaptation", "Reset history unknown", InvestigationQuestionCatalog.adaptationReport(AdaptationHistory.UNKNOWN, "Fictional unknown history"))
        add("reported-bank2", "Narrative is not bank evidence", InvestigationStep.Observation(InvestigationObservationKind.REPORTED, "Both banks allegedly negative", "Fictional reported claim; Bank 2 has no retained qualified interval"))
        add("manual", "No sealing measurement", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Fictional click account", "Illustration only; a click cannot prove sealing/flow"))
        add("vapor", "Competing explanation, not a failed part", InvestigationStep.Hypothesis("Unwanted vapor flow may contribute under some conditions; unproven", listOf("manual")))
        add("alternative", "Keep alternatives open", InvestigationStep.Hypothesis("Input plausibility or another fuel-delivery explanation remains open", listOf("starting")))
        questions.forEachIndexed { index, id -> add(id, if (index == 0) "Initial fictional occasion" else "Later fictional occasion, independently scoped",
            InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("vapor", "alternative"))) }
        InvestigationCodec.encode(SavedInvestigation(journal, listOf(case)))
    }
    fun saved() = InvestigationCodec.decode(legacyJson)
}

internal data class SyntheticOutcomeDraft(
    val questionId: String = SyntheticInvestigationSeed.questions.first(), val author: String = "", val reason: String = "", val provenance: String = "",
    val method: ManualOutcomeMethod = ManualOutcomeMethod.UNKNOWN, val outcome: DeclaredManualOutcome = DeclaredManualOutcome.UNKNOWN,
    val faultMode: ManualFaultMode = ManualFaultMode.UNKNOWN, val observer: String = "", val methodDetail: String = "",
    val conditions: String = "", val criteria: String = "", val applicability: ReportedApplicability = ReportedApplicability.UNKNOWN,
    val observedAt: String = "", val value: String = "", val unit: ManualValueUnit = ManualValueUnit.UNKNOWN, val otherUnit: String = "",
    val vaporEffect: HypothesisEffect = HypothesisEffect.UNCHANGED, val alternativeEffect: HypothesisEffect = HypothesisEffect.UNCHANGED,
    val effectReason: String = "", val corrects: String? = null,
) {
    /** Shared by inline feedback and the persistence boundary; no coercion, clamp or default zero. */
    fun manualValueError(): String? {
        if (value.isBlank()) return null
        val parsed = value.trim().toDoubleOrNull()
        return if (parsed == null || !parsed.isFinite())
            "Enter a finite manual value (for example, -16 or 0), or leave it blank if unknown."
        else null
    }
    private fun observedTime(text: String): Long {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z").matches(text)) { "Use UTC yyyy-MM-ddTHH:mm:ssZ or leave observation time unknown" }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        return requireNotNull(format.parse(text, position)?.takeIf { position.index == text.length }) { "Invalid UTC observation time" }.time
    }
    fun record(snapshot: OutcomeSandboxSnapshot, id: String, now: Long): AttributedManualOutcome {
        manualValueError()?.let { throw IllegalArgumentException(it) }
        require(questionId in SyntheticInvestigationSeed.questions)
        require(provenance.isNotBlank()) { "Attribute the fictional result source" }
        fun detail(text: String) = if (text.isBlank()) ReportedDetail(ReportKnowledge.UNKNOWN) else ReportedDetail(ReportKnowledge.REPORTED, text.trim())
        val case = snapshot.contract.legacySnapshot().cases.single()
        val rationale = effectReason.trim().ifEmpty {
            require(vaporEffect == HypothesisEffect.UNCHANGED && alternativeEffect == HypothesisEffect.UNCHANGED) { "Explain any supporting or weakening assertion" }
            "No change asserted; fictional outcome only"
        }
        return AttributedManualOutcome(id, snapshot.contract.legacySnapshot().journal.revisions.size + snapshot.contract.outcomes.size + 1,
            now, author.trim(), reason.trim(), ManualOutcomeContext(questionId, case.id, OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 1),
                OwnerReportSelection(ReportSelection.SELECTED, "starting"), OwnerReportSelection(ReportSelection.SELECTED, "timing"), listOf("manual"), emptyList(),
                OwnerReportSelection(ReportSelection.SELECTED, "adaptation")), ManualOutcomeOrigin.SYNTHETIC, faultMode, method, outcome,
            detail(observer), detail(methodDetail), detail(conditions), detail(criteria), applicability,
            observedAt.trim().takeIf { it.isNotEmpty() }?.let { observedTime(it) }, provenance.trim(),
            if (value.isBlank()) emptyList() else listOf(AttributedManualValue(value.trim().toDouble(), unit, if (unit == ManualValueUnit.OTHER_REPORTED) otherUnit.trim() else null)),
            listOf(InvestigationEffect("vapor", vaporEffect, rationale), InvestigationEffect("alternative", alternativeEffect, rationale)), corrects).also { it.validate() }
    }
}

/** One fresh synthetic namespace; no installed history stores, imports, migrations or deletion API. */
internal class SyntheticPilotRepository(root: File, files: OutcomeSandboxFiles = OutcomeSandboxFiles()) {
    private fun validatePilot(contract: ManualOutcomeContract) {
        require(contract.legacyJson == SyntheticInvestigationSeed.legacyJson)
        contract.outcomes.forEach { require(it.origin == ManualOutcomeOrigin.SYNTHETIC && it.context.questionRevisionId in SyntheticInvestigationSeed.questions) }
    }
    private val guardedFiles = object : OutcomeSandboxFiles() {
        override fun stage(file: File, bytes: ByteArray) = files.stage(file, bytes)
        override fun sync(file: File) = files.sync(file)
        override fun publish(pending: File, committed: File) {
            require(pending.length() <= InvestigationCodec.MAX_BYTES)
            validatePilot(ManualOutcomeContractCodec.decode(pending.readText(Charsets.UTF_8)))
            files.publish(pending, committed)
        }
    }
    private val store = ManualOutcomeSandboxStore(File(root, "synthetic-investigation-pilot-v1"), guardedFiles)
    fun inspect(): OutcomeSandboxState = store.inspect(SyntheticInvestigationSeed.ID, SyntheticInvestigationSeed.legacyJson).also { state ->
        state.committed?.contract?.let(::validatePilot)
    }
    fun start(): OutcomeSandboxState {
        val old = inspect()
        if (old.committed == null && old.pending == null) store.create(SyntheticInvestigationSeed.legacyJson)
        return inspect()
    }
    fun append(expected: String, draft: SyntheticOutcomeDraft, now: Long, id: String = UUID.randomUUID().toString()): OutcomeSandboxState {
        val snapshot = requireNotNull(inspect().committed) { "Start the fictional pilot first" }
        require(snapshot.digest == expected) { "Pilot changed; reload before recording" }
        val record = draft.record(snapshot, id, now)
        store.append(SyntheticInvestigationSeed.ID, expected, SyntheticInvestigationSeed.legacyJson, record)
        return inspect()
    }
    fun recover(expectedCommitted: String?, expectedPending: String): OutcomeSandboxState {
        // Store validates a complete extension before publishing. No discard path is exposed.
        store.recover(SyntheticInvestigationSeed.ID, expectedCommitted, expectedPending, SyntheticInvestigationSeed.legacyJson)
        return inspect()
    }
}

internal data class SyntheticPilotPreview(val basis: InvestigationReasoningReport, val active: List<AttributedManualOutcome>,
    val nextQuestion: InvestigationQuestion, val why: String, val gaps: List<String>)
internal object SyntheticPilotReasoning {
    fun preview(contract: ManualOutcomeContract): SyntheticPilotPreview {
        contract.validate()
        require(contract.legacyJson == SyntheticInvestigationSeed.legacyJson && contract.outcomes.all { it.origin == ManualOutcomeOrigin.SYNTHETIC })
        val saved = contract.legacySnapshot()
        val basis = InvestigationReasoning.recommend(saved, saved.cases.single().id, "starting", "timing")
        val activeIds = contract.activeResultIds().values.toSet()
        val active = contract.outcomes.filter { it.id in activeIds }
        val latest = active.lastOrNull()
        val gaps = latest?.scopeAssessment()?.gaps.orEmpty()
        val next = when {
            latest == null -> basis.nextQuestion.question
            gaps.isNotEmpty() -> InvestigationQuestionCatalog.question(InvestigationQuestionId.RESULT_SCOPE)
            else -> InvestigationQuestionCatalog.question(InvestigationQuestionId.INPUT_PLAUSIBILITY)
        }
        return SyntheticPilotPreview(basis, active, next, when {
            latest == null -> basis.nextQuestion.why
            gaps.isNotEmpty() -> "The active fictional account leaves scope or method gaps. Clarify what was actually reported before using an author's effect. A click, continuity or pressure decay alone cannot establish sealing/flow."
            else -> "Illustration only: a scoped author assertion motivates examining the competing input explanation. It does not establish leakage as the full cause, rule out intermittent behavior or prove repair."
        }, gaps)
    }
}
