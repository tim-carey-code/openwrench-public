package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.frontend.nativeSignalDefinition
import org.openwrench.frontend.DASHBOARD_RECEIPT_STALE_MILLIS
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.NATIVE_STANDARD_READ_TIMEOUT_MILLIS
import org.openwrench.nativecore.ObdDecoder

/** A pure plan. [dashboard] is the user's selection; [acquisition] adds admitted scalar health inputs. */
data class HealthInputPlan(
    val dashboard: Map<Int, WatchRate>,
    /** Scalar reads, including the dashboard selection. */
    val acquisition: Map<Int, WatchRate>,
    val decisions: List<HealthInputDecision>,
    /** Health-only structured reads; currently only Mode 01 PID 03. */
    val structured: Map<Int, WatchRate> = emptyMap(),
)
data class HealthPlanUpdate(val plan: HealthInputPlan, val status: HealthPlanUpdateStatus)
enum class HealthPlanUpdateStatus { APPLIED, DEFERRED_DISCONNECTED, BUSY, RUNNING, CLEANUP_UNCERTAIN }

enum class HealthInputKind { SCALAR, STRUCTURED, UNKNOWN }
data class HealthInputDecision(
    val requirement: InputRequirement, val pid: Int?, val reason: HealthAdmission,
    val kind: HealthInputKind = HealthInputKind.UNKNOWN,
)
data class HealthInputFreshness(
    val pid: Int,
    val latestAvailability: SignalAvailability?,
    val receiptAgeMillis: Long?,
    val measuredCadenceMillis: Long?,
    val requestedIntervalMillis: Long,
)

/** Replay stays historical; its saved simulator origin is retained separately. */
data class HealthSourceContext(
    val source: ObservationSource,
    val sessionGeneration: Long,
    val replayOriginallySimulated: Boolean?,
)

fun NativeUiState.healthSourceContext() = HealthSourceContext(
    when (origin) {
        ReadingOrigin.HARDWARE -> ObservationSource.HARDWARE
        ReadingOrigin.SIMULATOR -> ObservationSource.SIMULATOR
        ReadingOrigin.REPLAY -> ObservationSource.REPLAY
    },
    sessionGeneration,
    if (origin == ReadingOrigin.REPLAY) replaySimulated else null,
)

/** Null age means no usable current-generation receipt or a clock mismatch. */
fun NativeUiState.healthInputFreshness(nowMonotonicMillis: Long): List<HealthInputFreshness> =
    (healthPlan.acquisition + healthPlan.structured).map { (pid, rate) ->
        val identity = if (pid == 0x03) "fuel-system-status" else nativeSignalDefinition(pid, SignalSupport.SUPPORTED).id
        val latest = healthObservations.lastOrNull { it.definition.id == identity &&
            it.sessionGeneration == sessionGeneration }
        HealthInputFreshness(pid, latest?.availability,
            latest?.receivedAtMonotonicMillis?.takeIf { it <= nowMonotonicMillis }?.let { nowMonotonicMillis - it },
            healthCadenceMillis[pid], rate.intervalMillis)
    }
enum class HealthAdmission {
    SHARED_WITH_DASHBOARD, SHARED_WITH_HEALTH, ADMITTED, UNKNOWN_SIGNAL,
    NOT_ALLOWLISTED, NOT_ADVERTISED, NO_CAPACITY, CADENCE_CONFLICT,
}

/** Inputs are ordered by the caller; eligible scalars use free scalar slots and PID 03 uses its separate path. */
object HealthInputPlanner {
    val MAX_SCALARS: Int = MAX_LIVE_SIGNALS
    // Two sequential bounded reads already exceed the dashboard freshness limit.
    // Measured fast responses cannot prove that a later read will finish quickly.
    const val MAX_READ_MILLIS = NATIVE_STANDARD_READ_TIMEOUT_MILLIS
    const val INTER_READ_MILLIS = 200L

