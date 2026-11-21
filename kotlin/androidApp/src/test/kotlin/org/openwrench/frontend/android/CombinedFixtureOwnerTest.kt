package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.openwrench.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CombinedFixtureOwnerTest {
    private fun root(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ow-combined-fictional").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun committed(root: File) = File(root, "${CombinedFixtureStore.DIRECTORY}/fixture.json")
    private fun pending(root: File) = File(root, "${CombinedFixtureStore.DIRECTORY}/fixture.pending")
    private fun create(store: CombinedFixtureStore) = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed()))).snapshot
    private fun ready(store: CombinedFixtureStore) = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Load)).snapshot
    private fun failedPublish() = object : OutcomeSandboxFiles() {
        override fun publish(pending: File, committed: File) { throw IOException("Injected before rename") }
    }
    @Test fun emptyLoadIsReadOnlyAndCreateRetryKeepsByteIdentity() = root { dir ->
        val ownerSentinel = File(dir, "owner-history").apply { writeText("Do not inspect or overwrite") }
        val store = CombinedFixtureStore(dir)
        assertEquals(CombinedState.Empty, store.execute(CombinedAction.Load))
        assertFalse(File(dir, CombinedFixtureStore.DIRECTORY).exists())
        val first = create(store); val bytes = committed(dir).readBytes()
        assertTrue(assertIs<CombinedState.Ready>(store.execute(CombinedAction.Create(first.document))).replayedRequest)
        assertContentEquals(bytes, committed(dir).readBytes())
        assertEquals(first, ready(CombinedFixtureStore(dir)))
        assertEquals("Do not inspect or overwrite", ownerSentinel.readText())
    }
    @Test fun fullCycleReopensWithoutPrefixRewriteAndRequestRetryIsIdempotentBeforeStaleCheck() = root { dir ->
        val store = CombinedFixtureStore(dir)
        var state = create(store)
        val first = state
        var action: CombinedAction.Append? = null
        while (state.document.events.size < 14) {
            val oldEvents = state.document.events
            action = CombinedInvestigationFixtures.nextCycleAction(state) as CombinedAction.Append
            val next = assertIs<CombinedState.Ready>(store.execute(action)).snapshot
            assertEquals(oldEvents, next.document.events.dropLast(1))
            state = next
        }
        val bytes = committed(dir).readBytes()
        assertEquals(CombinedInvestigationFixtures.completedCycle(), ready(CombinedFixtureStore(dir)).document)
        assertTrue(assertIs<CombinedState.Ready>(store.execute(action!!)).replayedRequest)
        assertTrue(assertIs<CombinedState.Ready>(store.execute(CombinedAction.Create(first.document))).replayedRequest)
        assertContentEquals(bytes, committed(dir).readBytes())
        val conflicting = action.copy(event = (action.event as CombinedEvent.Manual).let { it.copy(outcome = it.outcome.copy(reason = "Different request payload")) })
        assertIs<CombinedState.ValidationRejected>(store.execute(conflicting))
        assertContentEquals(bytes, committed(dir).readBytes())
    }
    @Test fun staleActionReturnsCurrentTokenAndPreservesTheSubmittedDraft() = root { dir ->
        val store = CombinedFixtureStore(dir); val before = create(store)
        val first = CombinedInvestigationFixtures.nextCycleAction(before) as CombinedAction.Append
        val current = assertIs<CombinedState.Ready>(store.execute(first)).snapshot
        val next = CombinedInvestigationFixtures.nextCycleAction(current) as CombinedAction.Append
        val stale = next.copy(expectedToken = before.token)
        val bytes = committed(dir).readBytes()
        val result = assertIs<CombinedState.Stale>(store.execute(stale))
        assertEquals(current, result.snapshot); assertEquals(stale, result.submitted)
        assertContentEquals(bytes, committed(dir).readBytes()); assertFalse(pending(dir).exists())
    }
    @Test fun invalidFiniteUnitContextAndOutcomeFieldsDoNotStageOrChangeBytes() = root { dir ->
        val store = CombinedFixtureStore(dir)
        val prefix = CombinedInvestigationFixtures.questionAPrefix()
        var s = create(store)
        for (event in prefix.events.drop(1)) s = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Append(s.token, event))).snapshot
        val result = CombinedInvestigationFixtures.cycleEvents()[9] as CombinedEvent.Manual
        val bytes = committed(dir).readBytes()
        val invalid = listOf(result.outcome.copy(values = listOf(AttributedManualValue(Double.NaN, ManualValueUnit.KPA))),
            result.outcome.copy(values = listOf(AttributedManualValue(2.0, ManualValueUnit.OTHER_REPORTED))),
            result.outcome.copy(context = result.outcome.context.copy(caseId = "missing-case")),
            result.outcome.copy(authorEffects = listOf(InvestigationEffect("vapor", HypothesisEffect.SUPPORTS, "Unknown cannot support"))))
        for (outcome in invalid) {
            val action = CombinedAction.Append(s.token, result.copy(outcome = outcome))
            val rejected = assertIs<CombinedState.ValidationRejected>(store.execute(action))
            assertEquals(action, rejected.submitted); assertEquals(s, rejected.snapshot)
            assertContentEquals(bytes, committed(dir).readBytes()); assertFalse(pending(dir).exists())
        }
    }
    @Test fun pendingAppendBlocksRetryAndExplicitRecoveryKeepsPriorUntilPublish() = root { dir ->
        val good = CombinedFixtureStore(dir); val old = create(good)
        val action = CombinedInvestigationFixtures.nextCycleAction(old)
        val bytes = committed(dir).readBytes()
        val state = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, failedPublish()).execute(action))
        assertEquals(PendingDisposition.RECOVERABLE, state.pending.disposition)
        assertContentEquals(bytes, committed(dir).readBytes())
        assertEquals(state, good.execute(action)); assertEquals(state, good.execute(CombinedAction.Load))
        assertIs<CombinedState.Stale>(good.execute(CombinedAction.Recover("wrong", state.pending.digest!!)))
        assertContentEquals(bytes, committed(dir).readBytes())
        val recovered = assertIs<CombinedState.Ready>(good.execute(CombinedAction.Recover(old.token, state.pending.digest!!)))
        assertEquals(2, recovered.snapshot.document.events.size); assertFalse(pending(dir).exists())
        assertTrue(assertIs<CombinedState.Ready>(good.execute(action)).replayedRequest)
    }
    @Test fun partialFutureAndDivergentPendingRemainBlockedAndPreserved() = root { dir ->
        val store = CombinedFixtureStore(dir); val old = create(store)
        val prior = committed(dir).readBytes()
        val diverged = CombinedInvestigationFixtures.partialBankSeed().let { d -> d.copy(events = listOf((d.events.first() as CombinedEvent.Ordinary).let { it.copy(revision = it.revision.copy(reason = "Divergent seed")) })) }
        for (text in listOf("{partial", CombinedInvestigationCodec.encode(old.document).replace(CombinedInvestigationCodec.SCHEMA, "future-format/9"), CombinedInvestigationCodec.encode(diverged))) {
            pending(dir).writeText(text)
            val pendingBytes = pending(dir).readBytes()
            val state = assertIs<CombinedState.RecoveryRequired>(store.execute(CombinedAction.Load))
            assertEquals(PendingDisposition.BLOCKED, state.pending.disposition)
            assertEquals(state, store.execute(CombinedAction.Recover(old.token, state.pending.digest!!)))
            assertContentEquals(prior, committed(dir).readBytes()); assertContentEquals(pendingBytes, pending(dir).readBytes())
            if ("future-format" in text) assertEquals("future-format/9", state.pending.unknownValue)
        }
    }
    @Test fun failedSeedRemainsRecoverableWithoutCreatingASecondDocument() = root { dir ->
        val state = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, failedPublish()).execute(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())))
        assertNull(state.snapshot); assertFalse(committed(dir).exists())
        assertEquals(PendingDisposition.RECOVERABLE, state.pending.disposition)
        val store = CombinedFixtureStore(dir)
        assertEquals(state, store.execute(CombinedAction.Recover(null, "wrong")))
        assertIs<CombinedState.Ready>(store.execute(CombinedAction.Recover(null, state.pending.digest!!)))
    }
    @Test fun stagePartialFailureAndSyncFailurePreserveBothFiles() = root { dir ->
        val old = create(CombinedFixtureStore(dir)); val prior = committed(dir).readBytes()
        val partialFiles = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) { file.writeText("{partial"); throw IOException("Injected partial stage") }
        }
        val result = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, partialFiles).execute(CombinedInvestigationFixtures.nextCycleAction(old)))
        assertEquals(PendingDisposition.BLOCKED, result.pending.disposition)
        assertContentEquals(prior, committed(dir).readBytes()); assertEquals("{partial", pending(dir).readText())
    }
    @Test fun recoveryRefsyncFailureRetainsValidatedPendingForAnotherExplicitAttempt() = root { dir ->
        val good = CombinedFixtureStore(dir); val old = create(good)
        val recovery = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, failedPublish()).execute(CombinedInvestigationFixtures.nextCycleAction(old)))
        val pendingBytes = pending(dir).readBytes(); val prior = committed(dir).readBytes()
        val files = object : OutcomeSandboxFiles() { override fun sync(file: File) { throw IOException("Injected sync failure") } }
        assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, files).execute(CombinedAction.Recover(old.token, recovery.pending.digest!!)))
        assertContentEquals(prior, committed(dir).readBytes()); assertContentEquals(pendingBytes, pending(dir).readBytes())
    }
    @Test fun lostAcknowledgmentIsUnknownThenSameRequestReplayFindsOneCommittedEvent() = root { dir ->
        val good = CombinedFixtureStore(dir); val old = create(good)
        val lost = object : OutcomeSandboxFiles() {
            override fun publish(pending: File, committed: File) { super.publish(pending, committed); throw IOException("Injected lost acknowledgment") }
        }
        val action = CombinedInvestigationFixtures.nextCycleAction(old)
        val result = assertIs<CombinedState.Unavailable>(CombinedFixtureStore(dir, lost).execute(action))
        assertEquals(CombinedUnavailableReason.WRITE_OUTCOME_UNKNOWN, result.reason)
        assertFalse(pending(dir).exists()); assertEquals(2, ready(good).document.events.size)
        assertTrue(assertIs<CombinedState.Ready>(good.execute(action)).replayedRequest)
        assertEquals(2, ready(good).document.events.size)
    }
    @Test fun futureCorruptAndInvalidUtf8AreUnavailableWithoutFallbackOrMutation() = root { dir ->
        val store = CombinedFixtureStore(dir); val old = create(store)
        val encoded = CombinedInvestigationCodec.encode(old.document)
        for (bytes in listOf(encoded.replace(CombinedInvestigationCodec.SCHEMA, "unknown/2").toByteArray(), "{corrupt".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            committed(dir).writeBytes(bytes)
            val result = assertIs<CombinedState.Unavailable>(store.execute(CombinedAction.Load))
            assertTrue(result.preserved)
            assertIs<CombinedState.Unavailable>(store.execute(CombinedAction.Create(old.document)))
            assertContentEquals(bytes, committed(dir).readBytes()); assertFalse(pending(dir).exists())
            if (bytes.toString(Charsets.UTF_8).contains("unknown/2")) assertEquals("unknown/2", result.unknownValue)
        }
    }
    @Test fun previewChecksByteTokenAndReturnsTypedPureResultWithoutMutation() = root { dir ->
        val store = CombinedFixtureStore(dir); val old = create(store); val prior = committed(dir).readBytes()
        assertIs<CombinedState.Stale>(store.execute(CombinedAction.Preview("stale", CombinedPreviewRequest(CombinedRecommendationPreview.revision(old.document), old.document.cases().single().id))))
        val content = CombinedRecommendationPreview.revision(old.document)
        val invalid = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Preview(old.token, CombinedPreviewRequest(content, "missing-case"))))
        assertIs<CombinedPreviewResult.Invalid>(invalid.preview!!.result)
        val result = assertIs<CombinedState.Ready>(store.execute(CombinedAction.Preview(old.token, CombinedPreviewRequest(content, old.document.cases().single().id))))
        assertIs<CombinedPreviewResult.Ready>(result.preview!!.result)
        assertContentEquals(prior, committed(dir).readBytes())
    }
    @Test fun coordinatorLoadingAdmissionDetachAndReloadUseOneOwner() = root { dir -> runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = CombinedFixtureStore(dir)
        val owner = CombinedFixtureCoordinator(store, this, dispatcher)
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Load))
        assertIs<CombinedState.Loading>(owner.state.value)
        assertEquals(CombinedAdmission.BUSY, owner.dispatch(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())))
        runCurrent(); assertEquals(CombinedState.Empty, owner.state.value)
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())))
        owner.detach()
        assertEquals(CombinedAdmission.BUSY, owner.dispatch(CombinedAction.Load))
        runCurrent(); assertIs<CombinedState.Loading>(owner.state.value) // detached callback never delivered
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Load))
        runCurrent(); assertIs<CombinedState.Ready>(owner.state.value)
    } }
    @Test fun cancelImmediatelyAfterAdmissionStillCommitsAndFreshOwnerReopens() = root { dir -> runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        val store = CombinedFixtureStore(dir)
        val owner = CombinedFixtureCoordinator(store, scope, dispatcher)
        assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())))
        job.cancel(); runCurrent()
        assertIs<CombinedState.Loading>(owner.state.value)
        assertEquals(CombinedAdmission.INACTIVE, owner.dispatch(CombinedAction.Load))
        val reopened = CombinedFixtureCoordinator(store, this, dispatcher)
        reopened.dispatch(CombinedAction.Load); runCurrent()
        assertEquals(1, assertIs<CombinedState.Ready>(reopened.state.value).snapshot.document.events.size)
    } }
    @Test fun blockedIoKeepsAdmissionOwnedAcrossDetachUntilPublishCompletes() = root { dir ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                super.stage(file, bytes)
            }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        try {
            val owner = CombinedFixtureCoordinator(CombinedFixtureStore(dir, files), scope, dispatcher)
            assertEquals(CombinedAdmission.ACCEPTED, owner.dispatch(CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            owner.detach()
            assertEquals(CombinedAdmission.BUSY, owner.dispatch(CombinedAction.Load))
            job.cancel() // durable admitted write must finish, delivery must remain canceled
            release.countDown()
            runBlocking { withTimeout(10000) { job.join() } }
            assertIs<CombinedState.Loading>(owner.state.value)
            assertEquals(1, ready(CombinedFixtureStore(dir)).document.events.size)
        } finally { release.countDown(); job.cancel(); dispatcher.close() }
    }
    @Test fun invalidTextAndGlobalLimitRejectBeforeStaging() = root { dir ->
        val store = CombinedFixtureStore(dir); val old = create(store); val prior = committed(dir).readBytes()
        val action = CombinedInvestigationFixtures.nextCycleAction(old) as CombinedAction.Append
        val ordinary = action.event as CombinedEvent.Ordinary
        assertIs<CombinedState.ValidationRejected>(store.execute(action.copy(event = ordinary.copy(revision = ordinary.revision.copy(reason = "Unpaired \uD800")))))
        assertContentEquals(prior, committed(dir).readBytes()); assertFalse(pending(dir).exists())
        assertFailsWith<CombinedValidation> { old.document.copy(events = List(501) { old.document.events.first() }).validate() }
    }

    @Test fun postPublishValidationExceptionsAreUnknownForCreateAppendAndRecovery() {
        val exceptions: List<() -> Exception> = listOf(
            { IllegalArgumentException("Injected acknowledgment with validation-shaped exception") },
            { CombinedValidation(CombinedFieldIssue("injected", CombinedIssueCode.INVALID_LOG, "Injected typed validation exception after publication")) })
        for (exception in exceptions) {
            fun lost() = object : OutcomeSandboxFiles() {
                override fun publish(pending: File, committed: File) { super.publish(pending, committed); throw exception() }
            }
            root { dir ->
                val action = CombinedAction.Create(CombinedInvestigationFixtures.partialBankSeed())
                val result = assertIs<CombinedState.Unavailable>(CombinedFixtureStore(dir, lost()).execute(action))
                assertEquals(CombinedUnavailableReason.WRITE_OUTCOME_UNKNOWN, result.reason)
                assertFalse(pending(dir).exists())
                val good = CombinedFixtureStore(dir)
                assertEquals(1, ready(good).document.events.size)
                assertTrue(assertIs<CombinedState.Ready>(good.execute(action)).replayedRequest)
            }
            root { dir ->
                val good = CombinedFixtureStore(dir); val old = create(good)
                val action = CombinedInvestigationFixtures.nextCycleAction(old)
                val result = assertIs<CombinedState.Unavailable>(CombinedFixtureStore(dir, lost()).execute(action))
                assertEquals(CombinedUnavailableReason.WRITE_OUTCOME_UNKNOWN, result.reason)
                assertEquals(old, result.snapshot); assertFalse(pending(dir).exists())
                assertEquals(2, ready(good).document.events.size)
                assertTrue(assertIs<CombinedState.Ready>(good.execute(action)).replayedRequest)
            }
            root { dir ->
                val good = CombinedFixtureStore(dir); val old = create(good)
                val original = CombinedInvestigationFixtures.nextCycleAction(old)
                val staged = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, failedPublish()).execute(original))
                val result = assertIs<CombinedState.Unavailable>(CombinedFixtureStore(dir, lost()).execute(CombinedAction.Recover(old.token, staged.pending.digest!!)))
                assertEquals(CombinedUnavailableReason.WRITE_OUTCOME_UNKNOWN, result.reason)
                assertFalse(pending(dir).exists()); assertEquals(2, ready(good).document.events.size)
                assertTrue(assertIs<CombinedState.Ready>(good.execute(original)).replayedRequest)
            }
        }
    }
    @Test fun validationShapedExceptionAfterStagingStillPreservesPendingRecovery() = root { dir ->
        val good = CombinedFixtureStore(dir); val old = create(good); val prior = committed(dir).readBytes()
        val files = object : OutcomeSandboxFiles() {
            override fun stage(file: File, bytes: ByteArray) {
                super.stage(file, bytes)
                throw CombinedValidation(CombinedFieldIssue("injected", CombinedIssueCode.INVALID_LOG, "Injected after stage"))
            }
        }
        val result = assertIs<CombinedState.RecoveryRequired>(CombinedFixtureStore(dir, files).execute(CombinedInvestigationFixtures.nextCycleAction(old)))
        assertEquals(PendingDisposition.RECOVERABLE, result.pending.disposition)
        assertContentEquals(prior, committed(dir).readBytes())
    }

}
