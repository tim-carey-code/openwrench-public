package org.openwrench.frontend.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import org.openwrench.core.OwnerReportSelection
import org.openwrench.core.ReportSelection

internal data class CombinedSourceNotice(val caseId: String, val fictional: Boolean, val scanEcu: String?, val driveEcu: String?)
internal data class CombinedSnapshot(val document: CombinedInvestigation, val token: String) {
    val sources get() = document.cases().map { CombinedSourceNotice(it.id, true, it.scan.capture?.sourceEcu, it.drive.sourceEcu) }
    val questions get() = document.events.filterIsInstance<CombinedEvent.Ordinary>().filter { it.revision.step is org.openwrench.core.InvestigationStep.ProposedTest }
    val activeResults get() = document.activeResultIds()
}
internal enum class CombinedUnavailableReason { INVALID_OR_UNREADABLE, UNSUPPORTED_FORMAT, WRITE_OUTCOME_UNKNOWN, COMBINED_RECOMMENDATIONS_NOT_IMPLEMENTED }
/** Selection identity excludes both revision tokens; changing any selector invalidates presentation. */
internal data class CombinedPreviewSelection(val caseId: String,
    val startingReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val timingReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val adaptationReport: OwnerReportSelection = OwnerReportSelection(ReportSelection.UNRESOLVED),
    val questionRevisionId: String? = null) {
    fun request(contentRevision: String) = CombinedPreviewRequest(contentRevision, caseId, startingReport, timingReport, adaptationReport, questionRevisionId)
}
internal fun CombinedPreviewRequest.selection() = CombinedPreviewSelection(caseId, startingReport, timingReport, adaptationReport, questionRevisionId)
internal data class CombinedPreviewDelivery(val submitted: CombinedAction.Preview, val result: CombinedPreviewResult)
internal enum class PendingDisposition { RECOVERABLE, BLOCKED }
internal data class CombinedPending(val digest: String?, val sizeBytes: Long, val disposition: PendingDisposition,
    val explanation: String, val unknownField: String? = null, val unknownValue: String? = null)
internal sealed interface CombinedAction {
    data object Load : CombinedAction
    data class Create(val seed: CombinedInvestigation) : CombinedAction
    data class Append(val expectedToken: String, val event: CombinedEvent) : CombinedAction
    data class Recover(val observedCommitted: String?, val observedPending: String) : CombinedAction
    /** Raw file token AND canonical content revision/selectors are explicit and independently checked. */
    data class Preview(val expectedToken: String, val request: CombinedPreviewRequest) : CombinedAction
}
internal sealed interface CombinedState {
    val snapshot: CombinedSnapshot? get() = null
    data class Loading(override val snapshot: CombinedSnapshot?) : CombinedState
    data object Empty : CombinedState
    data class Ready(override val snapshot: CombinedSnapshot, val replayedRequest: Boolean = false, val preview: CombinedPreviewDelivery? = null) : CombinedState
    data class ValidationRejected(override val snapshot: CombinedSnapshot?, val submitted: CombinedAction, val issues: List<CombinedFieldIssue>) : CombinedState
    data class Stale(val expectedToken: String?, override val snapshot: CombinedSnapshot, val submitted: CombinedAction) : CombinedState
    data class RecoveryRequired(override val snapshot: CombinedSnapshot?, val pending: CombinedPending) : CombinedState
    data class Unavailable(val reason: CombinedUnavailableReason, val preserved: Boolean, override val snapshot: CombinedSnapshot? = null,
        val detail: String, val unknownField: String? = null, val unknownValue: String? = null) : CombinedState
}

/** One document per explicit disposable fixture root. No discovery, migration, delete, eviction or runtime factory.
 * Process-wide lock only. fsync+rename is tested through faults, NOT Android power-loss qualification.
 */
