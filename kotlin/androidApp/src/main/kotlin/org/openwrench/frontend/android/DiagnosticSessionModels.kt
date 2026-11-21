package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.ObdDecoder
import java.util.UUID
import org.openwrench.core.GuidedDiagnosisPolicy
import org.openwrench.core.GuidedDiagnosisProgress

enum class DiagnosisStatus { RECORDING, PAUSED, COMPLETED, INTERRUPTED }

data class DiagnosisSegment(
    val id: String,
    val generation: Long,
    val sourceEcu: String?,
    val startedAtEpochMillis: Long,
    val startedAtMonotonicMillis: Long,
    val endedAtOffsetMillis: Long? = null,
    val driveId: String? = null,
    val endReason: String? = null,
    val sampleCount: Long = 0,
    val droppedSamples: Long = 0,
    val guidance: GuidedDiagnosisProgress? = null,
)

/** A parent case groups separate receipt timelines; a resume never joins their clocks. */
data class DiagnosisRecord(
    val id: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: DiagnosisStatus,
    val segments: List<DiagnosisSegment> = emptyList(),
    val scanReports: List<String> = emptyList(),
    val note: String? = null,
    val guidedPolicyVersion: Int? = null,
) {
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH diagnosis · recorded hardware evidence")
        appendLine("Diagnosis: $id")
        appendLine("Status: ${status.name.lowercase()}")
        appendLine("Started at epoch ms: $startedAtEpochMillis; updated at epoch ms: $updatedAtEpochMillis")
        note?.let { appendLine("Session note: $it") }
        guidedPolicyVersion?.let { appendLine("Guided policy: $it (provisional app screening)") }
        if (guidedPolicyVersion != null && segments.isEmpty()) appendLine("Partial assessment: initial scan/monitoring did not complete; no qualified window exists.")
        appendLine("Observed evidence")
        appendLine("${segments.size} separate recording segments; ${scanReports.size} attached scans")
        segments.forEachIndexed { index, s ->
            appendLine("Segment ${index + 1}: ${s.id}; session ${s.generation}; source ECU ${s.sourceEcu ?: "unaddressed"}")
            appendLine("Started at epoch ms: ${s.startedAtEpochMillis}; elapsed ms: ${s.endedAtOffsetMillis ?: "recording"}")
            appendLine("Received samples: ${s.sampleCount}; samples not retained: ${s.droppedSamples}")
            appendLine("End: ${s.endReason ?: "not ended"}; linked drive: ${s.driveId ?: "unavailable"}")
            s.guidance?.let { appendLine(it.reportText()) }
        }
        scanReports.forEachIndexed { index, report -> appendLine("\nAttached scan ${index + 1}\n$report") }
        appendLine("\nInterpretation")
        appendLine("Recorded drive assessments are included separately when available. A measurement or code does not establish a fault cause; no overall healthy state is inferred.")
        appendLine("\nCoverage and recording limits")
        appendLine("CSV contains actual completed decoded Mode 01 receipts, including NO DATA, not raw adapter frames or a complete ECU sample timeline.")
        appendLine("Each segment has its own monotonic receipt offsets. The CSV epoch column is anchored to that segment's start wall clock; it is not ECU time.")
        appendLine("Gaps, missing inputs, interrupted recordings, and dropped samples limit the assessment. Old evidence remains historical; resuming requires an explicit adapter connection.")
        appendLine("VIN and adapter identifiers are not collected by this record. No vehicle write commands are issued.")
    }
}

data class DiagnosisSample(
    val elapsedMillis: Long,
    val pid: Int,
    val value: Double?,
    val textValue: String?,
    val unit: String,
    val availability: String,
) {
    internal fun validate() {
        val metadata = ObdDecoder.catalog.getValue(pid)
        require(elapsedMillis in 0..DiagnosisCodec.MAX_ELAPSED)
        require(unit == metadata.unit.orEmpty()) { "Sample unit does not match the PID" }
        require(availability in setOf("AVAILABLE", "NO_DATA"))
        require(value == null || value.isFinite())
        if (availability == "NO_DATA") require(value == null && textValue == null)
        else if (metadata.structured) {
            require(value == null && !textValue.isNullOrEmpty())
            DiagnosisCodec.plainText(textValue, 4_000, multiline = false)
            require(textValue.first() !in "=+@-\t") { "Unsafe structured CSV text" }
        } else require(value != null && textValue == null)
    }
}

