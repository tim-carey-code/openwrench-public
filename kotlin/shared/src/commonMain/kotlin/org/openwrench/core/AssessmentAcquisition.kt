package org.openwrench.core

import org.openwrench.nativecore.ObdDecoder

/** The only profile mode in H03B-2. Profiles and their numeric limits are synthetic test data. */
enum class AssessmentMode { SYNTHETIC_MIXED_READ_ONLY }

data class AssessmentProfileIdentity(val id: String, val version: String) {
    init { require(id.isNotBlank() && version.isNotBlank()) }
}

data class AssessmentInput(
    val requirement: InputRequirement,
    val contextKeys: Set<String> = emptySet(),
    /** A NO DATA receipt for any named signal also ends this input's current sampling run. */
    val invalidatedBy: Set<String> = emptySet(),
) {
    init { require(contextKeys.all(String::isNotBlank) && invalidatedBy.all(String::isNotBlank)) }
}

/** H03B-2 limits are intentionally small and synthetic; no active diagnostic profile is installed. */
data class AssessmentProfile(
    val identity: AssessmentProfileIdentity,
    val mode: AssessmentMode,
    val required: List<AssessmentInput>,
    val optional: List<AssessmentInput>,
    val totalBudgetMillis: Long,
    val commandCap: Int,
    val maximumCrossSignalGapMillis: Long,
) {
    val inputs: List<AssessmentInput> get() = required + optional

    init {
        require(required.isNotEmpty() && inputs.size <= 6)
        require(inputs.map { it.requirement.signalId }.distinct().size == inputs.size)
        require(inputs.all { it.requirement.minimumSamples in 1..2 && it.requirement.expectedPid != null })
        val allIds = inputs.map { it.requirement.signalId }.toSet()
        val requiredIds = required.map { it.requirement.signalId }.toSet()
        require(inputs.all { it.invalidatedBy.all(allIds::contains) })
        // A signal capable of resetting required evidence is itself a required prerequisite.
        require(required.all { it.invalidatedBy.all(requiredIds::contains) })
        require(totalBudgetMillis in 1..60_000 && commandCap in 1..24)
        require(maximumCrossSignalGapMillis in 0..10_000)
        require(inputs.all { input ->
            val pid = input.requirement.expectedPid!!.drop(2).toInt(16)
            val metadata = ObdDecoder.catalog[pid]
            val identity = metadata?.let { NativeSignalCatalog.definition(pid, SignalSupport.SUPPORTED) }
            identity != null && input.requirement.signalId == identity.id &&
                input.requirement.expectedUnit == identity.unit && input.requirement.expectedType == identity.type &&
                (pid == 0x03 || pid in ObdDecoder.scalarPids)
        })
    }
}

enum class AssessmentCollectionMode { FOCUSED, PASSIVE }

fun interface AssessmentClock { fun nowMillis(): Long }

enum class AcquisitionState { COLLECTING, EVIDENCE_READY, PARTIAL, DEFERRED, CANCELLED, FAILED }
enum class AcquisitionLimitReason { TIME_BUDGET, COMMAND_CAP }
enum class InputQualificationReason {
    MISSING, TOO_FEW_SAMPLES, WINDOW_TOO_SHORT, STALE, INCOMPATIBLE_COHORT,
    NO_DATA, IDENTITY_MISMATCH, CONTEXT_CHANGED,
    NOT_ADVERTISED, BUDGET_EXHAUSTED,
}

data class InputQualification(
    val signalId: String,
    val qualified: Boolean,
    val reasons: Set<InputQualificationReason>,
    val evidence: List<Observation>,
)

data class AcquisitionSnapshot(
    val profile: AssessmentProfileIdentity,
    val instanceId: String,
    val sessionGeneration: Long,
    val state: AcquisitionState,
    val attemptsDispatched: Int,
    val elapsedMillis: Long,
    val inputs: List<InputQualification>,
    /** Exact selected cohort, empty until all required inputs qualify. */
    val selectedEvidence: Map<String, List<Observation>>,
    val failure: String? = null,
    val limitReason: AcquisitionLimitReason? = null,
)

