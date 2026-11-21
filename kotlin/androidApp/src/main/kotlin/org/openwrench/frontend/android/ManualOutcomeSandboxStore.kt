package org.openwrench.frontend.android

import org.openwrench.core.AttributedManualOutcome
import org.openwrench.core.DiagnosticInvestigation
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class OutcomeSandboxSnapshot(val contract: ManualOutcomeContract, val digest: String)
internal data class OutcomeSandboxPending(val sizeBytes: Long, val digest: String?)
internal data class OutcomeSandboxState(val committed: OutcomeSandboxSnapshot?, val pending: OutcomeSandboxPending?)

/** Fault injection boundary; real writes are staged, flushed and fsynced before same-directory rename. */
internal open class OutcomeSandboxFiles {
    open fun stage(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    open fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }
    open fun publish(pending: File, committed: File) { check(pending.renameTo(committed)) { "Sandbox publish failed; pending and prior history were kept" } }
}

/**
 * DORMANT sandbox only: never instantiated by runtime owners. No legacy path discovery or migration.
 * Caller supplies exact current legacy text explicitly. All writers must share this process/lock;
 * caller owns background dispatch. This is not cross-process locking or power-loss qualification.
 */
internal class ManualOutcomeSandboxStore(private val sandboxRoot: File, private val files: OutcomeSandboxFiles = OutcomeSandboxFiles()) {
    companion object {
        const val DIRECTORY = "manual-outcome-sandbox-v1"
        private val lock = Any()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private val directory get() = File(sandboxRoot, DIRECTORY)
    private fun path(id: String, pending: Boolean = false): File {
        DiagnosticInvestigation.validId(id)
        return File(directory, "$id.${if (pending) "pending" else "json"}")
    }
    private fun bytes(file: File): ByteArray {
        require(file.isFile && file.length() <= InvestigationCodec.MAX_BYTES) { "Sandbox file unavailable or oversized; preserve it" }
        return file.inputStream().use { input ->
            // Bound the actual read as well as the earlier stat; never silently truncate an oversized file.
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var count = input.read(chunk)
            while (count >= 0) {
                require(buffer.size().toLong() + count <= InvestigationCodec.MAX_BYTES)
                buffer.write(chunk, 0, count)
                count = input.read(chunk)
            }
            buffer.toByteArray()
        }
    }
    private fun text(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8; preserve original bytes" }
    }
    private fun source(id: String, legacyJson: String) {
        require(InvestigationCodec.decode(legacyJson).journal.id == id) { "Wrong legacy investigation" }
    }
    private fun snapshot(file: File, id: String, legacyJson: String): OutcomeSandboxSnapshot {
        val b = bytes(file)
        val contract = ManualOutcomeContractCodec.decode(text(b))
        require(contract.legacySnapshot().journal.id == id && contract.legacyJson == legacyJson) {
            "Legacy bytes diverged or wrong investigation; no rebase, merge or fallback is permitted"
        }
        return OutcomeSandboxSnapshot(contract, digest(b))
    }
    /** Read-only, including when the directory is absent. Pending data is explicit, never silently cleared. */
    fun inspect(id: String, currentLegacyJson: String): OutcomeSandboxState = synchronized(lock) {
        source(id, currentLegacyJson)
        val committed = path(id)
        val pending = path(id, true)
        OutcomeSandboxState(if (committed.exists()) snapshot(committed, id, currentLegacyJson) else null,
            if (pending.exists()) OutcomeSandboxPending(pending.length(),
                if (pending.isFile && pending.length() <= InvestigationCodec.MAX_BYTES) digest(bytes(pending)) else null) else null)
    }
    /** Explicit sandbox seed only; copies legacy bytes in an envelope, never modifies a source file. */
    fun create(legacyJson: String): OutcomeSandboxSnapshot = synchronized(lock) {
        val contract = ManualOutcomeContractCodec.readLegacy(legacyJson)
        val id = contract.legacySnapshot().journal.id
        require(!path(id).exists() && !path(id, true).exists()) { "Sandbox already exists or needs recovery" }
        val encoded = ManualOutcomeContractCodec.encode(contract)
        require(directory.isDirectory || directory.mkdirs())
        val entries = requireNotNull(directory.listFiles())
        val ids = entries.map { f ->
            require(f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".pending"))) { "Unknown sandbox entry; no eviction" }
            f.name.substringBeforeLast('.').also(DiagnosticInvestigation::validId)
        }.toSet()
        require(ids.size < InvestigationStore.MAX_INVESTIGATIONS) { "Sandbox full; no history was evicted" }
        publish(id, encoded)
        snapshot(path(id), id, legacyJson)
    }
    /** Optimistic token hashes the complete committed envelope, not just a caller-supplied revision ID. */
    fun append(id: String, expectedDigest: String, currentLegacyJson: String, outcome: AttributedManualOutcome): OutcomeSandboxSnapshot = synchronized(lock) {
        source(id, currentLegacyJson)
        require(!path(id, true).exists()) { "Pending data must be inspected and recovered first" }
        val old = snapshot(path(id), id, currentLegacyJson)
        require(old.digest == expectedDigest) { "Stale sandbox writer; reload" }
        val next = old.contract.copy(outcomes = old.contract.outcomes + outcome)
        val encoded = ManualOutcomeContractCodec.encode(next) // validates exact question, context and correction chain
        publish(id, encoded)
        snapshot(path(id), id, currentLegacyJson)
    }
    private fun publish(id: String, encoded: String) {
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8)
        require(encodedBytes.toString(Charsets.UTF_8) == encoded) { "Unencodable text; no bytes were staged" }
        val pending = path(id, true)
        check(pending.createNewFile()) { "Pending data already exists; no overwrite" }
        // Never delete a partial or uncertain write in finally. Recovery is explicit and lossless.
        files.stage(pending, encodedBytes)
        files.publish(pending, path(id))
    }
    /**
     * Explicitly finish a validated seed or one-revision append, with both observed byte digests.
     * Partial/corrupt/future/diverged files are preserved and block recovery; no discard operation exists.
     */
    fun recover(id: String, expectedCommittedDigest: String?, expectedPendingDigest: String, currentLegacyJson: String): OutcomeSandboxSnapshot = synchronized(lock) {
        source(id, currentLegacyJson)
        val committed = path(id)
        val old = if (committed.exists()) snapshot(committed, id, currentLegacyJson) else null
        require(old?.digest == expectedCommittedDigest) { "Committed state changed; inspect again" }
        val pending = path(id, true)
        val next = snapshot(pending, id, currentLegacyJson)
        require(next.digest == expectedPendingDigest) { "Pending state changed; inspect again" }
        if (old == null) require(next.contract.outcomes.isEmpty()) { "Only a legacy seed may initialize a sandbox" }
        else require(next.contract.outcomes == old.contract.outcomes ||
            next.contract.outcomes.size == old.contract.outcomes.size + 1 &&
            next.contract.outcomes.dropLast(1) == old.contract.outcomes) { "Recovery must preserve the complete prior typed trail" }
        // A previous stage may have failed during fsync; validate and fsync again before publishing.
        files.sync(pending)
        files.publish(pending, committed)
        snapshot(committed, id, currentLegacyJson)
    }
}
