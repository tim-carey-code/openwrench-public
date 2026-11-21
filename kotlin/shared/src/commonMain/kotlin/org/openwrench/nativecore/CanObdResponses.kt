package org.openwrench.nativecore

/** ELM327 ATH1/ATD0/CAF1 replies for standard ISO 15765-4 OBD (protocols 6–9).
 * Headers stay enabled: every payload is associated with its observed response ID.
 * Mode 01 uses single frames; explicit VIN and code-service requests may assemble
 * bounded multi-frame replies.
 */
internal class CanObdResponses(private val idDigits: Int) {
    fun decode(lines: List<String>, vin: Boolean = false, storedDtcs: Boolean = false): Map<String, String> {
        require(!(vin && storedDtcs))
        if (lines.isEmpty() || lines.size > 32) malformed("Invalid CAN response count")
        val complete = mutableMapOf<String, String>()
        val partial = mutableMapOf<String, Fragment>()
        for (raw in lines) {
            val line = raw.uppercase()
            if (line.length < idDigits + 4 || line.any { it !in "0123456789ABCDEF" }) {
                malformed("Invalid addressed CAN response")
            }
            val source = line.take(idDigits)
            if (if (idDigits == 3) source.toInt(16) !in 0x7E8..0x7EF
                else !source.startsWith("18DAF1")) malformed("Unexpected CAN OBD response address")
            val data = line.drop(idDigits)
            if (data.length % 2 != 0 || data.length > 16) malformed("Invalid CAN frame size")
            val bytes = data.chunked(2).map { it.toInt(16) }
            if (source in complete) malformed("Duplicate CAN response from ECU $source")
            when (bytes[0] shr 4) {
                0 -> {
                    val size = bytes[0]
                    if (source in partial || size !in 1..7 || bytes.size < size + 1) {
                        malformed("Invalid CAN single-frame length")
                    }
                    complete[source] = data.substring(2, 2 + size * 2)
                }
                1 -> {
                    val totalBytes = (bytes[0] and 15) * 256 + bytes[1]
                    if ((!vin && !storedDtcs) || source in partial || bytes.size != 8 ||
                        (vin && totalBytes != 20) ||
                        (storedDtcs && (totalBytes !in 8..128 || totalBytes % 2 != 0))) {
                        malformed("Unexpected CAN multi-frame response")
                    }
                    partial[source] = Fragment(data.drop(4), 1, totalBytes * 2)
                }
                2 -> {
                    val fragment = partial[source] ?: malformed("CAN continuation has no first frame")
                    if ((bytes[0] and 15) != fragment.nextSequence) malformed("CAN fragment sequence mismatch")
                    val remaining = fragment.targetLength - fragment.payload.length
                    val take = minOf(14, remaining)
                    if (data.length - 2 < take) malformed("Truncated CAN continuation")
                    fragment.payload += data.substring(2, 2 + take)
                    fragment.nextSequence = (fragment.nextSequence + 1) % 16
                    if (fragment.payload.length == fragment.targetLength) {
                        complete[source] = fragment.payload
                        partial.remove(source)
                    }
                }
                else -> malformed("Unexpected CAN PCI frame type")
            }
        }
        if (partial.isNotEmpty()) malformed("Incomplete CAN multi-frame response")
        return complete
    }

    private data class Fragment(var payload: String, var nextSequence: Int, val targetLength: Int)

    private fun malformed(message: String): Nothing =
        throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, message)

    companion object {
        fun forProtocol(reply: List<String>): CanObdResponses {
            val protocol = reply.singleOrNull()?.uppercase()?.removePrefix("A")
            return when (protocol) {
                "6", "8" -> CanObdResponses(3)
                "7", "9" -> CanObdResponses(8)
                else -> throw NativeDiagnosticException(ErrorKind.UNSUPPORTED,
                    "Multiple ECU recovery currently requires ISO 15765-4 CAN (ELM protocols 6–9)")
            }
        }
    }
}
