package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import java.io.*
import java.nio.file.Files
import kotlin.test.*

/** Nick's executable service-boundary reference. Every byte/account is fictional; no Android IO. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedFrontendContractTest {
    private val id = "fictional-frontend-reference"
    private class FictionalSource : RetainedCaseSource {
        var bytes = DiagnosticCaseCodec.encode(CombinedInvestigationFixtures.completedCycle().cases()).toByteArray()
        override fun openArchive(): InputStream = ByteArrayInputStream(bytes)
    }
    private fun example(test: suspend TestScope.(File, FictionalSource) -> Unit) = runTest {
        val root = Files.createTempDirectory("ow-frontend-contract-fictional-").toFile()
        try { test(root, FictionalSource()) } finally { root.deleteRecursively() } // Only this test's new temp directory.
    }
    private fun TestScope.newOwner(root: File, source: FictionalSource, files: OutcomeSandboxFiles = OutcomeSandboxFiles()) =
        RetainedInvestigationService(RetainedInvestigationRepository(root, source, files), this, StandardTestDispatcher(testScheduler))
    private fun TestScope.observe(owner: RetainedInvestigationService): List<RetainedFrontendState> =
        mutableListOf<RetainedFrontendState>().also { states ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { states += it } }
        }
    private fun TestScope.send(owner: RetainedInvestigationService, action: RetainedAction): RetainedResult {
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(action))
        assertTrue(owner.state.value.loading)
        runCurrent()
        assertFalse(owner.state.value.loading)
        return assertNotNull(owner.state.value.outcome)
    }
    private fun RetainedResult.snapshot() = assertNotNull(assertIs<RetainedResult.Opened>(this).inspection.snapshot)
    private fun attachment(s: RetainedSnapshot, choice: RetainedCaseChoice, name: String = "attach-a") =
        RetainedAction.Attach(id, s.byteToken, choice.selection, AttachmentIntent("request-$name", name,
            s.document.events.size + 1, 90000, "Fictional frontend author", "Explicit fictional selection",
            InvestigationVehicleAssertion.UNKNOWN, "Synthetic source; no real vehicle identity or test"))
    private fun TestScope.prefix(owner: RetainedInvestigationService): RetainedSnapshot {
        var s = send(owner, RetainedAction.Create(id, CombinedInvestigationFixtures.partialBankSeed().events.single() as CombinedEvent.Ordinary)).snapshot()
        val choices = assertIs<RetainedResult.Cases>(send(owner, RetainedAction.CaseChoices))
        s = send(owner, attachment(s, choices.choices.first())).snapshot()
        SyntheticInvestigationSeed.saved().journal.revisions.drop(1).take(8).forEach { questionOrContext ->
            s = send(owner, RetainedAction.Append(id, s.byteToken, CombinedEvent.Ordinary("request-${questionOrContext.id}",
                questionOrContext.copy(sequence = s.document.events.size + 1)))).snapshot()
        }
        return s
    }
    private fun manual(s: RetainedSnapshot, resultId: String, question: String = "sealing-first", corrects: String? = null,
        caseId: String = s.document.cases().first().id): CombinedEvent.Manual =
        (CombinedInvestigationFixtures.cycleEvents()[9] as CombinedEvent.Manual).let { template ->
            template.copy(requestId = "request-$resultId", outcome = template.outcome.copy(id = resultId,
                sequence = s.document.events.size + 1, context = template.outcome.context.copy(questionRevisionId = question, caseId = caseId),
                supersedesResultId = corrects))
        }
    private fun preview(s: RetainedSnapshot) = RetainedAction.Preview(id, s.byteToken, CombinedPreviewRequest(
        RetainedInvestigationCodec.revision(s.document), s.document.cases().first().id,
        OwnerReportSelection(ReportSelection.SELECTED, "starting"), OwnerReportSelection(ReportSelection.SELECTED, "timing"),
        OwnerReportSelection(ReportSelection.SELECTED, "adaptation"), "sealing-first"))

    @Test fun fictionalWorkflowUsesActionsAndObservedStatesThroughCorrectionPreviewDetachAndReopen() = example { root, source ->
        val owner = newOwner(root, source)
        val states = observe(owner)
        assertTrue(assertIs<RetainedResult.Listing>(send(owner, RetainedAction.ListNew)).entries.isEmpty())
        assertEquals(RetainedStorageStatus.EMPTY, assertIs<RetainedResult.Opened>(send(owner, RetainedAction.Open(id))).inspection.status)
        var s = prefix(owner)
        s = send(owner, RetainedAction.Append(id, s.byteToken, manual(s, "result-a"))).snapshot()
        s = send(owner, RetainedAction.Append(id, s.byteToken, manual(s, "correct-a", corrects = "result-a"))).snapshot()
        val second = assertIs<RetainedResult.Cases>(send(owner, RetainedAction.CaseChoices)).choices.last()
        s = send(owner, attachment(s, second, "attach-b")).snapshot()
        val questionB = SyntheticInvestigationSeed.saved().journal.revisions.last().copy(sequence = s.document.events.size + 1)
        s = send(owner, RetainedAction.Append(id, s.byteToken, CombinedEvent.Ordinary("request-${questionB.id}", questionB))).snapshot()
        s = send(owner, RetainedAction.Append(id, s.byteToken, manual(s, "result-b", "sealing-later", caseId = second.selection.caseId))).snapshot()
        val request = preview(s)
        owner.selectPreview(RetainedPreviewSelection(id, request.request.selection()))
        val delivered = assertIs<RetainedResult.Previewed>(send(owner, request))
        assertEquals(request, delivered.submitted)
        val report = assertIs<CombinedPreviewResult.Ready>(delivered.result).report
        assertEquals(mapOf("sealing-first" to "correct-a", "sealing-later" to "result-b"), s.document.activeResultIds())
        assertTrue("result-a" in report.supersededResultIds)
        // The selected exact question takes precedence over the different-case explanation.
        assertEquals(CombinedOutcomeUse.OTHER_QUESTION, report.activeOutcomes.single { it.event.id == "result-b" }.use)
        val caseRequest = request.copy(request = request.request.copy(questionRevisionId = null))
        owner.selectPreview(RetainedPreviewSelection(id, caseRequest.request.selection()))
        val caseReport = assertIs<CombinedPreviewResult.Ready>(assertIs<RetainedResult.Previewed>(send(owner, caseRequest)).result).report
        assertEquals(CombinedOutcomeUse.OTHER_CASE, caseReport.activeOutcomes.single { it.event.id == "result-b" }.use)
        assertEquals(DriveEvidenceOrigin.SYNTHETIC, report.source.driveOrigin)
        assertTrue(report.limitations.isNotEmpty())
        assertTrue(states.any { it.loading } && states.any { !it.loading && it.outcome is RetainedResult.Cases })
        owner.detach()
        assertIs<RetainedResult.Opened>(owner.state.value.outcome) // Old recommendation is no longer displayed.
        val reopened = newOwner(root, source) // Prior IO completed before replacing this owner.
        val reopenedStates = observe(reopened)
        assertEquals(s, send(reopened, RetainedAction.Open(id)).snapshot())
        assertIs<RetainedResult.Opened>(reopenedStates.last().outcome)
        assertEquals(CombinedAdmission.SELECTION_MISMATCH, reopened.dispatch(request)) // Reopen does not select context for us.
    }

    @Test fun validationRetainsDraftAndRequestUntilAnExplicitFieldEditResubmits() = example { root, source ->
        val owner = newOwner(root, source); val states = observe(owner); val s = prefix(owner)
        val valid = manual(s, "draft-result")
        // This local immutable value represents frontend-owned input; the service is not a draft store.
        val draft = RetainedAction.Append(id, s.byteToken, valid.copy(outcome = valid.outcome.copy(values = listOf(
            AttributedManualValue(Double.NaN, ManualValueUnit.UNKNOWN)))))
        val rejected = assertIs<RetainedResult.Rejected>(send(owner, draft))
        assertEquals(RetainedFailure.VALIDATION, rejected.failure)
        assertSame(draft, rejected.submitted)
        assertEquals("outcome.values[0].value", rejected.issues.single().field)
        assertTrue((draft.event as CombinedEvent.Manual).outcome.values.single().value.isNaN())
        assertEquals(s, send(owner, RetainedAction.Open(id)).snapshot())
        // Only the invalid field is edited; the same request/outcome IDs remain unused and available.
        val edited = draft.copy(event = valid)
        val saved = send(owner, edited).snapshot()
        assertEquals(valid, saved.document.events.last())
        assertEquals(valid.requestId, draft.event.requestId)
        assertTrue(states.any { it.outcome === rejected })
    }

    @Test fun staleSourceRetainsAttachmentIntentAndRequiresDeliberateReselection() = example { root, source ->
        val owner = newOwner(root, source); observe(owner)
        val s = send(owner, RetainedAction.Create(id, CombinedInvestigationFixtures.partialBankSeed().events.single() as CombinedEvent.Ordinary)).snapshot()
        val choice = assertIs<RetainedResult.Cases>(send(owner, RetainedAction.CaseChoices)).choices.first()
        val draft = attachment(s, choice)
        source.bytes += "\n ".toByteArray()
        val rejected = assertIs<RetainedResult.Rejected>(send(owner, draft))
        assertEquals(RetainedFailure.STALE_SOURCE, rejected.failure); assertSame(draft, rejected.submitted)
        assertEquals(s, send(owner, RetainedAction.Open(id)).snapshot())
        val fresh = assertIs<RetainedResult.Cases>(send(owner, RetainedAction.CaseChoices)).choices.first()
        assertNotEquals(choice.selection.archiveToken, fresh.selection.archiveToken)
        assertEquals(choice.selection.caseDigest, fresh.selection.caseDigest)
        val reselected = draft.copy(selected = fresh.selection) // Explicit user decision, no automatic retry/rebase.
        val saved = send(owner, reselected).snapshot()
        assertEquals(draft.intent.requestId, saved.document.events.last().requestId)
        assertEquals(fresh.selection, saved.document.revisions.last().source)
    }

    @Test fun busyIsNotQueuedAndStalePreviewTokensNeverRebaseOrReplaceDrafts() = example { root, source ->
        val owner = newOwner(root, source); observe(owner); val s = prefix(owner)
        val oldPreview = preview(s); owner.selectPreview(RetainedPreviewSelection(id, oldPreview.request.selection()))
        val action = RetainedAction.Append(id, s.byteToken, manual(s, "result-a"))
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(action))
        assertEquals(CombinedAdmission.BUSY, owner.dispatch(oldPreview))
        runCurrent()
        val newer = assertNotNull(assertIs<RetainedResult.Opened>(owner.state.value.outcome).inspection.snapshot)
        val staleBytes = assertIs<RetainedResult.Rejected>(send(owner, oldPreview))
        assertEquals(RetainedFailure.STALE_DOCUMENT, staleBytes.failure); assertSame(oldPreview, staleBytes.submitted)
        val staleContent = assertIs<RetainedResult.Previewed>(send(owner, oldPreview.copy(expectedToken = newer.byteToken)))
        assertIs<CombinedPreviewResult.Stale>(staleContent.result)
        assertEquals(newer, send(owner, RetainedAction.Open(id)).snapshot())
    }

    @Test fun selectionChangeAndDetachSuppressInFlightPreviewDeliveryUntilExplicitReopen() = example { root, source ->
        val owner = newOwner(root, source); val states = observe(owner); val s = prefix(owner); val request = preview(s)
        owner.selectPreview(RetainedPreviewSelection(id, request.request.selection()))
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(request))
        owner.selectPreview(null); runCurrent()
        assertIs<RetainedResult.Opened>(owner.state.value.outcome)
        assertFalse(states.any { it.outcome is RetainedResult.Previewed })
        owner.selectPreview(RetainedPreviewSelection(id, request.request.selection()))
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(request)); owner.detach(); runCurrent()
        assertFalse(states.any { it.outcome is RetainedResult.Previewed })
        assertEquals(s, send(owner, RetainedAction.Open(id)).snapshot())
        assertEquals(CombinedAdmission.SELECTION_MISMATCH, owner.dispatch(request))
    }

    @Test fun lostPublishAcknowledgmentKeepsRequestAndReplaysOneCommittedEvent() = example { root, source ->
        var loseAck = false
        val files = object : OutcomeSandboxFiles() {
            override fun publish(pending: File, committed: File) {
                super.publish(pending, committed)
                if (loseAck) { loseAck = false; throw IOException("Fictional lost acknowledgment after publish") }
            }
        }
        val owner = newOwner(root, source, files); val states = observe(owner); val s = prefix(owner)
        val draft = RetainedAction.Append(id, s.byteToken, manual(s, "uncertain-result")); loseAck = true
        val uncertain = assertIs<RetainedResult.Rejected>(send(owner, draft))
        assertEquals(RetainedFailure.IO_OR_ACK_UNKNOWN, uncertain.failure); assertSame(draft, uncertain.submitted)
        val inspected = assertIs<RetainedResult.Opened>(send(owner, RetainedAction.InspectRecovery(id))).inspection
        assertEquals(draft.event, inspected.snapshot!!.document.events.last()); assertNull(inspected.pending)
        val replay = assertIs<RetainedResult.Opened>(send(owner, draft)) // Original token/request, not regenerated IDs.
        assertTrue(replay.replayed)
        assertEquals(1, replay.inspection.snapshot!!.document.events.count { it.requestId == draft.event.requestId })
        assertTrue(states.any { (it.outcome as? RetainedResult.Rejected)?.failure == RetainedFailure.IO_OR_ACK_UNKNOWN })
    }

    @Test fun explicitObservedRecoveryAndExportPreservePendingAndCommittedIdentities() = example { root, source ->
        var interrupt = false
        val files = object : OutcomeSandboxFiles() {
            override fun publish(pending: File, committed: File) {
                if (interrupt) throw IOException("Fictional interruption before publish")
                super.publish(pending, committed)
            }
        }
        val owner = newOwner(root, source, files); observe(owner); val s = prefix(owner)
        val draft = RetainedAction.Append(id, s.byteToken, manual(s, "pending-result")); interrupt = true
        assertEquals(RetainedFailure.IO_OR_ACK_UNKNOWN, assertIs<RetainedResult.Rejected>(send(owner, draft)).failure)
        val inspection = assertIs<RetainedResult.Opened>(send(owner, RetainedAction.InspectRecovery(id))).inspection
        assertTrue(inspection.recoverable); assertEquals(s, inspection.snapshot)
        val originals = listOfNotNull(inspection.committed, inspection.pending)
        val copies = mutableMapOf<String, ByteArrayOutputStream>()
        val sink = OriginalExportSink { name -> check(name !in copies); ByteArrayOutputStream().also { copies[name] = it } }
        val exported = assertIs<RetainedResult.Exported>(send(owner, RetainedAction.ExportOriginals(originals, sink)))
        assertEquals(originals, exported.originals)
        originals.forEach { original ->
            val bytes = copies.getValue(original.name).toByteArray()
            assertEquals(original.size, bytes.size.toLong()); assertEquals(original.digest, investigationDigest(bytes))
        }
        val recovery = RetainedAction.Recover(id, inspection.committed!!.digest, inspection.pending!!.digest!!)
        val wrong = recovery.copy(observedPending = "0".repeat(64))
        assertEquals(RetainedFailure.STORAGE_BLOCKED, assertIs<RetainedResult.Rejected>(send(owner, wrong)).failure)
        assertEquals(inspection, assertIs<RetainedResult.Opened>(send(owner, RetainedAction.InspectRecovery(id))).inspection)
        interrupt = false
        val recovered = send(owner, recovery).snapshot()
        assertEquals(draft.event, recovered.document.events.last())
        assertTrue(assertIs<RetainedResult.Opened>(send(owner, draft)).replayed)
    }

    @Test fun exportFailureAndUnavailableSourceAreExplicitStatesNotEmptySuccessOrLostDrafts() = example { root, source ->
        val owner = newOwner(root, source); val states = observe(owner); val s = prefix(owner)
        source.bytes = "{fictional unsupported archive".toByteArray()
        val unavailable = assertIs<RetainedResult.Rejected>(send(owner, RetainedAction.CaseChoices))
        assertEquals(RetainedFailure.SOURCE_UNAVAILABLE, unavailable.failure)
        assertEquals(InvestigationIoFailure.SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED, unavailable.ioFailure)
        val inspection = assertIs<RetainedResult.Opened>(send(owner, RetainedAction.Open(id))).inspection
        val sink = OriginalExportSink { object : OutputStream() { override fun write(value: Int) { throw IOException("Fictional failed destination") } } }
        val request = RetainedAction.ExportOriginals(listOf(inspection.committed!!), sink)
        val failed = assertIs<RetainedResult.Rejected>(send(owner, request))
        assertEquals(RetainedFailure.EXPORT_INCOMPLETE, failed.failure); assertSame(request, failed.submitted)
        assertEquals(s, send(owner, RetainedAction.Open(id)).snapshot())
        assertTrue(states.any { it.outcome === unavailable } && states.any { it.outcome === failed })
    }
}
