package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import org.openwrench.nativecore.ScanOrigin
import java.io.*
import java.nio.file.Files
import kotlin.test.*

/** Every byte is invented, including cases carrying modeled HARDWARE/UNKNOWN metadata. No hardware qualification. */
internal object ModeledRetainedFixtures {
    fun cases(): List<DiagnosticCase> = CombinedInvestigationFixtures.completedCycle().cases().map { c ->
        DiagnosticCaseBuilder.create(c.scan.copy(report = "OPENWRENCH read-only diagnostic scan\nMODELED REAL-ORIGIN FICTIONAL TEST ONLY\nVIN omitted.\n",
            capture = c.scan.capture!!.copy(origin = ScanOrigin.HARDWARE, sessionId = "00000000-0000-4000-8000-000000000099",
                startedAtEpochMillis = 1000, completedAtEpochMillis = 2000, durationMillis = 1000)), c.drive.copy(origin = DriveEvidenceOrigin.HARDWARE,
            report = "OPENWRENCH live health monitoring\nMODELED REAL-ORIGIN FICTIONAL TEST ONLY\n" + c.drive.review.reportText()), true, c.savedAtEpochMillis)
            .copy(id = c.id).let { it.copy(sections = it.sections.map { section -> section + "\nMODELED REAL-ORIGIN FICTIONAL TEST ONLY; not hardware evidence." }) }
    }
    fun complaint() = CombinedInvestigationFixtures.partialBankSeed().events.single() as CombinedEvent.Ordinary
    fun typed(question: String, id: String, sequence: Int, caseId: String, corrects: String? = null): CombinedEvent.Manual =
        (CombinedInvestigationFixtures.cycleEvents()[9] as CombinedEvent.Manual).let { it.copy(requestId = "request-$id", outcome = it.outcome.copy(
            id = id, sequence = sequence, context = it.outcome.context.copy(questionRevisionId = question, caseId = caseId),
            origin = ManualOutcomeOrigin.AUTHOR_REPORTED_MANUAL, supersedesResultId = corrects, provenance = "Fictional modeled author account; no actual vehicle test")) }
}
private class ModeledCaseSource : RetainedCaseSource {
    var bytes = DiagnosticCaseCodec.encode(ModeledRetainedFixtures.cases()).toByteArray()
    var reads = 0
    override fun openArchive(): InputStream { reads++; return ByteArrayInputStream(bytes) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedInvestigationCandidateTest {
    private val id = "new-investigation-a"
    private fun root(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ow-disabled-retained-fictional").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun file(root: File, key: String = id, pending: Boolean = false) = File(root, "${RetainedInvestigationRepository.DIRECTORY}/$key.${if(pending) "pending" else "json"}")
    private fun opened(repo: RetainedInvestigationRepository, key: String = id) = assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Open(key))).inspection
    private fun create(repo: RetainedInvestigationRepository, key: String = id) = assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Create(key, ModeledRetainedFixtures.complaint()))).inspection.snapshot!!
    private fun choices(repo: RetainedInvestigationRepository) = assertIs<RetainedResult.Cases>(repo.execute(RetainedAction.CaseChoices)).choices
    private fun attachment(s: RetainedSnapshot, choice: RetainedCaseChoice, name: String = "attach-a") = RetainedAction.Attach(s.document.id, s.byteToken, choice.selection,
        AttachmentIntent("request-$name", name, s.document.events.size+1, 90000, "Fictional author", "Deliberate modeled retained case",
            InvestigationVehicleAssertion.UNKNOWN, "Fictional selected source copy; actual identity unknown"))
    private fun append(repo: RetainedInvestigationRepository, s: RetainedSnapshot, event: CombinedEvent) =
        assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Append(s.document.id, s.byteToken, event))).inspection.snapshot!!
    private fun questionPrefix(repo: RetainedInvestigationRepository): RetainedSnapshot {
        var s = create(repo)
        s = assertIs<RetainedResult.Opened>(repo.execute(attachment(s, choices(repo).first()))).inspection.snapshot!!
        SyntheticInvestigationSeed.saved().journal.revisions.drop(1).take(8).forEach { r ->
            s = append(repo, s, CombinedEvent.Ordinary("request-${r.id}", r.copy(sequence = s.document.events.size + 1)))
        }
        return s
    }
    private fun preview(s: RetainedSnapshot, caseId: String = s.document.cases().first().id) = RetainedAction.Preview(s.document.id, s.byteToken,
        CombinedPreviewRequest(RetainedInvestigationCodec.revision(s.document), caseId, OwnerReportSelection(ReportSelection.SELECTED,"starting"),
            OwnerReportSelection(ReportSelection.SELECTED,"timing"), OwnerReportSelection(ReportSelection.SELECTED,"adaptation")))

    @Test fun completeWorkflowRetainsSourceCorrectionsLaterQuestionsAndRestartWithoutLegacyAccess() = root { dir ->
        val legacy = File(dir,"investigations-v1/private-sentinel.json").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,0xff.toByte())) }
        val sentinel = legacy.readBytes(); val source = ModeledCaseSource(); val repo = RetainedInvestigationRepository(dir,source)
        assertEquals(RetainedStorageStatus.EMPTY, opened(repo).status); assertFalse(file(dir).exists())
        var s = questionPrefix(repo)
        val caseA = s.document.cases().first().id
        s = append(repo,s,ModeledRetainedFixtures.typed("sealing-first","result-a",11,caseA))
        s = append(repo,s,ModeledRetainedFixtures.typed("sealing-first","correct-a",12,caseA,"result-a"))
        s = assertIs<RetainedResult.Opened>(repo.execute(attachment(s, choices(repo).last(), "attach-b"))).inspection.snapshot!!
        val later = SyntheticInvestigationSeed.saved().journal.revisions.last().copy(sequence=14)
        s = append(repo,s,CombinedEvent.Ordinary("request-${later.id}",later))
        s = append(repo,s,ModeledRetainedFixtures.typed("sealing-later","result-b",15,s.document.cases().last().id))
        assertEquals(mapOf("sealing-first" to "correct-a","sealing-later" to "result-b"),s.document.activeResultIds())
        assertEquals((1..15).toList(),s.document.events.map { it.sequence })
        val p = assertIs<RetainedResult.Previewed>(repo.execute(preview(s)))
        val report = assertIs<CombinedPreviewResult.Ready>(p.result).report
        assertFalse(report.fictionalOnly); assertEquals(DriveEvidenceOrigin.HARDWARE,report.source.driveOrigin)
        assertNull(report.source.driveEcu); assertTrue(report.retainedBasis.correctionEvidence.all { it.origin == DriveEvidenceOrigin.HARDWARE && it.interval.bank == 1 })
        assertEquals(CombinedOutcomeUse.OTHER_CASE,report.activeOutcomes.single { it.event.id == "result-b" }.use)
        assertEquals(s,opened(RetainedInvestigationRepository(dir,source)).snapshot)
        assertEquals(s.document,RetainedInvestigationCodec.decode(RetainedInvestigationCodec.encode(s.document)))
        assertTrue(assertIs<RetainedResult.Listing>(repo.execute(RetainedAction.ListNew)).entries.single().status == RetainedStorageStatus.READY)
        assertTrue(s.document.revisions.filter { it.source!=null }.all { it.source!!.caseDigest == investigationDigest(DiagnosticCaseCodec.encode(listOf((it.event as CombinedEvent.Attachment).retainedCase)).toByteArray()) })
        assertTrue(s.document.cases().all { it.scan.vin==null && it.sections.all { section -> "MODELED REAL-ORIGIN FICTIONAL" in section } })
        assertContentEquals(sentinel,legacy.readBytes())
    }
    @Test fun staleSourceSelectionRejectsEvenWhitespaceOnlyChangeBeforeAnyWrite() = root { dir ->
        val source=ModeledCaseSource(); val repo=RetainedInvestigationRepository(dir,source); val s=create(repo);val selected=choices(repo).first()
        val action=attachment(s,selected); val before=file(dir).readBytes();source.bytes += "\n ".toByteArray()
        assertEquals(RetainedFailure.STALE_SOURCE,assertIs<RetainedResult.Rejected>(repo.execute(action)).failure)
        assertContentEquals(before,file(dir).readBytes());assertFalse(file(dir,pending=true).exists())
        val fresh=choices(repo).first();assertEquals(selected.selection.caseDigest,fresh.selection.caseDigest)
        assertIs<RetainedResult.Opened>(repo.execute(action.copy(selected=fresh.selection)))
    }
    @Test fun sourceAndDocumentIdentityCannotBeForgedOrReplaced() = root { dir ->
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource());val s=create(repo);val a=attachment(s,choices(repo).first())
        assertIs<RetainedResult.Rejected>(repo.execute(a.copy(selected=a.selected.copy(caseDigest="0".repeat(64)))))
        val attached=assertIs<RetainedResult.Opened>(repo.execute(a)).inspection.snapshot!!;val bytes=file(dir).readBytes()
        assertIs<RetainedResult.Rejected>(repo.execute(attachment(attached,choices(repo).first(),"duplicate-case")))
        val differentComplaint = ModeledRetainedFixtures.complaint().let { it.copy(revision = it.revision.copy(reason = "Different complaint")) }
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Create(id, differentComplaint)))
        assertContentEquals(bytes,file(dir).readBytes())
    }
    @Test fun sameRequestRetryIsIdempotentButDifferentPayloadDuplicateIdsAndStaleTokensReject() = root { dir ->
        val source=ModeledCaseSource();val repo=RetainedInvestigationRepository(dir,source);val s=create(repo);val a=attachment(s,choices(repo).first())
        val next=assertIs<RetainedResult.Opened>(repo.execute(a)).inspection.snapshot!!;val bytes=file(dir).readBytes();val reads=source.reads
        source.bytes="{corrupt changed source".toByteArray()
        assertTrue(assertIs<RetainedResult.Opened>(repo.execute(a)).replayed);assertEquals(reads,source.reads)
        assertTrue(assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Create(id,ModeledRetainedFixtures.complaint()))).replayed)
        assertIs<RetainedResult.Rejected>(repo.execute(a.copy(intent=a.intent.copy(reason="different immutable request"))))
        val note=CombinedEvent.Ordinary("request-note",InvestigationRevision("note",3,90001,"Fictional","Later",InvestigationStep.Observation(InvestigationObservationKind.REPORTED,"Report","Fictional")))
        assertEquals(RetainedFailure.STALE_DOCUMENT,assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Append(id,s.byteToken,note))).failure)
        val duplicate=note.copy(revision=note.revision.copy(id="complaint"))
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Append(id,next.byteToken,duplicate)))
        assertContentEquals(bytes,file(dir).readBytes())
    }
    @Test fun fixturesRemainStrictWhileRetainedOriginsUnknownAndMixedStayExplicit() = root { dir ->
        val source=ModeledCaseSource();val c=ModeledRetainedFixtures.cases().first().let { it.copy(drive=it.drive.copy(origin=DriveEvidenceOrigin.UNKNOWN)) }
        source.bytes=DiagnosticCaseCodec.encode(listOf(c)).toByteArray();val repo=RetainedInvestigationRepository(dir,source);val s=create(repo)
        val next=assertIs<RetainedResult.Opened>(repo.execute(attachment(s,choices(repo).single()))).inspection.snapshot!!
        assertEquals(ScanOrigin.HARDWARE,next.document.cases().single().scan.capture!!.origin)
        assertEquals(DriveEvidenceOrigin.UNKNOWN,next.document.cases().single().drive.origin)
        assertFailsWith<CombinedValidation> { CombinedInvestigationFixtures.partialBankSeed().copy(initialCases=listOf(c)).validate() }
        val missing=ModeledRetainedFixtures.complaint().let { it.copy(revision=it.revision.copy(id="measured",sequence=2,step=InvestigationStep.Observation(InvestigationObservationKind.CASE_SCAN_READING,"Value","Fictional",InvestigationEvidenceRef(c.id,InvestigationObservationKind.CASE_SCAN_READING,pid=5)))) }
        val empty=RetainedInvestigation("empty-test",listOf(RetainedRevision(ModeledRetainedFixtures.complaint())))
        assertFails { empty.append(RetainedRevision(missing)) }
    }
    @Test fun interruptedAppendRequiresObservedRecoveryAndPreservesOriginals() = root { dir ->
        val source=ModeledCaseSource();val good=RetainedInvestigationRepository(dir,source);val s=create(good);val a=attachment(s,choices(good).first());val before=file(dir).readBytes()
        val files=object:OutcomeSandboxFiles(){ override fun publish(pending:File,committed:File){throw IOException("Injected before publish")} }
        val failed=assertIs<RetainedResult.Rejected>(RetainedInvestigationRepository(dir,source,files).execute(a))
        assertEquals(RetainedFailure.IO_OR_ACK_UNKNOWN,failed.failure);val inspection=failed.inspection!!;assertTrue(inspection.recoverable)
        assertContentEquals(before,file(dir).readBytes());val stage=file(dir,pending=true).readBytes()
        assertIs<RetainedResult.Rejected>(good.execute(a));assertIs<RetainedResult.Rejected>(good.execute(RetainedAction.Recover(id,"wrong",inspection.pending!!.digest!!)))
        assertContentEquals(stage,file(dir,pending=true).readBytes())
        val recovered=assertIs<RetainedResult.Opened>(good.execute(RetainedAction.Recover(id,inspection.committed!!.digest,inspection.pending.digest!!)))
        assertEquals(2,recovered.inspection.snapshot!!.document.events.size);assertFalse(file(dir,pending=true).exists())
        assertTrue(assertIs<RetainedResult.Opened>(good.execute(a)).replayed)
    }
    @Test fun postPublishValidationShapedExceptionIsUncertainAndReplayFindsOneCommit() = root { dir ->
        val source=ModeledCaseSource();val good=RetainedInvestigationRepository(dir,source);val s=create(good);val a=attachment(s,choices(good).first())
        val files=object:OutcomeSandboxFiles(){override fun publish(pending:File,committed:File){super.publish(pending,committed);throw IllegalArgumentException("Injected lost ack")}}
        val failed=assertIs<RetainedResult.Rejected>(RetainedInvestigationRepository(dir,source,files).execute(a));assertEquals(RetainedFailure.IO_OR_ACK_UNKNOWN,failed.failure)
        assertEquals(2,failed.inspection!!.snapshot!!.document.events.size);assertTrue(assertIs<RetainedResult.Opened>(good.execute(a)).replayed)
    }
    @Test fun corruptFutureDivergentOriginalsAreBlockedAndLosslesslyExported() = root { dir ->
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource());create(repo);val original=file(dir).readBytes()
        val divergedComplaint = ModeledRetainedFixtures.complaint().let { it.copy(revision = it.revision.copy(reason = "diverged")) }
        val diverged = RetainedInvestigationCodec.encode(RetainedInvestigation(id, listOf(RetainedRevision(divergedComplaint))))
        val examples = listOf("{partial", file(dir).readText().replace(RetainedInvestigationCodec.SCHEMA, "future/9"), diverged)
        for (text in examples) {
            file(dir,pending=true).writeText(text);val inspection=opened(repo)
            assertEquals(RetainedStorageStatus.BLOCKED,inspection.status);assertFalse(inspection.recoverable)
            val observed=listOf(inspection.committed!!,inspection.pending!!);val exported=mutableMapOf<String,ByteArrayOutputStream>()
            val result=repo.execute(RetainedAction.ExportOriginals(observed,OriginalExportSink { name -> ByteArrayOutputStream().also { exported[name]=it } }))
            assertIs<RetainedResult.Exported>(result);assertContentEquals(original,exported.getValue("$id.json").toByteArray())
            assertContentEquals(text.toByteArray(),exported.getValue("$id.pending").toByteArray())
            assertContentEquals(original,file(dir).readBytes());assertEquals(text,file(dir,pending=true).readText())
        }
    }
    @Test fun blockedCommittedFutureCanBeInspectedListedAndExportedWithoutReinterpretation() = root { dir ->
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource());create(repo)
        val bytes=file(dir).readText().replace(RetainedInvestigationCodec.SCHEMA,"future/999").toByteArray();file(dir).writeBytes(bytes)
        val inspection=opened(repo);assertEquals(RetainedStorageStatus.BLOCKED,inspection.status);assertNull(inspection.snapshot)
        assertTrue(inspection.problem!!.contains("future/999"));assertIs<RetainedResult.Listing>(repo.execute(RetainedAction.ListNew))
        val out=ByteArrayOutputStream();assertIs<RetainedResult.Exported>(repo.execute(RetainedAction.ExportOriginals(listOf(inspection.committed!!),OriginalExportSink { out })))
        assertContentEquals(bytes,out.toByteArray());assertContentEquals(bytes,file(dir).readBytes())
    }
    @Test fun exportRejectsChangedIdentityAndUnknownNamesCannotEscapeNamespace() = root { dir ->
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource());create(repo);val observed=opened(repo).committed!!;file(dir).appendText("\n")
        var destinations=0
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.ExportOriginals(listOf(observed),OriginalExportSink { destinations++;ByteArrayOutputStream() })))
        assertEquals(0,destinations)
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.ExportOriginals(listOf(observed.copy(name="../owner")),OriginalExportSink { fail("must not open") })))
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Create("../escape",ModeledRetainedFixtures.complaint())))
    }
    @Test fun quotasRejectNewIdsWithoutEvictionAndOversizedOriginalStaysVisible() = root { dir ->
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource())
        assertFails { RetainedInvestigation("over-limit", List(501) { RetainedRevision(ModeledRetainedFixtures.complaint()) }).validate() }
        repeat(10){create(repo,"new-$it")}
        assertEquals(RetainedFailure.QUOTA,assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Create("new-11",ModeledRetainedFixtures.complaint()))).failure)
        assertEquals(10,assertIs<RetainedResult.Listing>(repo.execute(RetainedAction.ListNew)).entries.size)
        val f=file(dir,"new-0");RandomAccessFile(f,"rw").use { it.setLength(RetainedInvestigationRepository.MAX_ORIGINAL_BYTES+1) }
        val inspection=opened(repo,"new-0");assertEquals(RetainedStorageStatus.BLOCKED,inspection.status);assertNull(inspection.committed!!.digest)
        assertTrue(f.length()>RetainedInvestigationRepository.MAX_ORIGINAL_BYTES)
    }
    @Test fun partialStageAndPendingOnlySeedStayPreservedAndExplicit() = root { dir ->
        val source=ModeledCaseSource();val files=object:OutcomeSandboxFiles(){override fun stage(file:File,bytes:ByteArray){file.writeText("{partial");throw IOException("Injected partial stage")}}
        val result=assertIs<RetainedResult.Rejected>(RetainedInvestigationRepository(dir,source,files).execute(RetainedAction.Create(id,ModeledRetainedFixtures.complaint())))
        assertEquals(RetainedFailure.IO_OR_ACK_UNKNOWN,result.failure);assertEquals(RetainedStorageStatus.BLOCKED,result.inspection!!.status)
        assertFalse(file(dir).exists());assertEquals("{partial",file(dir,pending=true).readText())
    }
    @Test fun serviceSerializesActionsAndCanceledWriteReopensWithoutPreview() = root { dir -> runTest {
        val repo=RetainedInvestigationRepository(dir,ModeledCaseSource());val dispatcher=StandardTestDispatcher(testScheduler);val job=SupervisorJob();val scope=CoroutineScope(job+dispatcher)
        val service=RetainedInvestigationService(repo,scope,dispatcher)
        assertEquals(CombinedAdmission.ACCEPTED,service.dispatch(RetainedAction.Create(id,ModeledRetainedFixtures.complaint())))
        assertTrue(service.state.value.loading);assertEquals(CombinedAdmission.BUSY,service.dispatch(RetainedAction.ListNew));job.cancel();runCurrent()
        assertEquals(1,opened(repo).snapshot!!.document.events.size)
        val reopened=RetainedInvestigationService(repo,this,dispatcher);reopened.dispatch(RetainedAction.Open(id));runCurrent()
        assertFalse(reopened.state.value.loading);assertIs<RetainedResult.Opened>(reopened.state.value.outcome)
    } }
    @Test fun twoOwnersWithSameTokenCannotBothAppendAndPreviewNeverMutates() = root { dir -> runTest {
        val source=ModeledCaseSource();val repo=RetainedInvestigationRepository(dir,source);val s=questionPrefix(repo);val dispatcher=StandardTestDispatcher(testScheduler)
        val a=RetainedInvestigationService(repo,this,dispatcher);val b=RetainedInvestigationService(RetainedInvestigationRepository(dir,source),this,dispatcher)
        val one=ModeledRetainedFixtures.typed("sealing-first","one",11,s.document.cases().first().id)
        val two=ModeledRetainedFixtures.typed("sealing-first","two",11,s.document.cases().first().id)
        a.dispatch(RetainedAction.Append(id,s.byteToken,one));b.dispatch(RetainedAction.Append(id,s.byteToken,two));runCurrent()
        assertIs<RetainedResult.Opened>(a.state.value.outcome);assertEquals(RetainedFailure.STALE_DOCUMENT,assertIs<RetainedResult.Rejected>(b.state.value.outcome).failure)
        val snapshot=opened(repo).snapshot!!;val action=preview(snapshot);val selected=RetainedPreviewSelection(id,action.request.selection());a.selectPreview(selected)
        assertEquals(CombinedAdmission.ACCEPTED,a.dispatch(action));a.selectPreview(null);runCurrent()
        assertIs<RetainedResult.Opened>(a.state.value.outcome)
        a.selectPreview(selected);a.dispatch(action);runCurrent();val before=file(dir).readBytes()
        assertIs<RetainedResult.Previewed>(a.state.value.outcome);a.detach();assertIs<RetainedResult.Opened>(a.state.value.outcome)
        assertContentEquals(before,file(dir).readBytes())
    } }
    @Test fun regularAndBrokenSymbolicLinksAreBlockedWithoutReadingTheirTargets() = root { dir ->
        val repo = RetainedInvestigationRepository(dir, ModeledCaseSource())
        val outside = File(dir, "unrelated-original").apply { writeText("unrelated fictional sentinel") }
        file(dir).parentFile!!.mkdirs()
        Files.createSymbolicLink(file(dir).toPath(), outside.toPath())
        val linked = opened(repo)
        assertEquals(RetainedStorageStatus.BLOCKED, linked.status)
        assertNull(linked.committed!!.digest)
        assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Create(id, ModeledRetainedFixtures.complaint())))
        assertEquals("unrelated fictional sentinel", outside.readText())
        val brokenId = "broken-original"
        Files.createSymbolicLink(file(dir, brokenId).toPath(), File(dir, "not-present").toPath())
        assertEquals(RetainedStorageStatus.BLOCKED, opened(repo, brokenId).status)
    }
    @Test fun unavailableSourceAndFailedExportRemainExplicitWithoutFallbackOrSourceMutation() = root { dir ->
        val source = ModeledCaseSource()
        val repo = RetainedInvestigationRepository(dir, source)
        create(repo)
        val bytes = file(dir).readBytes()
        source.bytes = "{future-case-format".toByteArray()
        assertEquals(RetainedFailure.SOURCE_UNAVAILABLE, assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.CaseChoices)).failure)
        val original = opened(repo).committed!!
        val failed = repo.execute(RetainedAction.ExportOriginals(listOf(original), OriginalExportSink {
            object : OutputStream() { override fun write(value: Int) { throw IOException("Injected destination failure") } }
        }))
        assertEquals(RetainedFailure.EXPORT_INCOMPLETE, assertIs<RetainedResult.Rejected>(failed).failure)
        assertContentEquals(bytes, file(dir).readBytes())
    }
    @Test fun oversizedDecodedOriginalWithinExportLimitStreamsLosslesslyAndUnknownNamesRemainVisible() = root { dir ->
        val repo = RetainedInvestigationRepository(dir, ModeledCaseSource())
        create(repo)
        val original = file(dir)
        RandomAccessFile(original, "rw").use { it.setLength(InvestigationCodec.MAX_BYTES + 1L) }
        val inspection = opened(repo)
        assertEquals(RetainedStorageStatus.BLOCKED, inspection.status)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var count = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()); count++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length); count += length }
        }
        assertIs<RetainedResult.Exported>(repo.execute(RetainedAction.ExportOriginals(listOf(inspection.committed!!), OriginalExportSink { output })))
        assertEquals(original.length(), count)
        assertEquals(inspection.committed.digest, digest.digest().joinToString("") { "%02x".format(it) })
        val unknown = File(original.parentFile, "unrecognized-original").apply { writeText("future opaque bytes") }
        val listing = assertIs<RetainedResult.Listing>(repo.execute(RetainedAction.ListNew))
        val entry = listing.entries.single { it.id == null }.originals.single()
        val bytes = ByteArrayOutputStream()
        assertIs<RetainedResult.Exported>(repo.execute(RetainedAction.ExportOriginals(listOf(entry), OriginalExportSink { bytes })))
        assertContentEquals(unknown.readBytes(), bytes.toByteArray())
    }
    @Test fun pendingOnlyValidSeedRecoversButTamperedAttachmentDigestAndFutureCodecFailClosed() = root { dir ->
        val source = ModeledCaseSource()
        val files = object : OutcomeSandboxFiles() { override fun publish(pending: File, committed: File) { throw IOException("Injected seed publish failure") } }
        val failed = assertIs<RetainedResult.Rejected>(RetainedInvestigationRepository(dir, source, files).execute(RetainedAction.Create(id, ModeledRetainedFixtures.complaint())))
        val pending = failed.inspection!!
        assertTrue(pending.recoverable); assertNull(pending.committed)
        val good = RetainedInvestigationRepository(dir, source)
        val recovered = assertIs<RetainedResult.Opened>(good.execute(RetainedAction.Recover(id, null, pending.pending!!.digest!!))).inspection.snapshot!!
        val attached = assertIs<RetainedResult.Opened>(good.execute(attachment(recovered, choices(good).first()))).inspection.snapshot!!
        val bad = attached.document.copy(revisions = attached.document.revisions.map { if (it.source == null) it else it.copy(source = it.source.copy(caseDigest = "0".repeat(64))) })
        assertFails { bad.validate() }
        val text = RetainedInvestigationCodec.encode(attached.document)
        assertFailsWith<CombinedUnsupported> { RetainedInvestigationCodec.decode(text.replace(RetainedInvestigationCodec.SCHEMA, "future/88")) }
        assertFails { RetainedInvestigationCodec.decode(text.replaceFirst("\"id\":", "\"id\":\"duplicate\",\"id\":")) }
    }
    @Test fun concurrentRepositoryOwnersSerializeAStagedWriteAndRejectTheSecondOldToken() = root { dir ->
        val source = ModeledCaseSource()
        val good = RetainedInvestigationRepository(dir, source)
        val s = create(good)
        val a = attachment(s, choices(good).first())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        val files = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) {
                entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); super.stage(file, bytes)
            }
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<RetainedResult> { RetainedInvestigationRepository(dir, source, files).execute(a) }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<RetainedResult> { secondStarted.countDown(); good.execute(a.copy(intent = a.intent.copy(requestId = "other-request", revisionId = "other-attachment"))) }
            assertTrue(secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(second.isDone)
            release.countDown()
            assertIs<RetainedResult.Opened>(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(RetainedFailure.STALE_DOCUMENT, assertIs<RetainedResult.Rejected>(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).failure)
            assertEquals(2, opened(good).snapshot!!.document.events.size)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun listingFailureNeverReportsEmptyOrAllowsCreateOverUnseenOriginals() = root { dir ->
        val source = ModeledCaseSource()
        val good = RetainedInvestigationRepository(dir, source)
        create(good)
        val before = file(dir).readBytes()
        for (failRoot in listOf(false, true)) {
            val unavailable = RetainedInvestigationRepository(dir, source, enumerate = { directory ->
                if ((failRoot && directory == dir) || (!failRoot && directory.name == RetainedInvestigationRepository.DIRECTORY)) null else directory.listFiles()
            })
            val result = assertIs<RetainedResult.Rejected>(unavailable.execute(RetainedAction.Open(id)))
            assertEquals(RetainedFailure.STORAGE_BLOCKED, result.failure)
            assertNull(result.inspection) // unavailable, never a fabricated EMPTY inspection
            assertIs<RetainedResult.Rejected>(unavailable.execute(RetainedAction.Create(id, ModeledRetainedFixtures.complaint())))
            assertContentEquals(before, file(dir).readBytes())
        }
    }

}
