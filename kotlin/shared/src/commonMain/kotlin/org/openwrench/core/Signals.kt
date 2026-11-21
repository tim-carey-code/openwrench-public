package org.openwrench.core

/** Catalog identity is independent of the legacy simulator's fixed LiveParameter enum. */
enum class SignalType { INTEGER, NUMBER, BOOLEAN, CATEGORY, TEXT, STRUCTURED, TROUBLE_CODES, UNKNOWN }
enum class SignalSupport { UNKNOWN, SUPPORTED, UNSUPPORTED }
/** Shared availability vocabulary, also used by the existing dashboard. */
enum class SignalAvailability { AVAILABLE, NOT_SAMPLED, UNAVAILABLE, STALE, ERROR, DISCONNECTED }

data class SignalDefinition(
    val id: String,
    val label: String,
    val unit: String?,
    val type: SignalType,
    val support: SignalSupport,
    val pid: String? = null,
    val module: String? = null,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank())
        require(unit == null || unit.isNotBlank())
        require(module == null || module.isNotBlank())
        require(pid == null || Regex("01[0-9A-F]{2}").matches(pid))
    }
}

/** Display values only; this model neither decodes wire data nor infers component health. */
sealed interface SignalValue {
    data class Integer(val value: Long) : SignalValue
    data class Number(val value: Double) : SignalValue { init { require(value.isFinite()) } }
    data class Boolean(val value: kotlin.Boolean) : SignalValue
    data class Category(val value: String) : SignalValue
    data class Text(val value: String) : SignalValue
    data class Structured(val fields: Map<String, String>) : SignalValue
    data class TroubleCodes(val codes: List<String>) : SignalValue
}

fun SignalValue.display(): String = when (this) {
    is SignalValue.Integer -> value.toString()
    is SignalValue.Number -> if (value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is SignalValue.Boolean -> if (value) "Yes" else "No"
    is SignalValue.Category -> value
    is SignalValue.Text -> value
    is SignalValue.Structured -> fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    is SignalValue.TroubleCodes -> codes.joinToString(", ").ifEmpty { "No codes supplied" }
}