internal class CombinedFixtureStore(private val root: File, private val files: OutcomeSandboxFiles = OutcomeSandboxFiles(),
    private val preview: (CombinedInvestigation, CombinedPreviewRequest) -> CombinedPreviewResult = CombinedRecommendationPreview::preview) {
    companion object {
        const val DIRECTORY = "combined-investigation-fixture-v1"
        private val lock = Any()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private val directory get() = File(root, DIRECTORY)
    private val committed get() = File(directory, "fixture.json")
    private val pending get() = File(directory, "fixture.pending")
    private fun bytes(file: File): ByteArray {
        require(file.isFile && file.length() <= InvestigationCodec.MAX_BYTES) { "Fixture unavailable or oversized; original bytes preserved" }
        return file.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(result.size().toLong() + count <= InvestigationCodec.MAX_BYTES)
                result.write(chunk, 0, count)
            }
            result.toByteArray()
        }
    }
    private fun read(file: File): CombinedSnapshot {
        val bytes = bytes(file)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8; original bytes preserved" }
        return CombinedSnapshot(CombinedInvestigationCodec.decode(text), digest(bytes))
    }
    private fun seed(document: CombinedInvestigation) = document.events.size == 1 && document.events.single() is CombinedEvent.Ordinary
    private fun extends(old: CombinedInvestigation?, next: CombinedInvestigation): Boolean = if (old == null) seed(next) else
        next.id == old.id && next.initialCases == old.initialCases &&
            (next.events == old.events || (next.events.size == old.events.size + 1 && next.events.dropLast(1) == old.events))
    private fun inspect(): CombinedState {
        val current = if (committed.exists()) read(committed) else null
        if (!pending.exists()) return current?.let { CombinedState.Ready(it) } ?: CombinedState.Empty
        var hash: String? = null
        var unknown: CombinedUnsupported? = null
        var explanation = "Valid pending seed, duplicate or single append; explicit observed-token recovery required."
        val recoverable = try {
            hash = digest(bytes(pending))
            val next = read(pending)
            extends(current?.document, next.document).also { if (!it) explanation = "Pending history diverges; both files are preserved." }
        } catch (error: Exception) {
            unknown = error as? CombinedUnsupported
            explanation = error.message ?: "Pending fixture cannot be validated; preserved."
            false
        }
        return CombinedState.RecoveryRequired(current, CombinedPending(hash, pending.length(),
            if (recoverable) PendingDisposition.RECOVERABLE else PendingDisposition.BLOCKED, explanation, unknown?.field, unknown?.rawValue))
    }
    fun execute(action: CombinedAction): CombinedState = synchronized(lock) {
        var current: CombinedSnapshot? = null
        var writeStarted = false
        try {
            val inspection = try { inspect() } catch (error: Exception) {
                return@synchronized CombinedState.Unavailable(
                    if (error is CombinedUnsupported) CombinedUnavailableReason.UNSUPPORTED_FORMAT else CombinedUnavailableReason.INVALID_OR_UNREADABLE,
                    committed.exists() || pending.exists(), detail = error.message ?: "Cannot read fixture; original bytes preserved.",
                    unknownField = (error as? CombinedUnsupported)?.field, unknownValue = (error as? CombinedUnsupported)?.rawValue)
            }
            current = inspection.snapshot
            if (inspection is CombinedState.RecoveryRequired && action !is CombinedAction.Recover) return@synchronized inspection
            when (action) {
                CombinedAction.Load -> inspection
                is CombinedAction.Create -> {
                    require(seed(action.seed)) { "Create from one fictional complaint, then append." }
                    action.seed.validate()
                    if (current != null) {
                        val old = current.document
                        if (old.id == action.seed.id && old.initialCases == action.seed.initialCases && old.events.take(1) == action.seed.events)
                            CombinedState.Ready(current, true)
                        else rejected(current, action, "create", "A different fixture already exists. No overwrite is available.")
                    } else write(action.seed) { writeStarted = true }
                }
                is CombinedAction.Append -> {
                    val old = current ?: return@synchronized rejected(null, action, "investigation", "Create the fictional investigation first.")
                    val prior = old.document.events.singleOrNull { it.requestId == action.event.requestId }
                    when {
                        prior == action.event -> CombinedState.Ready(old, true) // lost acknowledgment retry, even with its old token
                        prior != null -> rejected(old, action, "requestId", "Request ID already belongs to a different immutable action.")
                        old.token != action.expectedToken -> CombinedState.Stale(action.expectedToken, old, action)
                        else -> write(old.document.append(action.event)) { writeStarted = true }
                    }
                }
                is CombinedAction.Recover -> {
                    if (inspection !is CombinedState.RecoveryRequired) return@synchronized rejected(current, action, "recovery", "No pending fixture exists; reload before deciding.")
                    if (current?.token != action.observedCommitted || inspection.pending.digest != action.observedPending)
                        return@synchronized if (current != null) CombinedState.Stale(action.observedCommitted, current, action) else inspection
                    if (inspection.pending.disposition != PendingDisposition.RECOVERABLE) return@synchronized inspection
                    // Revalidate exact extension immediately before publication; no discard path.
                    val next = read(pending)
                    require(extends(current?.document, next.document))
                    writeStarted = true
                    files.sync(pending)
                    files.publish(pending, committed)
                    CombinedState.Ready(read(committed))
                }
                is CombinedAction.Preview -> {
                    val old = current ?: return@synchronized rejected(null, action, "investigation", "No investigation to preview.")
                    if (action.expectedToken != old.token) CombinedState.Stale(action.expectedToken, old, action)
                    else CombinedState.Ready(old, preview = CombinedPreviewDelivery(action, preview(old.document, action.request)))
                }
            }
        } catch (error: Exception) {
            if (!writeStarted && error is CombinedValidation)
                return@synchronized CombinedState.ValidationRejected(current, action, listOf(error.issue))
            // Phase, not exception type, decides whether a durable mutation may have happened.
            val preserved = runCatching { inspect() }.getOrNull()
            if (preserved is CombinedState.RecoveryRequired) preserved
            else if (writeStarted) CombinedState.Unavailable(CombinedUnavailableReason.WRITE_OUTCOME_UNKNOWN,
                committed.exists() || pending.exists(), current,
                "Write acknowledgment unavailable; reload and retry the same immutable request. ${error.message.orEmpty()}")
            else if (error is CombinedUnsupported) CombinedState.Unavailable(CombinedUnavailableReason.UNSUPPORTED_FORMAT,
                committed.exists() || pending.exists(), current, error.message.orEmpty(), error.field, error.rawValue)
            else if (error is IllegalArgumentException) rejected(current, action, "action", error.message ?: "Invalid fixture action.")
            else CombinedState.Unavailable(CombinedUnavailableReason.INVALID_OR_UNREADABLE,
                committed.exists() || pending.exists(), current, error.message ?: "Cannot access fixture; bytes preserved.")
        }
    }
    private fun rejected(snapshot: CombinedSnapshot?, action: CombinedAction, field: String, message: String) =
        CombinedState.ValidationRejected(snapshot, action, listOf(CombinedFieldIssue(field, CombinedIssueCode.INVALID_LOG, message)))
    private fun write(document: CombinedInvestigation, onWriteStarted: () -> Unit): CombinedState.Ready {
        val encoded = CombinedInvestigationCodec.encode(document).toByteArray(Charsets.UTF_8)
        require(directory.isDirectory || directory.mkdirs())
        require(directory.list()?.all { it == "fixture.json" } == true) { "Unexpected fixture files; preserved without overwrite." }
        onWriteStarted()
        check(pending.createNewFile()) { "Pending fixture already exists; recover explicitly." }
        files.stage(pending, encoded)
        files.publish(pending, committed)
        return CombinedState.Ready(read(committed))
    }
}

