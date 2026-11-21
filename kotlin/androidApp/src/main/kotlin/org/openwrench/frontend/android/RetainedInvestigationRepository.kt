package org.openwrench.frontend.android

import org.openwrench.core.*
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

internal data class RetainedSnapshot(val document: RetainedInvestigation, val byteToken: String)
internal data class OriginalIdentity(val name: String, val size: Long, val digest: String?, val problem: String? = null)
internal enum class RetainedStorageStatus { EMPTY, READY, RECOVERY_REQUIRED, BLOCKED }
internal data class RetainedInspection(val id: String, val status: RetainedStorageStatus, val snapshot: RetainedSnapshot?,
    val committed: OriginalIdentity?, val pending: OriginalIdentity?, val recoverable: Boolean, val problem: String?)
internal data class RetainedListing(val id: String?, val originals: List<OriginalIdentity>, val status: RetainedStorageStatus,
    val complaint: String?, val headRevision: String?, val problem: String?)
internal data class AttachmentIntent(val requestId: String, val revisionId: String, val sequence: Int, val recordedAtEpochMillis: Long,
    val author: String, val reason: String, val vehicleAssertion: InvestigationVehicleAssertion, val provenance: String) {
    fun event(case: DiagnosticCase) = CombinedEvent.Attachment(requestId, revisionId, sequence, recordedAtEpochMillis, author, reason, case, vehicleAssertion, provenance)
}
/** Implementations must open NEW destinations, not overwrite files. No production export destination is supplied. */
internal fun interface OriginalExportSink {
    fun openNew(name: String): OutputStream
    fun receipts(): List<InvestigationExportReceipt> = emptyList()
}
internal sealed interface RetainedAction {
    data object ListNew : RetainedAction
    data object CaseChoices : RetainedAction
    data class Open(val id: String) : RetainedAction
    data class Create(val id: String, val complaint: CombinedEvent.Ordinary) : RetainedAction
    data class Append(val id: String, val expectedToken: String, val event: CombinedEvent) : RetainedAction
    data class Attach(val id: String, val expectedToken: String, val selected: SelectedCaseRevision, val intent: AttachmentIntent) : RetainedAction
    data class Preview(val id: String, val expectedToken: String, val request: CombinedPreviewRequest) : RetainedAction
    data class InspectRecovery(val id: String) : RetainedAction
    data class Recover(val id: String, val observedCommitted: String?, val observedPending: String) : RetainedAction
    data class ExportOriginals(val observed: List<OriginalIdentity>, val sink: OriginalExportSink) : RetainedAction
}
internal enum class RetainedFailure { VALIDATION, STALE_DOCUMENT, STALE_SOURCE, STORAGE_BLOCKED, QUOTA, SOURCE_UNAVAILABLE, EXPORT_INCOMPLETE, IO_OR_ACK_UNKNOWN }
internal sealed interface RetainedResult {
    data class Listing(val entries: List<RetainedListing>) : RetainedResult
    data class Cases(val choices: List<RetainedCaseChoice>) : RetainedResult
    data class Opened(val inspection: RetainedInspection, val replayed: Boolean = false) : RetainedResult
    data class Previewed(val inspection: RetainedInspection, val submitted: RetainedAction.Preview, val result: CombinedPreviewResult) : RetainedResult
    data class Exported(val originals: List<OriginalIdentity>, val destinations: List<InvestigationExportReceipt> = emptyList()) : RetainedResult
    data class Rejected(val failure: RetainedFailure, val detail: String, val submitted: RetainedAction,
        val inspection: RetainedInspection? = null, val issues: List<CombinedFieldIssue> = emptyList(),
        val ioFailure: InvestigationIoFailure? = null, val destinations: List<InvestigationExportReceipt> = emptyList()) : RetainedResult
}

