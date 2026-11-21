package org.openwrench.frontend.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.json.*
import java.io.*
import java.net.URI
import java.security.MessageDigest

internal enum class InvestigationIoFailure { MISSING_SOURCE, ACCESS_DENIED_OR_REVOKED, NULL_STREAM, READ_FAILED, SOURCE_ARCHIVE_INVALID_OR_UNSUPPORTED, SOURCE_TOO_LARGE,
    NO_READ_PROGRESS, CANCELED, UNSUPPORTED_PROVIDER, DESTINATION_EXISTS, DESTINATION_FAILED, DESTINATION_PARTIAL, RECEIPT_FAILED, PRIVATE_AREA_UNAVAILABLE, QUOTA }
/** Sanitized metadata only: never retain provider messages, raw URI or capture bytes in errors. */
internal class InvestigationIoException(val failure: InvestigationIoFailure) : IOException(failure.name)
internal class SelectedArchiveHandle(val selectionId: String, private val value: String) {
    init {
        org.openwrench.core.DiagnosticInvestigation.validId(selectionId)
        val uri = try { URI(value) } catch (_: Exception) { throw InvestigationIoException(InvestigationIoFailure.UNSUPPORTED_PROVIDER) }
        if (uri.scheme != "content" || uri.rawAuthority.isNullOrBlank() || uri.rawFragment != null || uri.rawUserInfo != null)
            throw InvestigationIoException(InvestigationIoFailure.UNSUPPORTED_PROVIDER)
    }
    internal fun contentUri() = value
    override fun toString() = "SelectedArchiveHandle($selectionId)"
}
internal fun interface SelectedArchiveStreams { fun openRead(handle: SelectedArchiveHandle): InputStream? }
/** Cold adapter: no grant acquisition, picker, discovery, query or output-stream access. */
internal class AndroidSelectedArchiveStreams(private val resolver: ContentResolver) : SelectedArchiveStreams {
    override fun openRead(handle: SelectedArchiveHandle): InputStream? = resolver.openInputStream(Uri.parse(handle.contentUri()))
}
internal class AndroidRetainedArchiveSource(private val handle: SelectedArchiveHandle, private val streams: SelectedArchiveStreams,
    private val canceled: () -> Boolean = { false }) : RetainedCaseSource {
    private fun checkCancellation() { if (canceled()) throw InvestigationIoException(InvestigationIoFailure.CANCELED) }
    private fun failure(error: Exception): Nothing = throw if (error is InvestigationIoException) error else InvestigationIoException(when (error) {
        is SecurityException -> InvestigationIoFailure.ACCESS_DENIED_OR_REVOKED
        is FileNotFoundException -> InvestigationIoFailure.MISSING_SOURCE
        is java.util.concurrent.CancellationException, is android.os.OperationCanceledException -> InvestigationIoFailure.CANCELED
        else -> InvestigationIoFailure.READ_FAILED
    })
    override fun openArchive(): InputStream {
        checkCancellation()
        val input = try { streams.openRead(handle) ?: throw InvestigationIoException(InvestigationIoFailure.NULL_STREAM) }
            catch (error: Exception) { failure(error) }
        return object : InputStream() {
            private var count = 0L
            private var zeroReads = 0
            private fun received(n: Int): Int {
                checkCancellation()
                if (n == 0 && ++zeroReads > 8) throw InvestigationIoException(InvestigationIoFailure.NO_READ_PROGRESS)
                if (n > 0) { zeroReads = 0; count += n; if (count > DiagnosticCaseCodec.MAX_BYTES) throw InvestigationIoException(InvestigationIoFailure.SOURCE_TOO_LARGE) }
                return n
            }
            override fun read(): Int = try { checkCancellation(); val value = input.read(); received(if (value < 0) -1 else 1); value } catch (error: Exception) { failure(error) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try {
                if (length == 0) 0 else { checkCancellation(); received(input.read(bytes, offset, minOf(length.toLong(), DiagnosticCaseCodec.MAX_BYTES - count + 1).toInt())) }
            } catch (error: Exception) { failure(error) }
            override fun close() { try { input.close() } catch (error: Exception) { failure(error) } }
        }
    }
}

internal fun interface AppPrivateFilesLocation { fun directory(): File }
/** Context/filesDir is consulted only when explicitly called by a future owner. No app factory uses this. */
internal class AndroidAppPrivateFiles(private val context: Context) : AppPrivateFilesLocation {
    override fun directory(): File = context.filesDir
}
internal interface ExclusiveInvestigationFile : Closeable { val output: OutputStream; fun sync() }
internal fun interface ExclusiveInvestigationFiles { fun createNew(file: File): ExclusiveInvestigationFile }
/** API21+ atomic exclusive creation, no-follow final component, mode0600. Existing destinations never opened. */
internal class AndroidExclusiveInvestigationFiles : ExclusiveInvestigationFiles {
    override fun createNew(file: File): ExclusiveInvestigationFile {
        val descriptor = try { Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, OsConstants.S_IRUSR or OsConstants.S_IWUSR) }
        catch (error: android.system.ErrnoException) { throw InvestigationIoException(if (error.errno == OsConstants.EEXIST) InvestigationIoFailure.DESTINATION_EXISTS else InvestigationIoFailure.DESTINATION_FAILED) }
        val output = try { FileOutputStream(descriptor) } catch (_: Exception) {
            runCatching { Os.close(descriptor) }
            throw InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED)
        }
        return object : ExclusiveInvestigationFile {
            override val output: OutputStream = output
            override fun sync() { descriptor.sync() }
            override fun close() { output.close() }
        }
    }
}
internal class InvestigationPrivateArea(private val location: AppPrivateFilesLocation, private val exclusive: ExclusiveInvestigationFiles) {
    companion object { const val DIRECTORY = "investigation-android-boundary-candidate-v1" }
    private fun base(): File {
        val root = location.directory()
        if (!root.isDirectory || root.canonicalFile != root.absoluteFile.normalize()) throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
        val base = File(root, DIRECTORY)
        val children = root.list() ?: throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
        if (base.canonicalFile != base.absoluteFile.normalize() || base.name in children && !base.isDirectory) throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
        return base
    }
    /** Path ownership only; does not create an owner, open histories or activate writes. */
    fun candidateRoot(): File = File(base(), "new-investigations")
    fun newExport(sessionId: String, expected: List<OriginalIdentity>, canceled: () -> Boolean = { false }): PrivateInvestigationExport =
        PrivateInvestigationExport.create(File(base(), "exports"), sessionId, expected, exclusive, canceled)
    fun inspectExport(sessionId: String): List<InvestigationExportReceipt> = PrivateInvestigationExport.inspect(File(base(), "exports"), sessionId)
    fun providerExportUnsupported(): Nothing = throw InvestigationIoException(InvestigationIoFailure.UNSUPPORTED_PROVIDER)
}
internal enum class InvestigationExportStatus { PLANNED, PARTIAL, CANCELED, COMPLETE, MATCHED_ACK_UNCONFIRMED, RECEIPT_FAILED }
internal data class InvestigationExportReceipt(val sessionId: String, val original: OriginalIdentity, val status: InvestigationExportStatus,
    val destinationName: String, val observedSize: Long?, val observedDigest: String?)

