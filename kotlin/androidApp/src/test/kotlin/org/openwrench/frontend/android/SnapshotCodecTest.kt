package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.NativeReading
import org.openwrench.nativecore.ObdDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotCodecTest {
    private fun reference(): String = checkNotNull(javaClass.getResourceAsStream("/native-snapshot-reference.json")).bufferedReader().use { it.readText() }

    @Test
    fun importsAllPythonSnapshotPidsAndExportsEquivalentDocument() {
        val original = reference()
        val imported = SnapshotCodec.decode(original)
        assertEquals(ObdDecoder.snapshotPids, imported.readings.map { it.pid }.toSet())
        assertEquals(41, imported.readings.size)
        assertNull(imported.identity)
        assertTrue(imported.simulated)
        val exported = SnapshotCodec.encode(imported.readings, true, "untrusted adapter identity")
        assertFalse(exported.contains("untrusted adapter identity"))
        assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(exported))
    }

    @Test
    fun structuredSnapshotRoundTripsAllOptionalAndReadinessStates() {
        val responses = mapOf(
            0x01 to "410185079110", 0x03 to "41030010", 0x13 to "411381", 0x15 to "4115C0FF",
            0x1C to "411C34", 0x24 to "4124FFFF8000", 0x41 to "4141000FFFFF", 0x4F to "414F01020304",
            0x51 to "4151FF", 0x55 to "415580FF", 0x56 to "4156817F", 0x66 to "416601006400C8",
            0x67 to "416700D3FF", 0x68 to "416803D3FF",
        )
        val readings = responses.map { (pid, response) -> NativeReading(pid, ObdDecoder.decode(pid, response)) }
        val imported = SnapshotCodec.decode(SnapshotCodec.encode(readings, false, null))
        assertEquals(readings, imported.readings)
    }

    @Test
    fun unavailableReadingsAndVinOptInPreserveReferenceSemantics() {
        val readings = listOf(NativeReading(0x05, null), NativeReading(0x04, DecodedReading.Scalar(40.0)))
        val plain = SnapshotCodec.encode(readings, false, null)
        assertFalse(plain.contains("vehicle_identification"))
        val imported = SnapshotCodec.decode(plain)
        assertEquals(listOf(0x04, 0x05), imported.readings.map { it.pid })
        assertNull(imported.readings.last().value)
        val withVin = SnapshotCodec.encode(readings, false, null, "TEST0000000000001")
        assertTrue(withVin.contains("vehicle_identification"))
        val replay = SnapshotCodec.decode(withVin)
        assertFalse(replay.simulated)
        assertEquals("TEST0000000000001", replay.vin)
        assertEquals(Json.parseToJsonElement(withVin), Json.parseToJsonElement(SnapshotCodec.encode(replay.readings, replay.simulated, replay.identity, replay.vin)))
        assertFailsWith<IllegalArgumentException> { SnapshotCodec.encode(readings, false, null, "INVALIDVIN") }
    }

    @Test
    fun rejectsDuplicateKeysMalformedNumbersOversizeAndDeepNesting() {
        for (text in listOf(
            "{\"schema_version\":\"openwrench.snapshot/1\",\"schema_version\":\"bad\"}",
            "{\"source\":{\"kind\":1,\"k\\u0069nd\":2}}",
            "NaN", "Infinity", "1e999", "{\"a\":01}", "{} trailing", "{\"a\":\"unterminated}",
            "[".repeat(18) + "0" + "]".repeat(18), " ".repeat(SnapshotCodec.MAX_BYTES + 1),
        )) assertFailsWith<IllegalArgumentException> { SnapshotCodec.decode(text) }
    }

    @Test
    fun rejectsSourceMetadataAndReadingConflicts() {
        val root = Json.parseToJsonElement(reference()).jsonObject
        fun reject(changed: JsonObject) { assertFailsWith<IllegalArgumentException> { SnapshotCodec.decode(changed.toString()) } }
        reject(JsonObject(root + ("schema_version" to JsonPrimitive("openwrench.snapshot/2"))))
        reject(JsonObject(root + ("unexpected" to JsonPrimitive(true))))
        reject(JsonObject(root + ("source" to JsonObject(root.getValue("source").jsonObject + ("simulated" to JsonPrimitive(false))))))
        val rows = root.getValue("readings").jsonArray
        reject(JsonObject(root + ("readings" to JsonArray(listOf(rows[0], rows[0])))))
        reject(JsonObject(root + ("readings" to JsonArray(rows.reversed()))))
        for (change in listOf(
            "name" to JsonPrimitive("Other PID"), "unit" to JsonPrimitive("wrong"),
            "description" to JsonPrimitive("Wrong description"), "value_type" to JsonPrimitive("number"),
            "status" to JsonPrimitive("unsupported"), "reason" to JsonPrimitive("no_data"),
        )) reject(JsonObject(root + ("readings" to JsonArray(listOf(JsonObject(rows[0].jsonObject + change))))))
    }

    @Test
    fun validatesStructuredRangesEnumerationsAndReadinessConsistency() {
        val root = Json.parseToJsonElement(reference()).jsonObject
        val rows = root.getValue("readings").jsonArray.associateBy { it.jsonObject.getValue("pid").jsonPrimitive.int }
        fun reject(pid: Int, value: JsonObject) {
            val row = JsonObject(rows.getValue(pid).jsonObject + ("value" to value))
            val document = JsonObject(root + ("readings" to JsonArray(listOf(row))))
            assertFailsWith<IllegalArgumentException> { SnapshotCodec.decode(document.toString()) }
        }
        fun original(pid: Int) = rows.getValue(pid).jsonObject.getValue("value").jsonObject
        reject(0x03, JsonObject(original(0x03) + ("system_1" to JsonPrimitive("unknown mode"))))
        reject(0x1C, JsonObject(original(0x1C) + ("meaning" to JsonPrimitive("invented standard"))))
        reject(0x15, JsonObject(original(0x15) + ("voltage_volts" to JsonPrimitive(9.0))))
        reject(0x24, JsonObject(original(0x24) + ("equivalence_ratio" to JsonPrimitive(2.0))))
        reject(0x4F, JsonObject(original(0x4F) + ("intake_manifold_pressure_kilopascals" to JsonPrimitive(11))))
        reject(0x55, JsonObject(original(0x55) + ("bank_1_percent" to JsonPrimitive(100.0))))
        reject(0x66, JsonObject(original(0x66) + ("sensor_a_grams_per_second" to JsonPrimitive(-1.0))))
        reject(0x67, JsonObject(original(0x67) + ("sensor_1_celsius" to JsonPrimitive(216.0))))
        reject(0x41, JsonObject(original(0x41) + ("mil_on" to JsonPrimitive(true))))
        val monitors = original(0x01).getValue("monitors").jsonArray.toMutableList()
        monitors[0] = JsonObject(monitors[0].jsonObject + ("available" to JsonPrimitive(false)))
        reject(0x01, JsonObject(original(0x01) + ("monitors" to JsonArray(monitors))))
    }

    @Test
    fun refusesExportWithUnknownDuplicateOrNonSnapshotPidsAndInvalidValues() {
        for (readings in listOf(
            listOf(NativeReading(0xFF, null)), listOf(NativeReading(0xA6, DecodedReading.Scalar(1.0))),
            listOf(NativeReading(0x04, null), NativeReading(0x04, null)),
            listOf(NativeReading(0x04, DecodedReading.Scalar(Double.NaN))),
            listOf(NativeReading(0x1F, DecodedReading.Scalar(1.5, integer = true))),
        )) assertFailsWith<IllegalArgumentException> { SnapshotCodec.encode(readings, true, null) }
    }
}
