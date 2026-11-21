package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** App-private diagnostic reports; completed hardware scans are saved automatically without VIN. */
data class SavedScan(val id: String, val savedAtEpochMillis: Long, val report: String, val vin: String?,
    val capture: org.openwrench.nativecore.ScanCapture? = null) {
    fun copyText(): String = if (vin == null) report.trimEnd() + "\n"
        else report.trimEnd().replace("VIN omitted.", "VIN included by user.") +
            "\nVIN (explicitly included): $vin\n"
}

object ScanHistoryCodec {
    private const val VERSION = "openwrench.scan-history/2"
    private const val LEGACY = "openwrench.scan-history/1"
    const val MAX_BYTES = 2_000_000
    const val MAX_ENTRIES = 50
    private const val MAX_REPORT = 60_000
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
    private val json = Json { prettyPrint = true }

    fun newEntry(report: String, vin: String?, nowMillis: Long = System.currentTimeMillis(),
        capture: org.openwrench.nativecore.ScanCapture? = null): SavedScan =
        SavedScan(UUID.randomUUID().toString(), nowMillis, report, vin, capture).also(::validate)

    fun encode(entries: List<SavedScan>): String {
        require(entries.size <= MAX_ENTRIES && entries.map { it.id }.distinct().size == entries.size)
        entries.forEach(::validate)
        val root = buildJsonObject {
            put("schema_version", VERSION)
            put("entries", JsonArray(entries.map { entry -> buildJsonObject {
                put("id", entry.id)
                put("saved_at_epoch_millis", entry.savedAtEpochMillis)
                put("report", entry.report)
                put("vin", entry.vin?.let(::JsonPrimitive) ?: JsonNull)
                put("capture", entry.capture?.let(ScanCaptureCodec::encode) ?: JsonNull)
            } }))
        }
        return json.encodeToString(JsonElement.serializer(), root).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Scan history is too large" }
        }
    }

    fun decode(text: String): List<SavedScan> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Scan history is too large" }
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid scan history")
        require(root.keys == setOf("schema_version", "entries") && root["schema_version"]?.jsonPrimitive?.content in setOf(VERSION, LEGACY)) {
            "Unsupported scan history"
        }
        val legacy = root.getValue("schema_version").jsonPrimitive.content == LEGACY
        val rows = root["entries"] as? JsonArray ?: error("Invalid scan history entries")
        require(rows.size <= MAX_ENTRIES) { "Too many scan history entries" }
        val entries = rows.map { item ->
            val row = item as? JsonObject ?: error("Invalid scan history entry")
            require(row.keys == setOf("id", "saved_at_epoch_millis", "report", "vin") + if (legacy) emptySet() else setOf("capture"))
            SavedScan(
                row.getValue("id").jsonPrimitive.also { require(it.isString) }.content,
                row.getValue("saved_at_epoch_millis").jsonPrimitive.also { require(!it.isString) }.long,
                row.getValue("report").jsonPrimitive.also { require(it.isString) }.content,
                row.getValue("vin").takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content,
                if (legacy) null else row.getValue("capture").takeUnless { it == JsonNull }?.let(ScanCaptureCodec::decode),
            ).also(::validate)
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate scan history entry" }
        return entries
    }

    private fun validate(entry: SavedScan) {
        require(runCatching { UUID.fromString(entry.id).toString() == entry.id }.getOrDefault(false) && entry.savedAtEpochMillis > 0)
        require(entry.report.startsWith("OPENWRENCH read-only diagnostic scan\n") &&
            entry.report.length in 1..MAX_REPORT && !entry.report.contains('\u0000')) { "Invalid scan report" }
        entry.capture?.let(ScanCaptureCodec::encode)
        require(entry.vin == null || vinPattern.matches(entry.vin)) { "Invalid VIN" }
    }
}

/** A failed read blocks writes, so corrupt history is never silently replaced. */
class ScanHistoryStore(private val directory: File) {
    private val file get() = File(directory, "scan-history-v1.json")

    @Synchronized fun load(): List<SavedScan> {
        if (!file.exists()) return emptyList()
        require(file.length() <= ScanHistoryCodec.MAX_BYTES) { "Scan history is too large" }
        return ScanHistoryCodec.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized fun add(entry: SavedScan): List<SavedScan> {
        val previous = load()
        require(previous.size < ScanHistoryCodec.MAX_ENTRIES) { "Scan history is full; delete an old scan first" }
        return save(listOf(entry) + previous)
    }

    /** Idempotent across Activity recreation, retries, and APK upgrades.
     * Capture identity, not report prose or connection state, determines whether this is a duplicate.
     * Explicit VIN opt-in can enrich the same saved record without changing its ID or its report.
     */
    @Synchronized fun saveCapture(report: String, capture: org.openwrench.nativecore.ScanCapture,
        explicitVin: String? = null): List<SavedScan> {
        val previous = load() // Reject corrupt/unknown history before any mutation.
        val existing = previous.firstOrNull { it.capture?.captureId == capture.captureId }
        if (existing != null) {
            if (explicitVin == null || existing.vin == explicitVin) return previous
            val updated = existing.copy(vin = explicitVin)
            return save(previous.map { if (it.id == existing.id) updated else it })
        }
        require(previous.size < ScanHistoryCodec.MAX_ENTRIES) { "Scan history is full; delete an old scan first" }
        return save(listOf(ScanHistoryCodec.newEntry(report, explicitVin, capture = capture)) + previous)
    }

    @Synchronized fun delete(id: String): List<SavedScan> {
        val before = load()
        require(before.any { it.id == id }) { "Scan was not found" }
        return save(before.filterNot { it.id == id })
    }

    private fun save(entries: List<SavedScan>): List<SavedScan> {
        val content = ScanHistoryCodec.encode(entries)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create scan history directory" }
        val temporary = File(directory, "scan-history-v1.json.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                (output as java.io.FileOutputStream).fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not replace scan history" }
        } finally { temporary.delete() }
        return entries
    }
}
