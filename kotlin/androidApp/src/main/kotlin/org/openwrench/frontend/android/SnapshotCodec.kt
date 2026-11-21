package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import kotlin.math.abs

/** Imported measurements are historical replay data and must never be labelled live. */
data class ImportedSnapshot(val readings: List<NativeReading>, val identity: String?, val simulated: Boolean, val vin: String?)

/** Offline, bounded snapshot/1 interchange. No adapter or Python runtime is used. */
object SnapshotCodec {
    const val MAX_BYTES = 1_048_576
    private const val VERSION = "openwrench.snapshot/1"
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private val json = Json { prettyPrint = true }

    /** Export only the VIN retained with this snapshot, never the current connection's VIN. */
    fun encode(state: NativeUiState, includeVin: Boolean, expectedGeneration: Long?): String {
        val readings = state.snapshot ?: error("Capture a snapshot first")
        require(expectedGeneration != null && state.snapshotSessionGeneration == expectedGeneration) {
            "Snapshot changed. Review the export selection and try again."
        }
        require(!includeVin || state.snapshotVin != null) { "This snapshot has no VIN to include" }
        return encode(readings,
            state.origin == ReadingOrigin.SIMULATOR || (state.origin == ReadingOrigin.REPLAY && state.replaySimulated),
            state.identity, if (includeVin) state.snapshotVin else null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun encode(readings: List<NativeReading>, simulated: Boolean, identity: String?, vin: String? = null): String =
        encodeValidatedReadings(readings, simulated, identity, vin, ObdDecoder.snapshotPids)

    @Suppress("UNUSED_PARAMETER")
    internal fun encodeValidatedReadings(readings: List<NativeReading>, simulated: Boolean, identity: String?,
        vin: String?, allowedPids: Set<Int>): String {
        // The reference format intentionally omits self-reported adapter identity.
        require(readings.map { it.pid }.distinct().size == readings.size) { "Snapshot PIDs must be unique" }
        val document = buildJsonObject {
            put("schema_version", VERSION)
            put("source", buildJsonObject {
                put("adapter_id", if (simulated) "simulator" else "elm327")
                put("kind", if (simulated) "simulator" else "real_ecu")
                put("simulated", simulated)
            })
            put("readings", JsonArray(readings.sortedBy { it.pid }.map { reading ->
                val metadata = ObdDecoder.catalog[reading.pid] ?: invalid("Unknown PID")
                val value = reading.value
                buildJsonObject {
                    put("pid", reading.pid)
                    put("name", metadata.name)
                    put("description", description(reading.pid))
                    put("status", if (value == null) "unavailable" else "available")
                    put("value", when (value) {
                        null -> JsonNull
                        is DecodedReading.Scalar -> if (value.integer) {
                            require(value.value.isFinite() && value.value % 1.0 == 0.0 && abs(value.value) <= MAX_INTEGER.toDouble()) { "Invalid integer reading" }
                            JsonPrimitive(value.value.toLong())
                        } else JsonPrimitive(value.value)
                        is DecodedReading.Structured -> unflatten(value.fields)
                    })
                    put("value_type", when (value) {
                        null -> JsonNull
                        is DecodedReading.Scalar -> JsonPrimitive(if (value.integer) "integer" else "number")
                        is DecodedReading.Structured -> JsonPrimitive("structured")
                    })
                    put("unit", metadata.unit?.let(::JsonPrimitive) ?: JsonNull)
                    put("reason", if (value == null) JsonPrimitive("no_data") else JsonNull)
                }
            }))
            if (vin != null) put("vehicle_identification", buildJsonObject {
                put("status", "available")
                put("vin", vin)
                put("identity_kind", if (simulated) "simulated" else "reported")
            })
        }
        val text = json.encodeToString(JsonElement.serializer(), document) + "\n"
        decodeValidatedReadings(text, allowedPids) // Apply the same semantic and size checks before export.
        return text
    }

    fun decode(text: String): ImportedSnapshot = decodeValidatedReadings(text, ObdDecoder.snapshotPids)

    internal fun decodeValidatedReadings(text: String, allowedPids: Set<Int>): ImportedSnapshot {
        require(text.encodeToByteArray().size <= MAX_BYTES) { "Snapshot exceeds the size limit" }
        StrictJsonCheck(text).check()
        val document = try { json.parseToJsonElement(text) } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Snapshot is malformed", error)
        }
        validatePrimitives(document)
        val root = document as? JsonObject ?: invalid("Snapshot must be a JSON object")
        exact(root, setOf("schema_version", "source", "readings") + if ("vehicle_identification" in root) setOf("vehicle_identification") else emptySet())
        require(string(root["schema_version"]) == VERSION) { "Unsupported snapshot version" }
        val source = obj(root["source"])
        exact(source, setOf("adapter_id", "kind", "simulated"))
        val simulated = boolean(source["simulated"])
        require(string(source["kind"]) == if (simulated) "simulator" else "real_ecu") { "Conflicting snapshot source" }
        require(string(source["adapter_id"]) == if (simulated) "simulator" else "elm327") { "Unsupported snapshot adapter" }
        val rows = root["readings"] as? JsonArray ?: invalid("Snapshot readings must be an array")
        require(rows.size <= allowedPids.size) { "Too many snapshot readings" }
        var previous = -1
        val readings = rows.map { element ->
            val row = obj(element)
            exact(row, setOf("pid", "name", "description", "status", "value", "value_type", "unit", "reason"))
            val pid = integer(row["pid"], 0, 255).toInt()
            require(pid in allowedPids && pid > previous) { "Snapshot PIDs must be supported, unique and ordered" }
            previous = pid
            val metadata = ObdDecoder.catalog.getValue(pid)
            require(string(row["name"]) == metadata.name && string(row["description"]) == description(pid)) { "Snapshot metadata does not match PID" }
            require(row["unit"] == (metadata.unit?.let(::JsonPrimitive) ?: JsonNull)) { "Snapshot unit does not match PID" }
            val value = when (string(row["status"])) {
                "unavailable" -> {
                    require(row["value"] == JsonNull && row["value_type"] == JsonNull && string(row["reason"]) == "no_data") { "Malformed unavailable reading" }
                    null
                }
                "available" -> {
                    require(row["reason"] == JsonNull && row["value"] != JsonNull) { "Available reading requires a value and no reason" }
                    if (metadata.structured) {
                        require(string(row["value_type"]) == "structured") { "Malformed structured type" }
                        val structured = obj(row["value"])
                        validateStructured(pid, structured)
                        DecodedReading.Structured(flatten(structured))
                    } else {
                        val primitive = row["value"] as? JsonPrimitive ?: invalid("Scalar reading must be numeric")
                        val isInteger = primitive.content.toLongOrNull() != null && !primitive.isString
                        require(string(row["value_type"]) == if (isInteger) "integer" else "number") { "Scalar value type conflicts" }
                        val scalar = number(primitive)
                        DecodedReading.Scalar(scalar, isInteger)
                    }
                }
                else -> invalid("Unsupported snapshot reading status")
            }
            NativeReading(pid, value)
        }
        if ("vehicle_identification" in root) {
            val identification = obj(root["vehicle_identification"])
            exact(identification, setOf("status", "vin", "identity_kind"))
            val vin = string(identification["vin"])
            require(string(identification["status"]) == "available" && vin.length == 17 && vin.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) { "Invalid VIN identification" }
            require(string(identification["identity_kind"]) == if (simulated) "simulated" else "reported") { "VIN source conflicts" }
            if (!simulated) require(vin.all { it in "ABCDEFGHJKLMNPRSTUVWXYZ0123456789" }) { "Invalid reported VIN" }
        }
        return ImportedSnapshot(readings, identity = null, simulated = simulated,
            vin = (root["vehicle_identification"] as? JsonObject)?.get("vin")?.jsonPrimitive?.content)
    }

    private fun validateStructured(pid: Int, value: JsonObject) {
        val sampleResponse = when (pid) {
            0x01, 0x41 -> "41${hex(pid)}00${if ((value["ignition_type"] as? JsonPrimitive)?.content == "compression") "08" else "00"}0000"
            0x03, 0x15, 0x55, 0x56 -> "41${hex(pid)}0000"
            0x24, 0x4F -> "41${hex(pid)}00000000"
            0x66 -> "41660000000000"
            0x67, 0x68 -> "41${hex(pid)}000000"
            else -> "41${hex(pid)}00"
        }
        val sample = (ObdDecoder.decode(pid, sampleResponse) as DecodedReading.Structured).fields
        exact(value, sample.keys.map { it.substringBefore('.') }.toSet())
        fun range(field: String, low: Double, high: Double, optional: Boolean = false, exclusive: Boolean = false) {
            val item = value[field]
            if (optional && item == JsonNull) return
            val numeric = number(item)
            require(numeric >= low && if (exclusive) numeric < high else numeric <= high) { "Structured snapshot value is outside the PID range" }
        }
        when (pid) {
            0x01, 0x41 -> {
                val mil = boolean(value["mil_on"])
                val count = integer(value["dtc_count"], 0, 127)
                require(string(value["ignition_type"]) in setOf("spark", "compression")) { "Invalid ignition type" }
                if (pid == 0x41) require(!mil && count == 0L) { "Drive-cycle MIL fields must be zero" }
                val monitors = value["monitors"] as? JsonArray ?: invalid("Invalid readiness monitors")
                val names = sample.filterKeys { it.startsWith("monitors.") && it.endsWith(".name") }.values.toList()
                require(monitors.size == names.size) { "Readiness monitor count conflicts" }
                monitors.forEachIndexed { index, item ->
                    val monitor = obj(item)
                    exact(monitor, setOf("name", "available", "complete"))
                    require(string(monitor["name"]) == names[index]) { "Readiness monitor names conflict" }
                    if (boolean(monitor["available"])) boolean(monitor["complete"])
                    else require(monitor["complete"] == JsonNull) { "Unavailable monitor cannot have completion state" }
                }
            }
            0x03 -> {
                val modes = setOf(0, 1, 2, 4, 8, 16).map { code ->
                    (ObdDecoder.decode(0x03, "4103${hex(code)}00") as DecodedReading.Structured).fields.getValue("system_1")
                }.toSet()
                require(value.values.all { string(it) in modes }) { "Invalid fuel-system mode" }
            }
            0x13 -> value.values.forEach { item ->
                val flags = item as? JsonArray ?: invalid("Invalid oxygen-sensor flags")
                require(flags.size == 4) { "Four oxygen-sensor flags required" }
                flags.forEach { boolean(it) }
            }
            0x1C, 0x51 -> {
                require(integer(value["pid"], 0, 255) == pid.toLong()) { "Enumerated PID conflicts" }
                val code = integer(value["code"], 0, 255).toInt()
                val meaning = (ObdDecoder.decode(pid, "41${hex(pid)}${hex(code)}") as DecodedReading.Structured).fields.getValue("meaning")
                require(value["meaning"] == if (meaning == "unavailable") JsonNull else JsonPrimitive(meaning)) { "Enumerated meaning conflicts" }
            }
            0x15 -> { range("voltage_volts", 0.0, 1.275); range("short_term_fuel_trim_percent", -100.0, 99.21875, optional = true) }
            0x24 -> { range("equivalence_ratio", 0.0, 2.0, exclusive = true); range("voltage_volts", 0.0, 8.0, exclusive = true) }
            0x4F -> {
                listOf("equivalence_ratio", "oxygen_sensor_voltage_volts", "oxygen_sensor_current_milliamps").forEach { integer(value[it], 0, 255) }
                require(integer(value["intake_manifold_pressure_kilopascals"], 0, 2550) % 10 == 0L) { "Invalid maximum pressure" }
            }
            0x55, 0x56 -> value.keys.forEach { range(it, -100.0, 99.21875) }
            0x66 -> value.keys.forEach { range(it, 0.0, 2047.96875, optional = true) }
            0x67, 0x68 -> value.keys.forEach { range(it, -40.0, 215.0, optional = true) }
        }
    }

    private fun flatten(element: JsonElement, prefix: String = ""): Map<String, String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) -> flatten(value, if (prefix.isEmpty()) key else "$prefix.$key").entries }.associate { it.toPair() }
        is JsonArray -> element.flatMapIndexed { index, value -> flatten(value, "$prefix.$index").entries }.associate { it.toPair() }
        JsonNull -> mapOf(prefix to "unavailable")
        is JsonPrimitive -> mapOf(prefix to element.content)
    }

    private fun unflatten(fields: Map<String, String>): JsonElement {
        require(fields.isNotEmpty()) { "Empty structured reading" }
        fun tree(entries: Map<List<String>, String>): JsonElement {
            val leaf = entries.keys.singleOrNull()?.takeIf { it.isEmpty() }
            if (leaf != null) return primitive(entries.getValue(leaf))
            require(entries.keys.none { it.isEmpty() }) { "Conflicting field paths" }
            val groups = entries.entries.groupBy { it.key.first() }
            if (groups.keys.all { it.toIntOrNull() != null }) {
                val keys = groups.keys.map { it.toInt() }.sorted()
                require(keys == (0 until keys.size).toList()) { "Invalid array field paths" }
                return JsonArray(keys.map { key -> tree(groups.getValue(key.toString()).associate { it.key.drop(1) to it.value }) })
            }
            return JsonObject(groups.mapValues { (_, group) -> tree(group.associate { it.key.drop(1) to it.value }) })
        }
        return tree(fields.mapKeys { it.key.split('.') })
    }
    private fun primitive(value: String): JsonElement = when (value) {
        "unavailable" -> JsonNull
        "true" -> JsonPrimitive(true)
        "false" -> JsonPrimitive(false)
        else -> value.toLongOrNull()?.let(::JsonPrimitive)
            ?: value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
    }
    private fun validatePrimitives(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach(::validatePrimitives)
            is JsonArray -> element.forEach(::validatePrimitives)
            is JsonPrimitive -> if (!element.isString && element != JsonNull && element.booleanOrNull == null) {
                val number = number(element)
                if (!element.content.contains('.') && !element.content.contains('e', ignoreCase = true)) {
                    require(abs(number) <= MAX_INTEGER.toDouble()) { "Snapshot integer exceeds interoperable range" }
                }
            }
        }
    }
    private fun description(pid: Int): String = "Reports ${ObdDecoder.catalog.getValue(pid).name.lowercase()} using standard Mode 01 PID 0x${hex(pid)}."
    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
    private fun exact(value: JsonObject, keys: Set<String>) { require(value.keys == keys) { "Unexpected or missing snapshot fields" } }
    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: invalid("Expected snapshot object")
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("Expected snapshot string")
    private fun boolean(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: invalid("Expected snapshot boolean")
    private fun number(value: JsonElement?): Double = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: invalid("Expected finite snapshot number")
    private fun integer(value: JsonElement?, low: Long, high: Long): Long = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in low..high } ?: invalid("Expected bounded snapshot integer")
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)

    /** Reject duplicates and excessive nesting before kotlinx.serialization builds a tree. */
    internal class StrictJsonCheck(private val text: String) {
        private var position = 0
        fun check() { value(0); space(); require(position == text.length) { "Trailing snapshot data" } }
        private fun space() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(char: Char): Boolean { space(); if (text.getOrNull(position) != char) return false; position++; return true }
        private fun expect(char: Char) { require(take(char)) { "Malformed snapshot JSON" } }
        private fun quoted(): String {
            space()
            val start = position
            expect('"')
            while (position < text.length) {
                val char = text[position++]
                if (char == '\\') { require(position < text.length); position++; continue }
                if (char == '"') return json.parseToJsonElement(text.substring(start, position)).jsonPrimitive.content
            }
            invalid("Unterminated snapshot string")
        }
        private fun value(depth: Int) {
            require(depth <= 16) { "Snapshot exceeds nesting limit" }
            space()
            when (text.getOrNull(position)) {
                '{' -> {
                    position++
                    val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do { require(keys.add(quoted())) { "Duplicate snapshot JSON key" }; expect(':'); value(depth + 1) } while (take(','))
                    expect('}')
                }
                '[' -> {
                    position++
                    if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    expect(']')
                }
                '"' -> quoted()
                else -> {
                    val start = position
                    while (position < text.length && text[position] !in ",]} \t\r\n") position++
                    val token = text.substring(start, position)
                    require(token in setOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "Malformed snapshot primitive" }
                }
            }
        }
    }
}
