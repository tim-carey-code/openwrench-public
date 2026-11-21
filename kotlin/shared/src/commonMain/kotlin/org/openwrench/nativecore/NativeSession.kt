package org.openwrench.nativecore

const val NATIVE_STANDARD_READ_TIMEOUT_MILLIS = 3_000L

/** Platform transport for one explicitly selected adapter; implementations never auto-discover hardware. */
interface NativeTransport {
    fun open()
    /** Connection negotiation must check the session owner's cancellation before further I/O. */
    fun open(checkActive: () -> Unit) {
        checkActive()
        open()
    }
    fun close()

    /** Bounded metadata only. Implementations must not record response text. */
    fun reportSessionFailure(stage: NativeFailureStage, kind: ErrorKind) {}

    /**
     * Send [command] followed by one CR, then return ASCII response INCLUDING the consumed `>` prompt.
     * Bound the entire exchange by [timeoutMillis] and 4096 bytes, and discard stale input before sending.
     * Implementations report transport failures with [NativeDiagnosticException].
     */
    fun exchange(command: String, timeoutMillis: Long = NATIVE_STANDARD_READ_TIMEOUT_MILLIS): String
}

enum class ErrorKind { NOT_CONNECTED, UNSUPPORTED, MALFORMED_RESPONSE, ADAPTER, TRANSPORT, TIMEOUT }
enum class NativeFailureStage { OPEN, INITIALIZATION, CAPABILITY_DISCOVERY, READING, VIN, SNAPSHOT, STORED_DTCS, DIAGNOSTIC_CONTEXT }

class NativeDiagnosticException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class NativeSessionState { DISCONNECTED, CONNECTING, CONNECTED, FAULTED }

data class NativeCapabilities(val pids: Set<Int>, val ranges: List<Int>, val sourceEcu: String? = null)

/** A null value means the ECU returned an exact, standalone NO DATA response. */
data class NativeReading(val pid: Int, val value: DecodedReading?, val sourceEcu: String? = null)

/**
 * Experimental, generic, read-only native OBD-II session, ported from the Python ELM327 backend.
 * The owning platform must serialize calls on a worker thread. No raw-command API is exposed.
 * Android hardware has not been qualified by the earlier Python/desktop RPM acceptance test.
 */
class NativeSession(private val transport: NativeTransport) {
    var state: NativeSessionState = NativeSessionState.DISCONNECTED
        private set
    var identity: String? = null
        private set
    private var discovered: NativeCapabilities? = null
    private var cleanupUncertain = false
    private var canResponses: CanObdResponses? = null
    private var selectedEcu: String? = null
    private var storedDtcProtocol: String? = null
    private var failureStage = NativeFailureStage.OPEN

    /** Return copies so callers cannot alter the capability gate used for future wire requests. */
    val capabilities: NativeCapabilities?
        get() = discovered?.let { NativeCapabilities(it.pids.toSet(), it.ranges.toList(), it.sourceEcu) }

    fun connect(checkActive: () -> Unit = {}): NativeCapabilities {
        if (cleanupUncertain) {
            throw NativeDiagnosticException(
                ErrorKind.TRANSPORT,
                "Adapter cleanup is unconfirmed; disconnect successfully before reconnecting",
            )
        }
        if (state == NativeSessionState.CONNECTED) return checkNotNull(capabilities)
        if (state == NativeSessionState.CONNECTING) {
            throw NativeDiagnosticException(ErrorKind.ADAPTER, "A connection is already in progress")
        }
        state = NativeSessionState.CONNECTING
        clearVehicle()
        failureStage = NativeFailureStage.OPEN
        return guarded {
            checkActive()
            transportOperation("open adapter") { transport.open(checkActive) }
            failureStage = NativeFailureStage.INITIALIZATION
            initialize(checkActive)
            failureStage = NativeFailureStage.CAPABILITY_DISCOVERY
            val pids = mutableSetOf<Int>()
            val ranges = mutableListOf<Int>()
            var base = 0
            while (true) {
                checkActive()
                val current = try {
                    val lines = command(mode01(base))
                    if (base == 0 && lines.size > 1) {
                        discoverAddressedSource(lines, checkActive)
                    } else decode { ObdDecoder.supported(base, mode01Response(base, lines)) }
                } catch (error: NativeDiagnosticException) {
                    throw NativeDiagnosticException(
                        error.kind,
                        "Capability request ${mode01(base)} failed after ranges " +
                            "${ranges.joinToString { mode01(it) }.ifEmpty { "none" }}: ${error.message}",
                        error,
                    )
                }
                pids.addAll(current)
                ranges.add(base)
                val next = base + 0x20
                if (next !in current || next !in ObdDecoder.rangeBases) break
                base = next
            }
            discovered = NativeCapabilities(pids.toSet(), ranges.toList(), selectedEcu)
            state = NativeSessionState.CONNECTED
            checkNotNull(capabilities)
        }
    }

