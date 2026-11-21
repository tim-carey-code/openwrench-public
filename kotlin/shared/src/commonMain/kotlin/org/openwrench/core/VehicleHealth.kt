package org.openwrench.core

/** A normalized receipt. [definition.unit] is the canonical unit; no display conversion is applied. */
data class Observation(
    val definition: SignalDefinition,
    val value: SignalValue?,
    val availability: SignalAvailability,
    val source: ObservationSource,
    val sessionGeneration: Long,
    val receivedAtMonotonicMillis: Long,
    /** Source-supplied timestamp, if any. It is never inferred from receipt time. */
    val sourceTimestamp: String? = null,
    val qualityReasons: Set<ObservationQualityReason> = emptySet(),
) {
    init {
        require(sessionGeneration >= 0 && receivedAtMonotonicMillis >= 0)
        require(sourceTimestamp == null || sourceTimestamp.isNotBlank())
        require((availability == SignalAvailability.AVAILABLE) == (value != null))
        require(value == null || definition.support == SignalSupport.SUPPORTED)
        require(value == null || value.matches(definition.type))
        require(availability != SignalAvailability.AVAILABLE || qualityReasons.isEmpty())
    }

    fun ageMillis(nowMonotonicMillis: Long): Long {
        require(nowMonotonicMillis >= receivedAtMonotonicMillis)
        return nowMonotonicMillis - receivedAtMonotonicMillis
    }
}

enum class ObservationSource { HARDWARE, SIMULATOR, REPLAY }
enum class ObservationQualityReason { NO_DATA, MALFORMED, READ_FAILURE, EXPIRED, SESSION_ENDED, SOURCE_UNVERIFIED }

private fun SignalValue.matches(type: SignalType): Boolean = when (type) {
    SignalType.INTEGER -> this is SignalValue.Integer
    SignalType.NUMBER -> this is SignalValue.Number
    SignalType.BOOLEAN -> this is SignalValue.Boolean
    SignalType.CATEGORY -> this is SignalValue.Category
    SignalType.TEXT -> this is SignalValue.Text
    SignalType.STRUCTURED -> this is SignalValue.Structured
    SignalType.TROUBLE_CODES -> this is SignalValue.TroubleCodes
    SignalType.UNKNOWN -> false
}

data class RuleIdentity(val id: String, val version: String) {
    init { require(id.isNotBlank() && version.isNotBlank()) }
}

/** Requirement bounds are rule metadata, never polling instructions. */
data class InputRequirement(
    val signalId: String,
    val minimumSamples: Int,
    val minimumWindowMillis: Long,
    val maximumAgeMillis: Long,
    /** Optional identity constraints. A module-specific rule must name its verified module. */
    val expectedPid: String? = null,
    val expectedModule: String? = null,
    val expectedUnit: String? = null,
    val expectedType: SignalType? = null,
) {
    init {
        require(signalId.isNotBlank() && minimumSamples > 0 && minimumWindowMillis >= 0 && maximumAgeMillis > 0)
        require(minimumWindowMillis == 0L || minimumSamples >= 2)
        require(expectedPid == null || Regex("01[0-9A-F]{2}").matches(expectedPid))
        require(expectedModule == null || expectedModule.isNotBlank())
        require(expectedUnit == null || expectedUnit.isNotBlank())
        require(expectedType != SignalType.UNKNOWN)
    }
}

data class EvidenceProvenance(val reference: String, val description: String) {
    init { require(reference.isNotBlank() && description.isNotBlank()) }
}

enum class HealthSeverity { INFORMATIONAL, ATTENTION, URGENT }
enum class EvidenceConfidence { LOW, MODERATE, HIGH }
enum class CoverageStatus { EVALUATED, INSUFFICIENT_DATA, NOT_APPLICABLE }
enum class InsufficientDataReason {
    DISCONNECTED, UNAPPROVED, UNSUPPORTED_CONDITION, MISSING, UNSUPPORTED_SIGNAL,
    UNKNOWN_SUPPORT, UNAVAILABLE, NOT_SAMPLED, INVALID, STALE, WRONG_SESSION, SOURCE_MISMATCH,
    TOO_FEW_SAMPLES, WINDOW_TOO_SHORT, IDENTITY_MISMATCH, INCOMPATIBLE_WINDOWS,
}

data class AssessmentCoverage(
    val rule: RuleIdentity,
    val status: CoverageStatus,
    val reasons: Set<InsufficientDataReason> = emptySet(),
    val provenance: EvidenceProvenance,
) {
    init { require((status == CoverageStatus.EVALUATED) == reasons.isEmpty()) }
}

data class HealthEvidence(
    val observation: Observation,
    val ageMillis: Long,
) {
    init { require(ageMillis >= 0) }
}

data class HealthFinding(
    val rule: RuleIdentity,
    val severity: HealthSeverity,
    val confidence: EvidenceConfidence,
    val explanation: String,
    val limitations: List<String>,
    /** Priority order is significant. */
    val nextChecks: List<String>,
    val evidence: List<HealthEvidence>,
) {
    init {
        require(explanation.isNotBlank() && evidence.isNotEmpty())
        require(limitations.all(String::isNotBlank) && nextChecks.all(String::isNotBlank))
    }
}

