package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.core.*
import java.security.MessageDigest

internal fun investigationDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal data class SelectedCaseRevision(val caseId: String, val archiveToken: String, val caseDigest: String) {
    fun validate() { DiagnosticInvestigation.validId(caseId); listOf(archiveToken, caseDigest).forEach { require(Regex("[a-f0-9]{64}").matches(it)) } }
}
internal data class RetainedRevision(val event: CombinedEvent, val source: SelectedCaseRevision? = null)
/** New IDs only. Origins are preserved metadata, never an assertion of hardware authenticity. */
internal data class RetainedInvestigation(override val id: String, val revisions: List<RetainedRevision>) : InvestigationRevisionLog {
    override val events get() = revisions.map { it.event }
    override fun cases() = events.filterIsInstance<CombinedEvent.Attachment>().map { it.retainedCase }
    fun append(revision: RetainedRevision) = copy(revisions = revisions + revision).also { it.validate() }
    override fun validate() { activeResultIds() }
    override fun activeResultIds(): Map<String, String> {
        require(revisions.size in 1..DiagnosticInvestigation.MAX_REVISIONS)
        revisions.forEach { revision ->
            if (revision.event is CombinedEvent.Attachment) {
                val selection = requireNotNull(revision.source) { "Retained attachments require exact source identity" }
                selection.validate()
                require(selection.caseId == revision.event.retainedCase.id && selection.caseDigest ==
                    investigationDigest(DiagnosticCaseCodec.encode(listOf(revision.event.retainedCase)).toByteArray(Charsets.UTF_8))) { "Attachment copy differs from selected immutable source revision" }
            } else require(revision.source == null)
        }
        return InvestigationLogValidator.check(id, emptyList(), events, fixtureOnly = false)
    }
}
internal object RetainedInvestigationCodec {
    const val SCHEMA = "openwrench.retained-investigation-candidate/1"
    fun encode(document: RetainedInvestigation): String {
        document.validate()
        return buildJsonObject {
            put("schema", SCHEMA); put("id", document.id)
            put("revisions", JsonArray(document.revisions.map { revision -> JsonArray(listOf(
                CombinedInvestigationCodec.event(revision.event), revision.source?.let {
                    JsonArray(listOf(JsonPrimitive(it.caseId), JsonPrimitive(it.archiveToken), JsonPrimitive(it.caseDigest)))
                } ?: JsonNull)) }))
        }.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= InvestigationCodec.MAX_BYTES)
            require(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it)
        }
    }
    fun revision(document: RetainedInvestigation) = investigationDigest(encode(document).toByteArray(Charsets.UTF_8))
    fun decode(text: String): RetainedInvestigation {
        require(text.toByteArray(Charsets.UTF_8).size <= InvestigationCodec.MAX_BYTES)
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
        require(root.keys == setOf("schema", "id", "revisions"))
        val schema = root.getValue("schema").text()
        if (schema != SCHEMA) throw CombinedUnsupported("schema", schema)
        return RetainedInvestigation(root.getValue("id").text(), root.getValue("revisions").jsonArray.also {
            require(it.size <= DiagnosticInvestigation.MAX_REVISIONS)
        }.map { row -> row.jsonArray.also { require(it.size == 2) }.let {
            RetainedRevision(CombinedInvestigationCodec.parseEvent(it[0]), if (it[1] == JsonNull) null else it[1].jsonArray.also { a -> require(a.size == 3) }.let { a ->
                SelectedCaseRevision(a[0].text(), a[1].text(), a[2].text())
            })
        } }).also { it.validate() }
    }
}

/** Explicit port only: no default installed-history reader or app/runtime factory exists. */
internal fun interface RetainedCaseSource { fun openArchive(): java.io.InputStream }
internal data class RetainedCaseChoice(val selection: SelectedCaseRevision, val retainedCase: DiagnosticCase)
internal fun readRetainedCaseChoices(source: RetainedCaseSource): List<RetainedCaseChoice> {
    val bytes = source.openArchive().use { boundedBytes(it, DiagnosticCaseCodec.MAX_BYTES) }
    require(bytes.size <= DiagnosticCaseCodec.MAX_BYTES)
    val text = bytes.toString(Charsets.UTF_8)
    require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
    val token = investigationDigest(bytes)
    return DiagnosticCaseCodec.decode(text).map { case -> RetainedCaseChoice(
        SelectedCaseRevision(case.id, token, investigationDigest(DiagnosticCaseCodec.encode(listOf(case)).toByteArray(Charsets.UTF_8))), case)
    }
}
