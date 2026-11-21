package org.openwrench.frontend

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.*
import org.openwrench.core.*

private const val MAX_REPLAY_BYTES = 262_144

/** A user-selected document stream; caller retains ownership and closes it. */
fun readCivicRecording(input: InputStream): ReplayLoadResult = try {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= MAX_REPLAY_BYTES) {
        val read = input.read(buffer, 0, minOf(buffer.size, MAX_REPLAY_BYTES + 1 - output.size()))
        if (read < 0) break
        if (read == 0) {
            val next = input.read()
            if (next < 0) break
            output.write(next)
        } else output.write(buffer, 0, read)
    }
    require(output.size() <= MAX_REPLAY_BYTES)
    val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    readCivicRecordingText(text)
} catch (_: Exception) {
    ReplayLoadResult.Invalid("Cannot read a valid timed Civic recording (UTF-8 JSON, maximum 256 KiB). No data loaded.")
}

/** Parses bounded document contents without a desktop path, server, or adapter. */
fun readCivicRecordingText(text: String): ReplayLoadResult = try {
    require(text.length <= MAX_REPLAY_BYTES)
    val encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
    require(encoded.remaining() <= MAX_REPLAY_BYTES)
    rejectDuplicateObjectKeys(text)
    val root = Json.parseToJsonElement(text).jsonObject
    root.fields("schema", "capture", "duration_ms", "signals", "observations")
    require(root.text("schema") == "openwrench.civic-replay/1")
    val metadata = root.getValue("capture").jsonObject
    metadata.fields("origin", "vehicle_label", "captured_at_utc", "clock_basis", "provenance")
    require(metadata.text("origin") == "hardware") { "Simulation is not a Civic recording" }
    val vehicle = metadata.text("vehicle_label")
    require(vehicle.length <= 100 && vehicle.contains("Honda", ignoreCase = true) && vehicle.contains("Civic", ignoreCase = true))
    val basis = metadata.text("clock_basis")
    require(basis in setOf("backend_observation", "client_receipt"))
    val provenance = metadata.text("provenance")
    require(provenance.isNotBlank() && provenance.length <= 1000)
    val capture = CivicCaptureMetadata(vehicle, metadata.timestamp("captured_at_utc"), basis, provenance)
    val catalog = root.getValue("signals").jsonArray.map { element ->
        val item = element.jsonObject
        item.fields("id", "pid", "label", "unit", "value_type", "support")
        val known = requireNotNull(LiveParameter.fromIdentifier(item.text("id")))
        require(item.text("support") == "supported")
        require(item.text("pid") == "01" + known.pid.toString(16).uppercase().padStart(2, '0'))
        require(item.text("unit") == known.unit.symbol)
        val type = if (known == LiveParameter.RUNTIME) SignalType.INTEGER else SignalType.NUMBER
        require(item.text("value_type") == if (type == SignalType.INTEGER) "integer" else "number")
        val label = item.text("label"); require(label.isNotBlank() && label.length <= 100)
        SignalDefinition(known.identifier, label, known.unit.symbol, type, SignalSupport.SUPPORTED, item.text("pid"))
    }
    val events = root.getValue("observations").jsonArray.map { element ->
        val item = element.jsonObject
        item.fields("at_ms", "signal", "availability", "value", "observed_at_utc", "age_at_capture_ms", "reason")
        val definition = catalog.single { it.id == item.text("signal") }
        val availability = when (item.text("availability")) {
            "available" -> SignalAvailability.AVAILABLE
            "unavailable" -> SignalAvailability.UNAVAILABLE
            "stale" -> SignalAvailability.STALE
            else -> error("Unsupported observation state")
        }
        val value = item.getValue("value").let { raw ->
            if (raw == JsonNull) null else {
                val number = raw.jsonPrimitive.also { require(!it.isString) }
                if (definition.type == SignalType.INTEGER) SignalValue.Integer(number.long) else SignalValue.Number(number.double)
            }
        }
        val age = item.getValue("age_at_capture_ms").let { if (it == JsonNull) null else item.integer("age_at_capture_ms") }
        val reason = item.nullableText("reason"); require(reason == null || reason.length <= 500)
        RecordedObservation(item.integer("at_ms"), definition.id, availability, value,
            item.timestamp("observed_at_utc"), age, reason)
    }
    ReplayLoadResult.Loaded(CivicRecording(capture, root.integer("duration_ms"), catalog, events))
} catch (_: Exception) {
    ReplayLoadResult.Invalid("Invalid timed Civic recording. Check version, supported signals, units/types, monotonic offsets and repeated observations. No data loaded.")
}