data class HealthAssessment(
    val packId: String,
    val packVersion: String,
    val sessionGeneration: Long,
    val coverage: List<AssessmentCoverage>,
    val findings: List<HealthFinding>,
    val packProvenance: EvidenceProvenance,
    val source: ObservationSource,
) {
    init {
        require(packId.isNotBlank() && packVersion.isNotBlank() && sessionGeneration >= 0)
        require(findings.all { finding -> coverage.any { it.rule == finding.rule && it.status == CoverageStatus.EVALUATED } })
    }
}

data class EvaluationContext(
    val sessionGeneration: Long,
    val nowMonotonicMillis: Long,
    val source: ObservationSource,
    val connected: Boolean,
    /** Supplied operating facts for pack/rule applicability; absent facts grant no approval. */
    val operatingContext: Map<String, String> = emptyMap(),
) {
    init { require(sessionGeneration >= 0 && nowMonotonicMillis >= 0) }
}

/** Only an explicit APPROVED result permits evaluation. */
enum class RuleApplicability { APPROVED, UNSUPPORTED, UNAPPROVED }

interface HealthRule {
    val identity: RuleIdentity
    val requirements: List<InputRequirement>
    val provenance: EvidenceProvenance
    /** Maximum span of one receipt from every required signal. Zero requires equal receipt times. */
    val maximumCrossSignalGapMillis: Long get() = 0
    fun applicability(context: EvaluationContext): RuleApplicability
    fun evaluate(evidence: Map<String, List<HealthEvidence>>): List<HealthFinding>
}

interface HealthRulePack {
    val id: String
    val version: String
    val provenance: EvidenceProvenance
    val rules: List<HealthRule>
    fun applicability(context: EvaluationContext): RuleApplicability
}