internal enum class CombinedAdmission { ACCEPTED, BUSY, INACTIVE, SELECTION_MISMATCH }
/** Caller explicitly owns lifetime/root. No app/session factory. One admitted IO job, including after detach. */
internal class CombinedFixtureCoordinator(private val store: CombinedFixtureStore, private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val presentationLock = Any()
    private var admitted = false
    private var generation = 0L
    private var previewGeneration = 0L
    private var previewSelection: CombinedPreviewSelection? = null
    private val mutable = MutableStateFlow<CombinedState>(CombinedState.Empty)
    val state = mutable.asStateFlow()

    /** Call on every selection/context change, including cancel. Does not dispatch IO or cancel durable writes. */
    fun selectPreview(selection: CombinedPreviewSelection?) = synchronized(presentationLock) {
        if (previewSelection != selection) {
            previewSelection = selection
            previewGeneration++
            clearPreview()
        }
    }
    private fun clearPreview() {
        val ready = mutable.value as? CombinedState.Ready
        if (ready?.preview != null) mutable.value = ready.copy(preview = null)
    }
    fun dispatch(action: CombinedAction): CombinedAdmission = synchronized(presentationLock) {
        if (!scope.isActive) return@synchronized CombinedAdmission.INACTIVE
        if (admitted) return@synchronized CombinedAdmission.BUSY
        if (action is CombinedAction.Preview && action.request.selection() != previewSelection)
            return@synchronized CombinedAdmission.SELECTION_MISMATCH
        admitted = true
        val token = generation
        val previewToken = ++previewGeneration
        mutable.value = CombinedState.Loading(mutable.value.snapshot) // every admitted action clears displayed preview
        // ATOMIC start ensures cancellation cannot strand admission before the finally block is entered.
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch(start = CoroutineStart.ATOMIC) {
            var result: CombinedState? = null
            try {
                result = withContext(if (action == CombinedAction.Load || action is CombinedAction.Preview) io else io + NonCancellable) { store.execute(action) }
                ensureActive()
            } finally {
                synchronized(presentationLock) {
                    admitted = false // completion and publication are one atomic presentation transition
                    val completed = result
                    if (completed != null && generation == token && coroutineContext.isActive) {
                        mutable.value = if (completed is CombinedState.Ready && completed.preview != null &&
                            (previewGeneration != previewToken || previewSelection != completed.preview.submitted.request.selection()))
                            completed.copy(preview = null) else completed
                    }
                }
            }
        }
        CombinedAdmission.ACCEPTED
    }
    /** Cancels delivery only. Reload/reselect on return; never release admitted-write ownership early. */
    fun detach() = synchronized(presentationLock) {
        generation++
        previewGeneration++
        previewSelection = null
        clearPreview()
    }
}
