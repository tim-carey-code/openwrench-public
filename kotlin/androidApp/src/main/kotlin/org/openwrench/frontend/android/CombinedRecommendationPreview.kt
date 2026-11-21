package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.ScanOrigin
import java.security.MessageDigest

/** Content revision is a canonical document digest, distinct from the store's exact-file byte token. */
internal data class CombinedPreviewRequest(val expectedRevision: String, val caseId: String,
    val startingReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val timingReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val adaptationReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val questionRevisionId: String? = null)
internal enum class CombinedOutcomeUse {
    SCOPED_ATTRIBUTED_ONLY, OTHER_CASE, OTHER_QUESTION, OTHER_REPORT_CONTEXT,
    UNSUPPORTED_QUESTION, LEGACY_CONTEXT_UNAVAILABLE
}
internal data class CombinedOutcomeTrace(val event: CombinedEvent, val questionRevisionId: String,
    val catalogQuestionId: InvestigationQuestionId?, val use: CombinedOutcomeUse, val explanation: String,
    val scope: ManualScopeAssessment?)
internal data class CombinedCaseTrace(val caseId: String, val attachmentRevisionId: String?,
    val ownerVehicleAssertion: FixtureVehicleAssertion, val scanOrigin: ScanOrigin, val driveOrigin: DriveEvidenceOrigin,
    val scanEcu: String?, val driveEcu: String?)
internal enum class PreviewOriginPolicy { SYNTHETIC_ONLY, RETAINED_ORIGINS_UNVERIFIED }
internal data class CombinedRecommendationReport(val revision: String, val request: CombinedPreviewRequest,
    val source: CombinedCaseTrace, val retainedBasis: InvestigationReasoningReport,
    val activeOutcomes: List<CombinedOutcomeTrace>, val supersededResultIds: List<String>,
    val recommendations: List<InvestigationRecommendation>, val limitations: List<String>, val policyVersion: Int, val fictionalOnly: Boolean) {
    val originPolicy get() = if (fictionalOnly) PreviewOriginPolicy.SYNTHETIC_ONLY else PreviewOriginPolicy.RETAINED_ORIGINS_UNVERIFIED
    val nextQuestion get() = recommendations.firstOrNull()
}
internal enum class CombinedPreviewUnsupported { QUESTION_NOT_MAPPABLE }
internal sealed interface CombinedPreviewResult {
    data class Ready(val report: CombinedRecommendationReport) : CombinedPreviewResult
    data class Stale(val expectedRevision: String, val actualRevision: String) : CombinedPreviewResult
    data class Invalid(val issues: List<CombinedFieldIssue>) : CombinedPreviewResult
    data class Unsupported(val reason: CombinedPreviewUnsupported, val revision: String, val detail: String) : CombinedPreviewResult
}