/** Check raw object members before JsonObject construction can replace an earlier value. */
private fun rejectDuplicateObjectKeys(text: String) {
    var index = 0
    fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
    fun quoted(): String {
        require(index < text.length && text[index] == '"')
        val start = index++
        while (index < text.length) {
            when (text[index++]) {
                '"' -> return Json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content
                '\\' -> { require(index < text.length); index++ }
            }
        }
        error("Unterminated JSON string")
    }
    fun value(depth: Int) {
        // Replay schema has at most three nested containers; this also bounds malformed input.
        require(depth <= 32)
        whitespace()
        require(index < text.length)
        when (text[index]) {
            '{' -> {
                index++; whitespace()
                val keys = mutableSetOf<String>()
                if (index < text.length && text[index] == '}') { index++; return }
                while (true) {
                    require(keys.add(quoted())) { "Duplicate JSON object key" }
                    whitespace(); require(index < text.length && text[index++] == ':')
                    value(depth + 1); whitespace()
                    require(index < text.length)
                    when (text[index++]) {
                        '}' -> return
                        ',' -> whitespace()
                        else -> error("Invalid JSON object")
                    }
                }
            }
            '[' -> {
                index++; whitespace()
                if (index < text.length && text[index] == ']') { index++; return }
                while (true) {
                    value(depth + 1); whitespace()
                    require(index < text.length)
                    when (text[index++]) {
                        ']' -> return
                        ',' -> Unit
                        else -> error("Invalid JSON array")
                    }
                }
            }
            '"' -> quoted()
            else -> {
                val start = index
                while (index < text.length && text[index] !in ",]} \t\r\n") index++
                require(index > start)
            }
        }
    }
    value(0)
    whitespace()
    require(index == text.length)
}

private fun JsonObject.fields(vararg names: String) { require(keys == names.toSet()) }
private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.integer(name: String) = getValue(name).jsonPrimitive.also { require(!it.isString) }.long
private fun JsonObject.nullableText(name: String): String? = if (getValue(name) == JsonNull) null else text(name)
private fun JsonObject.timestamp(name: String): String? = nullableText(name)?.also(::validateReplayTimestamp)

// java.time.Instant is unavailable on Android API 24. Validate the original UTC
// ISO timestamp with Gregorian arithmetic instead of changing or re-stamping it.
private val replayTimestamp = Regex("""([0-9]{4}|[+][0-9]{5,10}|-[0-9]{4,10})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{0,9}))?Z""")
private fun validateReplayTimestamp(value: String) {
    val parts = requireNotNull(replayTimestamp.matchEntire(value)).groupValues
    val year = parts[1].toLong()
    require(year in -1_000_000_000L..1_000_000_000L)
    require(!(parts[1].startsWith("-") && year == 0L))
    val month = parts[2].toInt()
    val day = parts[3].toInt()
    val hour = parts[4].toInt()
    val minute = parts[5].toInt()
    val second = parts[6].toInt()
    val leapYear = year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)
    require(month in 1..12)
    val days = when (month) { 2 -> if (leapYear) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    require(day in 1..days)
    require(minute in 0..59)
    // Match Instant's end-of-day and leap-second acceptance without normalizing
    // either representation; the capture's original spelling remains visible.
    if (hour == 24) {
        require(minute == 0 && second == 0 && parts[7].all { it == '0' })
        require(!(year == 1_000_000_000L && month == 12 && day == 31))
    } else {
        require(hour in 0..23)
        require(second in 0..59 || (hour == 23 && minute == 59 && second == 60))
    }
}