/** Strict bounded metadata; no vehicle identities, VIN, raw frames, or adapter IDs. */
internal object DiagnosisCodec {
    const val SCHEMA = "openwrench.diagnosis/1"
    const val GUIDED_SCHEMA = "openwrench.diagnosis/2"
    const val MAX_METADATA_BYTES = 1_048_576
    const val MAX_ELAPSED = 7 * 24 * 60 * 60_000L
    private fun primitive(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull
    private fun primitive(value: Long?) = value?.let(::JsonPrimitive) ?: JsonNull
    fun uuid(id: String) = require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid diagnosis identifier" }
    fun plainText(text: String, maximum: Int, multiline: Boolean = true) {
        require(text.length <= maximum && text.none { it.code < 32 && !(multiline && it in "\n\r\t") }) { "Invalid diagnosis text" }
    }
    fun validate(r: DiagnosisRecord) {
        uuid(r.id)
        require(r.guidedPolicyVersion == null || r.guidedPolicyVersion == GuidedDiagnosisPolicy.VERSION)
        require(r.startedAtEpochMillis > 0 && r.updatedAtEpochMillis > 0)
        require(r.segments.size <= 50 && r.segments.map { it.id }.distinct().size == r.segments.size)
        require(r.segments.count { it.endedAtOffsetMillis == null } <= 1)
        require(r.segments.dropLast(1).none { it.endedAtOffsetMillis == null })
        require(r.status != DiagnosisStatus.COMPLETED || r.segments.none { it.endedAtOffsetMillis == null })
        r.segments.forEach { s ->
            uuid(s.id); s.driveId?.let(::uuid)
            require(s.generation >= 0 && s.startedAtEpochMillis in 1..Long.MAX_VALUE - MAX_ELAPSED && s.startedAtMonotonicMillis >= 0)
            require(s.sourceEcu == null || Regex("[0-9A-F]{3}|[0-9A-F]{8}").matches(s.sourceEcu))
            require(s.endedAtOffsetMillis == null || s.endedAtOffsetMillis in 0..MAX_ELAPSED)
            require(s.sampleCount in 0..1_000_000_000L && s.droppedSamples in 0..1_000_000_000L)
            s.endReason?.let { plainText(it, 1_000) }
            s.guidance?.let { p ->
                require(r.guidedPolicyVersion == p.policyVersion)
                GuidedDiagnosisArchive.validate(p)
                require(s.endedAtOffsetMillis == null || p.evaluatedAtOffsetMillis <= s.endedAtOffsetMillis)
            }
        }
        require(r.scanReports.size <= 50)
        r.scanReports.forEach {
            require(it.startsWith("OPENWRENCH read-only diagnostic scan\n"))
            plainText(it, 60_000)
            require(!it.contains("VIN (explicitly included):")) { "VIN is not supported in diagnosis records" }
        }
        r.note?.let { plainText(it, 2_000) }
    }
    fun encode(record: DiagnosisRecord): String {
        validate(record)
        return buildJsonObject {
            put("schema", if (record.guidedPolicyVersion == null) SCHEMA else GUIDED_SCHEMA)
            if (record.guidedPolicyVersion != null) put("guided_policy", record.guidedPolicyVersion)
            put("id", record.id); put("started_epoch_ms", record.startedAtEpochMillis)
            put("updated_epoch_ms", record.updatedAtEpochMillis); put("status", record.status.name)
            put("note", primitive(record.note)); put("scans", JsonArray(record.scanReports.map(::JsonPrimitive)))
            put("segments", JsonArray(record.segments.map { s -> buildJsonObject {
               put("id", s.id); put("generation", s.generation); put("ecu", primitive(s.sourceEcu))
                put("started_epoch_ms", s.startedAtEpochMillis); put("started_monotonic_ms", s.startedAtMonotonicMillis)
                put("ended_offset_ms", primitive(s.endedAtOffsetMillis)); put("drive_id", primitive(s.driveId))
                if (record.guidedPolicyVersion != null) put("guidance", s.guidance?.let(GuidedDiagnosisArchive::encode) ?: JsonNull)
                put("end_reason", primitive(s.endReason)); put("sample_count", s.sampleCount); put("dropped_samples", s.droppedSamples)
            } }))
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "Diagnosis metadata is full" } }
    }
    fun decode(text: String): DiagnosisRecord {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        SnapshotCodec.StrictJsonCheck(text).check()
        val r = Json.parseToJsonElement(text).jsonObject
        val guided = r.getValue("schema").text() == GUIDED_SCHEMA
        require(r.keys == (if (guided) setOf("guided_policy") else emptySet()) + setOf("schema", "id", "started_epoch_ms", "updated_epoch_ms", "status", "note", "scans", "segments"))
        require(r.getValue("schema").text() in setOf(SCHEMA, GUIDED_SCHEMA)) { "Unsupported diagnosis format" }
        val segments = r.getValue("segments").jsonArray.also { require(it.size <= 50) }.map { value ->
            val s = value.jsonObject
            require(s.keys == (if (guided) setOf("guidance") else emptySet()) + setOf("id", "generation", "ecu", "started_epoch_ms", "started_monotonic_ms", "ended_offset_ms", "drive_id", "end_reason", "sample_count", "dropped_samples"))
            DiagnosisSegment(s.getValue("id").text(), s.getValue("generation").integer(), s.getValue("ecu").nullableText(),
                s.getValue("started_epoch_ms").integer(), s.getValue("started_monotonic_ms").integer(),
                s.getValue("ended_offset_ms").let { if (it == JsonNull) null else it.integer() },
                s.getValue("drive_id").nullableText(), s.getValue("end_reason").nullableText(),
                s.getValue("sample_count").integer(), s.getValue("dropped_samples").integer(),
                if (guided) s.getValue("guidance").takeUnless { it == JsonNull }?.let(GuidedDiagnosisArchive::decode) else null)
        }
        return DiagnosisRecord(r.getValue("id").text(), r.getValue("started_epoch_ms").integer(),
            r.getValue("updated_epoch_ms").integer(), DiagnosisStatus.valueOf(r.getValue("status").text()), segments,
            r.getValue("scans").jsonArray.also { require(it.size <= 50) }.map { it.text() }, r.getValue("note").nullableText(),
            if (guided) r.getValue("guided_policy").integer().also { require(it in 1..Int.MAX_VALUE) }.toInt() else null).also(::validate)
    }
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonElement.nullableText() = if (this == JsonNull) null else text()
    private fun JsonElement.integer() = jsonPrimitive.also { require(!it.isString) }.long
}