    fun plan(
        dashboard: Map<Int, WatchRate>,
        requirements: List<InputRequirement>,
        advertised: Set<Int>,
        healthRate: WatchRate = WatchRate.NORMAL,
    ): HealthInputPlan {
        require(dashboard.size <= MAX_SCALARS)
        require(dashboard.keys.all { it in advertised && it in ObdDecoder.scalarPids })
        val acquisition = dashboard.toMutableMap()
        val structured = mutableMapOf<Int, WatchRate>()
        val decisions = requirements.map { requirement ->
            val pid = if (requirement.signalId == "fuel-system-status") 0x03
                else org.openwrench.frontend.nativeSignalIds.entries.firstOrNull { it.value == requirement.signalId }?.key
            val kind = when {
                pid == null -> HealthInputKind.UNKNOWN
                ObdDecoder.catalog[pid]?.structured == true -> HealthInputKind.STRUCTURED
                pid in ObdDecoder.scalarPids -> HealthInputKind.SCALAR
                else -> HealthInputKind.UNKNOWN
            }
            val reason = when {
                pid == null -> HealthAdmission.UNKNOWN_SIGNAL
                pid !in ObdDecoder.catalog -> HealthAdmission.NOT_ALLOWLISTED
                kind == HealthInputKind.STRUCTURED && pid != 0x03 -> HealthAdmission.NOT_ALLOWLISTED
                kind == HealthInputKind.UNKNOWN -> HealthAdmission.NOT_ALLOWLISTED
                pid !in advertised -> HealthAdmission.NOT_ADVERTISED
                pid in dashboard -> HealthAdmission.SHARED_WITH_DASHBOARD
                pid in acquisition -> HealthAdmission.SHARED_WITH_HEALTH
                pid in structured -> HealthAdmission.SHARED_WITH_HEALTH
                kind == HealthInputKind.SCALAR && acquisition.size >= MAX_SCALARS -> HealthAdmission.NO_CAPACITY
                dashboard.isNotEmpty() && 2L * MAX_READ_MILLIS + INTER_READ_MILLIS >= DASHBOARD_RECEIPT_STALE_MILLIS ->
                    HealthAdmission.CADENCE_CONFLICT
                // The same worst-case interval also prevents mixing structured and scalar health-only reads.
                // Preserve scalar-only H02A planning and defer the later mixed-type requirement.
                (kind == HealthInputKind.STRUCTURED && acquisition.isNotEmpty()) ||
                    (kind == HealthInputKind.SCALAR && structured.isNotEmpty()) -> HealthAdmission.CADENCE_CONFLICT
                else -> {
                    if (kind == HealthInputKind.STRUCTURED) structured[pid] = healthRate else acquisition[pid] = healthRate
                    HealthAdmission.ADMITTED
                }
            }
            HealthInputDecision(requirement, pid, reason, kind)
        }
        return HealthInputPlan(dashboard.toMap(), acquisition.toMap(), decisions, structured.toMap())
    }
}

/** Single conversion boundary. The native decoder already supplied the typed value and canonical unit. */
object HealthObservationBridge {
    fun normalize(
        reading: NativeReading,
        support: SignalSupport,
        source: ObservationSource,
        sessionGeneration: Long,
        receivedAtMonotonicMillis: Long,
        availability: SignalAvailability = if (reading.value == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
        module: String? = null,
        sourceTimestamp: String? = null,
        qualityReasons: Set<ObservationQualityReason> = if (reading.value == null) setOf(ObservationQualityReason.NO_DATA) else emptySet(),
    ): Observation {
        require(reading.pid in ObdDecoder.catalog)
        val metadata = nativeSignalDefinition(reading.pid, support).copy(module = module)
        // Saved snapshots can preserve a numeric type different from current live metadata.
        val definition = (reading.value as? DecodedReading.Scalar)?.let {
            metadata.copy(type = if (it.integer) SignalType.INTEGER else SignalType.NUMBER)
        } ?: metadata
        val value = reading.value?.let {
            when (it) {
                is DecodedReading.Scalar -> if (it.integer) SignalValue.Integer(it.value.toLong()) else SignalValue.Number(it.value)
                is DecodedReading.Structured -> SignalValue.Structured(it.fields)
            }
        }?.takeIf { availability == SignalAvailability.AVAILABLE }
        return Observation(definition, value, availability, source, sessionGeneration,
            receivedAtMonotonicMillis, sourceTimestamp, qualityReasons)
    }

    fun unavailable(
        pid: Int, support: SignalSupport, source: ObservationSource, sessionGeneration: Long,
        receivedAtMonotonicMillis: Long, availability: SignalAvailability,
        reason: ObservationQualityReason,
        module: String? = null,
    ): Observation = normalize(NativeReading(pid, null), support, source, sessionGeneration,
        receivedAtMonotonicMillis, availability, module, qualityReasons = setOf(reason))
}
