package org.openwrench.nativecore

/** A null code list means an exact standalone NO DATA reply, not zero stored codes. */
data class StoredDtcRead(val codes: List<String>?, val sourceEcu: String?)

/** Standard stored/pending/permanent identifiers; descriptions require separate reviewed data. */
internal object StoredDtcDecoder {
    fun decode(payload: String, can: Boolean, service: DtcService = DtcService.STORED): List<String> {
        require(payload.length % 2 == 0 && payload.length >= 2 &&
            payload.all { it in "0123456789ABCDEFabcdef" }) { "Invalid code-service hex payload" }
        require(payload.take(2).equals(service.response, ignoreCase = true)) { "Unexpected diagnostic service response" }
        val bytes = payload.chunked(2).map { it.toInt(16) }
        val codeBytes = if (can) {
            require(bytes.size >= 2) { "CAN code-service response lacks a code count" }
            val count = bytes[1]
            require(count <= 63 && bytes.size == 2 + count * 2) { "CAN code-service code count or length mismatch" }
            bytes.drop(2)
        } else {
            require(bytes.size >= 3 && (bytes.size - 1) % 2 == 0 && bytes.size <= 255) {
                "Invalid code-service code-pair length"
            }
            bytes.drop(1)
        }
        return codeBytes.chunked(2).mapNotNull { pair ->
            val high = pair[0]
            val low = pair[1]
            if (high == 0 && low == 0) {
                require(!can) { "CAN code-service contained an empty counted code" }
                null
            } else {
                val family = "PCBU"[high shr 6]
                "$family${(high shr 4) and 3}${(high and 15).toString(16).uppercase()}" +
                    low.toString(16).uppercase().padStart(2, '0')
            }
        }
    }
}
