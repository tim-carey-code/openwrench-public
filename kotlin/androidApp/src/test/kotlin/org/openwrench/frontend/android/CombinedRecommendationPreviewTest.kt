package org.openwrench.frontend.android

import org.openwrench.core.*
import kotlin.test.*

class CombinedRecommendationPreviewTest {
    private fun document() = CombinedInvestigationFixtures.completedCycle()
    private fun selected(id: String) = OwnerReportSelection(ReportSelection.SELECTED, id)
    private fun request(d: CombinedInvestigation, caseId: String = d.initialCases.single().id, question: String? = null) =
        CombinedPreviewRequest(CombinedRecommendationPreview.revision(d), caseId, selected("starting"), selected("timing"), selected("adaptation"), question)
    private fun report(d: CombinedInvestigation, request: CombinedPreviewRequest = request(d)) =
        assertIs<CombinedPreviewResult.Ready>(CombinedRecommendationPreview.preview(d, request)).report
    private fun alter(d: CombinedInvestigation, id: String, change: (AttributedManualOutcome) -> AttributedManualOutcome) = d.copy(events = d.events.map { event ->
        if (event.id == id) (event as CombinedEvent.Manual).copy(outcome = change(event.outcome)) else event
    }).also { it.validate() }

    @Test fun consumesScopedActiveOutcomeWhileKeepingCaseReceiptsAndUnknownSourcesSeparate() {
        val d = document(); val p = report(d)
        assertEquals(request(d).expectedRevision, p.revision)
        assertTrue(p.fictionalOnly)
        assertNull(p.source.scanEcu); assertNull(p.source.driveEcu)
        assertEquals(FixtureVehicleAssertion.UNKNOWN, p.source.ownerVehicleAssertion)
        assertEquals(BankEvidenceRelationship.MISSING_QUALIFIED_EVIDENCE, p.retainedBasis.bankRelationship)
        assertTrue(p.retainedBasis.correctionEvidence.all { it.interval.bank == 1 && it.origin == DriveEvidenceOrigin.SYNTHETIC && it.sourceEcu == null })
        assertTrue(p.retainedBasis.activeResults.isEmpty())
        assertEquals(listOf("result-a"), p.supersededResultIds)
        assertEquals(CombinedOutcomeUse.OTHER_CASE, p.activeOutcomes.single { it.event.id == "result-b" }.use)
        assertEquals(CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(InvestigationQuestionId.BANK_COVERAGE, p.nextQuestion!!.question.id)
        val scope = p.recommendations.single { it.question.id == InvestigationQuestionId.RESULT_SCOPE }
        assertEquals(listOf("correct-a"), scope.activeResultIds)
        assertTrue(scope.evidence.isEmpty()); assertFalse(scope.executable)
        assertContains(scope.why, "unknown/inconclusive remains unknown")
        val vapor = p.recommendations.single { it.question.id == InvestigationQuestionId.VAPOR_SEALING }
        assertEquals(listOf("correct-a"), vapor.activeResultIds)
        assertContains(vapor.why, "answers sealing-first")
        assertTrue(vapor.missingEvidence.any { "not sealing/flow proof" in it })
    }
    @Test fun correctionReplacesPriorEffectsWithoutResurrectingSupersededLegacyOrTypedResults() {
        val original = alter(document(), "result-a") { it.copy(declaredOutcome = DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE,
            authorEffects = listOf(InvestigationEffect("vapor", HypothesisEffect.SUPPORTS, "Fictional earlier assertion"))) }
        val p = report(original)
        assertEquals(listOf("result-b", "correct-a"), p.activeOutcomes.map { it.event.id })
        val correction = (p.activeOutcomes.single { it.event.id == "correct-a" }.event as CombinedEvent.Manual).outcome
        assertEquals("result-a", correction.supersedesResultId)
        assertEquals(DeclaredManualOutcome.INCONCLUSIVE, correction.declaredOutcome)
        assertEquals(HypothesisEffect.UNCHANGED, correction.authorEffects.single().effect)
        assertTrue(p.recommendations.all { "result-a" !in it.activeResultIds })
        assertEquals(original.initialCases, document().initialCases)
    }
    @Test fun laterCaseSelectionDoesNotReuseOriginalCaseOutcomeOrInferCrossCaseIdentity() {
        val d = document(); val p = report(d, request(d, d.cases().last().id))
        assertEquals("attach-b", p.source.attachmentRevisionId)
        assertEquals(FixtureVehicleAssertion.UNKNOWN, p.source.ownerVehicleAssertion)
        assertEquals(CombinedOutcomeUse.OTHER_CASE, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY, p.activeOutcomes.single { it.event.id == "result-b" }.use)
        assertEquals(listOf("result-b"), p.recommendations.single { it.question.id == InvestigationQuestionId.RESULT_SCOPE }.activeResultIds)
        assertTrue(p.retainedBasis.correctionEvidence.all { it.reference.caseId == d.cases().last().id })
    }
    @Test fun focusUsesExactQuestionNotJustMatchingCatalogTemplate() {
        val d = document()
        val p = report(d, request(d, question = "sealing-later"))
        assertEquals(CombinedOutcomeUse.OTHER_QUESTION, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(CombinedOutcomeUse.OTHER_CASE, p.activeOutcomes.single { it.event.id == "result-b" }.use)
        assertTrue(p.recommendations.none { it.question.id == InvestigationQuestionId.RESULT_SCOPE })
        assertTrue(p.recommendations.all { it.activeResultIds.isEmpty() })
    }
    @Test fun unrelatedOwnerAnswerContextIsNotConsumedOrTreatedAsNormal() {
        val d = document()
        val unresolved = request(d).copy(startingReport = OwnerReportSelection(ReportSelection.UNRESOLVED))
        val p = report(d, unresolved)
        assertEquals(CombinedOutcomeUse.OTHER_REPORT_CONTEXT, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertNull(p.retainedBasis.selectedStartingReport)
        assertEquals(InvestigationQuestionId.CLARIFY_START, p.nextQuestion!!.question.id)
        assertTrue(p.recommendations.none { it.question.id == InvestigationQuestionId.RESULT_SCOPE })
    }
    @Test fun deliberateUnknownReferenceRemainsInRequestAndNeverFallsBackToEarlierAnswer() {
        val d = document().append(CombinedEvent.Ordinary("request-unknown-start", InvestigationRevision("unknown-start", 15, 70000, "Fictional", "Unknown starting account",
            InvestigationQuestionCatalog.startingReport(StartingBehavior.UNKNOWN, "Invented unknown account"))))
        val req = request(d).copy(startingReport = selected("unknown-start"))
        val p = report(d, req)
        assertEquals(selected("unknown-start"), p.request.startingReport)
        assertNull(p.retainedBasis.selectedStartingReport)
        assertEquals(InvestigationQuestionId.CLARIFY_START, p.nextQuestion!!.question.id)
        assertTrue(p.retainedBasis.startingReports.any { it.revisionId == "unknown-start" && it.value == StartingBehavior.UNKNOWN })
        assertEquals(CombinedOutcomeUse.OTHER_REPORT_CONTEXT, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(selected("adaptation"), p.request.adaptationReport) // selected UNKNOWN adaptation is explicit too
    }
    @Test fun reportedNumberOrTypedManualNumberCannotSupplyAbsentCorrectionEvidence() {
        val d = document().copy(initialCases = CombinedInvestigationFixtures.reportedOnlySeed().initialCases)
        val withValue = alter(d, "correct-a") { it.copy(values = listOf(AttributedManualValue(-16.0, ManualValueUnit.OTHER_REPORTED, "invented percent")),
            declaredOutcome = DeclaredManualOutcome.ABNORMAL_REPORTED_FOR_SCOPE,
            authorEffects = listOf(InvestigationEffect("vapor", HypothesisEffect.SUPPORTS, "Fictional author opinion, not OBD"))) }
        val p = report(withValue)
        assertTrue(p.retainedBasis.correctionEvidence.isEmpty())
        assertEquals(InvestigationQuestionId.QUALIFY_CORRECTION, p.nextQuestion!!.question.id)
        assertTrue(p.recommendations.none { it.question.id in setOf(InvestigationQuestionId.RESULT_SCOPE, InvestigationQuestionId.VAPOR_SEALING) })
        assertEquals(CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(HypothesisEffect.SUPPORTS, ((p.activeOutcomes.single { it.event.id == "correct-a" }.event as CombinedEvent.Manual).outcome.authorEffects.single().effect))
        assertTrue(p.limitations.any { "not qualified OBD" in it })
    }
    @Test fun unknownAndUnavailableDeclarationsStayExplicitWithoutInventedNegativeJudgment() {
        for (value in listOf(DeclaredManualOutcome.UNKNOWN, DeclaredManualOutcome.UNAVAILABLE, DeclaredManualOutcome.NOT_APPLICABLE, DeclaredManualOutcome.INCONCLUSIVE)) {
            val d = alter(document(), "correct-a") { it.copy(declaredOutcome = value, method = ManualOutcomeMethod.UNKNOWN) }
            val p = report(d)
            val trace = p.activeOutcomes.single { it.event.id == "correct-a" }
            assertEquals(value, (trace.event as CombinedEvent.Manual).outcome.declaredOutcome)
            assertEquals(ManualScopeUse.SYNTHETIC_ONLY, trace.scope!!.use)
            assertTrue(trace.scope.gaps.any { "inconclusive, unavailable, inapplicable or unknown" in it })
            assertContains(p.recommendations.single { it.question.id == InvestigationQuestionId.VAPOR_SEALING }.why, value.name)
        }
    }
    @Test fun normalAuthorAssertionKeepsMethodLimitationsAndNeverProvesSealingOrRepair() {
        val d = alter(document(), "correct-a") { it.copy(declaredOutcome = DeclaredManualOutcome.NORMAL_REPORTED_FOR_SCOPE,
            authorEffects = listOf(InvestigationEffect("vapor", HypothesisEffect.WEAKENS, "Only the fictional tested mode under stated conditions"))) }
        val p = report(d)
        val trace = p.activeOutcomes.single { it.event.id == "correct-a" }
        assertEquals(HypothesisEffect.WEAKENS, (trace.event as CombinedEvent.Manual).outcome.authorEffects.single().effect)
        assertTrue(trace.scope!!.gaps.any { "not sealing/flow proof" in it })
        assertTrue(p.recommendations.all { !it.executable })
        assertContains(p.limitations.last(), "No hypothesis closure")
        assertContains(p.recommendations.single { it.question.id == InvestigationQuestionId.RESULT_SCOPE }.why, "Normal concerns only the tested scope")
    }
    @Test fun customQuestionIsExplicitlyUnsupportedAndNotParsedFromWordingOrOutcomeText() {
        val original = document()
        val d = original.copy(events = original.events.map { event ->
            when {
                event.id == "sealing-first" -> (event as CombinedEvent.Ordinary).copy(revision = event.revision.copy(step = (event.revision.step as InvestigationStep.ProposedTest).copy(question = "Custom fictional question")))
                event is CombinedEvent.Manual && event.outcome.context.questionRevisionId == "sealing-first" -> event.copy(outcome = event.outcome.copy(context = event.outcome.context.copy(catalog = null)))
                else -> event
            }
        }).also { it.validate() }
        val unsupported = assertIs<CombinedPreviewResult.Unsupported>(CombinedRecommendationPreview.preview(d, request(d, question = "sealing-first")))
        assertEquals(CombinedPreviewUnsupported.QUESTION_NOT_MAPPABLE, unsupported.reason)
        val p = report(d)
        assertEquals(CombinedOutcomeUse.UNSUPPORTED_QUESTION, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertTrue(p.recommendations.none { it.question.id == InvestigationQuestionId.RESULT_SCOPE })
    }
    @Test fun legacyResultWithoutCaseAndOwnerScopeRemainsVisibleButUnconsumed() {
        val prefix = CombinedInvestigationFixtures.questionAPrefix()
        val legacy = CombinedEvent.Ordinary("request-legacy", InvestigationRevision("legacy", 10, 60000, "Fictional", "Unknown legacy scope",
            InvestigationStep.TestResult("sealing-first", InvestigationOutcome.INCONCLUSIVE, "Not measured", "Invented legacy account", emptyList(), emptyList())))
        val d = prefix.append(legacy)
        val p = report(d)
        assertEquals(CombinedOutcomeUse.LEGACY_CONTEXT_UNAVAILABLE, p.activeOutcomes.single().use)
        assertTrue(p.retainedBasis.activeResults.isEmpty())
        assertTrue(p.recommendations.none { it.question.id == InvestigationQuestionId.RESULT_SCOPE })
        val typed = CombinedInvestigationFixtures.cycleEvents()[9] as CombinedEvent.Manual
        val corrected = d.append(typed.copy(outcome = typed.outcome.copy(sequence = 11, supersedesResultId = "legacy")))
        val result = report(corrected)
        assertEquals(listOf("legacy"), result.supersededResultIds)
        assertEquals(listOf("result-a"), result.activeOutcomes.map { it.event.id })
    }
    @Test fun previewIsDeterministicAndStaleAfterAnyInputRevisionChange() {
        val d = document(); val bytes = CombinedInvestigationCodec.encode(d); val req = request(d)
        assertEquals(report(d), report(d))
        assertEquals(bytes, CombinedInvestigationCodec.encode(d))
        val changed = alter(d, "correct-a") { it.copy(reason = "Different attributed correction reason") }
        val stale = assertIs<CombinedPreviewResult.Stale>(CombinedRecommendationPreview.preview(changed, req))
        assertEquals(req.expectedRevision, stale.expectedRevision)
        assertEquals(CombinedRecommendationPreview.revision(changed), stale.actualRevision)
        assertNotEquals(stale.expectedRevision, stale.actualRevision)
    }
    @Test fun invalidDocumentAndRequestReferencesReturnValidationNotRecommendations() {
        val d = document()
        for ((field, req) in listOf("caseId" to request(d).copy(caseId = "missing-case"),
            "questionRevisionId" to request(d).copy(questionRevisionId = "starting"),
            "startingReport" to request(d).copy(startingReport = selected("manual")),
            "timingReport" to request(d).copy(timingReport = selected("starting")),
            "adaptationReport" to request(d).copy(adaptationReport = selected("timing")))) {
            assertEquals(field, assertIs<CombinedPreviewResult.Invalid>(CombinedRecommendationPreview.preview(d, req)).issues.single().field)
        }
        val bad = d.copy(events = d.events.map { if (it.id == "correct-a") (it as CombinedEvent.Manual).copy(outcome = it.outcome.copy(supersedesResultId = "result-b")) else it })
        assertIs<CombinedPreviewResult.Invalid>(CombinedRecommendationPreview.preview(bad, request(d)))
    }
    @Test fun unresolvedSelectorsDoNotAlterLegacyDefaultInference() {
        val saved = SyntheticInvestigationSeed.saved()
        assertEquals(StartingBehavior.NORMAL_SPEED_LONG_CRANK, InvestigationReasoning.recommend(saved, saved.cases.single().id).selectedStartingReport!!.value)
        val d = document()
        val p = report(d, CombinedPreviewRequest(CombinedRecommendationPreview.revision(d), d.initialCases.single().id))
        assertNull(p.retainedBasis.selectedStartingReport); assertNull(p.retainedBasis.selectedTimingReport)
        assertEquals(InvestigationQuestionId.CLARIFY_START, p.nextQuestion!!.question.id)
    }
    @Test fun sameExplicitUnknownAnswerReferenceCanBeRetainedWithoutResolvingItsUnknownValue() {
        val d = document().copy(events = document().events.map { event ->
            if (event.id == "starting") (event as CombinedEvent.Ordinary).copy(revision = event.revision.copy(
                step = InvestigationQuestionCatalog.startingReport(StartingBehavior.UNKNOWN, "Fictional unknown starting category"))) else event
        }).also { it.validate() }
        val p = report(d)
        assertEquals(CombinedRecommendationPreview.VERSION, p.policyVersion)
        assertEquals(selected("starting"), p.request.startingReport)
        assertNull(p.retainedBasis.selectedStartingReport)
        assertEquals(InvestigationQuestionId.CLARIFY_START, p.nextQuestion!!.question.id)
        assertEquals(CombinedOutcomeUse.SCOPED_ATTRIBUTED_ONLY, p.activeOutcomes.single { it.event.id == "correct-a" }.use)
        assertEquals(DeclaredManualOutcome.INCONCLUSIVE, ((p.activeOutcomes.single { it.event.id == "correct-a" }.event as CombinedEvent.Manual).outcome.declaredOutcome))
    }
    @Test fun unsupportedOriginOrAbsentCaptureFailsClosedInsteadOfInventingSourceEvidence() {
        val d = document()
        val original = d.initialCases.single()
        for (badCase in listOf(original.copy(drive = original.drive.copy(origin = DriveEvidenceOrigin.UNKNOWN)),
            original.copy(scan = original.scan.copy(capture = null)))) {
            val bad = d.copy(initialCases = listOf(badCase))
            val result = assertIs<CombinedPreviewResult.Invalid>(CombinedRecommendationPreview.preview(bad, request(d)))
            assertEquals(CombinedIssueCode.FICTIONAL_ONLY, result.issues.single().code)
        }
    }
    @Test fun missingCatalogBindingIsVisibleAsUnsupportedRatherThanGuessingFromCanonicalText() {
        val d = document().copy(events = document().events.map { event ->
            if (event is CombinedEvent.Manual && event.outcome.context.questionRevisionId == "sealing-first")
                event.copy(outcome = event.outcome.copy(context = event.outcome.context.copy(catalog = null))) else event
        }).also { it.validate() }
        val p = report(d)
        val trace = p.activeOutcomes.single { it.event.id == "correct-a" }
        assertEquals(InvestigationQuestionId.VAPOR_SEALING, trace.catalogQuestionId)
        assertEquals(CombinedOutcomeUse.UNSUPPORTED_QUESTION, trace.use)
        assertTrue(p.recommendations.all { it.activeResultIds.isEmpty() })
    }

}