/** Bounded NEW app-private export session. All originals and partial destinations are preserved. */
internal class PrivateInvestigationExport private constructor(private val directory: File, private val sessionId: String,
    private val expected: List<OriginalIdentity>, private val exclusive: ExclusiveInvestigationFiles, private val canceled: () -> Boolean) : OriginalExportSink {
    companion object {
        const val MAX_SESSIONS = 10
        const val MAX_SESSION_BYTES = 50_000_000L
        private val lock = Any()
        private fun canonical(file: File) = file.canonicalFile == file.absoluteFile.normalize()
        private fun safeName(name: String) = name.length in 1..160 && Regex("[A-Za-z0-9][A-Za-z0-9_.-]*").matches(name) && name !in setOf(".", "..")
        private fun row(original: OriginalIdentity) = JsonArray(listOf(JsonPrimitive(original.name), JsonPrimitive(original.size), JsonPrimitive(requireNotNull(original.digest))))
        private fun plan(expected: List<OriginalIdentity>) = buildJsonObject { put("schema", "openwrench.private-original-export/1"); put("originals", JsonArray(expected.map(::row))) }.toString()
        private fun writeNew(file: File, text: String, exclusive: ExclusiveInvestigationFiles) {
            exclusive.createNew(file).use { it.output.write(text.toByteArray(Charsets.UTF_8)); it.output.flush(); it.sync() }
        }
        fun create(parent: File, id: String, expected: List<OriginalIdentity>, exclusive: ExclusiveInvestigationFiles, canceled: () -> Boolean): PrivateInvestigationExport = synchronized(lock) {
            org.openwrench.core.DiagnosticInvestigation.validId(id)
            require(expected.isNotEmpty() && expected.size <= RetainedInvestigationRepository.MAX_DOCUMENTS * 2)
            require(expected.map { it.name }.distinct().size == expected.size)
            expected.forEach { require(safeName(it.name) && it.size in 0..MAX_SESSION_BYTES && Regex("[a-f0-9]{64}").matches(it.digest.orEmpty())) }
            require(expected.sumOf { it.size } <= MAX_SESSION_BYTES)
            if (canceled()) throw InvestigationIoException(InvestigationIoFailure.CANCELED)
            if (!canonical(parent) || parent.exists() && !parent.isDirectory) throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
            if (!parent.exists() && !parent.mkdirs()) throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
            val sessions = parent.listFiles() ?: throw InvestigationIoException(InvestigationIoFailure.PRIVATE_AREA_UNAVAILABLE)
            if (sessions.size >= MAX_SESSIONS) throw InvestigationIoException(InvestigationIoFailure.QUOTA)
            val directory = File(parent, id)
            if (!directory.mkdir()) throw InvestigationIoException(if (directory.exists()) InvestigationIoFailure.DESTINATION_EXISTS else InvestigationIoFailure.DESTINATION_FAILED)
            if (!File(directory, "files").mkdir() || !File(directory, "receipts").mkdir()) throw InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED)
            try { writeNew(File(directory, "plan.json"), plan(expected), exclusive) }
            catch (_: Exception) { throw InvestigationIoException(InvestigationIoFailure.RECEIPT_FAILED) }
            PrivateInvestigationExport(directory, id, expected.toList(), exclusive, canceled)
        }
        private fun observed(file: File): Pair<Long, String>? {
            if (!file.isFile || !canonical(file) || file.length() > MAX_SESSION_BYTES) return null
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            file.inputStream().use { input -> val chunk = ByteArray(8192); while (true) { val n = input.read(chunk); if (n < 0) break; size += n; require(size <= MAX_SESSION_BYTES); digest.update(chunk, 0, n) } }
            return size to digest.digest().joinToString("") { "%02x".format(it) }
        }
        /** Explicit session only; no discovery, writes, cleanup or automatic resume. */
        fun inspect(parent: File, id: String): List<InvestigationExportReceipt> = synchronized(lock) {
            org.openwrench.core.DiagnosticInvestigation.validId(id)
            val directory = File(parent, id)
            require(canonical(parent) && canonical(directory) && directory.isDirectory)
            val file = File(directory, "plan.json")
            require(canonical(file) && file.isFile && file.length() <= 32_000)
            val text = file.inputStream().use { boundedBytes(it, 32_000) }.toString(Charsets.UTF_8)
            SnapshotCodec.StrictJsonCheck(text).check()
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.keys == setOf("schema", "originals") && root.getValue("schema") == JsonPrimitive("openwrench.private-original-export/1"))
            val originals = root.getValue("originals").jsonArray.also { require(it.size in 1..20) }.map { a -> a.jsonArray.also { require(it.size == 3) }.let {
                OriginalIdentity(it[0].jsonPrimitive.also { p -> require(p.isString) }.content, it[1].jsonPrimitive.also { p -> require(!p.isString) }.long, it[2].jsonPrimitive.also { p -> require(p.isString) }.content)
            } }
            require(originals.map { it.name }.distinct().size == originals.size && originals.sumOf { it.size } <= MAX_SESSION_BYTES)
            originals.map { original ->
                require(safeName(original.name) && original.size in 0..MAX_SESSION_BYTES && Regex("[a-f0-9]{64}").matches(original.digest.orEmpty()))
                val data = File(directory, "files/${original.name}"); val bytes = observed(data)
                // Current matching bytes + receipt do not prove the earlier sync/close acknowledgment succeeded.
                // Lost receipt acknowledgment is explicit; inspection never upgrades it to COMPLETE.
                val completion = File(directory, "receipts/${original.name}.receipt.json")
                val certified = if (completion.isFile && canonical(completion) && completion.length() <= 1000) runCatching {
                    val receipt = completion.inputStream().use { boundedBytes(it, 1000) }.toString(Charsets.UTF_8)
                    SnapshotCodec.StrictJsonCheck(receipt).check()
                    val r = Json.parseToJsonElement(receipt).jsonObject
                    r.keys == setOf("status", "digest", "size") && r.getValue("status") == JsonPrimitive("COMPLETE") &&
                        r.getValue("digest") == JsonPrimitive(original.digest) && r.getValue("size") == JsonPrimitive(original.size)
                }.getOrDefault(false) else false
                InvestigationExportReceipt(id, original, when { certified && bytes?.first == original.size && bytes.second == original.digest -> InvestigationExportStatus.MATCHED_ACK_UNCONFIRMED
                    data.exists() -> InvestigationExportStatus.PARTIAL else -> InvestigationExportStatus.PLANNED }, "files/${original.name}", bytes?.first, bytes?.second)
            }
        }
    }
    private val opened = mutableSetOf<String>()
    private val current = expected.associate { it.name to InvestigationExportReceipt(sessionId, it, InvestigationExportStatus.PLANNED, "files/${it.name}", null, null) }.toMutableMap()
    override fun receipts(): List<InvestigationExportReceipt> = synchronized(lock) { expected.map { current.getValue(it.name) } }
    override fun openNew(name: String): OutputStream = synchronized(lock) {
        val original = expected.singleOrNull { it.name == name } ?: throw InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED)
        if (canceled()) throw InvestigationIoException(InvestigationIoFailure.CANCELED)
        if (!opened.add(name)) throw InvestigationIoException(InvestigationIoFailure.DESTINATION_EXISTS)
        val file = File(directory, "files/$name")
        val handle = try { exclusive.createNew(file) } catch (error: Exception) { throw if (error is InvestigationIoException) error else InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED) }
        current[name] = current.getValue(name).copy(status = InvestigationExportStatus.PARTIAL)
        object : OutputStream() {
            private var count = 0L
            private var failed = false
            private var closed = false
            private fun writable(length: Int) {
                check(!closed)
                if (canceled()) { failed = true; throw InvestigationIoException(InvestigationIoFailure.CANCELED) }
                if (count + length > original.size) { failed = true; throw InvestigationIoException(InvestigationIoFailure.DESTINATION_PARTIAL) }
            }
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writable(length)
                try { handle.output.write(bytes, offset, length); count += length }
                catch (_: Exception) { failed = true; throw InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED) }
            }
            override fun flush() { try { handle.output.flush() } catch (_: Exception) { failed = true; throw InvestigationIoException(InvestigationIoFailure.DESTINATION_FAILED) } }
            override fun close() = synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                var closeFailure = false
                try { handle.output.flush(); handle.sync() } catch (_: Exception) { closeFailure = true }
                try { handle.close() } catch (_: Exception) { closeFailure = true }
                val actual = runCatching { observed(file) }.getOrNull()
                val success = !failed && !closeFailure && !canceled() && actual?.first == original.size && actual.second == original.digest
                var status = if (success) InvestigationExportStatus.COMPLETE else if (canceled()) InvestigationExportStatus.CANCELED else InvestigationExportStatus.PARTIAL
                if (success) try { writeNew(File(directory, "receipts/$name.receipt.json"), buildJsonObject {
                    put("status", "COMPLETE"); put("digest", original.digest); put("size", original.size)
                }.toString(), exclusive) } catch (_: Exception) { status = InvestigationExportStatus.RECEIPT_FAILED }
                current[name] = current.getValue(name).copy(status = status, observedSize = actual?.first, observedDigest = actual?.second)
                if (status != InvestigationExportStatus.COMPLETE) throw InvestigationIoException(if (status == InvestigationExportStatus.RECEIPT_FAILED) InvestigationIoFailure.RECEIPT_FAILED else if (status == InvestigationExportStatus.CANCELED) InvestigationIoFailure.CANCELED else InvestigationIoFailure.DESTINATION_PARTIAL)
            }
        }
    }
}