/** Pure, deterministic and fixture-only. No persistence, IO owner activation, procedure or vehicle operation. */
internal object CombinedRecommendationPreview {
    const val VERSION = 1
    fun revision(document: CombinedInvestigation): String = MessageDigest.getInstance("SHA-256")
        .digest(CombinedInvestigationCodec.encode(document).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun preview(document: CombinedInvestigation, request: CombinedPreviewRequest): CombinedPreviewResult =
        previewLog(document, request, { revision(document) }, fictionalOnly = true)

    internal fun previewRetained(document: RetainedInvestigation, request: CombinedPreviewRequest): CombinedPreviewResult =
        previewLog(document, request, { RetainedInvestigationCodec.revision(document) }, fictionalOnly = false)

    private fun previewLog(document: InvestigationRevisionLog, request: CombinedPreviewRequest,
        contentRevision: () -> String, fictionalOnly: Boolean): CombinedPreviewResult {
        fun invalid(field: String, message: String): Nothing = throw CombinedValidation(
            CombinedFieldIssue(field, CombinedIssueCode.INVALID_REFERENCE_OR_SCOPE, message))
        try {
            val revision = contentRevision() // validates the original global history before any projection
            if (request.expectedRevision != revision) return CombinedPreviewResult.Stale(request.expectedRevision, revision)
            val accountLabel = if (fictionalOnly) "fictional" else "author-attributed"
            val selectedCase = document.cases().singleOrNull { it.id == request.caseId }
                ?: invalid("caseId", "Select a retained case in this exact investigation revision.")
            val ordinary = document.events.filterIsInstance<CombinedEvent.Ordinary>().map { it.revision }
            val byId = ordinary.associateBy { it.id }
            fun selection(field: String, selection: OwnerReportSelection, accepts: (InvestigationStep) -> Boolean) {
                try { selection.validate() } catch (error: Exception) { invalid(field, "Select an exact report or explicitly leave it unresolved.") }
                selection.revisionId?.let { if (byId[it]?.step?.let(accepts) != true) invalid(field, "Select a reported catalog answer of the correct kind, including explicit UNKNOWN.") }
            }
            selection("startingReport", request.startingReport) { InvestigationQuestionCatalog.startingValue(it) != null }
            selection("timingReport", request.timingReport) { InvestigationQuestionCatalog.timingValue(it) != null }
            selection("adaptationReport", request.adaptationReport) { InvestigationQuestionCatalog.adaptationValue(it) != null }
            request.questionRevisionId?.let { id ->
                val question = byId[id]?.step as? InvestigationStep.ProposedTest ?: invalid("questionRevisionId", "Select an exact prior proposed question.")
                if (question.kind == InvestigationTestKind.ACTUATOR_UNSUPPORTED || InvestigationQuestionCatalog.identify(question) == null)
                    return CombinedPreviewResult.Unsupported(CombinedPreviewUnsupported.QUESTION_NOT_MAPPABLE, revision,
                        "This exact question is unsupported by the curated catalog. No result text or executable capability is inferred.")
            }
            // Read-only evidence/context projection: omit ALL result payloads, including superseded legacy assertions.
            // Never manufacture legacy observations/results from typed manual values. No projected journal is returned or saved.
            val context = ordinary.filter { it.step !is InvestigationStep.TestResult }.mapIndexed { index, r -> r.copy(sequence = index + 1) }
            val basis = InvestigationReasoning.recommendExplicitContext(
                SavedInvestigation(DiagnosticInvestigation(document.id, context), document.cases()), request.caseId,
                request.startingReport.revisionId, request.timingReport.revisionId)
            val activeIds = document.activeResultIds().values.toSet()
            val traces = document.events.filter { it.id in activeIds }.map { event ->
                when (event) {
                    is CombinedEvent.Manual -> {
                        val outcome = event.outcome
                        val questionId = outcome.context.questionRevisionId
                        val question = byId.getValue(questionId).step as InvestigationStep.ProposedTest
                        val catalogId = InvestigationQuestionCatalog.identify(question)
                        val (use, explanation) = when {
                            request.questionRevisionId != null && questionId != request.questionRevisionId -> CombinedOutcomeUse.OTHER_QUESTION to
                                "Active result belongs to another exact question; it does not answer the focused question."
                            outcome.context.caseId != request.caseId -> CombinedOutcomeUse.OTHER_CASE to
                                "Active result belongs to another retained case; no common vehicle, occasion or tested conditions are inferred."
                            outcome.context.startingReport != request.startingReport || outcome.context.timingReport != request.timingReport ||
                                outcome.context.adaptationReport != request.adaptationReport -> CombinedOutcomeUse.OTHER_REPORT_CONTEXT to
                                "Active result has different or unresolved owner-answer references. It is not a result for the selected context."
                            catalogId != InvestigationQuestionId.VAPOR_SEALING || outcome.context.catalog != OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, InvestigationQuestionCatalog.VERSION) ->
                                CombinedOutcomeUse.UNSUPPORTED_QUESTION to "Only an exact catalog-bound sealing/flow outcome can be mapped in this slice."
                            else -> CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY to
                                "${if (fictionalOnly) "Fictional" else "Retained"} author-attributed outcome only. Unknown/inconclusive is not a negative result; effects do not establish cause or repair."
                        }
                        CombinedOutcomeTrace(event, questionId, catalogId, use, explanation, outcome.scopeAssessment())
                    }
                    is CombinedEvent.Ordinary -> {
                        val result = event.revision.step as InvestigationStep.TestResult
                        val question = byId.getValue(result.testId).step as InvestigationStep.ProposedTest
                        CombinedOutcomeTrace(event, result.testId, InvestigationQuestionCatalog.identify(question), CombinedOutcomeUse.LEGACY_CONTEXT_UNAVAILABLE,
                            "Legacy result has no exact typed case/owner-answer scope. It remains retained, but cannot safely modify this combined preview.", null)
                    }
                    is CombinedEvent.Attachment -> error("Attachment cannot be an active test result")
                }
            }
            val consumed = traces.filter { it.use == CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY }
            val scopedIds = consumed.map { it.event.id }
            val recommendations = basis.recommendations.map { recommendation ->
                val associated = consumed.filter { it.catalogQuestionId == recommendation.question.id }
                if (associated.isEmpty()) recommendation else recommendation.copy(
                    why = recommendation.why + " Active scoped $accountLabel accounts: " + associated.joinToString { trace ->
                        val outcome = (trace.event as CombinedEvent.Manual).outcome
                        "${outcome.id} answers ${trace.questionRevisionId}: ${outcome.declaredOutcome}; method ${outcome.method}."
                    } + " These accounts do not close the question or prove a fault mode.",
                    missingEvidence = recommendation.missingEvidence + associated.flatMap { it.scope!!.gaps }.distinct(),
                    activeResultIds = associated.map { it.event.id })
            }.toMutableList()
            // Preserve prerequisite ordering. Scope review is useful only within the established running-correction path.
            val technical = recommendations.indexOfFirst { it.question.id in setOf(InvestigationQuestionId.INPUT_PLAUSIBILITY,
                InvestigationQuestionId.VAPOR_SEALING, InvestigationQuestionId.FUEL_DELIVERY) }
            if (consumed.isNotEmpty() && technical >= 0) recommendations.add(technical,
                InvestigationRecommendation(InvestigationQuestionCatalog.question(InvestigationQuestionId.RESULT_SCOPE),
                    "Review the exact method, tested fault mode, conditions and uncertainty of active $accountLabel accounts: ${scopedIds.joinToString()}. " +
                        "A click/acknowledgment or continuity is not sealing/flow proof; pressure decay alone does not identify a component. " +
                        "Normal concerns only the tested scope, and unknown/inconclusive remains unknown.",
                    emptyList(), consumed.flatMap { it.scope!!.gaps }.distinct(), scopedIds))
            val attachment = document.events.filterIsInstance<CombinedEvent.Attachment>().singleOrNull { it.retainedCase.id == request.caseId }
            val source = CombinedCaseTrace(request.caseId, attachment?.id, attachment?.vehicleAssertion ?: FixtureVehicleAssertion.UNKNOWN,
                selectedCase.scan.capture!!.origin, selectedCase.drive.origin, selectedCase.scan.capture.sourceEcu, selectedCase.drive.sourceEcu)
            val superseded = document.events.filter { event ->
                event is CombinedEvent.Manual || event is CombinedEvent.Ordinary && event.revision.step is InvestigationStep.TestResult
            }.filter { it.id !in activeIds }.map { it.id }
            return CombinedPreviewResult.Ready(CombinedRecommendationReport(revision, request, source, basis, traces, superseded,
                recommendations, basis.limitations + listOf(
                    "${if (fictionalOnly) "Fixture-only" else "Disabled retained-candidate"} combined preview. Manual declarations, values and hypothesis effects are attributed accounts, not qualified OBD evidence or verified causation.",
                    "Only the selected retained case supplies measured evidence; case attachment and unknown ECU attribution do not establish shared vehicle, event or conditions.",
                    "Legacy/unmapped/other-case/question/context outcomes remain visible and are not silently treated as passed, normal or negative.",
                    "No hypothesis closure, cause ranking, OEM specification, executable test, actuator support or repair verdict is generated."), VERSION, fictionalOnly))
        } catch (error: CombinedValidation) {
            return CombinedPreviewResult.Invalid(listOf(error.issue))
        } catch (error: Exception) {
            return CombinedPreviewResult.Invalid(listOf(CombinedFieldIssue("document", CombinedIssueCode.INVALID_LOG,
                error.message ?: "Invalid combined input; no recommendations derived.")))
        }
    }
}
