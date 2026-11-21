package org.openwrench.frontend.android

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Availability and retention are separate: a source drive may be readable but not retained. */
data class DiagnosisDriveEvidence(val drive: SavedDrive?, val warning: String? = null)

/** App-private evidence writer. The caller serializes it away from the adapter reader. */
class DiagnosticSessionStore(
    private val root: File,
    private val maximumSessionBytes: Long = MAX_SESSION_BYTES,
    private val maximumSessions: Int = MAX_SESSIONS,
    private val driveProvider: ((String) -> SavedDrive?)? = null,
    private val appendBytes: (RandomAccessFile, ByteArray) -> Unit = { file, bytes -> file.write(bytes) },
) {
    companion object {
        const val MAX_SESSION_BYTES = 20L * 1024 * 1024
        const val MAX_SESSIONS = 50
        internal const val CSV_HEADER = "diagnosis_id,segment_id,session_generation,source_ecu,receipt_epoch_ms_from_segment_start,elapsed_ms,pid,value,text_value,unit,availability\n"
    }
    private val directory get() = File(root, "diagnoses-v1")
    private val ownedRecording = mutableSetOf<String>()
    private data class Count(val rows: Long = 0, val lastOffset: Long = 0)
    private data class CsvState(val length: Long, val validLength: Long, val modified: Long,
        val identities: List<List<Any?>>, val counts: Map<String, Count>) {
        val trailingBytes get() = length - validLength
    }
    private val csvStates = mutableMapOf<String, CsvState>()
    init {
        require(maximumSessions in 1..MAX_SESSIONS)
        require(maximumSessionBytes in 2_048..MAX_SESSION_BYTES)
    }
    private fun folder(id: String): File { DiagnosisCodec.uuid(id); return File(directory, id) }
    private fun manifest(id: String) = File(folder(id), "manifest.json")
    private fun csv(id: String) = File(folder(id), "readings.csv")
    private fun evidence(id: String) = File(folder(id), "drives")
    private fun metadata(id: String): DiagnosisRecord {
        val f = manifest(id)
        require(f.isFile && f.length() <= DiagnosisCodec.MAX_METADATA_BYTES) { "Diagnosis metadata is unavailable or too large" }
        return DiagnosisCodec.decode(f.readText(Charsets.UTF_8)).also { require(it.id == id) }
    }
    private fun size(id: String): Long = folder(id).walkTopDown().filter(File::isFile).sumOf { it.length() }
    private fun identities(record: DiagnosisRecord) = record.segments.map {
        listOf(it.id, it.generation, it.sourceEcu, it.startedAtEpochMillis, it.startedAtMonotonicMillis)
    }
    private fun csvIdentities(record: DiagnosisRecord) = identities(record).mapIndexed { index, values ->
        values + record.segments[index].endedAtOffsetMillis
    }

    @Synchronized fun create(record: DiagnosisRecord) {
        val bytes = DiagnosisCodec.encode(record).toByteArray(Charsets.UTF_8)
        require(list().size < maximumSessions) { "Diagnosis history is full (50 sessions). Existing diagnoses were kept." }
        require(!folder(record.id).exists()) { "Diagnosis already exists" }
        require(bytes.size + CSV_HEADER.toByteArray().size < maximumSessionBytes) { "Diagnosis storage limit reached" }
        check(folder(record.id).mkdirs()) { "Cannot create diagnosis directory" }
        atomicWrite(csv(record.id), CSV_HEADER.toByteArray(Charsets.UTF_8))
        atomicWrite(manifest(record.id), bytes)
        if (record.status == DiagnosisStatus.RECORDING) ownedRecording += record.id
    }

    @Synchronized fun save(record: DiagnosisRecord) {
        val previous = metadata(record.id)
        val durable = csvState(previous) // A corrupt receipt file is preserved and blocks further writes.
        require(durable.trailingBytes == 0L) { "An incomplete final receipt was preserved. Export the recoverable evidence and start a new diagnosis." }
        require(record.startedAtEpochMillis == previous.startedAtEpochMillis)
        require(record.segments.size >= previous.segments.size)
        require(identities(record).take(previous.segments.size) == identities(previous)) { "Recorded segment identity cannot change" }
        previous.segments.forEachIndexed { index, old ->
            val next = record.segments[index]
            require(old.endedAtOffsetMillis == null || next.endedAtOffsetMillis == old.endedAtOffsetMillis)
            require(old.driveId == null || old.driveId == next.driveId)
            require(next.endedAtOffsetMillis == null || next.endedAtOffsetMillis >= (durable.counts[old.id]?.lastOffset ?: 0)) {
                "A segment cannot end before its last durable receipt"
            }
        }
        val bytes = DiagnosisCodec.encode(record).toByteArray(Charsets.UTF_8)
        require(size(record.id) - manifest(record.id).length() + bytes.size <= maximumSessionBytes) { "Diagnosis storage limit reached; existing evidence was kept" }
        atomicWrite(manifest(record.id), bytes)
        if (record.status == DiagnosisStatus.RECORDING) ownedRecording += record.id else ownedRecording -= record.id
        preserveDrives(record)
    }

    @Synchronized fun list(): List<DiagnosisRecord> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Diagnosis history is not a directory" }
        val entries = directory.listFiles() ?: error("Diagnosis history cannot be listed")
        require(entries.size <= MAX_SESSIONS) { "Diagnosis history exceeds its session limit" }
        return entries.map { entry ->
            val validId = runCatching { DiagnosisCodec.uuid(entry.name) }.isSuccess
            val id = if (validId) entry.name else UUID.nameUUIDFromBytes(
                ("openwrench-unrecognized-diagnosis-entry:" + entry.name).toByteArray(Charsets.UTF_8)).toString()
            try {
                require(validId && entry.isDirectory)
                read(id)
            } catch (_: Exception) {
                // History must expose damaged entries without letting one interrupted create hide
                // every healthy diagnosis. These summaries are never persisted as repaired data.
                val known = if (validId) runCatching { metadata(id) }.getOrNull() else null
                val warning = if (known != null)
                    "Recorded metadata only: receipt evidence is unavailable or corrupt. Original files are preserved; this diagnosis cannot resume or export until its evidence is recovered."
                else "Incomplete or unreadable diagnosis archive. Original files are preserved; no recording or diagnosis outcome can be inferred."
                known?.copy(status = DiagnosisStatus.INTERRUPTED,
                    note = listOfNotNull(known.note?.take(1_500), warning).joinToString("\n"))
                    ?: DiagnosisRecord(id, entry.lastModified().coerceAtLeast(1), entry.lastModified().coerceAtLeast(1),
                        DiagnosisStatus.INTERRUPTED, note = warning)
            }
        }.sortedByDescending { it.updatedAtEpochMillis }
    }

    @Synchronized fun read(id: String): DiagnosisRecord {
        val r = metadata(id)
        require(size(id) <= maximumSessionBytes) { "Diagnosis exceeds its storage limit" }
        val receiptState = csvState(r)
        val counts = receiptState.counts
        val truncated = receiptState.trailingBytes > 0
        val interrupted = truncated || r.status == DiagnosisStatus.RECORDING && id !in ownedRecording
        return r.copy(status = if (interrupted) DiagnosisStatus.INTERRUPTED else r.status,
            note = if (truncated) listOfNotNull(r.note?.take(1_500),
                "Incomplete final CSV row: ${receiptState.trailingBytes} trailing bytes preserved on device and excluded from export. Only validated complete receipts were recovered; export and start a new diagnosis.").joinToString("\n") else r.note,
            segments = r.segments.map { s ->
                val count = counts[s.id] ?: Count()
                s.copy(sampleCount = count.rows,
                    droppedSamples = s.droppedSamples + if (truncated && s.id == r.segments.lastOrNull()?.id) 1 else 0,
                    endedAtOffsetMillis = if (interrupted && s.endedAtOffsetMillis == null) count.lastOffset else s.endedAtOffsetMillis,
                    endReason = if (interrupted && s.endedAtOffsetMillis == null)
                        "App process interrupted; recovered only durable receipt rows" else s.endReason)
            })
    }

    /** Opens the best available validated evidence, without silently preferring a stale cache. */
    @Synchronized fun readDrive(diagnosisId: String, driveId: String): SavedDrive? =
        readDriveEvidence(diagnosisId, driveId).drive

    @Synchronized fun readDriveEvidence(diagnosisId: String, driveId: String): DiagnosisDriveEvidence {
        val record = metadata(diagnosisId)
        DiagnosisCodec.uuid(driveId)
        require(record.segments.any { it.driveId == driveId }) { "Drive is not linked to this diagnosis" }
        return retainDrive(record, driveId)
    }

    private fun validateDrive(record: DiagnosisRecord, id: String, drive: SavedDrive) {
        require(drive.id == id && record.segments.any {
            it.driveId == id && it.generation == drive.generation && it.sourceEcu == drive.sourceEcu
        }) { "Linked drive provenance does not match the segment" }
        // Apply the complete codec bounds before returning provider-supplied evidence to the UI.
        DriveHistoryCodec.encode(drive)
    }

    /** On any failed append, roll back to the durable length before returning the failure. */
    @Synchronized fun appendSamples(id: String, segmentId: String, samples: List<DiagnosisSample>) {
        require(samples.size <= 512) { "Receipt batch exceeds its bound" }
        if (samples.isEmpty()) return
        val record = metadata(id)
        require(record.status == DiagnosisStatus.RECORDING && id in ownedRecording) { "Diagnosis is not recording in this process" }
        val segment = record.segments.single { it.id == segmentId }
        require(segment.endedAtOffsetMillis == null) { "Segment has ended" }
        val before = csvState(record)
        require(before.trailingBytes == 0L) { "An incomplete final receipt was preserved. Export the recoverable evidence and start a new diagnosis." }
        val previous = before.counts[segmentId] ?: Count()
        samples.forEach(DiagnosisSample::validate)
        require(samples.first().elapsedMillis >= previous.lastOffset && samples.zipWithNext().all { (a, b) -> a.elapsedMillis <= b.elapsedMillis })
        val bytes = samples.joinToString("") { sample -> row(listOf(id, segment.id, segment.generation, segment.sourceEcu.orEmpty(),
            segment.startedAtEpochMillis + sample.elapsedMillis, sample.elapsedMillis,
            "01" + sample.pid.toString(16).uppercase().padStart(2, '0'), sample.value?.toString().orEmpty(),
            sample.textValue.orEmpty(), sample.unit, sample.availability)) }.toByteArray(Charsets.UTF_8)
        // Reserve room for terminal metadata so a full recording can still be closed honestly.
        val reserve = minOf(DiagnosisCodec.MAX_METADATA_BYTES.toLong(), maximumSessionBytes / 4)
        require(size(id) + bytes.size + reserve <= maximumSessionBytes) { "Diagnosis recording limit reached; no rows from this batch were written" }
        val f = csv(id)
        try {
            RandomAccessFile(f, "rw").use { file ->
                require(file.length() == before.length) { "Receipt file changed during append" }
                file.seek(before.length)
                try { appendBytes(file, bytes); file.fd.sync() }
                catch (failure: Exception) {
                    try { file.setLength(before.length); file.fd.sync() }
                    catch (rollback: Exception) { failure.addSuppressed(rollback); throw IllegalStateException("Receipt write and rollback failed; evidence requires recovery", failure) }
                    throw failure
                }
            }
            csvStates[id] = CsvState(f.length(), f.length(), f.lastModified(), csvIdentities(record),
                before.counts + (segmentId to Count(previous.rows + samples.size, samples.last().elapsedMillis)))
        } catch (failure: Exception) { csvStates.remove(id); throw failure }
    }

    /** Revalidate durable receipts even if external edits preserved length and mtime. */
    @Synchronized internal fun readForSharing(id: String, expected: DiagnosisRecord?): DiagnosisRecord {
        csvStates.remove(id)
        val saved = read(id)
        check(expected == null || saved == expected) {
            "This diagnosis has unsaved changes. Retry saving or reopen its saved evidence before exporting."
        }
        return saved
    }

    /** Includes linked frozen reports and chart traces; missing older drive evidence stays explicit. */
    @Synchronized fun exportZip(id: String, output: OutputStream, expected: DiagnosisRecord? = null) {
        val record = readForSharing(id, expected)
        val driveIds = record.segments.mapNotNull { it.driveId }.distinct()
        val missing = mutableListOf<String>()
        val aiDrives = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val driveHashes = mutableMapOf<String, List<Byte>>()
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toList()
        // Stream one drive at a time so long reports do not hold every decoded
        // drive plus a second concatenated copy in Android's heap.
        val zip = ZipOutputStream(output)
        fun text(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        text("manifest.json", DiagnosisCodec.encode(record))
        zip.putNextEntry(ZipEntry("report.txt"))
        val reportWriter = zip.writer(Charsets.UTF_8)
        reportWriter.write(record.reportText())
        if (record.segments.any { it.driveId == null }) reportWriter.write("Some segments have no linked drive report; their CSV receipts remain available.\n")
        driveIds.forEach { driveId ->
            val evidence = readDriveEvidence(id, driveId)
            aiDrives += DiagnosisAiHandoff.driveReference(driveId, evidence)
            evidence.warning?.let { reportWriter.write("\nDrive $driveId: $it\n") }
            val drive = evidence.drive
            if (drive == null) missing += driveId
            else {
                driveHashes[driveId] = hash(DriveHistoryCodec.encode(drive))
                reportWriter.write("\nRecorded drive assessment and evidence\n")
                reportWriter.write(drive.copyText())
            }
        }
        if (missing.isNotEmpty()) reportWriter.write("Linked drive evidence unavailable: ${missing.joinToString()}. No assessment or chart has been reconstructed from missing records.\n")
        reportWriter.flush()
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("readings.csv"))
        prefix(csv(id), csvState(metadata(id)).validLength).use { it.copyTo(zip) }
        zip.closeEntry()
        driveIds.filterNot { it in missing }.forEach { driveId ->
            val drive = checkNotNull(readDrive(id, driveId)) { "Linked drive disappeared during export" }
            val encoded = DriveHistoryCodec.encode(drive)
            check(hash(encoded) == driveHashes[driveId]) { "Linked drive changed during export. Retry exporting the available evidence." }
            text("drives/${drive.id}.json", encoded)
            text("charts/${drive.id}.csv", buildString {
                append(row(listOf("drive_id", "pid", "elapsed_ms", "value", "unit", "break_before")))
                drive.review.series.forEach { series -> series.points.forEach { p ->
                    append(row(listOf(drive.id, "01" + series.pid.toString(16).uppercase().padStart(2, '0'),
                        p.offsetMillis, p.value?.toString().orEmpty(), series.unit.orEmpty(), p.breakBefore)))
                } }
            })
        }
        text(DiagnosisAiHandoff.INDEX_PATH, DiagnosisAiHandoff.index(record, aiDrives))
        text(DiagnosisAiHandoff.REQUEST_PATH, DiagnosisAiHandoff.requestText)
        zip.finish(); zip.flush()
    }

    private fun preserveDrives(record: DiagnosisRecord) {
        record.segments.mapNotNull { it.driveId }.distinct().forEach { retainDrive(record, it) }
    }

    private fun retainDrive(record: DiagnosisRecord, id: String): DiagnosisDriveEvidence {
        val destination = File(evidence(record.id), "$id.json")
        val previous = if (destination.exists()) {
            require(destination.isFile && destination.length() <= DriveHistoryCodec.MAX_FILE_BYTES)
            DriveHistoryCodec.decode(destination.readText(Charsets.UTF_8)).also { validateDrive(record, id, it) }
        } else null
        val source = runCatching { driveProvider?.invoke(id) }.getOrNull()?.also { validateDrive(record, id, it) }
        val usePrevious = previous != null && (source == null ||
            previous.status != SavedDriveStatus.CHECKPOINT && source.status == SavedDriveStatus.CHECKPOINT ||
            previous.review.elapsedMillis > source.review.elapsedMillis)
        val best = if (usePrevious) previous else source
        var warning: String? = null
        if (best != null && best != previous) {
            val bytes = DriveHistoryCodec.encode(best).toByteArray(Charsets.UTF_8)
            // Terminal metadata is already durable. Reserve is only needed while a
            // diagnosis may still collect receipts or needs its terminal metadata.
            val reserve = if (record.status == DiagnosisStatus.COMPLETED) 0L
                else minOf(DiagnosisCodec.MAX_METADATA_BYTES.toLong(), maximumSessionBytes / 4)
            if (size(record.id) - destination.length() + bytes.size + reserve <= maximumSessionBytes) {
                check(evidence(record.id).isDirectory || evidence(record.id).mkdirs())
                atomicWrite(destination, bytes)
            } else {
                // Do not delete the old durable copy or truncate evidence to make room.
                // Return the newer source while it exists, explicitly without a retention promise.
                warning = "Storage limit: latest drive evidence is available in individual drive history but could not be retained in this diagnosis. Export it now; later history rotation may leave only an older checkpoint or no drive evidence."
            }
        }
        if (best?.status == SavedDriveStatus.CHECKPOINT) warning = listOfNotNull(warning,
            "Partial checkpoint only: terminal drive evidence is unavailable. This is not the final drive assessment; later receipts and findings may be missing.").joinToString("\n")
        return DiagnosisDriveEvidence(best, warning)
    }

    private fun csvState(record: DiagnosisRecord): CsvState {
        val f = csv(record.id)
        require(f.isFile && f.length() <= maximumSessionBytes) { "Diagnosis receipt file is missing or too large" }
        val identity = csvIdentities(record)
        csvStates[record.id]?.let { if (it.length == f.length() && it.modified == f.lastModified() && it.identities == identity) return it }
        val counts = mutableMapOf<String, Count>()
        val segments = record.segments.associateBy { it.id }
        // Recover only the validated complete prefix; leave incomplete bytes untouched on disk.
        val validLength = RandomAccessFile(f, "r").use { file ->
            val start = maxOf(0L, file.length() - 16_000)
            val tail = ByteArray((file.length() - start).toInt())
            file.seek(start); file.readFully(tail)
            val lastNewline = tail.indexOfLast { it == '\n'.code.toByte() }
            require(lastNewline >= 0) { "Receipt file has no complete row within its row-size bound; existing evidence was preserved" }
            start + lastNewline + 1
        }
        prefix(f, validLength).bufferedReader(Charsets.UTF_8).use { input ->
            require(input.readLine() + "\n" == CSV_HEADER) { "Unsupported diagnosis CSV" }
            input.lineSequence().forEach { line ->
                require(line.length <= 16_000)
                val cols = parseRow(line)
                require(cols.size == 11 && cols[0] == record.id)
                val segment = segments[cols[1]] ?: error("Receipt refers to an unknown segment")
                require(cols[2] == segment.generation.toString() && cols[3] == segment.sourceEcu.orEmpty())
                require(Regex("01[0-9A-F]{2}").matches(cols[6]))
                val sample = DiagnosisSample(cols[5].toLong(), cols[6].takeLast(2).toInt(16),
                    cols[7].takeIf(String::isNotEmpty)?.toDouble(), cols[8].takeIf(String::isNotEmpty), cols[9], cols[10])
                sample.validate()
                require(cols[4] == (segment.startedAtEpochMillis + sample.elapsedMillis).toString())
                val count = counts[segment.id] ?: Count()
                require(sample.elapsedMillis >= count.lastOffset)
                require(segment.endedAtOffsetMillis == null || sample.elapsedMillis <= segment.endedAtOffsetMillis)
                counts[segment.id] = Count(count.rows + 1, sample.elapsedMillis)
            }
        }
        return CsvState(f.length(), validLength, f.lastModified(), identity, counts).also { csvStates[record.id] = it }
    }

    private fun prefix(file: File, length: Long): InputStream = object : FilterInputStream(file.inputStream()) {
        private var remaining = length
        override fun read(): Int {
            if (remaining == 0L) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (remaining == 0L) return -1
            return `in`.read(bytes, offset, minOf(count.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        try {
            temporary.outputStream().use { file -> file.write(bytes); file.flush(); file.fd.sync() }
            check(temporary.renameTo(destination)) { "Could not replace diagnosis evidence" }
        } finally { temporary.delete() }
    }

    private fun row(values: List<Any?>): String = values.joinToString(",") { value ->
        val text = value?.toString().orEmpty()
        "\"" + text.replace("\"", "\"\"") + "\""
    } + "\n"

    private fun parseRow(line: String): List<String> {
        val result = mutableListOf<String>()
        var position = 0
        while (position < line.length) {
            require(line[position++] == '"')
            val value = StringBuilder()
            var closed = false
            while (position < line.length) {
                val c = line[position++]
                if (c != '"') value.append(c)
                else if (position < line.length && line[position] == '"') { value.append('"'); position++ }
                else { closed = true; break }
            }
            require(closed)
            result += value.toString()
            if (position < line.length) { require(line[position++] == ','); require(position < line.length) }
        }
        return result
    }
}
