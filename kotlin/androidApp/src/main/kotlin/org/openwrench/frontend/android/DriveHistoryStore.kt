package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.ObdDecoder
import java.io.File
import java.util.UUID

enum class DriveEvidenceOrigin { HARDWARE, SYNTHETIC, UNKNOWN }

enum class SavedDriveStatus { CHECKPOINT, COMPLETED, INTERRUPTED }

/** A frozen historical record. Stored elapsed offsets never become current receipt clocks. */
data class SavedDrive(
    val id: String, val startedAtEpochMillis: Long, val savedAtEpochMillis: Long,
    val status: SavedDriveStatus, val endReason: String?, val sourceEcu: String?,
    val generation: Long, val report: String, val review: LiveHealthDriveReview,
    val origin: DriveEvidenceOrigin = DriveEvidenceOrigin.UNKNOWN,
) {
    fun historicalReview(): LiveHealthDriveReview = review.copy(bookmarks = review.bookmarks.map {
        if (it.complete) it else it.copy(complete = true,
            collectionStatus = "Partial saved window: monitoring was interrupted at the last checkpoint; " + it.collectionStatus)
    })
    fun copyText(): String = "OPENWRENCH saved drive · historical\n" +
        "Started at epoch ms: $startedAtEpochMillis; saved at epoch ms: $savedAtEpochMillis\n" +
        "Storage status: ${status.name.lowercase()}; ${endReason ?: "last saved checkpoint; completion unconfirmed"}\n" +
        report.substringBefore("Drive review: monitor instance") + historicalReview().reportText() +
        "\nStored observations are historical, not current vehicle status. No fresh stored-code scan is inferred. VIN omitted.\n" +
        SavedEvidenceReview.build(this).reportText()
}

data class SavedDriveSummary(val id: String, val startedAtEpochMillis: Long, val savedAtEpochMillis: Long,
    val status: SavedDriveStatus, val elapsedMillis: Long, val bookmarks: Int, val bytes: Long)

