package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import java.io.File

/** Case schema 1 embeds scan schema 2 and versioned drive archives, including retained policy evidence. */
object DiagnosticCaseCodec {
    const val MAX_BYTES = 25_000_000
    const val MAX_ENTRIES = 10
    private const val SCHEMA = "openwrench.diagnostic-cases/1"
    fun encode(cases: List<DiagnosticCase>): String {
        require(cases.size <= MAX_ENTRIES && cases.map { it.id }.distinct().size == cases.size)
        cases.forEach(::validate)
        return buildJsonObject {
            put("schema", SCHEMA)
            put("cases", JsonArray(cases.map { c -> buildJsonObject {
                put("id", c.id); put("saved_ms", c.savedAtEpochMillis); put("same_vehicle_confirmed", c.sameVehicleConfirmed)
                put("report_version", c.reportVersion); put("sections", JsonArray(c.sections.map(::JsonPrimitive)))
                put("scan", Json.parseToJsonElement(ScanHistoryCodec.encode(listOf(c.scan))))
                put("drive", Json.parseToJsonElement(DriveHistoryCodec.encode(c.drive)))
            } }))
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Case history exceeds 25 MB; delete an old case" } }
    }
    fun decode(text: String): List<DiagnosticCase> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Case history exceeds 25 MB" }
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "cases") && root.getValue("schema") == JsonPrimitive(SCHEMA)) { "Unsupported case history" }
        val rows = root.getValue("cases").jsonArray.also { require(it.size <= MAX_ENTRIES) }
        val cases = rows.map { item ->
            val c = item.jsonObject
            require(c.keys == setOf("id", "saved_ms", "same_vehicle_confirmed", "report_version", "sections", "scan", "drive"))
            fun text(key: String) = c.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun number(key: String) = c.getValue(key).jsonPrimitive.also { require(!it.isString) }.long
            require(number("report_version") == 1L) { "Unsupported case report version" }
            val confirmed = c.getValue("same_vehicle_confirmed").jsonPrimitive.also { require(!it.isString) }.boolean
            DiagnosticCase(text("id"), number("saved_ms"), confirmed,
                ScanHistoryCodec.decode(c.getValue("scan").toString()).single(), DriveHistoryCodec.decode(c.getValue("drive").toString()),
                c.getValue("sections").jsonArray.also { require(it.size == 4) }.map { it.jsonPrimitive.also { p -> require(p.isString) }.content })
                .also(::validate)
        }
        require(cases.map { it.id }.distinct().size == cases.size)
        return cases
    }
    private fun validate(c: DiagnosticCase) {
        require(ScanCaptureCodec.validId(c.id) && c.savedAtEpochMillis > 0 && c.sameVehicleConfirmed && c.reportVersion == 1)
        require(c.scan.capture != null && c.scan.vin == null && c.drive.status == SavedDriveStatus.COMPLETED)
        require(c.sections.size == 4)
        c.sections.zip(DiagnosticCaseBuilder.headings).forEach { (section, heading) ->
            require(section.startsWith("$heading\n") && section.length <= 1_000_000 && '\u0000' !in section)
        }
        ScanHistoryCodec.encode(listOf(c.scan)); DriveHistoryCodec.encode(c.drive)
    }
}

/** No eviction, upsert or source mutation. A corrupt/unknown file blocks every write. */
class DiagnosticCaseStore(private val directory: File) {
    private val file get() = File(directory, "diagnostic-cases-v1.json")
    @Synchronized fun load(): List<DiagnosticCase> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() <= DiagnosticCaseCodec.MAX_BYTES) { "Case history is unavailable or too large" }
        return DiagnosticCaseCodec.decode(file.readText(Charsets.UTF_8))
    }
    @Synchronized fun add(case: DiagnosticCase): List<DiagnosticCase> {
        val previous = load()
        require(previous.size < DiagnosticCaseCodec.MAX_ENTRIES) { "Case history is full; delete an old case" }
        require(previous.none { it.id == case.id }) { "Case already saved" }
        return write(listOf(case) + previous)
    }
    @Synchronized fun delete(id: String): List<DiagnosticCase> {
        val previous = load()
        require(previous.any { it.id == id }) { "Case not found" }
        return write(previous.filterNot { it.id == id })
    }
    private fun write(cases: List<DiagnosticCase>): List<DiagnosticCase> {
        val bytes = DiagnosticCaseCodec.encode(cases).toByteArray(Charsets.UTF_8)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create case directory" }
        val temporary = File(directory, "diagnostic-cases-v1.json.tmp")
        try {
            temporary.outputStream().use { stream -> stream.write(bytes); stream.flush(); (stream as java.io.FileOutputStream).fd.sync() }
            check(temporary.renameTo(file)) { "Could not replace case history" }
        } finally { temporary.delete() }
        return cases
    }
}