sealed interface AssessmentReadAction {
    val instanceId: String
    val sessionGeneration: Long
    val attempt: Int
    val signalId: String
    data class Scalar(
        override val instanceId: String, override val sessionGeneration: Long,
        override val attempt: Int, override val signalId: String, val pid: Int,
    ) : AssessmentReadAction
    data class FuelSystemStatus(
        override val instanceId: String, override val sessionGeneration: Long,
        override val attempt: Int, override val signalId: String,
    ) : AssessmentReadAction
}

/**
 * Synchronous state machine. Its owner executes one returned action on the existing serialized worker,
 * then calls [complete]. It has no transport, coroutine, timer, or autonomous retry.
 */
class AssessmentCollector(
    val profile: AssessmentProfile,
    val instanceId: String,
    val sessionGeneration: Long,
    val source: ObservationSource,
    advertisedPids: Set<Int>,
    initialContext: Map<String, String>,
    private val clock: AssessmentClock,
    val collectionMode: AssessmentCollectionMode = AssessmentCollectionMode.FOCUSED,
) {
    init { require(instanceId.isNotBlank() && sessionGeneration >= 0) }

    private val advertised = advertisedPids.toSet()
    private var context = initialContext.toMap()
    private val startedAt = clock.nowMillis().also { require(it >= 0) }
    val deadlineMonotonicMillis: Long = startedAt + profile.totalBudgetMillis
    private var state = AcquisitionState.COLLECTING
    private var attempts = 0
    private var pending: AssessmentReadAction? = null
    private var pendingContextInvalid = false
    private var failure: String? = null
    private var terminalAt: Long? = null
    private var limitReason: AcquisitionLimitReason? = null
    private val receipts = profile.inputs.associate { it.requirement.signalId to mutableListOf<Observation>() }
    private val markers = profile.inputs.associate { it.requirement.signalId to mutableSetOf<InputQualificationReason>() }
    private val acceptedAfter = profile.inputs.associate { it.requirement.signalId to null as Long? }.toMutableMap()
    private var chosen: Map<String, List<Observation>> = emptyMap()
    private var cursor = 0

    fun snapshot(): AcquisitionSnapshot {
        refresh()
        val now = terminalAt ?: clock.nowMillis()
        val qualifications = profile.inputs.map { input ->
            val id = input.requirement.signalId
            val selected = chosen[id]
            val local = if (selected == null) localQualification(input, now) else emptySet()
            val reasons = if (selected != null) emptySet() else
                (if (local.isEmpty()) setOf(InputQualificationReason.INCOMPATIBLE_COHORT) else local) +
                    markers.getValue(id) + (if (state == AcquisitionState.PARTIAL)
                        setOf(InputQualificationReason.BUDGET_EXHAUSTED) else emptySet())
            InputQualification(id, selected != null, reasons, selected ?: emptyList())
        }
        return AcquisitionSnapshot(profile.identity, instanceId, sessionGeneration, state, attempts,
            elapsed(now), qualifications, chosen, failure, limitReason)
    }

    /** Dispatch admission consumes one command slot. Null means no action is currently admissible. */
    fun nextAction(requiredAllowanceMillis: Long = 0): AssessmentReadAction? {
        require(requiredAllowanceMillis >= 0)
        if (collectionMode == AssessmentCollectionMode.PASSIVE) return null
        refresh()
        if (state != AcquisitionState.COLLECTING || pending != null) return null
        if (profile.required.any { !isAdvertised(it) }) {
            state = AcquisitionState.DEFERRED
            terminalAt = clock.nowMillis()
            return null
        }
        val now = clock.nowMillis()
        val needed = profile.required.filter { !isLocallyQualified(it, now) && isAdvertised(it) }
        val candidates = needed.ifEmpty { profile.required.filter(::isAdvertised) }
        if (candidates.isEmpty()) {
            state = AcquisitionState.DEFERRED
            terminalAt = now
            return null
        }
        val input = candidates[cursor % candidates.size]
        cursor++
        val id = input.requirement.signalId
        val pid = input.requirement.expectedPid!!.drop(2).toInt(16)
        val dispatchTime = clock.nowMillis()
        if (elapsed(dispatchTime) >= profile.totalBudgetMillis ||
            requiredAllowanceMillis >= profile.totalBudgetMillis - elapsed(dispatchTime)) {
            state = AcquisitionState.PARTIAL
            terminalAt = dispatchTime
            limitReason = AcquisitionLimitReason.TIME_BUDGET
            return null
        }
        attempts++
        return (if (pid == 0x03) AssessmentReadAction.FuelSystemStatus(instanceId, sessionGeneration, attempts, id)
            else AssessmentReadAction.Scalar(instanceId, sessionGeneration, attempts, id, pid)).also { pending = it }
    }

    /** False means a stale or foreign completion was ignored without publishing any progress. */
    fun complete(action: AssessmentReadAction, observation: Observation): Boolean {
        if (state != AcquisitionState.COLLECTING || pending != action ||
            action.instanceId != instanceId || action.sessionGeneration != sessionGeneration) return false
        pending = null
        if (pendingContextInvalid) {
            pendingContextInvalid = false
            refresh()
            return false
        }
        val now = clock.nowMillis()
        if (elapsed(now) >= profile.totalBudgetMillis) {
            state = AcquisitionState.PARTIAL
            terminalAt = now
            limitReason = AcquisitionLimitReason.TIME_BUDGET
            return false
        }
        return acceptReceipt(action.signalId, observation, now)
    }

    /** Both acquisition modes use exactly the same receipt validation, invalidations and selector. */
    private fun acceptReceipt(id: String, observation: Observation, now: Long): Boolean {
        if (elapsed(now) >= profile.totalBudgetMillis) {
            state = AcquisitionState.PARTIAL
            terminalAt = now
            limitReason = AcquisitionLimitReason.TIME_BUDGET
            return false
        }
        // A foreign receipt belongs to another acquisition domain. It cannot alter qualification.
        if (observation.sessionGeneration != sessionGeneration || observation.source != source) return false
        if (acceptedAfter.getValue(id)?.let { observation.receivedAtMonotonicMillis <= it } == true) return false
        val input = profile.inputs.first { it.requirement.signalId == id }
        if (!isAdvertised(input)) return false
        val requirement = input.requirement
        if (observation.definition.id != id || observation.definition.pid != requirement.expectedPid ||
            (requirement.expectedModule != null && observation.definition.module != requirement.expectedModule) ||
            (requirement.expectedUnit != null && observation.definition.unit != requirement.expectedUnit) ||
            (requirement.expectedType != null && observation.definition.type != requirement.expectedType) ||
            observation.receivedAtMonotonicMillis > now ||
            observation.receivedAtMonotonicMillis < startedAt ||
            observation.definition.support != SignalSupport.SUPPORTED) {
            invalidate(id, InputQualificationReason.IDENTITY_MISMATCH, now)
            return true
        }
        if (observation.availability != SignalAvailability.AVAILABLE) {
            if (observation.availability != SignalAvailability.UNAVAILABLE ||
                ObservationQualityReason.NO_DATA !in observation.qualityReasons) {
                state = AcquisitionState.FAILED
                terminalAt = now
                failure = "Non-NO DATA unavailable receipt for $id"
                return true
            }
            invalidateForNoData(id, now)
        } else {
            val window = receipts.getValue(id)
            if (window.any { it.receivedAtMonotonicMillis == observation.receivedAtMonotonicMillis ||
                    it.definition.type != observation.definition.type || it.definition.unit != observation.definition.unit ||
                    it.definition.pid != observation.definition.pid || it.definition.module != observation.definition.module }) {
                invalidate(id, InputQualificationReason.IDENTITY_MISMATCH, now)
            } else {
                window += observation
                window.sortBy { it.receivedAtMonotonicMillis }
                trimReceipts(id, now)
                markers.getValue(id).clear()
            }
        }
        refresh()
        return true
    }

    /** Accept an optional input already read by the session owner; this never dispatches a command. */
    fun offerExistingOptional(forInstanceId: String, observation: Observation): Boolean =
        offerExisting(forInstanceId, observation, profile.optional)

    /** Validated zero-command path; only the owner may supply completed dashboard receipts. */
    fun offerPassive(forInstanceId: String, observation: Observation): Boolean {
        if (collectionMode != AssessmentCollectionMode.PASSIVE) return false
        refresh()
        if (state != AcquisitionState.COLLECTING || forInstanceId != instanceId || pending != null ||
            profile.inputs.none { it.requirement.signalId == observation.definition.id }) return false
        return acceptReceipt(observation.definition.id, observation, clock.nowMillis())
    }

    private fun offerExisting(forInstanceId: String, observation: Observation, inputs: List<AssessmentInput>): Boolean {
        if (state != AcquisitionState.COLLECTING || forInstanceId != instanceId || pending != null) return false
        val input = inputs.firstOrNull { it.requirement.signalId == observation.definition.id } ?: return false
        val now = clock.nowMillis()
        if (elapsed(now) >= profile.totalBudgetMillis || observation.receivedAtMonotonicMillis !in startedAt..now ||
            observation.sessionGeneration != sessionGeneration || observation.source != source) return false
        val requirement = input.requirement
        if (acceptedAfter.getValue(requirement.signalId)?.let { observation.receivedAtMonotonicMillis <= it } == true)
            return false
        if (!isAdvertised(input) || observation.definition.support != SignalSupport.SUPPORTED ||
            observation.definition.pid != requirement.expectedPid ||
            observation.definition.unit != requirement.expectedUnit ||
            observation.definition.type != requirement.expectedType ||
            (requirement.expectedModule != null && observation.definition.module != requirement.expectedModule)) return false
        val window = receipts.getValue(requirement.signalId)
        if (observation.availability == SignalAvailability.UNAVAILABLE &&
            ObservationQualityReason.NO_DATA in observation.qualityReasons) {
            invalidateForNoData(requirement.signalId, now)
        } else if (observation.availability == SignalAvailability.AVAILABLE) {
            if (window.any { it.receivedAtMonotonicMillis == observation.receivedAtMonotonicMillis ||
                    it.definition.module != observation.definition.module }) return false
            window += observation
            window.sortBy { it.receivedAtMonotonicMillis }
            trimReceipts(requirement.signalId, now)
            markers.getValue(requirement.signalId).clear()
        } else return false
        refresh()
        return true
    }

    private fun trimReceipts(id: String, now: Long) {
        val window = receipts.getValue(id)
        if (collectionMode == AssessmentCollectionMode.PASSIVE) {
            val maximumAge = profile.inputs.first { it.requirement.signalId == id }.requirement.maximumAgeMillis
            window.removeAll { now - it.receivedAtMonotonicMillis > maximumAge }
            // A fast dashboard stream must not replace the entire evidence span while
            // waiting for a slower input's next sample. Keep the fresh endpoints and
            // remove the most crowded interior point, retaining the four-receipt bound.
            while (window.size > 4) {
                val redundant = (1 until window.lastIndex).minBy { index ->
                    window[index + 1].receivedAtMonotonicMillis - window[index - 1].receivedAtMonotonicMillis
                }
                window.removeAt(redundant)
            }
        } else while (window.size > 4) window.removeAt(0)
    }

    /** Owner rejected an admitted action at the final wire boundary; no exchange consumed its slot. */
    fun declineDispatchForTimeBudget(action: AssessmentReadAction): Boolean {
        if (state != AcquisitionState.COLLECTING || pending != action) return false
        pending = null
        attempts--
        state = AcquisitionState.PARTIAL
        terminalAt = clock.nowMillis()
        limitReason = AcquisitionLimitReason.TIME_BUDGET
        return true
    }

    /** A transport or protocol fault is terminal. The session owner handles resource cleanup. */
    fun fail(action: AssessmentReadAction, description: String): Boolean {
        if (state != AcquisitionState.COLLECTING || pending != action) return false
        return fail(description)
    }

    /** The owner may terminate passive collection on a session fault without inventing an action. */
    fun fail(description: String): Boolean {
        if (state != AcquisitionState.COLLECTING || description.isBlank()) return false
        pending = null
        state = AcquisitionState.FAILED
        terminalAt = clock.nowMillis()
        failure = description
        return true
    }

    fun cancel() {
        if (state == AcquisitionState.COLLECTING) {
            state = AcquisitionState.CANCELLED
            terminalAt = clock.nowMillis()
            pending = null
        }
    }

    /** Only inputs naming changed context keys lose their sampling windows. The original budget continues. */
    fun updateContext(newContext: Map<String, String>) {
        if (state != AcquisitionState.COLLECTING) return
        val changed = (context.keys + newContext.keys).filter { context[it] != newContext[it] }.toSet()
        context = newContext.toMap()
        val changedAt = clock.nowMillis()
        if (pending != null && profile.inputs.first { it.requirement.signalId == pending!!.signalId }
                .contextKeys.any(changed::contains)) pendingContextInvalid = true
        profile.inputs.filter { it.contextKeys.any(changed::contains) }.forEach {
            val id = it.requirement.signalId
            invalidate(id, InputQualificationReason.CONTEXT_CHANGED, changedAt)
        }
        refresh()
    }

    private fun refresh() {
        if (state != AcquisitionState.COLLECTING) return
        val now = clock.nowMillis()
        require(now >= startedAt)
        if (pending != null) return
        if (elapsed(now) >= profile.totalBudgetMillis) {
            state = AcquisitionState.PARTIAL
            terminalAt = now
            limitReason = AcquisitionLimitReason.TIME_BUDGET
            return
        }
        if (profile.required.any { !isAdvertised(it) }) {
            state = AcquisitionState.DEFERRED
            terminalAt = now
            return
        }
        chosen = selectCohort(profile.required.map { it.requirement }, receipts, now, profile.maximumCrossSignalGapMillis)
        if (chosen.isNotEmpty()) {
            // Optional evidence is included only when it forms the same complete repeated cohort.
            val included = profile.required.map { it.requirement }.toMutableList()
            profile.optional.sortedBy { it.requirement.signalId }.forEach { optional ->
                val withOptional = selectCohort(included + optional.requirement,
                    receipts, now, profile.maximumCrossSignalGapMillis)
                if (withOptional.isNotEmpty()) { chosen = withOptional; included += optional.requirement }
            }
            state = AcquisitionState.EVIDENCE_READY
            terminalAt = now
        } else if (attempts >= profile.commandCap) {
            state = AcquisitionState.PARTIAL
            terminalAt = now
            limitReason = AcquisitionLimitReason.COMMAND_CAP
        }
    }

    private fun isAdvertised(input: AssessmentInput) = input.requirement.expectedPid!!.drop(2).toInt(16) in advertised

    private fun invalidateForNoData(signalId: String, now: Long) {
        invalidate(signalId, InputQualificationReason.NO_DATA, now)
        profile.inputs.filter { signalId in it.invalidatedBy }.forEach {
            invalidate(it.requirement.signalId, InputQualificationReason.NO_DATA, now)
        }
    }

    private fun invalidate(signalId: String, reason: InputQualificationReason, now: Long) {
        receipts.getValue(signalId).clear()
        markers.getValue(signalId) += reason
        acceptedAfter[signalId] = maxOf(acceptedAfter.getValue(signalId) ?: 0L, now)
    }

    private fun isLocallyQualified(input: AssessmentInput, now: Long) =
        // Every required input supplies one receipt per group, even if its own minimum is smaller.
        localQualification(input, now, profile.required.maxOf { it.requirement.minimumSamples }).isEmpty()

    private fun localQualification(
        input: AssessmentInput, now: Long, minimumSamples: Int = input.requirement.minimumSamples,
    ): Set<InputQualificationReason> {
        val requirement = input.requirement
        if (!isAdvertised(input)) return setOf(InputQualificationReason.NOT_ADVERTISED)
        val samples = receipts.getValue(requirement.signalId).filter { it.receivedAtMonotonicMillis <= now &&
            now - it.receivedAtMonotonicMillis <= requirement.maximumAgeMillis }
        if (samples.isEmpty()) return setOf(if (receipts.getValue(requirement.signalId).isEmpty())
            InputQualificationReason.MISSING else InputQualificationReason.STALE)
        if (samples.size < minimumSamples) return setOf(InputQualificationReason.TOO_FEW_SAMPLES)
        if (samples.last().receivedAtMonotonicMillis - samples.first().receivedAtMonotonicMillis < requirement.minimumWindowMillis)
            return setOf(InputQualificationReason.WINDOW_TOO_SHORT)
        return emptySet()
    }

    private fun elapsed(now: Long): Long = (now - startedAt).also { require(it >= 0) }
}