/** Compact, versioned, bounded local format. No VIN, raw frames, or adapter identifiers. */
object DriveHistoryCodec {
    const val MAX_FILE_BYTES = 2_000_000
    private const val SCHEMA = "openwrench.drive-history/6"
    private const val EXTENDED_SCHEMA = "openwrench.drive-history/7"
    // Generic retained telemetry is separate from the diagnostic monitor's fixed inputs.
    // The byte limit still applies. A 55-channel, five-minute, 1 Hz replay has 16,555 points.
    internal const val MAX_EXTENDED_POINTS = 20_000
    private const val SPLIT_SCHEMA = "openwrench.drive-history/5"
    private const val PAIRED_SCHEMA = "openwrench.drive-history/4"
    private const val WINDOW_SCHEMA = "openwrench.drive-history/3"
    private const val ASSESSMENT_SCHEMA = "openwrench.drive-history/2"
    private const val LEGACY_SCHEMA = "openwrench.drive-history/1"
    private const val MAX_ELAPSED = 7 * 24 * 60 * 60_000L
    private val scalarPids = LiveHealthInputs.scalarPids.toSet()
    private val eventPids = scalarPids + setOf(0x01, 0x03)
    private val telemetryPids = ObdDecoder.scalarPids
    private fun needsExtended(d: SavedDrive): Boolean = d.review.let { r ->
        r.signals.size > 12 || r.series.size > 9 ||
            r.signals.any { it.pid !in scalarPids } || r.series.any { it.pid !in scalarPids }
    }
    internal fun validTelemetryValue(pid: Int, value: Double): Boolean = value.isFinite() &&
        (!ObdDecoder.isIntegerScalar(pid) || (value >= 0 && value <= (if (pid == 0x30) 255 else 65_535) && value % 1.0 == 0.0))
    private fun arr(vararg values: Any?): JsonArray = JsonArray(values.map(::primitive))
    private fun primitive(value: Any?): JsonElement = when (value) {
        null -> JsonNull; is JsonElement -> value; is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value)
        else -> error("Unsupported drive-history value")
    }
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun signal(value: LiveHealthDriveSignal) = arr(value.pid, value.sampleCount,
        value.firstValue, value.lastValue, value.minimum, value.maximum, value.firstOffsetMillis, value.lastOffsetMillis)
    private fun assessment(value: DriveHealthAssessment?) = value?.let { a -> buildJsonObject {
        put("pack", a.packId); put("version", a.packVersion); put("evaluated_ms", a.evaluatedAtOffsetMillis)
        put("sources", strings(a.sourceRefs)); put("notes", strings(a.notes))
        put("findings", JsonArray(a.findings.map { f -> buildJsonObject {
            put("check", f.checkId); put("title", f.title); put("disposition", f.disposition.name)
            put("summary", f.summary); put("evidence", strings(f.evidence)); put("next", strings(f.nextSteps))
            put("limits", strings(f.limitations)); put("first_ms", primitive(f.firstOffsetMillis))
            put("last_ms", primitive(f.lastOffsetMillis)); put("samples", f.sampleCount)
            put("context", f.context); put("freshness_ms", primitive(f.freshnessMillis))
            put("fuel_intervals", JsonArray(f.fuelIntervals.map(FuelCorrectionArchive::encode)))
            put("comparison_window", f.comparisonWindow?.let { w -> arr(w.firstOffsetMillis, w.lastOffsetMillis, w.sampleCount, w.condition,
                w.trimMinimum, w.trimMaximum, w.rpmMinimum, w.rpmMaximum, w.loadMinimum, w.loadMaximum, w.coolantMinimum, w.coolantMaximum) } ?: JsonNull)
        } }))
    } } ?: JsonNull

    fun encode(drive: SavedDrive): String {
        val extended = needsExtended(drive)
        validate(drive, extended)
        val r = drive.review
        val root = buildJsonObject {
            put("schema", if (extended) EXTENDED_SCHEMA else SCHEMA); put("id", drive.id); put("origin", drive.origin.name)
            put("started_epoch_ms", drive.startedAtEpochMillis); put("saved_epoch_ms", drive.savedAtEpochMillis)
            put("status", drive.status.name); put("end_reason", primitive(drive.endReason))
            put("ecu", primitive(drive.sourceEcu)); put("generation", drive.generation); put("report", drive.report)
            put("review", buildJsonObject {
                put("instance", r.instanceId); put("started_monotonic_ms", r.startedAtMonotonicMillis); put("elapsed_ms", r.elapsedMillis)
                put("assessment", assessment(r.assessment))
                put("highlights", strings(r.highlights)); put("retention", strings(r.retentionNotes)); put("dropped_events", r.droppedBookmarks)
                put("signals", JsonArray(r.signals.map(::signal)))
                put("conditions", JsonArray(r.conditions.map { arr(it.condition, JsonArray(it.signals.map(::signal))) }))
                put("series", JsonArray(r.series.map { s -> arr(s.pid, s.maximumLinkGapMillis,
                    JsonArray(s.points.map { arr(it.offsetMillis, it.value, it.breakBefore) })) }))
                put("bookmarks", JsonArray(r.bookmarks.map { b -> buildJsonObject {
                    put("id", b.id); put("offset_ms", b.offsetMillis); put("title", b.title); put("observation", b.observation)
                    put("initial", b.initialObservation); put("collection", b.collectionStatus); put("complete", b.complete)
                    put("pre_ms", b.preWindowMillis); put("post_ms", b.postWindowMillis); put("dropped", b.samplesDropped)
                    put("pre_truncated", b.preWindowTruncated); put("post_truncated", b.postWindowTruncated)
                    put("samples", JsonArray(b.samples.map { arr(it.pid, it.offsetMillis, it.value, it.displayValue) }))
                } }))
            })
        }
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Drive archive is too large" } }
    }

    fun decode(text: String): SavedDrive {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Drive archive is too large" }
        SnapshotCodec.StrictJsonCheck(text).check()
        val root = Json.parseToJsonElement(text).jsonObject
        val schema = root.text("schema")
        // Schema 5 was shipped in two branches: origin-bearing main and MAF-bearing
        // diagnosis previews without origin. Never infer hardware provenance from a chart.
        val extended = schema == EXTENDED_SCHEMA
        val allowedTelemetry = if (extended) telemetryPids else scalarPids
        val hasOrigin = extended || schema == SCHEMA || (schema == SPLIT_SCHEMA && "origin" in root)
        require(root.keys == setOf("schema", "id", "started_epoch_ms", "saved_epoch_ms", "status", "end_reason", "ecu", "generation", "report", "review") + if (hasOrigin) setOf("origin") else emptySet())
        require(schema in setOf(EXTENDED_SCHEMA, SCHEMA, SPLIT_SCHEMA, PAIRED_SCHEMA, WINDOW_SCHEMA, ASSESSMENT_SCHEMA, LEGACY_SCHEMA)) { "Unsupported drive archive version" }
        val generation = root.integer("generation")
        val r = root.getValue("review").jsonObject
        val reviewKeys = setOf("instance", "started_monotonic_ms", "elapsed_ms", "highlights", "retention", "dropped_events", "signals", "conditions", "series", "bookmarks")
        require(r.keys == if (schema != LEGACY_SCHEMA) reviewKeys + "assessment" else reviewKeys)
        val instance = r.integer("instance")
        fun signal(row: JsonElement, allowed: Set<Int> = allowedTelemetry): LiveHealthDriveSignal {
            val a = row.jsonArray.also { require(it.size == 8) }
            val pid = a[0].pid().also { require(it in allowed) }
            val m = ObdDecoder.catalog.getValue(pid)
            return LiveHealthDriveSignal(pid, m.name, m.unit, a[1].long(), a[2].number(), a[3].number(), a[4].number(), a[5].number(), a[6].long(), a[7].long())
        }
        var totalPoints = 0
        val series = r.rows("series", if (extended) telemetryPids.size else 9).map { row ->
            val a = row.jsonArray.also { require(it.size == 3) }
            val pid = a[0].pid().also { require(it in allowedTelemetry) }
            val m = ObdDecoder.catalog.getValue(pid)
            val points = a[2].jsonArray.also {
                require(it.size <= 1_200)
                totalPoints += it.size
                require(!extended || totalPoints <= MAX_EXTENDED_POINTS)
            }.map { p ->
                val v = p.jsonArray.also { require(it.size == 3) }
                LiveHealthChartPoint(v[0].long(), v[1].nullableNumber(), v[2].bool())
            }
            LiveHealthChartSeries(pid, m.name, m.unit, points, a[1].long())
        }
        val review = LiveHealthDriveReview(instance, r.integer("started_monotonic_ms"), r.integer("elapsed_ms"),
            r.stringList("highlights"), r.rows("signals", if (extended) telemetryPids.size else 12).map { signal(it) },
            r.rows("conditions", 4).map { row ->
                val a = row.jsonArray.also { require(it.size == 2) }
                val signals = a[1].jsonArray.also { require(it.size <= 12) }.map { signal(it, scalarPids) }
                LiveHealthDriveCondition(a[0].text(), signals.sumOf { it.sampleCount }, signals)
            }, series,
            r.rows("bookmarks", 12).map { row ->
                val b = row.jsonObject
                require(b.keys == setOf("id", "offset_ms", "title", "observation", "initial", "collection", "complete", "pre_ms", "post_ms", "dropped", "pre_truncated", "post_truncated", "samples"))
                LiveHealthWarningBookmark(b.integer("id"), b.integer("offset_ms"), b.text("title"), b.text("observation"),
                    b.getValue("initial").bool(), b.text("collection"), b.getValue("complete").bool(), b.integer("pre_ms"), b.integer("post_ms"),
                    b.rows("samples", 512).map { sample ->
                        val a = sample.jsonArray.also { require(it.size == 4) }
                        val pid = a[0].pid().also { require(it in eventPids) }
                        val m = ObdDecoder.catalog.getValue(pid)
                        LiveHealthEventSample(pid, m.name, m.unit, a[1].long(), a[2].nullableNumber(), a[3].text(), generation)
                    }, b.integer("dropped"), generation, instance, b.getValue("pre_truncated").bool(), b.getValue("post_truncated").bool())
            }, r.stringList("retention"), r.integer("dropped_events"),
            series.flatMap { it.points }.minOfOrNull { it.offsetMillis }, series.flatMap { it.points }.maxOfOrNull { it.offsetMillis },
            if (schema != LEGACY_SCHEMA) decodeAssessment(r.getValue("assessment"), schema in setOf(EXTENDED_SCHEMA, SCHEMA, SPLIT_SCHEMA, PAIRED_SCHEMA, WINDOW_SCHEMA), schema in setOf(EXTENDED_SCHEMA, SCHEMA, SPLIT_SCHEMA, PAIRED_SCHEMA)) else null)
        return SavedDrive(root.text("id"), root.integer("started_epoch_ms"), root.integer("saved_epoch_ms"),
            SavedDriveStatus.valueOf(root.text("status")), root.getValue("end_reason").nullableText(), root.getValue("ecu").nullableText(),
            generation, root.text("report"), review, if (hasOrigin) DriveEvidenceOrigin.valueOf(root.text("origin")) else DriveEvidenceOrigin.UNKNOWN).also { validate(it, extended) }
    }

    private fun decodeAssessment(value: JsonElement, withWindows: Boolean, withIntervals: Boolean): DriveHealthAssessment? {
        if (value == JsonNull) return null
        val a = value.jsonObject
        require(a.keys == setOf("pack", "version", "evaluated_ms", "sources", "notes", "findings"))
        return DriveHealthAssessment(a.text("pack"), a.text("version"), a.integer("evaluated_ms"),
            a.rows("findings", 16).map { row ->
                val f = row.jsonObject
                val keys = setOf("check", "title", "disposition", "summary", "evidence", "next", "limits", "first_ms", "last_ms", "samples", "context", "freshness_ms")
                require(f.keys == keys + (if (withWindows) setOf("comparison_window") else emptySet()) +
                    (if (withIntervals) setOf("fuel_intervals") else emptySet()))
                val w = if (withWindows) f.getValue("comparison_window").takeUnless { it == JsonNull }?.jsonArray?.also { require(it.size == 12) } else null
                val window = w?.let { DriveHealthWindow(it[0].long(), it[1].long(), it[2].long().also { n -> require(n in 0..512) }.toInt(), it[3].text(),
                    it[4].number(), it[5].number(), it[6].number(), it[7].number(), it[8].number(), it[9].number(), it[10].number(), it[11].number()) }
                DriveHealthFinding(f.text("check"), f.text("title"), DriveHealthDisposition.valueOf(f.text("disposition")),
                    f.text("summary"), f.stringList("evidence"), f.stringList("next"), f.stringList("limits"),
                    f.getValue("first_ms").nullableLong(), f.getValue("last_ms").nullableLong(),
                    f.integer("samples"), f.text("context"), f.getValue("freshness_ms").nullableLong(), window,
                    if (withIntervals) f.rows("fuel_intervals", 8).map(FuelCorrectionArchive::decode) else emptyList())
            }, a.stringList("sources"), a.stringList("notes"))
    }

    private fun validate(d: SavedDrive, extended: Boolean) {
        require(runCatching { UUID.fromString(d.id).toString() == d.id }.getOrDefault(false))
        require(d.startedAtEpochMillis > 0 && d.savedAtEpochMillis > 0 && d.generation >= 0)
        require(d.sourceEcu == null || Regex("[0-9A-F]{3}|[0-9A-F]{8}").matches(d.sourceEcu))
        require(d.endReason == null || d.endReason.length <= 200)
        require(d.report.startsWith("OPENWRENCH live health monitoring\n") && d.report.length <= 250_000 && '\u0000' !in d.report)
        val r = d.review
        require(r.instanceId >= 0 && r.startedAtMonotonicMillis >= 0 && r.elapsedMillis in 0..MAX_ELAPSED && r.droppedBookmarks >= 0)
        require(r.highlights.size <= 40 && r.retentionNotes.size <= 20 && (r.highlights + r.retentionNotes).all { it.length <= 2_000 })
        r.assessment?.let { a ->
            fun boundedText(value: String, maximum: Int = 2_000) = value.isNotBlank() && value.length <= maximum && '\u0000' !in value
            fun boundedStrings(values: List<String>, maximum: Int) = values.size <= maximum && values.all { boundedText(it) }
            require(boundedText(a.packId, 100) && boundedText(a.packVersion, 100))
            require(a.evaluatedAtOffsetMillis in 0..r.elapsedMillis)
            require(a.findings.size <= 16 && a.findings.map { it.checkId }.distinct().size == a.findings.size)
            require(boundedStrings(a.sourceRefs, 16) && boundedStrings(a.notes, 20))
            a.findings.forEach { f ->
                require(boundedText(f.checkId, 100) && boundedText(f.title, 200) && boundedText(f.summary))
                require(boundedStrings(f.evidence, 16) && boundedStrings(f.nextSteps, 16) && boundedStrings(f.limitations, 16))
                require(f.context.length <= 2_000 && '\u0000' !in f.context && f.sampleCount in 0..1_000_000_000L)
                require(f.firstOffsetMillis == null || f.firstOffsetMillis in 0..a.evaluatedAtOffsetMillis)
                require(f.lastOffsetMillis == null || f.lastOffsetMillis in (f.firstOffsetMillis ?: 0)..a.evaluatedAtOffsetMillis)
                require(f.freshnessMillis == null || f.freshnessMillis in 0..MAX_ELAPSED)
                require((f.firstOffsetMillis == null) == (f.lastOffsetMillis == null))
                if (f.fuelIntervals.isNotEmpty()) {
                    require(a.packId == "standard-obd-drive" && a.packVersion == "3")
                    val bank = when (f.checkId) { "fuel-pattern-bank-1" -> 1; "fuel-pattern-bank-2" -> 2; else -> error("Unexpected paired evidence finding") }
                    FuelCorrectionArchive.validate(f.fuelIntervals, bank, a.evaluatedAtOffsetMillis)
                    require(f.sampleCount == f.fuelIntervals.sumOf { it.pairs.size }.toLong())
                    require(f.firstOffsetMillis == f.fuelIntervals.first().firstOffsetMillis && f.lastOffsetMillis == f.fuelIntervals.last().lastOffsetMillis)
                    require(f.comparisonWindow == null)
                }
                f.comparisonWindow?.let { w ->
                    require(f.checkId in setOf("trim-bank-1", "trim-bank-2") && f.sampleCount > 0)
                    require(w.firstOffsetMillis in 0..a.evaluatedAtOffsetMillis && w.lastOffsetMillis in w.firstOffsetMillis..a.evaluatedAtOffsetMillis)
                    require(w.lastOffsetMillis - w.firstOffsetMillis in 30_000..60_000 && w.sampleCount in 10..512)
                    require(w.condition == f.context && boundedText(w.condition, 500))
                    if (f.disposition == DriveHealthDisposition.REVIEW) require(w.trimMinimum > 10 || w.trimMaximum < -10)
                    listOf(w.trimMinimum to w.trimMaximum, w.rpmMinimum to w.rpmMaximum, w.loadMinimum to w.loadMaximum,
                        w.coolantMinimum to w.coolantMaximum).forEach { (lo, hi) -> require(lo.isFinite() && hi.isFinite() && lo <= hi) }
                }
                if (f.disposition in setOf(DriveHealthDisposition.REASSURING, DriveHealthDisposition.REVIEW, DriveHealthDisposition.OBSERVED))
                    require(f.sampleCount > 0 && f.evidence.isNotEmpty() && f.lastOffsetMillis != null)
            }
        }
        val allowedTelemetry = if (extended) telemetryPids else scalarPids
        fun signals(values: List<LiveHealthDriveSignal>, allowed: Set<Int>, maximum: Int) {
            require(values.size <= maximum && values.map { it.pid }.distinct().size == values.size)
            values.forEach {
                val m = ObdDecoder.catalog.getValue(it.pid)
                require(it.pid in allowed && it.name == m.name && it.unit == m.unit && it.sampleCount in 1..1_000_000_000L)
                require(listOf(it.firstValue, it.lastValue, it.minimum, it.maximum).all { value ->
                    if (extended) validTelemetryValue(it.pid, value) else value.isFinite()
                })
                require(it.minimum <= it.maximum && it.firstValue in it.minimum..it.maximum && it.lastValue in it.minimum..it.maximum)
                require(it.firstOffsetMillis in 0..r.elapsedMillis && it.lastOffsetMillis in it.firstOffsetMillis..r.elapsedMillis)
            }
        }
        signals(r.signals, allowedTelemetry, if (extended) telemetryPids.size else 12)
        require(r.conditions.size <= 4 && r.conditions.map { it.condition }.distinct().size == r.conditions.size)
        r.conditions.forEach { require(it.condition in setOf("Moving", "Stationary with engine running", "Engine stopped", "Unknown")); signals(it.signals, scalarPids, 12); require(it.sampleCount == it.signals.sumOf { s -> s.sampleCount }) }
        require(r.series.size <= (if (extended) telemetryPids.size else 9) && r.series.map { it.pid }.distinct().size == r.series.size)
        require(!extended || r.series.sumOf { it.points.size.toLong() } <= MAX_EXTENDED_POINTS)
        r.series.forEach { s ->
            val m = ObdDecoder.catalog.getValue(s.pid)
            require(s.pid in allowedTelemetry && s.name == m.name && s.unit == m.unit && s.maximumLinkGapMillis in 0..60_000)
            require(s.points.size <= 1_200 && s.points.zipWithNext().all { (a,b) -> a.offsetMillis <= b.offsetMillis })
            require(s.points.all { it.offsetMillis in 0..r.elapsedMillis && (it.value == null || if (extended) validTelemetryValue(s.pid, it.value) else it.value.isFinite()) })
        }
        require(r.bookmarks.size <= 12 && r.bookmarks.map { it.id }.distinct().size == r.bookmarks.size)
        r.bookmarks.forEach { b ->
            require(b.id > 0 && b.offsetMillis in 0..r.elapsedMillis && b.sessionGeneration == d.generation && b.instanceId == r.instanceId)
            require(b.title.length <= 200 && b.observation.length <= 1_000 && b.collectionStatus.length <= 1_000)
            require(b.preWindowMillis in 0..15_000 && b.postWindowMillis in 0..15_000 && b.samplesDropped >= 0 && b.samples.size <= 512)
            require(b.samples.zipWithNext().all { (a,c) -> a.offsetMillis <= c.offsetMillis })
            b.samples.forEach { s ->
                val m = ObdDecoder.catalog.getValue(s.pid)
                require(s.pid in eventPids && s.name == m.name && s.unit == m.unit && s.sessionGeneration == d.generation)
                require(s.offsetMillis in maxOf(0, b.offsetMillis - 15_000)..minOf(r.elapsedMillis, b.offsetMillis + 15_000))
                require(s.value == null || s.value.isFinite()); require(s.displayValue.length <= 1_000 && '\u0000' !in s.displayValue)
            }
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).text()
    private fun JsonObject.integer(key: String) = getValue(key).long()
    private fun JsonObject.rows(key: String, maximum: Int) = getValue(key).jsonArray.also { require(it.size <= maximum) }
    private fun JsonObject.stringList(key: String) = rows(key, 40).map { it.text() }
    private fun JsonElement.text() = jsonPrimitive.also { require(it.isString) }.content
    private fun JsonElement.nullableText() = if (this == JsonNull) null else text()
    private fun JsonElement.long() = jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonElement.nullableLong() = if (this == JsonNull) null else long()
    private fun JsonElement.pid() = long().also { require(it in 0..255) }.toInt()
    private fun JsonElement.number() = jsonPrimitive.also { require(!it.isString) }.double.also { require(it.isFinite()) }
    private fun JsonElement.nullableNumber() = if (this == JsonNull) null else number()
    private fun JsonElement.bool() = jsonPrimitive.also { require(!it.isString) }.boolean
}

