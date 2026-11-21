package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*

/** Dormant review envelope, deliberately NOT investigation schema 2. No persistence or migration. */
object ManualOutcomeContractCodec {
    const val SCHEMA = "openwrench.investigation-outcome-contract/1"
    private fun row(vararg values: JsonElement) = JsonArray(values.toList())
    private fun string(value: String) = JsonPrimitive(value)
    private fun nullable(value: String?) = value?.let(::string) ?: JsonNull
    private fun detail(d: ReportedDetail) = row(string(d.knowledge.name), nullable(d.text))
    private fun selection(s: OwnerReportSelection) = row(string(s.status.name), nullable(s.revisionId))
    private fun evidence(e: InvestigationEvidenceRef) = row(string(e.caseId), string(e.kind.name),
        e.pid?.let(::JsonPrimitive) ?: JsonNull, e.findingIndex?.let(::JsonPrimitive) ?: JsonNull,
        e.intervalIndex?.let(::JsonPrimitive) ?: JsonNull)
    private fun context(c: ManualOutcomeContext) = row(string(c.questionRevisionId), string(c.caseId),
        c.catalog?.let { row(string(it.questionId.name), JsonPrimitive(it.version)) } ?: JsonNull,
        selection(c.startingReport), selection(c.timingReport), JsonArray(c.observationIds.map(::string)),
        JsonArray(c.retainedEvidence.map(::evidence)), selection(c.adaptationReport))
    internal fun encodeOutcome(o: AttributedManualOutcome) = row(string(o.id), JsonPrimitive(o.sequence),
        JsonPrimitive(o.recordedAtEpochMillis), string(o.author), string(o.reason), context(o.context),
        string(o.origin.name), string(o.faultMode.name), string(o.method.name), string(o.declaredOutcome.name),
        detail(o.observer), detail(o.methodDetail), detail(o.conditions), detail(o.criteriaReference),
        string(o.applicability.name), o.observedAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull,
        string(o.provenance), JsonArray(o.values.map { row(JsonPrimitive(it.value), string(it.unit.name), nullable(it.reportedUnit)) }),
        JsonArray(o.authorEffects.map { row(string(it.hypothesisId), string(it.effect.name), string(it.rationale)) }), nullable(o.supersedesResultId))

    /** Explicit opt-in read; never infers typed outcomes from historic free text. */
    fun readLegacy(text: String): ManualOutcomeContract = ManualOutcomeContract(text, emptyList()).also { it.validate() }
    fun encode(contract: ManualOutcomeContract): String {
        contract.validate()
        return buildJsonObject {
            put("schema", SCHEMA)
            put("legacy_json", contract.legacyJson)
            put("outcomes", JsonArray(contract.outcomes.map(::encodeOutcome)))
        }.toString().also(::bounded)
    }
    private fun bounded(text: String) { require(text.toByteArray(Charsets.UTF_8).size <= InvestigationCodec.MAX_BYTES) }
    private fun JsonElement.row(size: Int) = jsonArray.also { require(it.size == size) }
    private fun JsonElement.list(maximum: Int = 32) = jsonArray.also { require(it.size <= maximum) }
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonElement.long() = jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonElement.int() = long().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonElement.optionalText() = if (this == JsonNull) null else text()
    private fun JsonElement.optionalInt() = if (this == JsonNull) null else int()
    private fun parseDetail(e: JsonElement) = e.row(2).let { ReportedDetail(enumValueOf(it[0].text()), it[1].optionalText()) }
    private fun parseSelection(e: JsonElement) = e.row(2).let { OwnerReportSelection(enumValueOf(it[0].text()), it[1].optionalText()) }
    private fun parseContext(e: JsonElement) = e.row(8).let { a ->
        ManualOutcomeContext(a[0].text(), a[1].text(), if (a[2] == JsonNull) null else a[2].row(2).let {
            OutcomeCatalogReference(enumValueOf(it[0].text()), it[1].int())
        }, parseSelection(a[3]), parseSelection(a[4]), a[5].list().map { it.text() }, a[6].list().map { value ->
            value.row(5).let { InvestigationEvidenceRef(it[0].text(), enumValueOf(it[1].text()), it[2].optionalInt(), it[3].optionalInt(), it[4].optionalInt()) }
        }, parseSelection(a[7]))
    }
    internal fun decodeOutcome(e: JsonElement) = e.row(20).let { a ->
        AttributedManualOutcome(a[0].text(), a[1].int(), a[2].long(), a[3].text(), a[4].text(), parseContext(a[5]),
            enumValueOf(a[6].text()), enumValueOf(a[7].text()), enumValueOf(a[8].text()), enumValueOf(a[9].text()),
            parseDetail(a[10]), parseDetail(a[11]), parseDetail(a[12]), parseDetail(a[13]), enumValueOf(a[14].text()),
            if (a[15] == JsonNull) null else a[15].long(), a[16].text(), a[17].list().map { value -> value.row(3).let {
                AttributedManualValue(it[0].jsonPrimitive.also { p -> require(!p.isString) }.double, enumValueOf(it[1].text()), it[2].optionalText())
            } }, a[18].list().map { value -> value.row(3).let {
                InvestigationEffect(it[0].text(), enumValueOf(it[1].text()), it[2].text())
            } }, a[19].optionalText())
    }
    fun decode(text: String): ManualOutcomeContract {
        bounded(text)
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "legacy_json", "outcomes"))
        require(root.getValue("schema").text() == SCHEMA)
        return ManualOutcomeContract(root.getValue("legacy_json").text(),
            root.getValue("outcomes").list(DiagnosticInvestigation.MAX_REVISIONS).map(::decodeOutcome)).also { it.validate() }
    }
}
