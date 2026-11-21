package org.openwrench.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RpmSessionTest {
    private val source = DiagnosticSource("test-reference", "synthetic-peer", SourceKind.SIMULATED)
    private val identity = RpmSessionIdentity("owner-a", 1)
    private val capabilities = RpmCapabilities(0x00100001u)

    private fun ready() = RpmSessionSnapshot(
        source, RpmConnectionState.READY, identity, capabilities, releaseConfirmed = false,
    )

    @Test
    fun firstBitmapGrantsOnlyAdvertisedRpm() {
        assertEquals(listOf(12, 32), capabilities.advertisedPids)
        assertEquals(32, capabilities.nextUnqueriedRange)
        assertTrue(capabilities.rpmAdvertised)
        assertFalse(RpmCapabilities(1u).rpmAdvertised)
        assertEquals(null, RpmCapabilities(0u).nextUnqueriedRange)
        // Another advertised PID cannot make RPM usable.
        assertFalse(RpmCapabilities(0x08000000u).rpmAdvertised)
    }

    @Test
    fun observationsCannotCrossOwnersSourcesGenerationsOrDisconnect() {
        val observation = RpmObservation(identity, source, 1, 100, 1726.0)
        assertEquals(MeasurementUnit.RPM, observation.unit)
        assertTrue(observation.belongsTo(ready()))
        assertFalse(observation.belongsTo(ready().copy(session = RpmSessionIdentity("owner-b", 1))))
        assertFalse(observation.belongsTo(ready().copy(session = identity.copy(generation = 2))))
        assertFalse(observation.belongsTo(ready().copy(source = source.copy(kind = SourceKind.HARDWARE))))
        assertFalse(observation.belongsTo(RpmSessionSnapshot(source)))
        assertFalse(observation.belongsTo(ready().copy(capabilities = RpmCapabilities(1u))))
        val fault = ready().copy(
            state = RpmConnectionState.FAULTED, capabilities = null,
            lastError = RpmSessionError.TIMEOUT,
        )
        assertFalse(observation.belongsTo(fault))
        assertFalse(fault.releaseConfirmed)
    }

    @Test
    fun fakeClockTestsDeadlineBoundaryWithoutSleeping() {
        var now = 99L
        val clock = RpmClock { now }
        val deadline = RpmDeadline(100)
        assertFalse(deadline.expired(clock))
        now = 100
        assertTrue(deadline.expired(clock))
        now = 101
        assertTrue(deadline.expired(clock))
        // Receipt time does not change when the clock advances or state is inspected.
        val observation = RpmObservation(identity, source, 1, now, 0.25)
        now = 500
        assertEquals(101L, observation.receivedAtMillis)
    }

    @Test
    fun invalidStateAndNumericModelsFailClosed() {
        assertFailsWith<IllegalArgumentException> { RpmSessionIdentity("", 1) }
        assertFailsWith<IllegalArgumentException> { RpmSessionIdentity("owner", 0) }
        assertFailsWith<IllegalArgumentException> { RpmDeadline(-1) }
        for (rpm in listOf(-0.25, 16384.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { RpmObservation(identity, source, 1, 0, rpm) }
        }
        assertFailsWith<IllegalArgumentException> { RpmObservation(identity, source, 0, 0, 0.0) }
        assertFailsWith<IllegalArgumentException> { RpmObservation(identity, source, 1, -1, 0.0) }
        assertFailsWith<IllegalArgumentException> {
            RpmSessionSnapshot(source, RpmConnectionState.READY)
        }
        assertFailsWith<IllegalArgumentException> {
            ready().copy(state = RpmConnectionState.FAULTED) // stale capability authority
        }
        assertFailsWith<IllegalArgumentException> { ready().copy(releaseConfirmed = true) }
        assertFailsWith<IllegalArgumentException> {
            RpmSessionSnapshot(source, releaseConfirmed = false)
        }
        assertFailsWith<IllegalArgumentException> { ready().copy(lastError = RpmSessionError.TIMEOUT) }
    }

    @Test
    fun lifecycleSnapshotsKeepCleanupOwnershipUntilRelease() {
        val connecting = RpmSessionSnapshot(
            source, RpmConnectionState.CONNECTING, identity, releaseConfirmed = false,
        )
        val discovering = connecting.copy(state = RpmConnectionState.DISCOVERING)
        val connected = discovering.copy(state = RpmConnectionState.READY, capabilities = capabilities)
        val closing = connected.copy(state = RpmConnectionState.CLOSING, capabilities = null)
        val faulted = closing.copy(
            state = RpmConnectionState.FAULTED, lastError = RpmSessionError.CLEANUP_UNCONFIRMED,
        )
        assertFalse(faulted.releaseConfirmed)
        val released = RpmSessionSnapshot(source)
        assertTrue(released.releaseConfirmed)
        val next = connecting.copy(session = identity.copy(generation = 2))
        assertEquals(2L, next.session?.generation)
        assertEquals(null, next.capabilities)
    }

    @Test
    fun staleDisconnectFromACannotAffectActiveB() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client: RpmSession = owner.client
        val deadline = RpmDeadline(100)
        val a = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(a, deadline))
        val b = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(a.ownerId, b.ownerId)
        assertEquals(a.generation + 1, b.generation)
        val before = client.snapshot
        val observation = assertIs<RpmSessionResult.Success<RpmObservation>>(client.readRpm(b, deadline)).value
        val cleanupCount = owner.cleanupCount

        repeat(2) {
            assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(a, deadline))
            assertEquals(before, client.snapshot)
            assertEquals(cleanupCount, owner.cleanupCount)
            assertTrue(observation.belongsTo(client.snapshot))
            assertIs<RpmSessionResult.Success<RpmObservation>>(client.readRpm(b, deadline))
        }
        // Positive control: B's own disconnect still closes B and invalidates its observation.
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(b, deadline))
        assertEquals(cleanupCount + 1, owner.cleanupCount)
        assertFalse(observation.belongsTo(client.snapshot))
    }

    @Test
    fun disconnectChecksOwnerAndRetainsOnlyLatestReleasedIdentity() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client = owner.client
        val deadline = RpmDeadline(100)
        assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(identity, deadline))
        val a = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        val before = client.snapshot
        for (stale in listOf(a.copy(ownerId = "foreign-owner"), a.copy(generation = a.generation + 1))) {
            assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(stale, deadline))
            assertEquals(before, client.snapshot)
            assertEquals(0, owner.cleanupCount)
        }
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(a, deadline))
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(a, deadline))
        assertEquals(1, owner.cleanupCount)
        val b = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(b, deadline))
        val released = client.snapshot
        assertEquals(null, released.session)
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(b, deadline))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(a, deadline))
        assertEquals(released, client.snapshot)
        assertEquals(2, owner.cleanupCount)
    }

    @Test
    fun unconditionalShutdownIsSeparateFromNormalClientAuthority() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client: RpmSession = owner.client
        assertFalse(client is RpmSessionCoordinator)
        val deadline = RpmDeadline(100)
        val session = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        val observation = assertIs<RpmSessionResult.Success<RpmObservation>>(client.readRpm(session, deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), owner.coordinator.shutdown(deadline))
        assertEquals(RpmSessionSnapshot(source), client.snapshot)
        assertFalse(observation.belongsTo(client.snapshot))
        assertEquals(1, owner.cleanupCount)
        assertEquals(RpmSessionResult.Success(Unit), owner.coordinator.shutdown(deadline))
        assertEquals(1, owner.cleanupCount)
    }

    @Test
    fun busyReadCannotBlockMatchingDisconnectAndLateResultIsCancelled() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client = owner.client
        val deadline = RpmDeadline(100)
        val session = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        val observation = assertIs<RpmSessionResult.Success<RpmObservation>>(client.readRpm(session, deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), owner.beginRead(session))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.BUSY), client.readRpm(session, deadline))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.BUSY), client.connect(deadline))

        // The admitted read wins the first race; disconnect still crosses the busy slot.
        val firstDisconnect = startReference { client.disconnect(session, deadline) }
        assertEquals(null, firstDisconnect())
        assertEquals(RpmConnectionState.CLOSING, client.snapshot.state)
        assertFalse(observation.belongsTo(client.snapshot))
        assertEquals(1, owner.cancelCount)
        assertEquals(0, owner.cleanupCount) // close cannot overlap the active worker
        assertEquals(RpmSessionResult.Failure(RpmSessionError.NOT_CONNECTED), client.readRpm(session, deadline))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.BUSY), client.connect(deadline))
        val repeatedDisconnect = startReference { client.disconnect(session, deadline) }
        assertEquals(null, repeatedDisconnect())
        assertEquals(1, owner.cancelCount)
        assertEquals(0, owner.cleanupCount)

        assertEquals(RpmSessionResult.Failure(RpmSessionError.CANCELLED), owner.completeRead())
        assertEquals(RpmSessionResult.Success(Unit), firstDisconnect()?.getOrThrow())
        assertEquals(RpmSessionResult.Success(Unit), repeatedDisconnect()?.getOrThrow())
        assertEquals(RpmSessionSnapshot(source), client.snapshot)
        assertEquals(1, owner.cleanupCount)
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(session, deadline))
        assertEquals(1, owner.cleanupCount)
    }

    @Test
    fun staleDisconnectHasPriorityDuringBusyReadAndClosing() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client = owner.client
        val deadline = RpmDeadline(100)
        val a = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(a, deadline))
        val b = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), owner.beginRead(b))
        val before = client.snapshot
        for (stale in listOf(a, b.copy(ownerId = "foreign-owner"))) {
            assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(stale, deadline))
            assertEquals(before, client.snapshot)
        }
        assertEquals(1, owner.cleanupCount)
        assertEquals(0, owner.cancelCount)
        assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.readRpm(a, deadline))
        val disconnect = startReference { client.disconnect(b, deadline) }
        assertEquals(null, disconnect())
        assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(a, deadline))
        assertEquals(RpmConnectionState.CLOSING, client.snapshot.state)
        assertEquals(RpmSessionResult.Failure(RpmSessionError.CANCELLED), owner.completeRead())
        assertEquals(RpmSessionResult.Success(Unit), disconnect()?.getOrThrow())
        assertEquals(2, owner.cleanupCount)
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(b, deadline))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.STALE_SESSION), client.disconnect(a, deadline))
    }

    @Test
    fun disconnectBeforeReadAdmissionRejectsReadWithoutBusyOrIo() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client = owner.client
        val deadline = RpmDeadline(100)
        val session = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), client.disconnect(session, deadline))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.NOT_CONNECTED), owner.beginRead(session))
        assertEquals(RpmSessionResult.Failure(RpmSessionError.NOT_CONNECTED), client.readRpm(session, deadline))
        assertEquals(1, owner.cleanupCount)
        assertEquals(0, owner.cancelCount)
    }

    @Test
    fun coordinatorShutdownUsesSameBarrierWhileReadIsBusy() = referenceCall {
        val owner = DisconnectReferenceOwner()
        val client = owner.client
        val deadline = RpmDeadline(100)
        val session = assertIs<RpmSessionResult.Success<RpmSessionIdentity>>(client.connect(deadline)).value
        assertEquals(RpmSessionResult.Success(Unit), owner.beginRead(session))
        val shutdown = startReference { owner.coordinator.shutdown(deadline) }
        assertEquals(null, shutdown())
        assertEquals(RpmConnectionState.CLOSING, client.snapshot.state)
        val disconnect = startReference { client.disconnect(session, deadline) }
        assertEquals(null, disconnect())
        assertEquals(1, owner.cancelCount)
        assertEquals(0, owner.cleanupCount)
        assertEquals(RpmSessionResult.Failure(RpmSessionError.CANCELLED), owner.completeRead())
        assertEquals(RpmSessionResult.Success(Unit), shutdown()?.getOrThrow())
        assertEquals(RpmSessionResult.Success(Unit), disconnect()?.getOrThrow())
        assertEquals(1, owner.cleanupCount)
        assertEquals(RpmSessionResult.Success(Unit), owner.coordinator.shutdown(deadline))
    }

    /** Synchronous contract example only: no engine, transport, scheduling, or lifecycle wiring. */
    private inner class DisconnectReferenceOwner {
        private var current = RpmSessionSnapshot(source)
        private var latest: RpmSessionIdentity? = null
        private var sequence = 0L
        private var readPending = false
        private val teardownWaiters = mutableListOf<Continuation<RpmSessionResult<Unit>>>()
        var cancelCount = 0
            private set
        var cleanupCount = 0
            private set

        /** Simulate a read admitted before its adapter worker has completed. */
        fun beginRead(session: RpmSessionIdentity): RpmSessionResult<Unit> {
            if (session != latest) return RpmSessionResult.Failure(RpmSessionError.STALE_SESSION)
            if (current.state != RpmConnectionState.READY) return RpmSessionResult.Failure(RpmSessionError.NOT_CONNECTED)
            if (readPending) return RpmSessionResult.Failure(RpmSessionError.BUSY)
            readPending = true
            return RpmSessionResult.Success(Unit)
        }

        /** Complete the scripted worker after a competing disconnect intent. */
        fun completeRead(): RpmSessionResult<RpmObservation> {
            check(readPending)
            readPending = false
            if (current.state == RpmConnectionState.CLOSING) {
                release()
                teardownWaiters.forEach { it.resumeWith(Result.success(RpmSessionResult.Success(Unit))) }
                teardownWaiters.clear()
                return RpmSessionResult.Failure(RpmSessionError.CANCELLED)
            }
            return RpmSessionResult.Success(RpmObservation(checkNotNull(latest), source, ++sequence, 0, 1726.0))
        }

        val client: RpmSession = object : RpmSession {
            override val snapshot: RpmSessionSnapshot get() = current

            override suspend fun connect(deadline: RpmDeadline): RpmSessionResult<RpmSessionIdentity> {
                if (current.state != RpmConnectionState.DISCONNECTED) return RpmSessionResult.Failure(RpmSessionError.BUSY)
                val next = RpmSessionIdentity(identity.ownerId, (latest?.generation ?: 0) + 1)
                latest = next
                sequence = 0
                current = RpmSessionSnapshot(
                    source, RpmConnectionState.READY, next, capabilities, releaseConfirmed = false,
                )
                return RpmSessionResult.Success(next)
            }

            override suspend fun readRpm(session: RpmSessionIdentity, deadline: RpmDeadline): RpmSessionResult<RpmObservation> {
                if (session != latest) return RpmSessionResult.Failure(RpmSessionError.STALE_SESSION)
                if (current.state != RpmConnectionState.READY) return RpmSessionResult.Failure(RpmSessionError.NOT_CONNECTED)
                if (readPending) return RpmSessionResult.Failure(RpmSessionError.BUSY)
                return RpmSessionResult.Success(RpmObservation(session, source, ++sequence, 0, 1726.0))
            }

            override suspend fun disconnect(session: RpmSessionIdentity, deadline: RpmDeadline): RpmSessionResult<Unit> {
                if (session != latest) return RpmSessionResult.Failure(RpmSessionError.STALE_SESSION)
                if (current.state == RpmConnectionState.CLOSING) {
                    return awaitPendingRead()
                }
                if (readPending) return awaitPendingRead()
                return release()
            }
        }

        val coordinator: RpmSessionCoordinator = object : RpmSessionCoordinator {
            override suspend fun shutdown(deadline: RpmDeadline): RpmSessionResult<Unit> =
                if (current.state == RpmConnectionState.CLOSING) awaitPendingRead()
                else if (readPending) awaitPendingRead()
                else release()
        }

        /** Deterministic stand-in for one bounded teardown awaiting worker quiescence. */
        private suspend fun awaitPendingRead(): RpmSessionResult<Unit> {
            if (current.state != RpmConnectionState.CLOSING) {
                current = current.copy(state = RpmConnectionState.CLOSING, capabilities = null)
                cancelCount++
            }
            return suspendCoroutine { teardownWaiters += it }
        }

        private fun release(): RpmSessionResult<Unit> {
            if (!current.releaseConfirmed) cleanupCount++
            current = RpmSessionSnapshot(source)
            return RpmSessionResult.Success(Unit)
        }
    }

    /** These reference operations complete immediately; suspension would fail this helper. */
    private fun referenceCall(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        checkNotNull(outcome) { "Reference unexpectedly suspended" }.getOrThrow()
    }

    private fun <T> startReference(block: suspend () -> T): () -> Result<T>? {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return { outcome }
    }
}
