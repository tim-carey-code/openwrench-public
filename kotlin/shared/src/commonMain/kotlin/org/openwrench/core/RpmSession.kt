package org.openwrench.core

/** Phase A contract only. No transport, engine, queue, or hardware factory. */
interface RpmSession {
    val snapshot: RpmSessionSnapshot

    /** Explicit selection is fixed by the owner at construction; duplicate connect is Busy. */
    suspend fun connect(deadline: RpmDeadline): RpmSessionResult<RpmSessionIdentity>
    suspend fun readRpm(session: RpmSessionIdentity, deadline: RpmDeadline): RpmSessionResult<RpmObservation>

    /**
     * Close only this owner/generation. Validate atomically with admission, before effects:
     * a mismatch returns STALE_SESSION without changing state, cancelling work, or doing I/O.
     * A match preempts busy connect/read admission: accept CLOSING, reject new work, cancel
     * unpublished work, then wait for worker quiescence before bounded adapter cleanup.
     * Concurrent matching disconnects join one teardown; never overlap cleanup I/O.
     * After release, only the most recently admitted identity may disconnect idempotently;
     * an older identity stays stale even after the newer generation has been released.
     */
    suspend fun disconnect(session: RpmSessionIdentity, deadline: RpmDeadline): RpmSessionResult<Unit>
}

/**
 * Separate authority retained only by the owning module's lifecycle coordinator, never
 * exposed to regular RpmSession clients. No implementation or lifecycle wiring in Phase A.
 */
internal interface RpmSessionCoordinator {
    /** Invalidate any generation and perform bounded cleanup, including an unfinished connect. */
    suspend fun shutdown(deadline: RpmDeadline): RpmSessionResult<Unit>
}

/** One owner supplies a nondecreasing, process-local clock; never vehicle sampling time. */
fun interface RpmClock {
    fun nowMillis(): Long
}

data class RpmDeadline(val atMillis: Long) {
    init { require(atMillis >= 0) }
    fun expired(clock: RpmClock): Boolean = clock.nowMillis() >= atMillis
}

data class RpmSessionIdentity(val ownerId: String, val generation: Long) {
    init {
        require(ownerId.isNotBlank())
        require(generation > 0)
    }
}

enum class RpmConnectionState { DISCONNECTED, CONNECTING, DISCOVERING, READY, CLOSING, FAULTED }

enum class RpmSessionError {
    NOT_CONNECTED, STALE_SESSION, BUSY, UNSUPPORTED_RPM, TIMEOUT, CONNECTION_LOST,
    TRANSPORT_FAILURE, MALFORMED_RESPONSE, ADAPTER_REJECTED, UNSUPPORTED_ADAPTER,
    UNSUPPORTED_PROTOCOL, PERMISSION_DENIED, CANCELLED, CLEANUP_UNCONFIRMED,
}

sealed interface RpmSessionResult<out T> {
    data class Success<T>(val value: T) : RpmSessionResult<T>
    data class Failure(val error: RpmSessionError) : RpmSessionResult<Nothing>
}

/** Only the first standard bitmap is relevant. Continuation never authorizes another query. */
data class RpmCapabilities(val initialBitmap: UInt) {
    val rpmAdvertised: Boolean get() = initialBitmap and 0x00100000u != 0u
    val nextUnqueriedRange: Int? get() = if (initialBitmap and 1u != 0u) 0x20 else null
    val advertisedPids: List<Int> get() = (1..32).filter { initialBitmap and (1u shl (32 - it)) != 0u }
}

/** Completed-response time is host monotonic receipt time, scoped to this session. */
data class RpmObservation(
    val session: RpmSessionIdentity,
    val source: DiagnosticSource,
    val sequence: Long,
    val receivedAtMillis: Long,
    val rpm: Double,
) {
    init {
        require(sequence > 0)
        require(receivedAtMillis >= 0)
        require(rpm.isFinite() && rpm in 0.0..16_383.75)
    }
    val unit: MeasurementUnit get() = MeasurementUnit.RPM

    /** Disconnect/fault/another generation immediately makes an old observation unusable. */
    fun belongsTo(snapshot: RpmSessionSnapshot): Boolean =
        snapshot.state == RpmConnectionState.READY && snapshot.session == session &&
            snapshot.source == source && snapshot.capabilities?.rpmAdvertised == true
}

data class RpmSessionSnapshot(
    val source: DiagnosticSource,
    val state: RpmConnectionState = RpmConnectionState.DISCONNECTED,
    val session: RpmSessionIdentity? = null,
    val capabilities: RpmCapabilities? = null,
    val lastError: RpmSessionError? = null,
    /** Failed or still-running cleanup retains exclusive ownership even while faulted. */
    val releaseConfirmed: Boolean = true,
) {
    init {
        require(state != RpmConnectionState.READY || (session != null && capabilities != null))
        require(state == RpmConnectionState.READY || capabilities == null)
        require(state != RpmConnectionState.DISCONNECTED || (session == null && releaseConfirmed))
        require(state in setOf(RpmConnectionState.DISCONNECTED, RpmConnectionState.FAULTED) ||
            (session != null && !releaseConfirmed))
        require(state != RpmConnectionState.READY || lastError == null)
    }
}