/** Per-drive atomic replacement. Unknown/corrupt archives block rotation and are preserved. */
class DriveHistoryStore(private val root: File,
    private val maximumDrives: Int = MAX_DRIVES, private val maximumBytes: Long = MAX_TOTAL_BYTES,
    private val deleteFile: (File) -> Boolean = { it.delete() }) {
    companion object { const val MAX_DRIVES = 20; const val MAX_TOTAL_BYTES = 25_000_000L }
    init { require(maximumDrives in 1..MAX_DRIVES && maximumBytes in 1..MAX_TOTAL_BYTES) }
    private val directory get() = File(root, "drive-history-v1")
    private var catalog: List<SavedDriveSummary>? = null
    private fun file(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(directory, "$id.json")
    }
    private fun summary(d: SavedDrive, bytes: Long) = SavedDriveSummary(d.id, d.startedAtEpochMillis, d.savedAtEpochMillis,
        d.status, d.review.elapsedMillis, d.review.bookmarks.size, bytes)
    @Synchronized fun list(): List<SavedDriveSummary> {
        catalog?.let { return it }
        val allFiles = directory.listFiles().orEmpty()
        // A killed process may leave an uncommitted temporary write. The previous JSON is
        // authoritative; clean only this format's UUID-named temporary files.
        allFiles.filter { it.name.endsWith(".json.tmp") && runCatching {
            UUID.fromString(it.name.removeSuffix(".json.tmp")).toString() == it.name.removeSuffix(".json.tmp")
        }.getOrDefault(false) }.forEach { check(it.delete()) { "Could not remove an interrupted temporary drive write" } }
        val files = allFiles.filter { it.name.endsWith(".json") }
        require(files.size <= MAX_DRIVES + 4) { "Drive history contains too many files; existing files preserved" }
        require(files.sumOf { it.length() } <= MAX_TOTAL_BYTES + 4L * DriveHistoryCodec.MAX_FILE_BYTES) { "Drive history exceeds storage bounds" }
        return files.map { f -> val d = readFile(f); require(f == file(d.id)); summary(d, f.length()) }
            .sortedByDescending { it.startedAtEpochMillis }.also { catalog = it }
    }
    @Synchronized fun read(id: String): SavedDrive = try { readFile(file(id)) }
        catch (error: Exception) { catalog = null; throw error }
    private fun readFile(f: File): SavedDrive {
        require(f.isFile && f.length() <= DriveHistoryCodec.MAX_FILE_BYTES) { "Saved drive unavailable or too large" }
        return DriveHistoryCodec.decode(f.readText(Charsets.UTF_8))
    }
    @Synchronized fun upsert(drive: SavedDrive): List<SavedDriveSummary> {
        val previous = list() // Never overwrite a corrupt/unknown store.
        val content = DriveHistoryCodec.encode(drive)
        require(content.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "Drive exceeds storage budget" }
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create drive history directory" }
        val destination = file(drive.id)
        val temporary = File(directory, "${drive.id}.json.tmp")
        try {
            if (destination.exists()) require(readFile(destination).id == drive.id)
            temporary.outputStream().use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8)); stream.flush()
                (stream as java.io.FileOutputStream).fd.sync()
            }
            check(temporary.renameTo(destination)) { "Could not replace drive checkpoint" }
            val entries = (previous.filterNot { it.id == drive.id } + summary(drive, destination.length())).toMutableList()
            val victims = mutableListOf<SavedDriveSummary>()
            // Always retain the drive just saved, even if the wall clock was adjusted backwards.
            while (entries.size > maximumDrives || entries.sumOf { it.bytes } > maximumBytes) {
                val oldest = entries.filter { it.id != drive.id }.minByOrNull { it.startedAtEpochMillis } ?: error("Drive archive exceeds storage budget")
                victims += oldest; entries.remove(oldest)
            }
            // Validate the entire planned rotation before deleting even the first healthy file.
            victims.forEach { require(readFile(file(it.id)).id == it.id) }
            victims.forEach { check(deleteFile(file(it.id))) { "Could not rotate old drive history" } }
            return entries.sortedByDescending { it.startedAtEpochMillis }.also { catalog = it }
        } catch (error: Exception) {
            // A rename or some deletions may already have succeeded. Never retry from stale metadata.
            catalog = null
            throw error
        } finally { temporary.delete() }
    }
    @Synchronized fun delete(id: String): List<SavedDriveSummary> {
        val previous = list()
        require(previous.any { it.id == id }) { "Saved drive not found" }
        check(deleteFile(file(id))) { "Could not delete saved drive" }
        return previous.filterNot { it.id == id }.also { catalog = it }
    }
}