    fun disconnect() {
        clearVehicle()
        state = NativeSessionState.DISCONNECTED
        try {
            transportOperation("close adapter") { transport.close() }
            cleanupUncertain = false
        } catch (error: Throwable) {
            cleanupUncertain = true
            state = NativeSessionState.FAULTED
            throw error
        }
    }

    /** Read a supported scalar PID from the Python backend's explicit standard-PID allowlist. */
    fun read(pid: Int): NativeReading {
        requireConnected()
        if (pid !in ObdDecoder.scalarPids) {
            throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, "PID is outside the scalar read allowlist")
        }
        requireAdvertised(pid)
        failureStage = NativeFailureStage.READING
        return guarded { reading(pid, snapshot = false) }
    }

    /** Live structured reads are limited to advertised monitor and fuel-system status. */
    fun readStructured(pid: Int): NativeReading {
        requireConnected()
        if (pid != 0x01 && pid != 0x03) {
            throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, "PID is outside the structured read allowlist")
        }
        requireAdvertised(pid)
        failureStage = NativeFailureStage.READING
        return guarded { reading(pid, snapshot = false, structured = true) }
    }

    /** Read Mode 09 PID 02 only; exact NO DATA indicates unsupported VIN identification. */
    fun identifyVin(): String? {
        requireConnected()
        failureStage = NativeFailureStage.VIN
        return guarded {
            val lines = command("0902", allowNoData = true, allowVin = true)
            if (lines == listOf("NODATA")) null else decodeVin(
                if (canResponses == null) lines else listOf(selectedResponse(lines, vin = true)))
        }
    }

    /** A single sequential snapshot of advertised, decodable PIDs on the separate snapshot allowlist. */
    fun snapshot(checkActive: () -> Unit = {}): List<NativeReading> {
        requireConnected()
        val eligible = checkNotNull(discovered).pids
            .intersect(ObdDecoder.snapshotPids)
            .intersect(ObdDecoder.catalog.keys)
            .minus(ObdDecoder.rangeBases)
            .sorted()
        // A protocol failure throws away this local partial result and releases the session.
        failureStage = NativeFailureStage.SNAPSHOT
        return guarded { eligible.map { checkActive(); reading(it, snapshot = true) } }
    }

    /** Read stored Mode 03 identifiers from one validated response source. */
    fun readStoredDtcs(checkActive: () -> Unit = {}): StoredDtcRead {
        requireConnected()
        failureStage = NativeFailureStage.STORED_DTCS
        return guarded {
            checkActive()
            if (canResponses != null) {
                val lines = command("03", allowNoData = true, allowStoredDtcs = true)
                if (lines == listOf("NODATA")) StoredDtcRead(null, null)
                else {
                    val payload = checkNotNull(canResponses).decode(lines, storedDtcs = true)[selectedEcu]
                        ?: malformed("Selected ECU $selectedEcu did not respond to Mode 03")
                    StoredDtcRead(decode { StoredDtcDecoder.decode(payload, can = true) }, selectedEcu)
                }
            } else {
                val protocol = storedDtcProtocol ?: command("ATDPN").singleOrNull()?.uppercase()
                    ?.removePrefix("A")?.also { storedDtcProtocol = it }
                    ?: malformed("Could not identify the OBD protocol for Mode 03")
                when (protocol) {
                    in setOf("1", "2", "3", "4", "5") -> {
                        checkActive()
                        val lines = command("03", allowNoData = true, allowStoredDtcs = true)
                        if (lines == listOf("NODATA")) StoredDtcRead(null, null)
                        else {
                            if (lines.size != 1) malformed("Mode 03 returned multiple unaddressed responses")
                            StoredDtcRead(decode { StoredDtcDecoder.decode(lines.single(), can = false) }, null)
                        }
                    }
                    in setOf("6", "7", "8", "9") -> readStoredDtcsWithTemporaryCanHeaders(protocol, checkActive)
                    else -> throw NativeDiagnosticException(ErrorKind.UNSUPPORTED,
                        "Mode 03 protocol $protocol is not supported")
                }
            }
        }
    }

    /** User-invoked parked scan only. Standard code services and advertised frame-zero PIDs. */
    fun readDiagnosticContext(checkActive: () -> Unit = {}): DiagnosticContextRead {
        requireConnected()
        failureStage = NativeFailureStage.DIAGNOSTIC_CONTEXT
        return guarded {
            checkActive()
            val protocol = if (canResponses == null) storedDtcProtocol ?: command("ATDPN").singleOrNull()
                ?.uppercase()?.removePrefix("A")?.also { storedDtcProtocol = it }
                ?: malformed("Could not identify protocol for diagnostic context") else null
            val temporary = canResponses == null && protocol in setOf("6", "7", "8", "9")
            if (canResponses == null && !temporary && protocol !in setOf("1", "2", "3", "4", "5"))
                throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, "Diagnostic context protocol is unsupported")
            val parser = canResponses ?: if (temporary) CanObdResponses.forProtocol(listOf(checkNotNull(protocol))) else null
            if (temporary) for (request in listOf("ATCAF1", "ATD0", "ATH1")) {
                checkActive()
                if (command(request) != listOf("OK")) malformed("Unexpected response to $request")
            }
            var source = selectedEcu
            fun reply(request: String): Pair<String?, DiagnosticReplyStatus> {
                checkActive()
                val lines = command(request, allowNoData = true, allowDiagnosticContext = true)
                if (lines == listOf("NODATA")) return null to DiagnosticReplyStatus.NO_DATA
                val payload = if (parser == null) {
                    if (lines.size != 1) malformed("Diagnostic context returned ambiguous unaddressed responses")
                    lines.single().uppercase()
                } else {
                    val responses = parser.decode(lines, storedDtcs = request in DtcService.entries.map { it.request })
                    if (source == null) {
                        if (responses.size != 1) malformed("Diagnostic context source is ambiguous")
                        source = responses.keys.single()
                    }
                    responses[source] ?: malformed("Selected diagnostic context ECU did not respond")
                }
                val mode = request.take(2)
                if (payload.startsWith("7F")) {
                    if (payload.length != 6 || payload.substring(2, 4) != mode) malformed("Invalid negative diagnostic response")
                    return when (payload.takeLast(2)) {
                        "11", "12", "31" -> null to DiagnosticReplyStatus.UNSUPPORTED
                        "22" -> null to DiagnosticReplyStatus.UNAVAILABLE
                        else -> malformed("Unexpected negative diagnostic response")
                    }
                }
                return payload to DiagnosticReplyStatus.AVAILABLE
            }
            try {
                val codes = DtcService.entries.map { service ->
                    val (payload, status) = reply(service.request)
                    DiagnosticCodeRead(service, status, payload?.let { decode { StoredDtcDecoder.decode(it, parser != null, service) } }.orEmpty(), source.takeUnless { status == DiagnosticReplyStatus.NO_DATA })
                }
                val (support, supportStatus) = reply("020000")
                val freeze = if (support == null) FreezeFrameRead(supportStatus, sourceEcu = source.takeUnless { supportStatus == DiagnosticReplyStatus.NO_DATA },
                    note = "Frame-zero support was not available; no freeze-frame measurements requested.")
                else {
                    val advertised = decode { FreezeFrameDecoder.supported(support) }
                    if (0x02 !in advertised) FreezeFrameRead(DiagnosticReplyStatus.UNSUPPORTED, sourceEcu = source,
                        advertisedPids = advertised, note = "Frame-zero trigger PID was not advertised.")
                    else {
                        val (trigger, triggerStatus) = reply("020200")
                        val code = trigger?.let { decode { FreezeFrameDecoder.trigger(it) } }
                        if (trigger == null || code == null) FreezeFrameRead(triggerStatus, sourceEcu = source.takeUnless { triggerStatus == DiagnosticReplyStatus.NO_DATA },
                            advertisedPids = advertised, note = if (trigger == null) "Freeze-frame trigger was unavailable." else "ECU returned no frame-zero trigger code; no measurements requested.")
                        else {
                            val unavailable = mutableMapOf<Int, DiagnosticReplyStatus>()
                            val readings = FreezeFrameDecoder.pids.intersect(advertised).sorted().map { pid ->
                                val request = "02${hex(pid)}00"
                                val (payload, status) = reply(request)
                                if (status != DiagnosticReplyStatus.AVAILABLE) unavailable[pid] = status
                                if (status == DiagnosticReplyStatus.UNSUPPORTED || status == DiagnosticReplyStatus.UNAVAILABLE)
                                    NativeReading(pid, null, source)
                                else NativeReading(pid, payload?.let { decode { FreezeFrameDecoder.reading(it, pid) } }, source)
                            }
                            FreezeFrameRead(DiagnosticReplyStatus.AVAILABLE, code, readings, source, advertised,
                                "ECU-stored frame zero; its capture time and relation to this drive are unknown.", unavailable)
                        }
                    }
                }
                DiagnosticContextRead(codes, freeze)
            } finally {
                if (temporary) {
                    checkActive()
                    if (command("ATH0") != listOf("OK")) malformed("Could not restore hidden CAN headers")
                }
            }
        }
    }

    private fun readStoredDtcsWithTemporaryCanHeaders(protocol: String,
        checkActive: () -> Unit): StoredDtcRead {
        val parser = CanObdResponses.forProtocol(listOf(protocol))
        for (request in listOf("ATCAF1", "ATD0", "ATH1")) {
            checkActive()
            if (command(request) != listOf("OK")) malformed("Unexpected response to $request")
        }
        return try {
            checkActive()
            val lines = command("03", allowNoData = true, allowStoredDtcs = true)
            if (lines == listOf("NODATA")) StoredDtcRead(null, null)
            else {
                val responses = parser.decode(lines, storedDtcs = true)
                if (responses.size != 1) malformed("Mode 03 source is ambiguous without a selected ECU")
                val (source, payload) = responses.entries.single()
                StoredDtcRead(decode { StoredDtcDecoder.decode(payload, can = true) }, source)
            }
        } finally {
            // Initialization used ATH0. Restore it before a subsequent scalar read.
            checkActive()
            if (command("ATH0") != listOf("OK")) malformed("Could not restore hidden CAN headers")
        }
    }

    private fun reading(pid: Int, snapshot: Boolean, structured: Boolean = false): NativeReading {
        val lines = command(mode01(pid), allowNoData = true, allowSnapshot = snapshot, allowStructured = structured)
        if (lines == listOf("NODATA")) return NativeReading(pid, null, selectedEcu)
        val decoded = decode { ObdDecoder.decode(pid, mode01Response(pid, lines)) }
        if (structured && decoded !is DecodedReading.Structured) malformed("Live structured PID requires a structured result")
        if (!snapshot && !structured && decoded !is DecodedReading.Scalar) malformed("Live PID requires a scalar result")
        return NativeReading(pid, decoded, selectedEcu)
    }

    /** One bounded recovery, only for multiple valid initial capability bitmaps.
     * Prefer an RPM-advertising source, then the lowest response ID. This is a
     * deterministic source-selection policy, not an inference of ECU function.
     * Never union capabilities or change the chosen source during a session.
     */
    private fun discoverAddressedSource(lines: List<String>, checkActive: () -> Unit): Set<Int> {
        lines.forEach { line -> decode { ObdDecoder.supported(0, mode01Response(0, listOf(line))) } }
        checkActive()
        val parser = CanObdResponses.forProtocol(command("ATDPN"))
        for (request in listOf("ATCAF1", "ATD0", "ATH1")) {
            checkActive()
            if (command(request) != listOf("OK")) malformed("Unexpected response to $request")
        }
        checkActive()
        val responses = parser.decode(command("0100"))
        val capabilities = responses.mapValues { (_, payload) -> decode { ObdDecoder.supported(0, payload) } }
        val selected = capabilities.keys.sortedWith(compareBy<String> { 0x0C !in capabilities.getValue(it) }.thenBy { it })
            .firstOrNull() ?: malformed("No addressed ECU capability response")
        canResponses = parser
        selectedEcu = selected
        return capabilities.getValue(selected)
    }

    private fun selectedResponse(lines: List<String>, vin: Boolean = false): String =
        checkNotNull(canResponses).decode(lines, vin)[selectedEcu]
            ?: malformed("Selected ECU $selectedEcu did not respond; refusing another ECU's data")

    private fun initialize(checkActive: () -> Unit) {
        checkActive()
        val reset = command("ATZ", timeoutMillis = 10_000)
        if (reset.none { "ELM327" in it.uppercase() }) {
            throw NativeDiagnosticException(ErrorKind.ADAPTER, "Adapter did not identify as ELM327 during reset")
        }
        for (request in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            checkActive()
            if (command(request) != listOf("OK")) malformed("Unexpected response to $request")
        }
        checkActive()
        val response = command("ATI")
        if (response.size != 1 || !response.single().uppercase().startsWith("ELM327")) {
            malformed("ATI returned an invalid adapter identity")
        }
        identity = response.single()
    }

    private fun requireConnected() {
        if (state != NativeSessionState.CONNECTED || discovered == null) {
            throw NativeDiagnosticException(ErrorKind.NOT_CONNECTED, "Adapter is not connected")
        }
    }

    private fun requireAdvertised(pid: Int) {
        if (pid !in checkNotNull(discovered).pids) {
            throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, "Vehicle does not advertise ${mode01(pid)}")
        }
    }

    private fun clearVehicle() {
        discovered = null
        identity = null
        canResponses = null
        selectedEcu = null
        storedDtcProtocol = null
    }

    private inline fun <T> guarded(operation: () -> T): T {
        try {
            return operation()
        } catch (error: Throwable) {
            // A broken diagnostic sink must never prevent resource cleanup.
            try { transport.reportSessionFailure(failureStage,
                (error as? NativeDiagnosticException)?.kind ?: ErrorKind.TRANSPORT) } catch (_: Throwable) { }
            clearVehicle()
            state = NativeSessionState.FAULTED
            try {
                transport.close()
                cleanupUncertain = false
            } catch (_: Throwable) {
                cleanupUncertain = true
            }
            throw error
        }
    }

    private inline fun <T> transportOperation(description: String, operation: () -> T): T = try {
        operation()
    } catch (error: NativeDiagnosticException) {
        throw error
    } catch (error: Exception) {
        throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Failed to $description: ${error.message}", error)
    }

    private inline fun <T> decode(operation: () -> T): T = try {
        operation()
    } catch (error: NativeDiagnosticException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Invalid OBD response: ${error.message}", error)
    }

    /** This is intentionally private and validates its own allowlist, even for internal callers. */
    private fun command(
        request: String,
        timeoutMillis: Long = NATIVE_STANDARD_READ_TIMEOUT_MILLIS,
        allowNoData: Boolean = false,
        allowSnapshot: Boolean = false,
        allowStructured: Boolean = false,
        allowVin: Boolean = false,
        allowStoredDtcs: Boolean = false,
        allowDiagnosticContext: Boolean = false,
    ): List<String> {
        val standard = (ObdDecoder.scalarPids + ObdDecoder.rangeBases).any { mode01(it) == request }
        val snapshot = allowSnapshot && ObdDecoder.snapshotPids.any { mode01(it) == request }
        val structured = allowStructured && request in setOf("0101", "0103")
        if (request !in INIT_COMMANDS && !standard && !snapshot && !structured &&
            !(allowVin && request == "0902") && !(allowStoredDtcs && request == "03") &&
            !(allowDiagnosticContext && (request in DtcService.entries.map { it.request } || request in FreezeFrameDecoder.requests))) {
            throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, "Command is outside the read-only allowlist")
        }
        val raw = transportOperation("exchange $request") { transport.exchange(request, timeoutMillis) }
        if (raw.length > 4096 || !raw.endsWith('>') || '>' in raw.dropLast(1)) {
            malformed("ELM327 $request response is missing its prompt, has extra prompts, or exceeds the limit")
        }
        if (raw.any { it.code > 127 }) malformed("ELM327 $request response is not ASCII")
        val lines = raw.dropLast(1).replace('\n', '\r').split('\r')
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() && it.uppercase() != request && it.uppercase() !in INFORMATIONAL }
        if (lines.isEmpty()) malformed("ELM327 $request returned an empty response")
        for (line in lines) {
            val upper = line.uppercase()
            if (allowNoData && upper == "NODATA") continue
            if (ERROR_RESPONSES.any { upper.startsWith(it) }) {
                throw NativeDiagnosticException(ErrorKind.ADAPTER, "ELM327 $request reported $line")
            }
        }
        // Only a standalone marker is absence. Mixed data plus NO DATA is a malformed packet.
        return lines.map { if (it.uppercase() == "NODATA") "NODATA" else it }
    }

    private fun mode01Response(pid: Int, lines: List<String>): String {
        val prefix = "41${hex(pid)}"
        if (canResponses == null && lines.size != 1) {
            malformed("${mode01(pid)} returned multiple unaddressed ECU responses; source-aware headers are required")
        }
        val response = if (canResponses == null) lines.single().uppercase() else selectedResponse(lines)
        if (!response.startsWith(prefix)) malformed("${mode01(pid)} returned an unexpected response")
        return response
    }

    private fun decodeVin(lines: List<String>): String {
        if (lines.size > 16) malformed("VIN response exceeds the fragment limit")
        val fragments = mutableListOf<String>()
        val plain = mutableListOf<String>()
        for (line in lines) {
            if (':' !in line) {
                plain.add(line.uppercase())
                continue
            }
            val indexText = line.substringBefore(':')
            val payload = line.substringAfter(':')
            if (indexText.isEmpty() || indexText.any { it !in '0'..'9' }) malformed("Invalid VIN fragment index")
            val index = indexText.toIntOrNull() ?: malformed("VIN fragment index exceeds the limit")
            if (index >= 16 || index != fragments.size) malformed("VIN fragment ordering is invalid")
            validateHexFragment(payload)
            fragments.add(payload.uppercase())
        }
        if (fragments.isNotEmpty() && plain.isNotEmpty()) malformed("VIN response mixes indexed and plain data")
        val payload = if (fragments.isNotEmpty()) {
            fragments.joinToString("")
        } else {
            if (plain.size != 1) malformed("VIN response has multiple unaddressed ECU responses")
            plain.single().also { validateHexFragment(it) }
        }
        if (payload.first().digitToInt(16) <= 3) malformed("Raw ISO-TP frames require source-aware headers")
        if (payload.length != 40 || !payload.startsWith("490201")) {
            malformed("VIN response must contain Mode 09 PID 02, one VIN, and exactly 17 characters")
        }
        val vin = payload.drop(6).chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        if (vin.length != 17 || vin.any { it !in "ABCDEFGHJKLMNPRSTUVWXYZ0123456789" }) {
            malformed("VIN response is not a valid 17-character ASCII identifier")
        }
        return vin
    }

    private fun validateHexFragment(payload: String) {
        if (payload.isEmpty() || payload.length % 2 != 0 || payload.any { it !in "0123456789abcdefABCDEF" }) {
            malformed("VIN response contains malformed hex")
        }
    }

    private fun malformed(message: String): Nothing = throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, message)

    private fun hex(pid: Int): String = pid.toString(16).uppercase().padStart(2, '0')
    private fun mode01(pid: Int): String = "01${hex(pid)}"

    private companion object {
        val INIT_COMMANDS = setOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATI",
            "ATDPN", "ATCAF1", "ATD0", "ATH1")
        val INFORMATIONAL = setOf("SEARCHING...", "BUSINIT:OK")
        val ERROR_RESPONSES = setOf("?", "NODATA", "STOPPED", "BUSERROR", "CANERROR", "UNABLETOCONNECT", "BUFFERFULL", "DATAERROR")
    }
}
