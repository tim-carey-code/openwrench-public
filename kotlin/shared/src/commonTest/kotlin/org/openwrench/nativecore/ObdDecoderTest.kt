package org.openwrench.nativecore

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObdDecoderTest {
    @Test
    fun catalogAndAllowlistsMatchPythonReference() {
        val metadata = ObdReferenceFixtures.metadata.lines().map { row ->
            val columns = row.split('\t')
            val pid = columns[0].toInt(16)
            PidMetadata(pid, columns[1], columns[2].takeUnless { it == "-" }, columns[3].toBoolean())
        }.associateBy { it.pid }
        assertEquals(metadata, ObdDecoder.catalog)
        assertEquals(69, metadata.size)
        assertEquals(ObdReferenceFixtures.scalarPids, ObdDecoder.scalarPids)
        assertEquals(ObdReferenceFixtures.snapshotPids, ObdDecoder.snapshotPids)
        assertEquals(ObdReferenceFixtures.rangeBases, ObdDecoder.rangeBases)
        assertEquals(55, ObdDecoder.scalarPids.size)
        assertEquals(41, ObdDecoder.snapshotPids.size)
    }

    @Test
    fun everyDecoderMatchesPythonBoundaryEnumerationAndSyntheticFixtures() {
        val covered = mutableSetOf<Int>()
        for (group in ObdReferenceFixtures.groups) for (row in group.lines()) {
            val columns = row.split('\t')
            val pid = columns[0].toInt(16)
            covered += pid
            val response = columns[1]
            val context = "PID ${columns[0]} response $response"
            if (columns[2] == "E") {
                assertFailsWith<NativeDiagnosticException>(context) { ObdDecoder.decode(pid, response) }
                continue
            }
            when (val decoded = ObdDecoder.decode(pid, response)) {
                is DecodedReading.Scalar -> {
                    assertTrue(columns[2] in listOf("I", "S"), context)
                    assertEquals(columns[2] == "I", decoded.integer, context)
                    approximately(columns[3].toDouble(), decoded.value, context)
                }
                is DecodedReading.Structured -> {
                    assertEquals("F", columns[2], context)
                    val expected = columns[3].split('|').associate { field ->
                        field.substringBefore('=') to field.substringAfter('=')
                    }
                    assertEquals(expected.keys, decoded.fields.keys, context)
                    expected.forEach { (key, value) ->
                        val actual = decoded.fields.getValue(key)
                        val numeric = value.toDoubleOrNull()
                        if (numeric != null) approximately(numeric, actual.toDouble(), "$context $key")
                        else assertEquals(value, actual, "$context $key")
                    }
                }
            }
        }
        assertEquals(ObdDecoder.catalog.keys, covered)
    }

    @Test
    fun capabilityRangesMatchPythonIncludingContinuationAndLastRange() {
        for (row in ObdReferenceFixtures.bitmaps.lines()) {
            val columns = row.split('\t')
            val expected = columns[2].split(',').filter { it != "-" }.map { it.toInt(16) }.toSet()
            assertEquals(expected, ObdDecoder.supported(columns[0].toInt(16), columns[1]), row)
        }
        assertTrue(0x20 in ObdDecoder.supported(0, "410000000001"))
        assertTrue(0x100 !in ObdDecoder.supported(0xE0, "41E0FFFFFFFF"))
    }

    @Test
    fun everyPidRequiresExactPayloadLengthAndCorrectResponseHeader() {
        for (row in ObdReferenceFixtures.metadata.lines()) {
            val columns = row.split('\t')
            val pid = columns[0].toInt(16)
            val length = columns[4].toInt()
            val header = "41${columns[0]}"
            val payload = "00".repeat(length)
            for (response in listOf(header + payload.dropLast(2), header + payload + "00", "42${columns[0]}$payload", "41FF$payload")) {
                assertFailsWith<NativeDiagnosticException>("PID $pid response $response") { ObdDecoder.decode(pid, response) }
            }
        }
    }

    @Test
    fun parsingRejectsMalformedUnknownAndAmbiguousResponses() {
        for (response in listOf("", "4", "41", "410C0", "410C1Z00", "410C1AFF>", "7E804410C1AFF", "410C1AFF\r410C1AFF", "NO DATA")) {
            assertFailsWith<NativeDiagnosticException>(response) { ObdDecoder.decode(0x0C, response) }
        }
        for (pid in -1..256) {
            if (pid !in ObdDecoder.catalog) {
                assertFailsWith<NativeDiagnosticException>("PID $pid") { ObdDecoder.decode(pid, "410000000000") }
            }
        }
        for (base in listOf(-1, 1, 0x21, 0xFF, 0x100)) {
            assertFailsWith<NativeDiagnosticException> { ObdDecoder.supported(base, "410000000000") }
        }
        for (response in listOf("", "4100000000", "41000000000000", "412000000000", "420000000000", "41000000000G")) {
            assertFailsWith<NativeDiagnosticException>(response) { ObdDecoder.supported(0, response) }
        }
    }

    @Test
    fun whitespaceAndCaseAreToleratedWithoutChangingDecodedValue() {
        assertEquals(DecodedReading.Scalar(1726.0), ObdDecoder.decode(0x0C, " 41\t0c\n1a f8\r"))
        assertIs<DecodedReading.Structured>(ObdDecoder.decode(0x01, "410185079110"))
    }

    private fun approximately(expected: Double, actual: Double, context: String) {
        assertTrue(abs(expected - actual) <= maxOf(1e-10, abs(expected) * 1e-12), "$context: expected $expected, received $actual")
    }
}
