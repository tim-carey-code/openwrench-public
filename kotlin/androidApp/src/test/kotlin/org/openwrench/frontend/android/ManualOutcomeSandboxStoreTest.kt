package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.ScanOrigin
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

/** Fictional local directories and injected file failures only; never installed app data or transports. */
class ManualOutcomeSandboxStoreTest {
    private val legacy: String by lazy {
        val scan = caseScanFixture().let { it.copy(capture = it.capture!!.copy(origin = ScanOrigin.SYNTHETIC, sessionId = null, sourceEcu = null)) }
        val c = DiagnosticCaseBuilder.create(scan, caseDriveFixture(), true, 4000)
        var j = DiagnosticInvestigation("fictional", listOf(InvestigationRevision("complaint", 1, 5000, "Fictional author", "Fictional story",
            InvestigationStep.Complaint("Cold-start/-16% reported only; no verified cause"))))
        fun add(id: String, s: InvestigationStep) { j = j.append(id, 6000, "Fictional author", "Fixture", s) }
        add("obs", InvestigationStep.Observation(InvestigationObservationKind.MANUALLY_OBSERVED, "Fictional click", "No sealing proof"))
        add("h1", InvestigationStep.Hypothesis("Vapor-flow candidate, unproven", listOf("obs")))
        add("h2", InvestigationStep.Hypothesis("Other explanation remains open", listOf("obs")))
        add("q1", InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("h1", "h2")))
        add("q2", InvestigationQuestionCatalog.question(InvestigationQuestionId.VAPOR_SEALING).proposal(listOf("h1", "h2")))
        " \n" + InvestigationCodec.encode(SavedInvestigation(j, listOf(c))) + "\n "
    }
    private fun result(id: String = "typed1", offset: Int = 1, question: String = "q1", supersedes: String? = null): AttributedManualOutcome {
        val base = InvestigationCodec.decode(legacy)
        val unknown = ReportedDetail(ReportKnowledge.UNKNOWN)
        return AttributedManualOutcome(id, base.journal.revisions.size + offset, 8000, "Fictional author", "Scoped report",
            ManualOutcomeContext(question, base.cases.single().id, OutcomeCatalogReference(InvestigationQuestionId.VAPOR_SEALING, 1),
                OwnerReportSelection(ReportSelection.UNRESOLVED), OwnerReportSelection(ReportSelection.UNRESOLVED), listOf("obs"), emptyList()),
            ManualOutcomeOrigin.SYNTHETIC, ManualFaultMode.UNWANTED_FLOW_WHEN_EXPECTED_SEALED, ManualOutcomeMethod.CLICK_OR_ACKNOWLEDGMENT,
            DeclaredManualOutcome.INCONCLUSIVE, unknown, unknown, unknown, unknown, ReportedApplicability.UNKNOWN, null,
            "Fictional manual result, no mechanical test", emptyList(), listOf(InvestigationEffect("h1", HypothesisEffect.UNCHANGED, "Click is not sealing evidence")), supersedes)
    }
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ow-fictional-outcome-sandbox").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun committed(root: File) = File(root, "${ManualOutcomeSandboxStore.DIRECTORY}/fictional.json")
    private fun pending(root: File) = File(root, "${ManualOutcomeSandboxStore.DIRECTORY}/fictional.pending")
    private fun failingPublish() = object : OutcomeSandboxFiles() {
        override fun publish(pending: File, committed: File) { throw IOException("Injected publish failure") }
    }
    @Test fun reopenAndCorrectionsPreserveLegacyBytesProvenanceAndIndependentQuestions() = withRoot { root ->
        val legacyFile = File(root, "legacy.json").also { it.writeText(legacy) }
        val original = legacyFile.readBytes()
        val store = ManualOutcomeSandboxStore(root)
        val seed = store.create(legacy)
        val a = store.append("fictional", seed.digest, legacy, result())
        val b = store.append("fictional", a.digest, legacy, result("typed2", 2, "q2"))
        val c = store.append("fictional", b.digest, legacy, result("typed3", 3, supersedes = "typed1"))
        val reopened = ManualOutcomeSandboxStore(root).inspect("fictional", legacy)
        assertEquals(c, reopened.committed); assertNull(reopened.pending)
        assertEquals(mapOf("q1" to "typed3", "q2" to "typed2"), c.contract.activeResultIds())
        assertEquals(legacy, c.contract.legacyJson)
        assertEquals(result().provenance, c.contract.outcomes.first().provenance)
        assertEquals(ManualScopeUse.SYNTHETIC_ONLY, c.contract.outcomes.first().scopeAssessment().use)
        assertContentEquals(original, legacyFile.readBytes())
        assertEquals(InvestigationCodec.decode(legacy), c.contract.legacySnapshot())
    }
    @Test fun unicodeAttributionRoundTripsOrFailsBeforeAnyLossyWrite() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        val valid = result().copy(provenance = "Fictional café 🧪 attribution")
        val saved = store.append("fictional", seed.digest, legacy, valid)
        assertEquals(valid, saved.contract.outcomes.single())
        val original = committed(root).readBytes()
        val invalid = result("typed2", 2, "q2").copy(provenance = "Fictional \uD800 attribution")
        // A codec may escape a surrogate losslessly; otherwise storage must reject before staging.
        val canEncode = ManualOutcomeContractCodec.encode(saved.contract.copy(outcomes = saved.contract.outcomes + invalid)).let {
            it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it
        }
        if (canEncode) assertEquals(invalid, store.append("fictional", saved.digest, legacy, invalid).contract.outcomes.last())
        else {
            assertFails { store.append("fictional", saved.digest, legacy, invalid) }
            assertContentEquals(original, committed(root).readBytes()); assertFalse(pending(root).exists())
        }
    }
    @Test fun staleAndRepeatedWritersCannotOverwriteOrDuplicateResults() = withRoot { root ->
        val first = ManualOutcomeSandboxStore(root); val second = ManualOutcomeSandboxStore(root)
        val seed = first.create(legacy)
        val next = first.append("fictional", seed.digest, legacy, result())
        val bytes = committed(root).readBytes()
        assertFails { second.append("fictional", seed.digest, legacy, result()) }
        assertFails { second.append("fictional", next.digest, legacy, result()) }
        assertFails { second.create(legacy) }
        assertContentEquals(bytes, committed(root).readBytes()); assertFalse(pending(root).exists())
    }
    @Test fun invalidReferenceOrCorrectionIsRejectedBeforeStaging() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        val bytes = committed(root).readBytes()
        for (bad in listOf(result().copy(context = result().context.copy(questionRevisionId = "missing")), result().copy(supersedesResultId = "missing"),
            result().copy(authorEffects = listOf(InvestigationEffect("h1", HypothesisEffect.SUPPORTS, "Unsupported claim"))))) {
            assertFails { store.append("fictional", seed.digest, legacy, bad) }
            assertContentEquals(bytes, committed(root).readBytes()); assertFalse(pending(root).exists())
        }
    }
    @Test fun anyLegacyDivergenceIncludingWhitespaceBlocksWithoutRebaseOrFallback() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        val bytes = committed(root).readBytes()
        for (changed in listOf(legacy.trim(), legacy.replace("Cold-start/-16%", "Changed reported story"), legacy.replace("fictional\"", "other\""))) {
            assertFails { store.inspect("fictional", changed) }
            assertFails { store.append("fictional", seed.digest, changed, result()) }
            assertContentEquals(bytes, committed(root).readBytes())
        }
        assertEquals(seed, store.inspect("fictional", legacy).committed)
    }
    @Test fun failedPublishPreservesBothCopiesAndExplicitRecoveryFinishesOnlyObservedAppend() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        val original = committed(root).readBytes()
        assertFailsWith<IOException> { ManualOutcomeSandboxStore(root, failingPublish()).append("fictional", seed.digest, legacy, result()) }
        assertContentEquals(original, committed(root).readBytes())
        val state = store.inspect("fictional", legacy)
        assertNotNull(state.pending); assertEquals(seed, state.committed)
        val staged = pending(root).readBytes()
        assertFails { store.append("fictional", seed.digest, legacy, result()) }
        assertFails { store.recover("fictional", "wrong", state.pending!!.digest!!, legacy) }
        assertFails { store.recover("fictional", seed.digest, "wrong", legacy) }
        assertContentEquals(staged, pending(root).readBytes())
        val recovered = store.recover("fictional", seed.digest, state.pending!!.digest!!, legacy)
        assertEquals(listOf(result()), recovered.contract.outcomes); assertFalse(pending(root).exists())
        assertEquals(recovered, ManualOutcomeSandboxStore(root).inspect("fictional", legacy).committed)
    }
    @Test fun partialStageRemainsVisibleAndBlocksRetryOrRecoveryWithoutErasure() = withRoot { root ->
        val seed = ManualOutcomeSandboxStore(root).create(legacy); val original = committed(root).readBytes()
        val io = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) { file.writeText("{partial"); throw IOException("Injected partial write") }
        }
        assertFailsWith<IOException> { ManualOutcomeSandboxStore(root, io).append("fictional", seed.digest, legacy, result()) }
        val store = ManualOutcomeSandboxStore(root); val state = store.inspect("fictional", legacy)
        assertEquals(8L, state.pending!!.sizeBytes)
        assertFails { store.recover("fictional", seed.digest, state.pending!!.digest!!, legacy) }
        assertFails { store.append("fictional", seed.digest, legacy, result()) }
        assertEquals("{partial", pending(root).readText()); assertContentEquals(original, committed(root).readBytes())
    }
    @Test fun recoveryResyncFailurePreservesPendingAndOldHistoryThenCanBeRetried() = withRoot { root ->
        val seed = ManualOutcomeSandboxStore(root).create(legacy)
        assertFails { ManualOutcomeSandboxStore(root, failingPublish()).append("fictional", seed.digest, legacy, result()) }
        val state = ManualOutcomeSandboxStore(root).inspect("fictional", legacy)
        val before = committed(root).readBytes(); val staged = pending(root).readBytes()
        val io = object : OutcomeSandboxFiles() { override fun sync(file: File) { throw IOException("Injected fsync failure") } }
        assertFailsWith<IOException> { ManualOutcomeSandboxStore(root, io).recover("fictional", seed.digest, state.pending!!.digest!!, legacy) }
        assertContentEquals(before, committed(root).readBytes()); assertContentEquals(staged, pending(root).readBytes())
        assertEquals(1, ManualOutcomeSandboxStore(root).recover("fictional", seed.digest, state.pending!!.digest!!, legacy).contract.outcomes.size)
    }
    @Test fun pendingOnlySeedRecoversAfterProcessRecreationWithoutInventingResults() = withRoot { root ->
        assertFailsWith<IOException> { ManualOutcomeSandboxStore(root, failingPublish()).create(legacy) }
        val store = ManualOutcomeSandboxStore(root); val state = store.inspect("fictional", legacy)
        assertNull(state.committed); assertNotNull(state.pending)
        assertFails { store.create(legacy) }
        val recovered = store.recover("fictional", null, state.pending!!.digest!!, legacy)
        assertTrue(recovered.contract.outcomes.isEmpty()); assertEquals(legacy, recovered.contract.legacyJson)
    }
    @Test fun lostPublicationAcknowledgmentDoesNotCauseDuplicateAppend() = withRoot { root ->
        val seed = ManualOutcomeSandboxStore(root).create(legacy)
        val io = object : OutcomeSandboxFiles() {
            override fun publish(pending: File, committed: File) { super.publish(pending, committed); throw IOException("Injected lost acknowledgment") }
        }
        assertFails { ManualOutcomeSandboxStore(root, io).append("fictional", seed.digest, legacy, result()) }
        val store = ManualOutcomeSandboxStore(root); val state = store.inspect("fictional", legacy)
        assertNull(state.pending); assertEquals(listOf(result()), state.committed!!.contract.outcomes)
        assertFails { store.append("fictional", seed.digest, legacy, result()) }
    }
    @Test fun recoveryCannotRollbackReplaceOrSkipPriorTypedTrail() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        val first = store.append("fictional", seed.digest, legacy, result())
        val second = store.append("fictional", first.digest, legacy, result("typed2", 2, "q2"))
        val original = committed(root).readBytes()
        val variants = listOf(seed.contract, first.contract,
            second.contract.copy(outcomes = listOf(result().copy(reason = "Rewritten prior attribution"), result("typed2", 2, "q2"))),
            second.contract.copy(outcomes = second.contract.outcomes + listOf(result("typed3", 3, supersedes = "typed1"), result("typed4", 4, "q2", "typed2"))))
        for (v in variants) {
            pending(root).writeText(ManualOutcomeContractCodec.encode(v)) // deliberate fictional interrupted/corrupt state
            val staged = pending(root).readBytes(); val state = store.inspect("fictional", legacy)
            assertFails { store.recover("fictional", second.digest, state.pending!!.digest!!, legacy) }
            assertContentEquals(original, committed(root).readBytes()); assertContentEquals(staged, pending(root).readBytes())
        }
        pending(root).writeBytes(original) // exact duplicate is safe, never a rollback
        val state = store.inspect("fictional", legacy)
        assertEquals(second, store.recover("fictional", second.digest, state.pending!!.digest!!, legacy))
    }
    @Test fun malformedFutureWrongIdAndInvalidUtf8ArePreservedWithoutFallback() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); store.create(legacy)
        val original = committed(root).readBytes()
        val variants = listOf("{broken".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            String(original).replace(ManualOutcomeContractCodec.SCHEMA, "openwrench.investigation-outcome-contract/2").toByteArray(),
            String(original).replace("fictional", "different").toByteArray())
        for (bytes in variants) {
            committed(root).writeBytes(bytes)
            assertFails { store.inspect("fictional", legacy) }
            assertFails { store.append("fictional", "anything", legacy, result()) }
            assertContentEquals(bytes, committed(root).readBytes())
        }
        committed(root).writeBytes(original)
        for (bytes in variants) {
            pending(root).writeBytes(bytes)
            val state = store.inspect("fictional", legacy)
            assertFails { store.recover("fictional", state.committed!!.digest, state.pending!!.digest!!, legacy) }
            assertContentEquals(bytes, pending(root).readBytes()); assertContentEquals(original, committed(root).readBytes())
        }
    }
    @Test fun oversizedFilesAreBoundedAndNeverTruncatedOrDeleted() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root); val seed = store.create(legacy)
        RandomAccessFile(pending(root), "rw").use { it.setLength(InvestigationCodec.MAX_BYTES + 1L) }
        val state = store.inspect("fictional", legacy)
        assertNull(state.pending!!.digest)
        assertFails { store.recover("fictional", seed.digest, "anything", legacy) }
        assertEquals(InvestigationCodec.MAX_BYTES + 1L, pending(root).length())
        RandomAccessFile(committed(root), "rw").use { it.setLength(InvestigationCodec.MAX_BYTES + 1L) }
        assertFails { store.inspect("fictional", legacy) }
        assertEquals(InvestigationCodec.MAX_BYTES + 1L, committed(root).length())
    }
    @Test fun quotaCountsPendingOnlySeedsAndNeverEvictsExistingFiles() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root)
        repeat(10) { n ->
            val changed = legacy.replace("\"id\":\"fictional\"", "\"id\":\"fictional$n\"")
            assertFailsWith<IOException> { ManualOutcomeSandboxStore(root, failingPublish()).create(changed) }
        }
        val files = File(root, ManualOutcomeSandboxStore.DIRECTORY).listFiles()!!
        val before = files.associate { it.name to it.readBytes().toList() }
        assertFails { store.create(legacy) }
        assertEquals(before, File(root, ManualOutcomeSandboxStore.DIRECTORY).listFiles()!!.associate { it.name to it.readBytes().toList() })
    }
    @Test fun inspectAndInvalidIdsNeverCreateDirectoriesAndUnknownEntriesBlockCreation() = withRoot { root ->
        val store = ManualOutcomeSandboxStore(root)
        assertEquals(OutcomeSandboxState(null, null), store.inspect("fictional", legacy))
        assertFalse(File(root, ManualOutcomeSandboxStore.DIRECTORY).exists())
        assertFails { store.inspect("../fictional", legacy) }
        assertFalse(File(root, ManualOutcomeSandboxStore.DIRECTORY).exists())
        val dir = File(root, ManualOutcomeSandboxStore.DIRECTORY).also { it.mkdir() }
        val unknown = File(dir, "foreign.data").also { it.writeText("retain me") }
        assertFails { store.create(legacy) }; assertEquals("retain me", unknown.readText())
        assertFalse(committed(root).exists()); assertFalse(pending(root).exists())
    }
}