/** Pure gate for future rules. H01 supplies no rules, thresholds, or active diagnoses. */
object HealthEvaluator {
    fun evaluate(pack: HealthRulePack, context: EvaluationContext, observations: List<Observation>): HealthAssessment {
        require(pack.id.isNotBlank() && pack.version.isNotBlank())
        require(pack.rules.map { it.identity }.distinct().size == pack.rules.size)
        val coverage = mutableListOf<AssessmentCoverage>()
        val findings = mutableListOf<HealthFinding>()
        val packApproval = pack.applicability(context)
        for (rule in pack.rules.sortedWith(compareBy({ it.identity.id }, { it.identity.version }))) {
            require(rule.requirements.isNotEmpty() && rule.requirements.map { it.signalId }.distinct().size == rule.requirements.size)
            require(rule.maximumCrossSignalGapMillis >= 0)
            val approval = if (packApproval == RuleApplicability.APPROVED) rule.applicability(context) else packApproval
            val reasons = mutableSetOf<InsufficientDataReason>()
            if (approval != RuleApplicability.APPROVED) reasons += if (approval == RuleApplicability.UNAPPROVED)
                InsufficientDataReason.UNAPPROVED else InsufficientDataReason.UNSUPPORTED_CONDITION
            if (!context.connected && context.source == ObservationSource.HARDWARE) reasons += InsufficientDataReason.DISCONNECTED
            val inputs = linkedMapOf<String, List<HealthEvidence>>()
            if (reasons.isEmpty()) for (requirement in rule.requirements.sortedBy { it.signalId }) {
                val all = observations.filter { it.definition.id == requirement.signalId }
                if (all.isEmpty()) { reasons += InsufficientDataReason.MISSING; continue }
                if (all.any { it.sessionGeneration != context.sessionGeneration }) reasons += InsufficientDataReason.WRONG_SESSION
                if (all.any { it.source != context.source }) reasons += InsufficientDataReason.SOURCE_MISMATCH
                val sameSession = all.filter { it.sessionGeneration == context.sessionGeneration && it.source == context.source }
                if (sameSession.any { it.receivedAtMonotonicMillis > context.nowMonotonicMillis }) reasons += InsufficientDataReason.STALE
                val received = sameSession.filter { it.receivedAtMonotonicMillis <= context.nowMonotonicMillis }
                // A non-available receipt ends the prior sampling run. A later available receipt starts a new one.
                val lastBreak = received.filter { it.availability != SignalAvailability.AVAILABLE }
                    .maxOfOrNull { it.receivedAtMonotonicMillis }
                val active = received.filter { lastBreak == null || it.receivedAtMonotonicMillis >= lastBreak }
                val latest = active.maxOfOrNull { it.receivedAtMonotonicMillis }
                if (latest != null && active.count { it.receivedAtMonotonicMillis == latest } > 1) reasons += InsufficientDataReason.INVALID
                val terminal = active.filter { it.receivedAtMonotonicMillis == latest && it.availability != SignalAvailability.AVAILABLE }
                for (item in terminal) when (item.availability) {
                    SignalAvailability.UNAVAILABLE -> reasons += InsufficientDataReason.UNAVAILABLE
                    SignalAvailability.NOT_SAMPLED -> reasons += InsufficientDataReason.NOT_SAMPLED
                    SignalAvailability.DISCONNECTED -> reasons += InsufficientDataReason.DISCONNECTED
                    SignalAvailability.STALE -> reasons += InsufficientDataReason.STALE
                    SignalAvailability.ERROR -> reasons += InsufficientDataReason.INVALID
                    SignalAvailability.AVAILABLE -> Unit
                }
                if (terminal.any { ObservationQualityReason.MALFORMED in it.qualityReasons }) reasons += InsufficientDataReason.INVALID
                val run = active.filter { it.availability == SignalAvailability.AVAILABLE &&
                    (lastBreak == null || it.receivedAtMonotonicMillis > lastBreak) }
                val valid = run.filter { context.nowMonotonicMillis - it.receivedAtMonotonicMillis <= requirement.maximumAgeMillis }
                    .sortedBy { it.receivedAtMonotonicMillis }
                if (run.size > valid.size && valid.size < requirement.minimumSamples) reasons += InsufficientDataReason.STALE
                if (valid.any { it.definition.support == SignalSupport.UNSUPPORTED } || terminal.any { it.definition.support == SignalSupport.UNSUPPORTED })
                    reasons += InsufficientDataReason.UNSUPPORTED_SIGNAL
                if (valid.any { it.definition.support == SignalSupport.UNKNOWN } || terminal.any { it.definition.support == SignalSupport.UNKNOWN })
                    reasons += InsufficientDataReason.UNKNOWN_SUPPORT
                val identity = (valid + terminal).map { it.definition }
                // Preserve H01's INVALID contract for mixed physical identities in one window.
                if (identity.map { listOf(it.type, it.unit, it.pid, it.module) }.distinct().size > 1)
                    reasons += InsufficientDataReason.INVALID
                if (identity.any {
                        (requirement.expectedPid != null && it.pid != requirement.expectedPid) ||
                            (requirement.expectedModule != null && it.module != requirement.expectedModule) ||
                            (requirement.expectedUnit != null && it.unit != requirement.expectedUnit) ||
                            (requirement.expectedType != null && it.type != requirement.expectedType)
                    })
                    reasons += InsufficientDataReason.IDENTITY_MISMATCH
                if (valid.map { it.receivedAtMonotonicMillis }.distinct().size != valid.size) reasons += InsufficientDataReason.INVALID
                if (valid.size < requirement.minimumSamples) reasons += InsufficientDataReason.TOO_FEW_SAMPLES
                if (valid.size >= 2 && valid.last().receivedAtMonotonicMillis - valid.first().receivedAtMonotonicMillis < requirement.minimumWindowMillis)
                    reasons += InsufficientDataReason.WINDOW_TOO_SHORT
                inputs[requirement.signalId] = valid.map { HealthEvidence(it, it.ageMillis(context.nowMonotonicMillis)) }
            }
            if (reasons.isEmpty() && inputs.size > 1) {
                val receipts = inputs.flatMap { (signalId, evidence) -> evidence.map {
                    signalId to it.observation.receivedAtMonotonicMillis
                } }.sortedWith(compareBy({ it.second }, { it.first }))
                val counts = mutableMapOf<String, Int>()
                var start = 0
                var consistent = false
                for ((signalId, time) in receipts) {
                    counts[signalId] = (counts[signalId] ?: 0) + 1
                    while (time - receipts[start].second > rule.maximumCrossSignalGapMillis) {
                        val removed = receipts[start++].first
                        val remaining = counts.getValue(removed) - 1
                        if (remaining == 0) counts.remove(removed) else counts[removed] = remaining
                    }
                    if (counts.size == inputs.size) { consistent = true; break }
                }
                if (!consistent)
                    reasons += InsufficientDataReason.INCOMPATIBLE_WINDOWS
            }
            if (reasons.isNotEmpty()) {
                coverage += AssessmentCoverage(rule.identity, if (InsufficientDataReason.UNAPPROVED in reasons || InsufficientDataReason.UNSUPPORTED_CONDITION in reasons)
                    CoverageStatus.NOT_APPLICABLE else CoverageStatus.INSUFFICIENT_DATA,
                    reasons.sortedBy { it.ordinal }.toCollection(linkedSetOf()), rule.provenance)
                continue
            }
            val produced = rule.evaluate(inputs).map { finding ->
                finding.copy(evidence = finding.evidence.sortedWith(compareBy(
                    { it.observation.definition.id }, { it.observation.receivedAtMonotonicMillis })))
            }
            require(produced.all { it.rule == rule.identity && it.evidence.all { evidence -> inputs.values.flatten().contains(evidence) } })
            coverage += AssessmentCoverage(rule.identity, CoverageStatus.EVALUATED, provenance = rule.provenance)
            findings += produced
        }
        return HealthAssessment(pack.id, pack.version, context.sessionGeneration, coverage, findings, pack.provenance, context.source)
    }
}
