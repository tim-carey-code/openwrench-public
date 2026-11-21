package org.openwrench.frontend.android

import kotlinx.coroutines.test.*
import org.openwrench.core.*
import java.io.*
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.*

/** Only fictional streams/temp files. Android framework/provider/Os implementations are never invoked. */
private class FakeExclusiveFiles(private val fail: (File) -> Boolean = { false }, private val failSync: Boolean = false,
    private val partial: Boolean = false) : ExclusiveInvestigationFiles {
    override fun createNew(file: File): ExclusiveInvestigationFile {
        val channel = try { FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
            catch (_: java.nio.file.FileAlreadyExistsException) { throw InvestigationIoException(InvestigationIoFailure.DESTINATION_EXISTS) }
        val raw = Channels.newOutputStream(channel)
        if (fail(file)) { channel.close(); throw IOException("Injected creation failure") }
        val output = if (!partial || file.name == "plan.json" || file.name.endsWith(".receipt.json")) raw else object : OutputStream() {
            override fun write(value: Int) { raw.write(value); throw IOException("Injected partial write") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { raw.write(bytes, offset, minOf(3, length)); throw IOException("Injected partial write") }
        }
        return object : ExclusiveInvestigationFile {
            override val output = output
            override fun sync() { if (failSync && file.name != "plan.json") throw IOException("Injected sync failure"); channel.force(true) }
            override fun close() { channel.close() }
        }
    }
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidInvestigationIoTest {
    private fun root(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ow-android-io-fictional").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun area(root: File, files: ExclusiveInvestigationFiles = FakeExclusiveFiles()) = InvestigationPrivateArea(AppPrivateFilesLocation { root }, files)
    private fun handle() = SelectedArchiveHandle("fictional-choice-1", "content://fictional.provider/private-document-opaque")
    private fun source(bytes: () -> ByteArray) = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { ByteArrayInputStream(bytes()) })
    private fun create(repo: RetainedInvestigationRepository): RetainedSnapshot = assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Create("android-new", ModeledRetainedFixtures.complaint()))).inspection.snapshot!!
    private fun originals(repo: RetainedInvestigationRepository) = assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Open("android-new"))).inspection.let { listOfNotNull(it.committed, it.pending) }
    private fun archive() = DiagnosticCaseCodec.encode(ModeledRetainedFixtures.cases()).toByteArray()

    @Test fun adapterToOwnerAttachAndExclusiveExportPreserveOriginsIdentitiesAndOriginalBytes() = root { dir ->
        val area = area(dir); val repository = RetainedInvestigationRepository(area.candidateRoot(), source { archive() })
        val s = create(repository); val choice = assertIs<RetainedResult.Cases>(repository.execute(RetainedAction.CaseChoices)).choices.first()
        val attach = RetainedAction.Attach("android-new", s.byteToken, choice.selection,
            AttachmentIntent("request-attach", "attach", 2, 90000, "Fictional", "Modeled retained source", InvestigationVehicleAssertion.UNKNOWN, "Invented source only"))
        val attached = assertIs<RetainedResult.Opened>(repository.execute(attach)).inspection.snapshot!!
        assertEquals(choice.retainedCase, attached.document.cases().single()); assertNull(attached.document.cases().single().scan.vin)
        val observed = originals(repository); val before = File(area.candidateRoot(), "${RetainedInvestigationRepository.DIRECTORY}/android-new.json").readBytes()
        val session = area.newExport("export-one", observed)
        val exported = assertIs<RetainedResult.Exported>(repository.execute(RetainedAction.ExportOriginals(observed, session)))
        assertEquals(InvestigationExportStatus.COMPLETE, exported.destinations.single().status)
        val recovered = area.inspectExport("export-one").single(); assertEquals(InvestigationExportStatus.MATCHED_ACK_UNCONFIRMED, recovered.status)
        assertEquals(observed.single().digest, recovered.observedDigest)
        val copy = File(dir, "${InvestigationPrivateArea.DIRECTORY}/exports/export-one/files/android-new.json")
        assertContentEquals(before, copy.readBytes())
        assertContentEquals(before, File(area.candidateRoot(), "${RetainedInvestigationRepository.DIRECTORY}/android-new.json").readBytes())
    }
    @Test fun coldPrivateBoundaryDoesNotCreateFactoryOrReadInstalledHistories() = root { dir ->
        val sentinel = File(dir, "investigations-v1/owner-sentinel").apply { parentFile!!.mkdirs(); writeText("fictional unrelated original") }
        val boundary = area(dir); val path = boundary.candidateRoot()
        assertFalse(path.exists()); assertEquals("new-investigations", path.name)
        assertEquals("fictional unrelated original", sentinel.readText())
        assertEquals(InvestigationIoFailure.UNSUPPORTED_PROVIDER, assertFailsWith<InvestigationIoException> { boundary.providerExportUnsupported() }.failure)
        assertFalse(File(dir, InvestigationPrivateArea.DIRECTORY).exists())
    }
    @Test fun sourceFailuresAreDistinctStructuredAndNeverLeakRawUriOrProviderText() = root { dir ->
        val failures: List<Pair<InvestigationIoFailure, () -> InputStream?>> = listOf(
            InvestigationIoFailure.NULL_STREAM to { null },
            InvestigationIoFailure.MISSING_SOURCE to { throw FileNotFoundException("secret private URI") },
            InvestigationIoFailure.ACCESS_DENIED_OR_REVOKED to { throw SecurityException("secret private URI") },
            InvestigationIoFailure.READ_FAILED to { throw IOException("secret raw bytes") })
        for ((expected, open) in failures) {
            val source = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { open() })
            val repo = RetainedInvestigationRepository(area(dir).candidateRoot(), source)
            val rejected = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.CaseChoices))
            assertEquals(RetainedFailure.SOURCE_UNAVAILABLE, rejected.failure); assertEquals(expected, rejected.ioFailure)
            assertFalse(rejected.detail.contains("secret")); assertFalse(handle().toString().contains("content://"))
        }
        assertEquals(InvestigationIoFailure.UNSUPPORTED_PROVIDER, assertFailsWith<InvestigationIoException> { SelectedArchiveHandle("choice", "file:///private/history") }.failure)
    }
    @Test fun streamReadAndCloseFailuresCancelAndNoProgressAreExplicitAndOwned() {
        var closed = false
        val broken = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { object : InputStream() {
            override fun read(): Int { throw IOException("private source bytes") }
            override fun close() { closed = true }
        } })
        assertEquals(InvestigationIoFailure.READ_FAILED, assertFailsWith<InvestigationIoException> { broken.openArchive().use { it.read() } }.failure)
        assertTrue(closed)
        val stalled = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        } })
        assertEquals(InvestigationIoFailure.NO_READ_PROGRESS, assertFailsWith<InvestigationIoException> { stalled.openArchive().use { boundedBytes(it, 20) } }.failure)
        val canceled = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { fail("Canceled before provider open") }, canceled = { true })
        assertEquals(InvestigationIoFailure.CANCELED, assertFailsWith<InvestigationIoException> { canceled.openArchive() }.failure)
        val failedClose = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw IOException("private URI") }
        } })
        assertEquals(InvestigationIoFailure.READ_FAILED, assertFailsWith<InvestigationIoException> { failedClose.openArchive().use { it.readBytes() } }.failure)
    }
    @Test fun sourceReplacementIsRejectedThroughFreshAdapterReadWithoutWriting() = root { dir ->
        var bytes = archive(); var opens = 0
        val src = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { opens++; ByteArrayInputStream(bytes) })
        val repo = RetainedInvestigationRepository(area(dir).candidateRoot(), src); val s = create(repo)
        val choice = assertIs<RetainedResult.Cases>(repo.execute(RetainedAction.CaseChoices)).choices.first()
        bytes += "\n".toByteArray()
        val result = repo.execute(RetainedAction.Attach("android-new", s.byteToken, choice.selection,
            AttachmentIntent("request-attach", "attach", 2, 90000, "Fictional", "Selected source", InvestigationVehicleAssertion.UNKNOWN, "Fictional")))
        assertEquals(RetainedFailure.STALE_SOURCE, assertIs<RetainedResult.Rejected>(result).failure); assertEquals(2, opens)
        assertEquals(s, assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Open("android-new"))).inspection.snapshot)
    }
    @Test fun exclusiveDestinationSessionAndFilenameCannotOverwritePriorCopy() = root { dir ->
        val boundary = area(dir); val repo = RetainedInvestigationRepository(boundary.candidateRoot(), source { archive() }); create(repo)
        val expected = originals(repo); val session = boundary.newExport("one", expected)
        assertIs<RetainedResult.Exported>(repo.execute(RetainedAction.ExportOriginals(expected, session)))
        val before = boundary.inspectExport("one")
        assertEquals(InvestigationIoFailure.DESTINATION_EXISTS, assertFailsWith<InvestigationIoException> { session.openNew(expected.single().name) }.failure)
        assertEquals(InvestigationIoFailure.DESTINATION_EXISTS, assertFailsWith<InvestigationIoException> { boundary.newExport("one", expected) }.failure)
        assertEquals(before, boundary.inspectExport("one"))
        assertFails { boundary.newExport("../../escape", expected) }
        assertFails { boundary.newExport("invalid", listOf(expected.single().copy(name = "../owner.json"))) }
    }
    @Test fun partialWriteRetainsObservedDestinationReceiptAndReopensAsPartial() = root { dir ->
        val boundary = area(dir, FakeExclusiveFiles(partial = true)); val repo = RetainedInvestigationRepository(boundary.candidateRoot(), source { archive() }); create(repo)
        val expected = originals(repo); val session = boundary.newExport("partial", expected)
        val rejected = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.ExportOriginals(expected, session)))
        assertEquals(RetainedFailure.EXPORT_INCOMPLETE, rejected.failure); assertEquals(InvestigationIoFailure.DESTINATION_FAILED, rejected.ioFailure)
        assertEquals(InvestigationExportStatus.PARTIAL, rejected.destinations.single().status)
        assertEquals(3L, rejected.destinations.single().observedSize)
        val reopened = boundary.inspectExport("partial").single(); assertEquals(InvestigationExportStatus.PARTIAL, reopened.status); assertEquals(3L, reopened.observedSize)
        assertEquals(expected, originals(repo))
    }
    @Test fun cancellationPreservesPartialFileAndNeverMarksFullByteMatchCompleteWithoutReceipt() = root { dir ->
        val boundary = area(dir); val repo = RetainedInvestigationRepository(boundary.candidateRoot(), source { archive() }); create(repo)
        val expected = originals(repo); var cancel = false
        val session = boundary.newExport("cancel", expected) { cancel }
        val stream = session.openNew(expected.single().name)
        stream.write(byteArrayOf(1, 2, 3)); cancel = true
        assertEquals(InvestigationIoFailure.CANCELED, assertFailsWith<InvestigationIoException> { stream.write(4) }.failure)
        assertEquals(InvestigationIoFailure.CANCELED, assertFailsWith<InvestigationIoException> { stream.close() }.failure)
        assertEquals(InvestigationExportStatus.CANCELED, session.receipts().single().status)
        assertEquals(InvestigationExportStatus.PARTIAL, boundary.inspectExport("cancel").single().status)
        val receiptless = boundary.newExport("receiptless", expected)
        val copy = File(dir, "${InvestigationPrivateArea.DIRECTORY}/exports/receiptless/files/${expected.single().name}")
        val original = File(boundary.candidateRoot(), "${RetainedInvestigationRepository.DIRECTORY}/${expected.single().name}")
        Files.copy(original.toPath(), copy.toPath()) // scripted process interruption after byte copy, before successful-close receipt
        assertEquals(InvestigationExportStatus.PARTIAL, boundary.inspectExport("receiptless").single().status)
        assertEquals(InvestigationExportStatus.PLANNED, receiptless.receipts().single().status)
    }
    @Test fun syncAndCompletionReceiptFailuresNeverReportSuccess() = root { dir ->
        val standard = area(dir); val repo = RetainedInvestigationRepository(standard.candidateRoot(), source { archive() }); create(repo); val expected = originals(repo)
        for ((id, files, status) in listOf(Triple("sync-fail", FakeExclusiveFiles(failSync = true), InvestigationExportStatus.PARTIAL),
            Triple("receipt-fail", FakeExclusiveFiles(fail = { it.name.endsWith(".receipt.json") }), InvestigationExportStatus.RECEIPT_FAILED))) {
            val boundary = area(dir, files); val session = boundary.newExport(id, expected)
            val rejected = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.ExportOriginals(expected, session)))
            assertEquals(RetainedFailure.EXPORT_INCOMPLETE, rejected.failure); assertEquals(status, rejected.destinations.single().status)
            assertEquals(InvestigationExportStatus.PARTIAL, boundary.inspectExport(id).single().status)
        }
    }
    @Test fun planFailureAndSessionQuotaPreserveExistingDestinations() = root { dir ->
        val boundary = area(dir); val repo = RetainedInvestigationRepository(boundary.candidateRoot(), source { archive() }); create(repo); val expected = originals(repo)
        val failed = area(dir, FakeExclusiveFiles(fail = { it.name == "plan.json" }))
        assertEquals(InvestigationIoFailure.RECEIPT_FAILED, assertFailsWith<InvestigationIoException> { failed.newExport("bad-plan", expected) }.failure)
        val retained = File(dir, "${InvestigationPrivateArea.DIRECTORY}/exports/bad-plan/plan.json"); assertTrue(retained.exists())
        repeat(9) { boundary.newExport("session-$it", expected) }
        assertEquals(InvestigationIoFailure.QUOTA, assertFailsWith<InvestigationIoException> { boundary.newExport("extra", expected) }.failure)
        assertTrue(retained.exists())
    }
    @Test fun cancellationReceiptFlowsThroughRetainedServiceWithoutChangingOriginals() = root { dir -> runTest {
        val boundary = area(dir); val repo = RetainedInvestigationRepository(boundary.candidateRoot(), source { archive() }); create(repo)
        val expected = originals(repo); var cancel = false; val session = boundary.newExport("service-cancel", expected) { cancel }
        cancel = true
        val owner = RetainedInvestigationService(repo, this, StandardTestDispatcher(testScheduler))
        owner.dispatch(RetainedAction.ExportOriginals(expected, session)); runCurrent()
        val result = assertIs<RetainedResult.Rejected>(owner.state.value.outcome)
        assertEquals(RetainedFailure.EXPORT_INCOMPLETE, result.failure); assertEquals(InvestigationIoFailure.CANCELED, result.ioFailure)
        assertEquals(InvestigationExportStatus.PLANNED, result.destinations.single().status)
        assertEquals(expected, originals(repo))
    } }
    @Test fun malformedAndUnsupportedArchiveFailuresNeverExposePrivateInputSentinels() = root { dir ->
        val sentinel = "PRIVATE_CAPTURE_SENTINEL_DO_NOT_EXPOSE"
        val valid = String(archive())
        for (bytes in listOf(("{\"schema\":\"" + sentinel).toByteArray(), valid.replaceFirst("\"HARDWARE\"", "\"$sentinel\"").toByteArray())) {
            val repo = RetainedInvestigationRepository(area(dir).candidateRoot(), source { bytes })
            val rejected = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.CaseChoices))
            assertEquals(InvestigationIoFailure.SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED, rejected.ioFailure)
            assertFalse(rejected.toString().contains(sentinel))
        }
    }
    @Test fun receiptSyncOrCloseFailureWithValidBytesCannotBeInspectedAsAcknowledgedComplete() = root { dir ->
        val good = area(dir)
        val repo = RetainedInvestigationRepository(good.candidateRoot(), source { archive() }); create(repo); val expected = originals(repo)
        for (failClose in listOf(false, true)) {
            val base = FakeExclusiveFiles()
            val files = ExclusiveInvestigationFiles { file ->
                val delegate = base.createNew(file)
                object : ExclusiveInvestigationFile {
                    override val output = delegate.output
                    override fun sync() { delegate.sync(); if (!failClose && file.name.endsWith(".receipt.json")) throw IOException("Injected receipt sync ack loss") }
                    override fun close() { delegate.close(); if (failClose && file.name.endsWith(".receipt.json")) throw IOException("Injected receipt close ack loss") }
                }
            }
            val boundary = area(dir, files); val sessionId = if (failClose) "receipt-close" else "receipt-sync"
            val session = boundary.newExport(sessionId, expected)
            val result = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.ExportOriginals(expected, session)))
            assertEquals(InvestigationIoFailure.RECEIPT_FAILED, result.ioFailure)
            assertEquals(InvestigationExportStatus.RECEIPT_FAILED, result.destinations.single().status)
            assertEquals(InvestigationExportStatus.MATCHED_ACK_UNCONFIRMED, boundary.inspectExport(sessionId).single().status)
        }
    }
    @Test fun actualSourceReadIsBoundedAndCanceledReadsCloseTheStream() {
        var closed = false; var reads = 0L
        val huge = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { object : InputStream() {
            override fun read() = 0.also { reads++ }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { java.util.Arrays.fill(bytes, offset, offset + length, 0.toByte()); reads += length; return length }
            override fun close() { closed = true }
        } })
        assertEquals(InvestigationIoFailure.SOURCE_TOO_LARGE, assertFailsWith<InvestigationIoException> {
            huge.openArchive().use { boundedBytes(it, DiagnosticCaseCodec.MAX_BYTES) }
        }.failure)
        assertTrue(closed); assertEquals(DiagnosticCaseCodec.MAX_BYTES + 1L, reads)
        var cancel = false; closed = false
        val interrupted = AndroidRetainedArchiveSource(handle(), SelectedArchiveStreams { object : InputStream() {
            override fun read(): Int { cancel = true; return 1 }
            override fun close() { closed = true }
        } }, canceled = { cancel })
        assertEquals(InvestigationIoFailure.CANCELED, assertFailsWith<InvestigationIoException> { interrupted.openArchive().use { it.read() } }.failure)
        assertTrue(closed)
    }

    @Test fun sourceValidationDuringAttachIsSanitizedAndCannotAlterTheInvestigation() = root { dir ->
        var bytes = archive()
        val repo = RetainedInvestigationRepository(area(dir).candidateRoot(), source { bytes })
        val s = create(repo)
        val choice = assertIs<RetainedResult.Cases>(repo.execute(RetainedAction.CaseChoices)).choices.first()
        bytes = String(bytes).replaceFirst("\"HARDWARE\"", "\"PRIVATE_SENTINEL_FROM_ARCHIVE\"").toByteArray()
        val result = assertIs<RetainedResult.Rejected>(repo.execute(RetainedAction.Attach("android-new", s.byteToken, choice.selection,
            AttachmentIntent("request-attach", "attach", 2, 90000, "Fictional", "Invented source", InvestigationVehicleAssertion.UNKNOWN, "Fictional"))))
        assertEquals(InvestigationIoFailure.SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED, result.ioFailure)
        assertFalse(result.toString().contains("PRIVATE_SENTINEL_FROM_ARCHIVE"))
        assertEquals(s, assertIs<RetainedResult.Opened>(repo.execute(RetainedAction.Open("android-new"))).inspection.snapshot)
    }
    @Test fun originalNamedPlanJsonIsSeparateFromSessionMetadataAndDigestMismatchCannotComplete() = root { dir ->
        val boundary = area(dir)
        val bytes = "Fictional opaque blocked original".toByteArray()
        val expected = OriginalIdentity("plan.json", bytes.size.toLong(), investigationDigest(bytes))
        val session = boundary.newExport("plan-name", listOf(expected))
        session.openNew("plan.json").use { it.write(bytes) }
        assertEquals(InvestigationExportStatus.COMPLETE, session.receipts().single().status)
        assertEquals(InvestigationExportStatus.MATCHED_ACK_UNCONFIRMED, boundary.inspectExport("plan-name").single().status)
        val wrong = expected.copy(digest = "0".repeat(64))
        val mismatch = boundary.newExport("wrong-digest", listOf(wrong))
        val output = mismatch.openNew("plan.json")
        output.write(bytes)
        assertEquals(InvestigationIoFailure.DESTINATION_PARTIAL, assertFailsWith<InvestigationIoException> { output.close() }.failure)
        assertEquals(InvestigationExportStatus.PARTIAL, boundary.inspectExport("wrong-digest").single().status)
    }

    @Test fun incompleteOrEmptyPlansArePreservedAndNeverBecomeAnEmptySuccessfulInspection() = root { dir ->
        val boundary = area(dir)
        val expected = OriginalIdentity("original.json", 0, investigationDigest(byteArrayOf()))
        boundary.newExport("invalid-plan", listOf(expected))
        val file = File(dir, "${InvestigationPrivateArea.DIRECTORY}/exports/invalid-plan/plan.json")
        for (text in listOf("{partial", "{\"schema\":\"openwrench.private-original-export/1\",\"originals\":[]}")) {
            file.writeText(text) // fictional corruption fixture only
            assertFails { boundary.inspectExport("invalid-plan") }
            assertEquals(text, file.readText())
        }
    }

}
