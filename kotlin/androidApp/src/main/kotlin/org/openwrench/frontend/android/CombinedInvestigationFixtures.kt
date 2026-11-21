package org.openwrench.frontend.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.openwrench.core.*
import java.io.File

/** Callable packet for Nick. Every ID/time/account is invented; never imports installed histories. */
internal object CombinedInvestigationFixtures {
    const val ID = "fictional-combined-v1"
    private val source get() = SyntheticInvestigationSeed.saved()
    private fun revision(r: InvestigationRevision, sequence: Int = r.sequence) =
        CombinedEvent.Ordinary("request-${r.id}", r.copy(sequence = sequence))
    fun partialBankSeed(): CombinedInvestigation = CombinedInvestigation(ID, source.cases, listOf(revision(source.journal.revisions.first())))
    /** No qualified drive assessment; the percentage story is still only a complaint. */
    fun reportedOnlySeed(): CombinedInvestigation {
        val original = source.cases.single()
        val review = original.drive.review.copy(assessment = null)
        val drive = original.drive.copy(review = review, report = "OPENWRENCH live health monitoring\nFICTIONAL ONLY\n" + review.reportText())
        val retained = DiagnosticCaseBuilder.create(original.scan, drive, true, 41000).copy(id = original.id)
        return partialBankSeed().copy(initialCases = listOf(retained))
    }
    fun questionAPrefix(): CombinedInvestigation = partialBankSeed().copy(events = source.journal.revisions.take(9).map(::revision)).also { it.validate() }
    private fun manual(id: String, sequence: Int, question: String, caseId: String, correction: String? = null): CombinedEvent.Manual {
        val unknown = ReportedDetail(ReportKnowledge.UNKNOWN)
        val result = AttributedManualOutcome(id, sequence, 50000 + sequence.toLong(), "Fictional fixture author", "Invented account; not a vehicle test",
            ManualOutcomeContext(question, caseId, OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 1),
                OwnerReportSelection(ReportSelection.SELECTED, "starting"), OwnerReportSelection(ReportSelection.SELECTED, "timing"),
                listOf("manual"), emptyList(), OwnerReportSelection(ReportSelection.SELECTED, "adaptation")),
            ManualOutcomeOrigin.SYNTHETIC, ManualFaultMode.UNWANTED_FLOW_WHEN_EXPECTED_SEALED, ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT,
            DeclaredManualOutcome.INCONCLUSIVE, unknown, unknown, unknown, unknown, ReportedApplicability.UNKNOWN, null,
            "Entirely invented fixture account. A click is not sealing/flow evidence.", emptyList(),
            listOf(InvestigationEffect("vapor", HypothesisEffect.UNCHANGED, "Inconclusive synthetic account leaves competing explanations open")), correction)
        return CombinedEvent.Manual("request-$id", result)
    }
    /** Global sequence includes typed outcomes and deliberate attachments. Original question/result payloads are unchanged. */
    fun cycleEvents(): List<CombinedEvent> {
        val first = source.cases.single()
        val laterScan = first.scan.copy(id = "00000000-0000-4000-8000-000000000012",
            capture = first.scan.capture!!.copy(captureId = "00000000-0000-4000-8000-000000000011"))
        val laterDrive = first.drive.copy(id = "00000000-0000-4000-8000-000000000013")
        val later = DiagnosticCaseBuilder.create(laterScan, laterDrive, true, 51000).copy(id = "00000000-0000-4000-8000-000000000014")
        return questionAPrefix().events + listOf(
            manual("result-a", 10, "sealing-first", first.id),
            revision(source.journal.revisions.last(), 11),
            CombinedEvent.Attachment("request-attach-b", "attach-b", 12, 52000, "Fictional author", "Deliberate later case copy",
                later, FixtureVehicleAssertion.UNKNOWN, "Fictional later occasion. Common identity/event/conditions are not established."),
            manual("result-b", 13, "sealing-later", later.id),
            manual("correct-a", 14, "sealing-first", first.id, "result-a"))
    }
    fun completedCycle() = partialBankSeed().copy(events = cycleEvents()).also { it.validate() }
    /** Does not acquire evidence, select a real root or activate production. Caller supplies an isolated fixture directory. */
    fun coordinator(fixtureRoot: File, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO) =
        CombinedFixtureCoordinator(CombinedFixtureStore(fixtureRoot), scope, io)
    /** Retain this exact action for lost-ack retries. Never regenerate IDs or silently replace a stale token. */
    fun nextCycleAction(snapshot: CombinedSnapshot?): CombinedAction {
        if (snapshot == null) return CombinedAction.Create(partialBankSeed())
        val expected = cycleEvents()
        require(snapshot.document.id == ID && snapshot.document.events == expected.take(snapshot.document.events.size))
        require(snapshot.document.events.size < expected.size) { "Fictional cycle is complete" }
        return CombinedAction.Append(snapshot.token, expected[snapshot.document.events.size])
    }
}
