package org.openwrench.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Test-only peer reusable by a later engine driver. It performs no framing or decoding. */
internal class StrictRpmPeer(private val steps: List<JsonObject>) {
    private var index = 0
    private var pending: JsonObject? = null
    private val allowed = setOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATI", "ATDPN", "0100", "010C")

    fun write(bytes: ByteArray) {
        check(pending == null) { "write before pending response consumed" }
        val command = bytes.decodeToString().removeSuffix("\r")
        check(command in allowed) { "outside RPM-only operation boundary" }
        check(index < steps.size) { "unexpected write after transcript" }
        val step = steps[index]
        assertContentEquals(step.text("write_hex").hexBytes(), bytes)
        pending = step
        index++
    }

    /** A tagged event, never an executable exception expression from JSON. */
    fun read(): PeerEvent {
        val step = checkNotNull(pending) { "read without write" }
        pending = null
        return if ("failure" in step) PeerEvent.Failure(step.text("failure"))
        else PeerEvent.Bytes(step.text("response_hex").hexBytes())
    }

    fun assertComplete() {
        check(index == steps.size && pending == null) { "missing request or unconsumed response" }
    }
}

internal sealed interface PeerEvent {
    data class Bytes(val value: ByteArray) : PeerEvent
    data class Failure(val tag: String) : PeerEvent
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789ABCDEF" })
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class RpmCorpusTest {
    private fun corpus(): JsonObject {
        val input = assertNotNull(javaClass.getResourceAsStream("/v1/native-rpm.json"))
        return input.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }

    @Test
    fun corpusHasVersionProvenanceAndExactInitializationBoundary() {
        val corpus = corpus()
        assertEquals("openwrench.rpm-conformance/1", corpus.text("schema_version"))
        assertTrue(corpus.getValue("provenance").jsonObject.text("revision").matches(Regex("[0-9a-f]{40}")))
        val cases = corpus.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(40, cases.size)
        assertEquals(cases.size, cases.map { it.text("id") }.toSet().size)
        val commands = corpus.getValue("initialization").jsonArray.map {
            it.jsonObject.text("write_hex").hexBytes().decodeToString()
        }
        assertEquals(listOf("ATZ\r", "ATE0\r", "ATL0\r", "ATS0\r", "ATH0\r", "ATSP0\r", "ATI\r"), commands)
        cases.forEach { case ->
            assertTrue(case.text("setup") in setOf("none", "initialized"))
            assertTrue(case.text("operation") in setOf("initialize", "read_rpm", "capabilities", "protocol", "forbidden"))
            val error = case.getValue("expected").jsonObject["error"]?.jsonPrimitive?.content
            if (error != null && error != "forbidden_operation") {
                assertTrue(RpmSessionError.entries.any { it.name == error.uppercase() }, error)
            }
        }
    }

    @Test
    fun allTranscriptsArePortableAndPeerRequiresExactBytesAndConsumption() {
        val corpus = corpus()
        val initialization = corpus.getValue("initialization").jsonArray.map { it.jsonObject }
        corpus.getValue("cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val prefix = if (case.text("setup") == "initialized") initialization else emptyList()
            val steps = prefix + case.getValue("steps").jsonArray.map { it.jsonObject }
            val peer = StrictRpmPeer(steps)
            steps.forEach { step ->
                assertTrue(step.keys == setOf("write_hex", "response_hex") || step.keys == setOf("write_hex", "failure"))
                peer.write(step.text("write_hex").hexBytes())
                when (val event = peer.read()) {
                    is PeerEvent.Bytes -> assertContentEquals(step.text("response_hex").hexBytes(), event.value)
                    is PeerEvent.Failure -> assertTrue(event.tag in setOf("timeout", "connection_lost", "transport_failure"))
                }
            }
            peer.assertComplete()
            // Each fixture must fail if a driver sends an extra read or prohibited request.
            assertFailsWith<IllegalStateException> { peer.write("010C\r".encodeToByteArray()) }
            for (forbidden in listOf("04", "0800", "ATSH7E0", "0120", "0902", "010D")) {
                assertFailsWith<IllegalStateException> { peer.write("$forbidden\r".encodeToByteArray()) }
            }
        }
    }

    @Test
    fun missingIoWrongBytesAndOverlappingCommandsFailThePeer() {
        val step = corpus().getValue("initialization").jsonArray.first().jsonObject
        val peer = StrictRpmPeer(listOf(step))
        assertFailsWith<IllegalStateException> { peer.assertComplete() }
        assertFailsWith<IllegalStateException> { peer.read() }
        assertFailsWith<AssertionError> { peer.write("ATI\r".encodeToByteArray()) }
        peer.write("ATZ\r".encodeToByteArray())
        assertFailsWith<IllegalStateException> { peer.assertComplete() }
        assertFailsWith<IllegalStateException> { peer.write("ATI\r".encodeToByteArray()) }
        peer.read()
        peer.assertComplete()
    }

    @Test
    fun successfulRpmAndBitmapExpectationsHaveIndependentMathematicalOracles() {
        // Corpus integrity, not a native engine parity claim. No production decoder exists yet.
        val cases = corpus().getValue("cases").jsonArray.map { it.jsonObject }
        val values = mutableListOf<Double>()
        cases.forEach { case ->
            val expected = case.getValue("expected").jsonObject
            if ("error" in expected) return@forEach
            if (case.text("operation") == "read_rpm") {
                val response = case.getValue("steps").jsonArray.last().jsonObject.text("response_hex")
                    .hexBytes().decodeToString().replace(" ", "").split('\r').single { it.startsWith("410C") }
                val payload = response.hexBytes()
                assertEquals(4, payload.size)
                val count = (payload[2].toInt() and 255) * 256 + (payload[3].toInt() and 255)
                val rpm = expected.getValue("rpm").jsonPrimitive.double
                assertTrue(rpm.isFinite())
                assertEquals(count / 4.0, rpm) // exact quarter RPM; tolerance zero
                assertEquals("rpm", expected.text("unit"))
                values += rpm
            }
            if (case.text("operation") == "capabilities") {
                val response = case.getValue("steps").jsonArray.single().jsonObject.text("response_hex")
                    .hexBytes().decodeToString().removeSuffix("\r>")
                val bitmap = response.substring(4).toUInt(16)
                val capability = RpmCapabilities(bitmap)
                val pids = expected.getValue("supported_pids").jsonArray.map { it.jsonPrimitive.int }
                assertEquals(pids, capability.advertisedPids)
                assertEquals(expected.getValue("rpm_advertised").jsonPrimitive.boolean, capability.rpmAdvertised)
                val next = expected.getValue("next_unqueried_range")
                assertEquals(if (next == JsonNull) null else next.jsonPrimitive.int, capability.nextUnqueriedRange)
                assertFalse(capability.advertisedPids.any { it > 32 })
            }
        }
        assertEquals(listOf(0.0, 0.25, 1726.0, 16383.75), values)
    }
}
