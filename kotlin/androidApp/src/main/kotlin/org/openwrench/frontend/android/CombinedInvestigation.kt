package org.openwrench.frontend.android

import org.openwrench.core.*
import org.openwrench.nativecore.ScanOrigin

/** Immutable event payloads; fixture and retained-candidate envelopes validate separate authorities. */
internal sealed interface CombinedEvent {
    val requestId: String
    val id: String
    val sequence: Int
    data class Ordinary(override val requestId: String, val revision: InvestigationRevision) : CombinedEvent {
        override val id get() = revision.id
        override val sequence get() = revision.sequence
    }
    data class Manual(override val requestId: String, val outcome: AttributedManualOutcome) : CombinedEvent {
        override val id get() = outcome.id
        override val sequence get() = outcome.sequence
    }
    data class Attachment(override val requestId: String, override val id: String, override val sequence: Int,
        val recordedAtEpochMillis: Long, val author: String, val reason: String, val retainedCase: DiagnosticCase,
        val vehicleAssertion: FixtureVehicleAssertion, val provenance: String) : CombinedEvent
}
internal enum class InvestigationVehicleAssertion { OWNER_REPORTED_SAME, OWNER_REPORTED_DIFFERENT, UNKNOWN }
internal typealias FixtureVehicleAssertion = InvestigationVehicleAssertion
internal enum class CombinedIssueCode { INVALID_LOG, INVALID_REFERENCE_OR_SCOPE, NONFINITE_VALUE, FICTIONAL_ONLY }
internal data class CombinedFieldIssue(val field: String, val code: CombinedIssueCode, val message: String)
internal class CombinedValidation(val issue: CombinedFieldIssue) : IllegalArgumentException(issue.message)

internal interface InvestigationRevisionLog {
    val id: String
    val events: List<CombinedEvent>
    fun cases(): List<DiagnosticCase>
    fun validate()
    fun activeResultIds(): Map<String, String>
}
internal data class CombinedInvestigation(override val id: String, val initialCases: List<DiagnosticCase>, override val events: List<CombinedEvent>) : InvestigationRevisionLog {
    fun append(event: CombinedEvent) = copy(events = events + event).also { it.validate() }
    override fun cases(): List<DiagnosticCase> = initialCases + events.filterIsInstance<CombinedEvent.Attachment>().map { it.retainedCase }
    override fun activeResultIds(): Map<String, String> = InvestigationLogValidator.check(id, initialCases, events, fixtureOnly = true)
    override fun validate() { activeResultIds() }

}

