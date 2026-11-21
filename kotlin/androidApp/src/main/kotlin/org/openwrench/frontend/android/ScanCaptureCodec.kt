package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.*
import java.util.UUID

/** Strict typed archive, deliberately independent of report prose. */
object ScanCaptureCodec {
    private val framePids = setOf(3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15, 16, 17)
    internal fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun source(s: String?) { require(s == null || Regex("[0-9A-F]{3}|[0-9A-F]{8}").matches(s)) }
    private fun note(s: String?) { require(s == null || s.length <= 2_000 && '\u0000' !in s) }
    private fun code(s: String) { require(Regex("[PCBU][0-3][0-9A-F]{3}").matches(s)) }
    private fun validate(c: ScanCapture) {
        require(c.schemaVersion == 1 && validId(c.captureId) && c.publishedAtEpochMillis > 0)
        require(c.sessionId?.let(::validId) != false); source(c.sourceEcu)
        if (c.origin == ScanOrigin.HARDWARE) {
            require(c.sessionId != null && c.startedAtEpochMillis != null && c.completedAtEpochMillis != null && c.durationMillis != null)
        } else require(c.sessionId == null && c.sourceEcu == null)
        require((c.startedAtEpochMillis == null) == (c.completedAtEpochMillis == null))
        require((c.startedAtEpochMillis == null) == (c.durationMillis == null))
        require(c.startedAtEpochMillis?.let { it > 0 } != false)
        require(c.completedAtEpochMillis?.let { it > 0 } != false)
        // Wall clocks can move backwards. Duration is measured only with the monotonic clock.
        require(c.durationMillis?.let { it in 0L..86_400_000L } != false)
        require(c.eligiblePids.all { it in ObdDecoder.snapshotPids })
        require(c.readings.map { it.pid }.toSet() == c.eligiblePids)
        c.readings.forEach { source(it.sourceEcu) }
        require(c.services.size <= 3 && c.services.map { it.service }.distinct().size == c.services.size)
        c.services.forEach {
            source(it.sourceEcu); note(it.note)
            require(it.codes.size <= 256) // Preserve exactly what the ECU returned, including repeated identifiers.
            it.codes.forEach(::code)
            require(it.status == DiagnosticReplyStatus.AVAILABLE || it.codes.isEmpty())
        }
        c.frameZero?.let { f ->
            source(f.sourceEcu); note(f.note); f.triggerCode?.let(::code)
            require(f.advertisedPids.size <= 32 && f.advertisedPids.all { it in 1..32 })
            require(f.readings.size <= framePids.size && f.readings.map { it.pid }.distinct().size == f.readings.size)
            require(f.readings.all { it.pid in framePids && it.pid in f.advertisedPids })
            require(f.unavailablePids.keys == f.readings.filter { it.value == null }.map { it.pid }.toSet())
            require(f.unavailablePids.values.none { it == DiagnosticReplyStatus.AVAILABLE })
            f.readings.forEach { source(it.sourceEcu) }
            require(f.triggerCode == null || f.status == DiagnosticReplyStatus.AVAILABLE && 2 in f.advertisedPids)
            require(f.readings.isEmpty() || f.triggerCode != null)
            require(f.status == DiagnosticReplyStatus.AVAILABLE || f.triggerCode == null && f.readings.isEmpty())
        }
        if (c.origin !in setOf(ScanOrigin.HARDWARE, ScanOrigin.SYNTHETIC)) {
            require(c.services.isEmpty() && c.frameZero == null && c.readings.all { it.sourceEcu == null })
        }
    }
    private fun nullable(v: String?) = v?.let(::JsonPrimitive) ?: JsonNull
    private fun nullable(v: Long?) = v?.let(::JsonPrimitive) ?: JsonNull
    private fun ints(v: Collection<Int>) = JsonArray(v.map(::JsonPrimitive))
    private fun readings(rows: List<NativeReading>): JsonObject = buildJsonObject {
        // Reuse the bounded, PID-aware scalar/structured validation, keeping receipt sources separately.
        put("values", Json.parseToJsonElement(SnapshotCodec.encodeValidatedReadings(rows, false, null, null, ObdDecoder.snapshotPids + framePids)).jsonObject.getValue("readings"))
        put("sources", JsonArray(rows.map { buildJsonObject { put("pid", it.pid); put("ecu", nullable(it.sourceEcu)) } }))
    }
    fun encode(c: ScanCapture): JsonObject {
        validate(c)
        return buildJsonObject {
            put("version", c.schemaVersion); put("id", c.captureId); put("origin", c.origin.name)
            put("session", nullable(c.sessionId)); put("ecu", nullable(c.sourceEcu))
            put("started_ms", nullable(c.startedAtEpochMillis)); put("completed_ms", nullable(c.completedAtEpochMillis))
            put("duration_ms", nullable(c.durationMillis)); put("published_ms", c.publishedAtEpochMillis)
            put("eligible", ints(c.eligiblePids)); put("mode01", readings(c.readings))
            put("services", JsonArray(c.services.map { s -> buildJsonObject {
                put("service", s.service.name); put("status", s.status.name)
                put("codes", JsonArray(s.codes.map(::JsonPrimitive))); put("ecu", nullable(s.sourceEcu)); put("note", nullable(s.note))
            } }))
            put("frame_zero", c.frameZero?.let { f -> buildJsonObject {
                put("status", f.status.name); put("trigger", nullable(f.triggerCode)); put("ecu", nullable(f.sourceEcu))
                put("advertised", ints(f.advertisedPids)); put("readings", readings(f.readings)); put("note", nullable(f.note))
                put("unavailable", JsonArray(f.unavailablePids.map { (pid, status) -> buildJsonObject {
                    put("pid", pid); put("status", status.name)
                } }))
            } } ?: JsonNull)
        }
    }
    private fun JsonObject.keys(vararg expected: String) { require(keys == expected.toSet()) }
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.text(key: String) = getValue(key).text()
    private fun JsonObject.stringOrNull(key: String) = getValue(key).takeUnless { it == JsonNull }?.text()
    private fun JsonElement.number() = jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonObject.number(key: String) = getValue(key).number()
    private fun JsonObject.longOrNull(key: String) = getValue(key).takeUnless { it == JsonNull }?.number()
    private fun JsonObject.pids(key: String): Set<Int> {
        val rows = getValue(key).jsonArray.also { require(it.size <= 256) }
        val values = rows.map { it.number().also { n -> require(n in 0..255) }.toInt() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }
    private fun readRows(value: JsonElement): List<NativeReading> {
        val r = value.jsonObject; r.keys("values", "sources")
        val validationDocument = buildJsonObject {
            put("schema_version", "openwrench.snapshot/1")
            put("source", buildJsonObject { put("adapter_id", "elm327"); put("kind", "real_ecu"); put("simulated", false) })
            put("readings", r.getValue("values"))
        }
        val values = SnapshotCodec.decodeValidatedReadings(validationDocument.toString(), ObdDecoder.snapshotPids + framePids).readings.associateBy { it.pid }
        val sources = r.getValue("sources").jsonArray.also { require(it.size == values.size) }
        val rows = sources.map { element ->
            val s = element.jsonObject; s.keys("pid", "ecu")
            val pid = s.number("pid").also { require(it in 0..255) }.toInt()
            values.getValue(pid).copy(sourceEcu = s.stringOrNull("ecu"))
        }
        require(rows.map { it.pid }.distinct().size == rows.size)
        return rows
    }
    fun decode(value: JsonElement): ScanCapture {
        val c = value.jsonObject
        c.keys("version", "id", "origin", "session", "ecu", "started_ms", "completed_ms", "duration_ms", "published_ms", "eligible", "mode01", "services", "frame_zero")
        require(c.number("version") == 1L) { "Unsupported scan evidence version" }
        val services = c.getValue("services").jsonArray.also { require(it.size <= 3) }.map { row ->
            val s = row.jsonObject; s.keys("service", "status", "codes", "ecu", "note")
            DiagnosticCodeRead(DtcService.valueOf(s.text("service")), DiagnosticReplyStatus.valueOf(s.text("status")),
                s.getValue("codes").jsonArray.also { require(it.size <= 256) }.map { it.text() }, s.stringOrNull("ecu"), s.stringOrNull("note"))
        }
        val f = c.getValue("frame_zero").takeUnless { it == JsonNull }?.jsonObject?.let { f ->
            f.keys("status", "trigger", "ecu", "advertised", "readings", "note", "unavailable")
            val unavailable = f.getValue("unavailable").jsonArray.also { require(it.size <= framePids.size) }.map { item ->
                val u = item.jsonObject; u.keys("pid", "status")
                u.number("pid").also { require(it in 0..255) }.toInt() to DiagnosticReplyStatus.valueOf(u.text("status"))
            }
            require(unavailable.map { it.first }.distinct().size == unavailable.size)
            FreezeFrameRead(DiagnosticReplyStatus.valueOf(f.text("status")), f.stringOrNull("trigger"), readRows(f.getValue("readings")),
                f.stringOrNull("ecu"), f.pids("advertised"), f.stringOrNull("note"), unavailable.toMap())
        }
        return ScanCapture(c.text("id"), ScanOrigin.valueOf(c.text("origin")), c.stringOrNull("session"), c.stringOrNull("ecu"),
            c.longOrNull("started_ms"), c.longOrNull("completed_ms"), c.longOrNull("duration_ms"), c.number("published_ms"),
            c.pids("eligible"), readRows(c.getValue("mode01")), services, f).also(::validate)
    }
}