/** Disabled authority candidate for NEW IDs only. Never reads investigations-v1 or any installed histories. */
internal class RetainedStorageUnavailable(message: String) : IllegalStateException(message)
internal class RetainedInvestigationRepository(private val root: File, private val source: RetainedCaseSource,
    private val files: OutcomeSandboxFiles = OutcomeSandboxFiles(),
    private val enumerate: (File) -> Array<File>? = { it.listFiles() }) {
    companion object {
        const val DIRECTORY = "retained-investigations-candidate-v1"
        const val MAX_DOCUMENTS = 10
        const val MAX_ORIGINAL_BYTES = 50_000_000L // streamed hash/export; decoded documents remain <=25MB
        private val lock = Any()
    }
    private val directory get() = File(root, DIRECTORY)
    private fun path(id: String, pending: Boolean = false): File {
        DiagnosticInvestigation.validId(id)
        return File(directory, "$id.${if (pending) "pending" else "json"}")
    }
    // API24-compatible conservative path checks. A caller-owned canonical root is required;
    // external path replacement/cross-process writers are not qualified by this candidate.
    private fun canonical(file: File) = file.canonicalFile == file.absoluteFile.normalize()
    private fun safeDirectory() {
        require(canonical(root) && canonical(directory)) { "Candidate root must be canonical; no symbolic-link traversal" }
        val children = if (root.exists()) enumerate(root)
            ?: throw RetainedStorageUnavailable("Candidate root listing unavailable; absence is not established") else emptyArray()
        val present = children.any { it.name == DIRECTORY }
        require(!present || directory.isDirectory) { "Candidate namespace is not a directory; preserved" }
    }
    private fun entries(): List<File> {
        safeDirectory()
        if (!directory.exists()) return emptyList()
        val entries = enumerate(directory) ?: throw RetainedStorageUnavailable("Candidate namespace listing unavailable; originals may exist")
        require(entries.size <= MAX_DOCUMENTS * 2 + 1) { "Too many candidate originals; preserve directory" }
        return entries.sortedBy { it.name }
    }
    private fun regular(file: File) = file.isFile && canonical(file)
    private fun stream(file: File, output: OutputStream? = null): Pair<Long, String> {
        require(regular(file) && file.length() <= MAX_ORIGINAL_BYTES) { "Original is not a bounded regular file; preserved" }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                count += n; require(count <= MAX_ORIGINAL_BYTES)
                hash.update(chunk, 0, n); output?.write(chunk, 0, n)
            }
        }
        return count to hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun identity(file: File): OriginalIdentity = try {
        val (size, hash) = stream(file); OriginalIdentity(file.name, size, hash)
    } catch (error: Exception) { OriginalIdentity(file.name, file.length(), null, error.message ?: "Original unavailable; preserved") }
    private fun snapshot(file: File): RetainedSnapshot {
        require(regular(file) && file.length() <= InvestigationCodec.MAX_BYTES)
        val bytes = file.inputStream().use { boundedBytes(it, InvestigationCodec.MAX_BYTES) }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return RetainedSnapshot(RetainedInvestigationCodec.decode(text), investigationDigest(bytes))
    }
    private fun extension(old: RetainedInvestigation?, next: RetainedInvestigation) = if (old == null)
        next.revisions.size == 1 else next.id == old.id && (next == old ||
        next.revisions.size == old.revisions.size + 1 && next.revisions.dropLast(1) == old.revisions)
    private fun inspect(id: String): RetainedInspection {
        safeDirectory()
        val committed = path(id); val pending = path(id, true)
        val names = entries().map { it.name }.toSet() // one successful listing; unavailable never means absent
        val a = if (committed.name in names) identity(committed) else null
        val b = if (pending.name in names) identity(pending) else null
        if (a == null && b == null) return RetainedInspection(id, RetainedStorageStatus.EMPTY, null, null, null, false, null)
        val current = if (a != null) runCatching { snapshot(committed).also { require(it.document.id == id && it.byteToken == a.digest) } } else null
        val old = current?.getOrNull()
        if (current?.isFailure == true) return RetainedInspection(id, RetainedStorageStatus.BLOCKED, null, a, b, false,
            current.exceptionOrNull()?.message ?: "Invalid committed original; preserved")
        if (b == null) return RetainedInspection(id, RetainedStorageStatus.READY, old, a, null, false, null)
        val candidate = runCatching { snapshot(pending).also { require(it.document.id == id && it.byteToken == b.digest && extension(old?.document, it.document)) } }
        return RetainedInspection(id, if (candidate.isSuccess) RetainedStorageStatus.RECOVERY_REQUIRED else RetainedStorageStatus.BLOCKED,
            old, a, b, candidate.isSuccess, candidate.exceptionOrNull()?.message ?: "Explicit observed-token recovery required")
    }
    private fun writableNamespace() {
        val entries = entries()
        require(entries.size <= MAX_DOCUMENTS * 2)
        require(entries.map { it.name.substringBeforeLast('.') }.distinct().size <= MAX_DOCUMENTS) { "Candidate ID quota exceeded; preserve originals" }
        entries.forEach { f -> require(regular(f) && f.name.substringAfterLast('.') in setOf("json", "pending")); DiagnosticInvestigation.validId(f.name.substringBeforeLast('.')) }
    }
    private fun write(document: RetainedInvestigation, started: () -> Unit): RetainedResult.Opened {
        val bytes = RetainedInvestigationCodec.encode(document).toByteArray(Charsets.UTF_8)
        writableNamespace()
        require(directory.isDirectory || directory.mkdirs())
        val temporary = path(document.id, true)
        started(); check(temporary.createNewFile()) { "Pending original exists; inspect explicitly" }
        files.stage(temporary, bytes); files.publish(temporary, path(document.id))
        return RetainedResult.Opened(inspect(document.id))
    }
    fun execute(action: RetainedAction): RetainedResult = synchronized(lock) {
        var inspected: RetainedInspection? = null
        var id: String? = null
        var started = false
        var exportStarted = false
        fun reject(code: RetainedFailure, text: String, io: InvestigationIoFailure? = null) = RetainedResult.Rejected(code, text, action, inspected, ioFailure = io)
        try {
            when (action) {
                RetainedAction.ListNew -> {
                    val listing = entries().map { file ->
                        val key = file.name.substringBeforeLast('.')
                        val valid = runCatching { DiagnosticInvestigation.validId(key); require(file.name.substringAfterLast('.') in setOf("json", "pending")) }.isSuccess
                        if (valid) key else null
                    }.distinct().map { key ->
                        if (key != null) inspect(key).let { RetainedListing(key, listOfNotNull(it.committed, it.pending), it.status,
                            (it.snapshot?.document?.events?.firstOrNull() as? CombinedEvent.Ordinary)?.revision?.step?.let { step -> (step as InvestigationStep.Complaint).reported },
                            it.snapshot?.document?.events?.lastOrNull()?.id, it.problem) }
                        else RetainedListing(null, entries().filter { f -> runCatching {
                            DiagnosticInvestigation.validId(f.name.substringBeforeLast('.')); require(f.name.substringAfterLast('.') in setOf("json", "pending"))
                        }.isFailure }.map(::identity), RetainedStorageStatus.BLOCKED, null, null, "Unrecognized namespace entries; preserved")
                    }
                    return@synchronized RetainedResult.Listing(listing)
                }
                RetainedAction.CaseChoices -> return@synchronized try { RetainedResult.Cases(readRetainedCaseChoices(source)) }
                    catch (error: Exception) { reject(RetainedFailure.SOURCE_UNAVAILABLE, "Selected archive unavailable or invalid; no fallback.",
                        (error as? InvestigationIoException)?.failure ?: InvestigationIoFailure.SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED) }
                is RetainedAction.ExportOriginals -> {
                    safeDirectory(); require(action.observed.isNotEmpty() && action.observed.size <= MAX_DOCUMENTS * 2)
                    require(action.observed.map { it.name }.distinct().size == action.observed.size)
                    val selected = action.observed.map { observed ->
                        require(observed.name.length in 1..180 && observed.name !in setOf(".", "..") && observed.name.none { it == '/' || it == '\\' || it == '\u0000' })
                        val file = File(directory, observed.name)
                        require(observed.digest != null && identity(file) == observed) { "Original identity changed; inspect before exporting" }
                        file to observed
                    }
                    selected.forEach { (file, observed) -> exportStarted = true; action.sink.openNew(observed.name).use { output ->
                        val actual = stream(file, output); require(actual.first == observed.size && actual.second == observed.digest) { "Original changed during export; destination incomplete, original preserved" }
                        output.flush()
                    } }
                    return@synchronized RetainedResult.Exported(action.observed, action.sink.receipts())
                }
                else -> Unit
            }
            id = when (action) {
                is RetainedAction.Open -> action.id; is RetainedAction.Create -> action.id; is RetainedAction.Append -> action.id
                is RetainedAction.Attach -> action.id; is RetainedAction.Preview -> action.id; is RetainedAction.InspectRecovery -> action.id
                is RetainedAction.Recover -> action.id; else -> error("Unhandled action")
            }
            inspected = inspect(id)
            val old = inspected.snapshot
            if (action is RetainedAction.Open || action is RetainedAction.InspectRecovery) return@synchronized RetainedResult.Opened(inspected)
            if (inspected.status in setOf(RetainedStorageStatus.BLOCKED, RetainedStorageStatus.RECOVERY_REQUIRED) && action !is RetainedAction.Recover)
                return@synchronized reject(RetainedFailure.STORAGE_BLOCKED, "Originals preserved; inspect/recover/export explicitly")
            when (action) {
                is RetainedAction.Create -> {
                    val seed = RetainedInvestigation(action.id, listOf(RetainedRevision(action.complaint)))
                    require(action.complaint.sequence == 1 && action.complaint.revision.step is InvestigationStep.Complaint)
                    seed.validate()
                    if (old != null) return@synchronized if (old.document.revisions.first() == seed.revisions.first()) RetainedResult.Opened(inspected, true)
                        else reject(RetainedFailure.VALIDATION, "ID belongs to a different investigation; no overwrite")
                    writableNamespace()
                    val ids = entries().map { it.name.substringBeforeLast('.') }.distinct()
                    if (ids.size >= MAX_DOCUMENTS) return@synchronized reject(RetainedFailure.QUOTA, "New-investigation quota reached; no eviction")
                    write(seed) { started = true }
                }
                is RetainedAction.Append, is RetainedAction.Attach -> {
                    if (old == null) return@synchronized reject(RetainedFailure.VALIDATION, "Create the investigation first")
                    val requestId = if (action is RetainedAction.Append) action.event.requestId else (action as RetainedAction.Attach).intent.requestId
                    val previous = old.document.revisions.singleOrNull { it.event.requestId == requestId }
                    if (previous != null) {
                        val same = if (action is RetainedAction.Append) previous == RetainedRevision(action.event)
                        else (action as RetainedAction.Attach).let { previous.source == it.selected && previous.event is CombinedEvent.Attachment && previous.event == it.intent.event(previous.event.retainedCase) }
                        return@synchronized if (same) RetainedResult.Opened(inspected, true) else reject(RetainedFailure.VALIDATION, "Request ID already belongs to different immutable content")
                    }
                    val expected = if (action is RetainedAction.Append) action.expectedToken else (action as RetainedAction.Attach).expectedToken
                    if (expected != old.byteToken) return@synchronized reject(RetainedFailure.STALE_DOCUMENT, "Investigation changed; explicitly re-evaluate this action")
                    val revision = if (action is RetainedAction.Append) {
                        require(action.event !is CombinedEvent.Attachment) { "Attach a retained case through a fresh source read" }; RetainedRevision(action.event)
                    } else (action as RetainedAction.Attach).let {
                        it.selected.validate()
                        val choices = try { readRetainedCaseChoices(source) } catch (error: Exception) {
                            return@synchronized reject(RetainedFailure.SOURCE_UNAVAILABLE, "Selected archive unavailable or invalid; no fallback.",
                                (error as? InvestigationIoException)?.failure ?: InvestigationIoFailure.SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED)
                        }
                        val choice = choices.singleOrNull { c -> c.selection == it.selected }
                            ?: return@synchronized reject(RetainedFailure.STALE_SOURCE, "Selected source revision is absent or changed; select again")
                        RetainedRevision(it.intent.event(choice.retainedCase), it.selected)
                    }
                    write(old.document.append(revision)) { started = true }
                }
                is RetainedAction.Preview -> {
                    if (old == null) return@synchronized reject(RetainedFailure.VALIDATION, "Open an existing investigation")
                    if (old.byteToken != action.expectedToken) return@synchronized reject(RetainedFailure.STALE_DOCUMENT, "Investigation changed; no preview rebasing")
                    RetainedResult.Previewed(inspected, action, CombinedRecommendationPreview.previewRetained(old.document, action.request))
                }
                is RetainedAction.Recover -> {
                    if (!inspected.recoverable || inspected.committed?.digest != action.observedCommitted || inspected.pending?.digest != action.observedPending)
                        return@synchronized reject(RetainedFailure.STORAGE_BLOCKED, "Recovery not validated or observed identities changed; originals preserved")
                    val candidate = snapshot(path(id, true))
                    require(candidate.document.id == id && extension(old?.document, candidate.document))
                    writableNamespace(); started = true; files.sync(path(id, true)); files.publish(path(id, true), path(id))
                    RetainedResult.Opened(inspect(id))
                }
                else -> error("Unhandled action")
            }
        } catch (error: Exception) {
            val after = id?.let { runCatching { inspect(it) }.getOrNull() } ?: inspected
            if (exportStarted) RetainedResult.Rejected(RetainedFailure.EXPORT_INCOMPLETE,
                "Export destination may be incomplete; originals unchanged. Inspect the new destination before another export. ${error.message.orEmpty()}", action,
                ioFailure = (error as? InvestigationIoException)?.failure,
                destinations = (action as? RetainedAction.ExportOriginals)?.let { runCatching { it.sink.receipts() }.getOrDefault(emptyList()) }.orEmpty())
            else if (started) RetainedResult.Rejected(RetainedFailure.IO_OR_ACK_UNKNOWN,
                "Write outcome uncertain; inspect preserved originals and retry the same immutable request. ${error.message.orEmpty()}", action, after)
            else RetainedResult.Rejected(if (error is RetainedStorageUnavailable || after?.status == RetainedStorageStatus.BLOCKED) RetainedFailure.STORAGE_BLOCKED else RetainedFailure.VALIDATION,
                error.message ?: "Candidate operation unavailable; originals preserved", action, after,
                (error as? CombinedValidation)?.let { listOf(it.issue) }.orEmpty())
        }
    }
}

internal fun boundedBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
    while (true) { val n = input.read(chunk); if (n < 0) break; require(output.size().toLong() + n <= limit); output.write(chunk, 0, n) }
    return output.toByteArray()
}