/** Shared reference/receipt rules; origin admission remains explicit in each envelope. */
internal object InvestigationLogValidator {
    fun check(id: String, initialCases: List<DiagnosticCase>, events: List<CombinedEvent>, fixtureOnly: Boolean): Map<String, String> {
        fun reject(field: String, code: CombinedIssueCode, message: String): Nothing = throw CombinedValidation(CombinedFieldIssue(field, code, message))
        try {
            DiagnosticInvestigation.validId(id)
            require(initialCases.size <= 10 && (!fixtureOnly || initialCases.isNotEmpty()))
            require(events.size in 1..DiagnosticInvestigation.MAX_REVISIONS)
            require((events.first() as? CombinedEvent.Ordinary)?.revision?.step is InvestigationStep.Complaint)
            fun fixture(c: DiagnosticCase) {
                if (fixtureOnly && (c.scan.capture?.origin != ScanOrigin.SYNTHETIC || c.drive.origin != DriveEvidenceOrigin.SYNTHETIC))
                    reject("cases.${c.id}.origin", CombinedIssueCode.FICTIONAL_ONLY, "This adapter accepts explicitly synthetic case copies only.")
                require(c.scan.vin == null) { "Retained case copies must omit VIN" }
                DiagnosticCaseCodec.encode(listOf(c))
            }
            initialCases.forEach(::fixture)
            val cases = initialCases.toMutableList()
            require(cases.map { it.id }.distinct().size == cases.size)
            val ordinary = mutableListOf<InvestigationRevision>()
            val manual = mutableListOf<AttributedManualOutcome>()
            val ids = mutableSetOf<String>()
            val requests = mutableSetOf<String>()
            val active = mutableMapOf<String, String>()
            events.forEachIndexed { index, event ->
                DiagnosticInvestigation.validId(event.requestId)
                DiagnosticInvestigation.validId(event.id)
                require(requests.add(event.requestId) && ids.add(event.id) && event.sequence == index + 1) { "IDs, requests and ordered sequence must be unique" }
                when (event) {
                    is CombinedEvent.Attachment -> {
                        require(event.recordedAtEpochMillis > 0)
                        listOf(event.author, event.reason, event.provenance).forEach { require(it.isNotBlank() && it.length <= 4000 && '\u0000' !in it) }
                        require(cases.size < 10 && cases.none { it.id == event.retainedCase.id }) { "Cannot replace a frozen case copy" }
                        fixture(event.retainedCase)
                        cases += event.retainedCase
                    }
                    is CombinedEvent.Ordinary -> {
                        val result = event.revision.step as? InvestigationStep.TestResult
                        if (result != null) {
                            require(manual.none { it.id == active[result.testId] }) { "Correct a typed outcome with another typed outcome, never a legacy conversion" }
                            require(result.supersedesResultId == active[result.testId])
                        }
                        ordinary += event.revision
                        // Validation-only projection. Original global sequences are NEVER rewritten or persisted.
                        if (cases.isEmpty()) {
                            require((event.revision.step as? InvestigationStep.Observation)?.evidence == null) { "Attach the retained case before referencing measured evidence" }
                            DiagnosticInvestigation(id, ordinary.mapIndexed { i, r -> r.copy(sequence = i + 1) }).validate()
                        } else legacy(id, ordinary, cases).validate()
                        if (result != null) active[result.testId] = event.id
                    }
                    is CombinedEvent.Manual -> {
                        fun field(path: String, message: String, validate: () -> Unit) {
                            try { validate() } catch (error: Exception) { reject(path, CombinedIssueCode.INVALID_REFERENCE_OR_SCOPE, message) }
                        }
                        val outcome = event.outcome
                        outcome.values.forEachIndexed { valueIndex, value ->
                            if (!value.value.isFinite()) reject("outcome.values[$valueIndex].value", CombinedIssueCode.NONFINITE_VALUE,
                                "Enter a finite manual value or leave it unknown. The saved investigation was not changed.")
                            field("outcome.values[$valueIndex].reportedUnit",
                                "Provide a nonblank unit label only for OTHER_REPORTED; omit the label for standard or unknown units.") { value.validate() }
                        }
                        listOf("startingReport" to outcome.context.startingReport, "timingReport" to outcome.context.timingReport,
                            "adaptationReport" to outcome.context.adaptationReport).forEach { (name, selection) ->
                            field("outcome.context.$name", "Select an exact report reference or leave the selection unresolved.") { selection.validate() }
                        }
                        listOf("observer" to outcome.observer, "methodDetail" to outcome.methodDetail, "conditions" to outcome.conditions,
                            "criteriaReference" to outcome.criteriaReference).forEach { (name, detail) ->
                            field("outcome.$name", "Supply a nonblank reported detail or explicitly leave it unknown.") { detail.validate() }
                        }
                        field("outcome.context", "Check context IDs, unique same-case references and supported catalog scope.") { outcome.context.validate() }
                        field("outcome", "Check outcome metadata and effects; unknown/inconclusive outcomes cannot support or weaken hypotheses.") { outcome.validate() }
                        if (fixtureOnly && event.outcome.origin != ManualOutcomeOrigin.SYNTHETIC)
                            reject("outcome.origin", CombinedIssueCode.FICTIONAL_ONLY, "Fixture outcomes must be explicitly synthetic.")
                        if (outcome.supersedesResultId != active[outcome.context.questionRevisionId]) reject("outcome.supersedesResultId",
                            CombinedIssueCode.INVALID_REFERENCE_OR_SCOPE, "Explicitly correct the active result of this exact question.")
                        manual += event.outcome
                        // Reuse reviewed reference/effect/evidence validators without treating manual values as OBD readings.
                        val prefix = InvestigationCodec.encode(legacy(id, ordinary, cases))
                        ManualOutcomeContract(prefix, manual.mapIndexed { i, o -> o.copy(sequence = ordinary.size + i + 1) }).validate()
                        active[event.outcome.context.questionRevisionId] = event.id
                    }
                }
            }
            return active.toMap()
        } catch (error: CombinedValidation) { throw error }
        catch (error: ManualOutcomeFieldValidation) {
            reject(error.field, CombinedIssueCode.INVALID_REFERENCE_OR_SCOPE, error.message ?: "Invalid manual outcome reference.")
        }
        catch (error: Exception) {
            reject("events", CombinedIssueCode.INVALID_REFERENCE_OR_SCOPE, error.message ?: "Invalid combined investigation or reference.")
        }
    }
    private fun legacy(id: String, revisions: List<InvestigationRevision>, cases: List<DiagnosticCase>) =
        SavedInvestigation(DiagnosticInvestigation(id, revisions.mapIndexed { index, r -> r.copy(sequence = index + 1) }), cases)
}
