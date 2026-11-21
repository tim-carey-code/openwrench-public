package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.DiagnosticInvestigation

internal class CombinedUnsupported(val field: String, val rawValue: String) : IllegalArgumentException("Unsupported $field: $rawValue")

/** Separate fictional fixture format. Never reads or writes schema-1 or the frozen typed-tail store. */
internal object CombinedInvestigationCodec {
    const val SCHEMA = "openwrench.combined-investigation-fixture/1"
    private fun row(vararg elements: JsonElement) = JsonArray(elements.toList())
    private fun s(value: String) = JsonPrimitive(value)
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonElement.long() = jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonElement.int() = long().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonElement.row(size: Int) = jsonArray.also { require(it.size == size) }
    fun event(event: CombinedEvent): JsonElement = when (event) {
        is CombinedEvent.Ordinary -> row(s("ordinary"), s(event.requestId), InvestigationCodec.encodeRevision(event.revision))
        is CombinedEvent.Manual -> row(s("manual"), s(event.requestId), ManualOutcomeContractCodec.encodeOutcome(event.outcome))
        is CombinedEvent.Attachment -> row(s("attachment"), s(event.requestId), s(event.id), JsonPrimitive(event.sequence),
            JsonPrimitive(event.recordedAtEpochMillis), s(event.author), s(event.reason),
            Json.parseToJsonElement(DiagnosticCaseCodec.encode(listOf(event.retainedCase))), s(event.vehicleAssertion.name), s(event.provenance))
    }
    internal fun parseEvent(value: JsonElement): CombinedEvent {
        val a = value.jsonArray
        return when (val kind = a.first().text()) {
            "ordinary" -> { value.row(3); CombinedEvent.Ordinary(a[1].text(), InvestigationCodec.decodeRevision(a[2])) }
            "manual" -> { value.row(3); CombinedEvent.Manual(a[1].text(), ManualOutcomeContractCodec.decodeOutcome(a[2])) }
            "attachment" -> {
                value.row(10)
                val raw = a[8].text()
                val assertion = FixtureVehicleAssertion.entries.singleOrNull { it.name == raw } ?: throw CombinedUnsupported("vehicleAssertion", raw)
                CombinedEvent.Attachment(a[1].text(), a[2].text(), a[3].int(), a[4].long(), a[5].text(), a[6].text(),
                    DiagnosticCaseCodec.decode(a[7].toString()).single(), assertion, a[9].text())
            }
            else -> throw CombinedUnsupported("eventKind", kind)
        }
    }
    fun encode(document: CombinedInvestigation): String {
        document.validate()
        return buildJsonObject {
            put("schema", SCHEMA); put("fixture_only", true); put("id", document.id)
            put("initial_cases", Json.parseToJsonElement(DiagnosticCaseCodec.encode(document.initialCases)))
            put("events", JsonArray(document.events.map(::event)))
        }.toString().also { bounded(it); require(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it) { "Invalid UTF-8 text" } }
    }
    private fun bounded(text: String) { require(text.toByteArray(Charsets.UTF_8).size <= InvestigationCodec.MAX_BYTES) }
    fun decode(text: String): CombinedInvestigation {
        bounded(text)
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "fixture_only", "id", "initial_cases", "events"))
        val schema = root.getValue("schema").text()
        if (schema != SCHEMA) throw CombinedUnsupported("schema", schema)
        require(root.getValue("fixture_only").jsonPrimitive.let { !it.isString && it.boolean })
        val events = root.getValue("events").jsonArray.also { require(it.size <= DiagnosticInvestigation.MAX_REVISIONS) }.map(::parseEvent)
        return CombinedInvestigation(root.getValue("id").text(), DiagnosticCaseCodec.decode(root.getValue("initial_cases").toString()), events).also { it.validate() }
    }
}