/** Exact bounded search: every selected receipt belongs to one complete cross-signal group. */
internal fun selectCohort(
    requirements: List<InputRequirement>, receipts: Map<String, List<Observation>>,
    now: Long, gap: Long,
): Map<String, List<Observation>> {
    val ordered = requirements.sortedBy { it.signalId }
    val groups = ordered.maxOf { it.minimumSamples }
    val choices = ordered.map { requirement ->
        val fresh = receipts[requirement.signalId].orEmpty().filter {
            it.availability == SignalAvailability.AVAILABLE && it.receivedAtMonotonicMillis <= now &&
                now - it.receivedAtMonotonicMillis <= requirement.maximumAgeMillis &&
                it.definition.id == requirement.signalId && it.definition.pid == requirement.expectedPid &&
                it.definition.type == requirement.expectedType && it.definition.unit == requirement.expectedUnit &&
                it.definition.support == SignalSupport.SUPPORTED &&
                (requirement.expectedModule == null || it.definition.module == requirement.expectedModule)
        }.sortedBy { it.receivedAtMonotonicMillis }
        if (fresh.map { it.receivedAtMonotonicMillis }.distinct().size != fresh.size) return emptyMap()
        if (fresh.map { listOf(it.source, it.sessionGeneration, it.definition.module) }.distinct().size > 1)
            return emptyMap()
        if (groups == 1) fresh.map { listOf(it) } else fresh.indices.flatMap { first ->
            (first + 1 until fresh.size).mapNotNull { second ->
                listOf(fresh[first], fresh[second]).takeIf { pair ->
                    pair[1].receivedAtMonotonicMillis - pair[0].receivedAtMonotonicMillis >= requirement.minimumWindowMillis
                }
            }
        }
    }
    if (choices.any { it.isEmpty() }) return emptyMap()
    val selected = arrayOfNulls<List<Observation>>(ordered.size)
    fun search(index: Int): Boolean {
        if (index == ordered.size) return true
        for (candidate in choices[index]) {
            if (index > 0 && (candidate.any { it.source != selected[0]!![0].source ||
                    it.sessionGeneration != selected[0]!![0].sessionGeneration })) continue
            if ((0 until groups).any { round ->
                val times = (0 until index).map { selected[it]!![round].receivedAtMonotonicMillis } +
                    candidate[round].receivedAtMonotonicMillis
                times.max() - times.min() > gap
            }) continue
            selected[index] = candidate
            if (search(index + 1)) return true
        }
        return false
    }
    if (!search(0)) return emptyMap()
    return ordered.indices.associate { ordered[it].signalId to selected[it]!! }
}
