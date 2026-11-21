package org.openwrench.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulatorFixtureTest {
    private fun <T> DiagnosticResult<T>.value(): T = assertIs<DiagnosticResult.Success<T>>(this).value

    @Test
    fun simulatorMatchesVersionedFixture() {
        val resource = assertNotNull(javaClass.getResourceAsStream("/v1/simulator.json"), "Missing simulator fixture")
        val fixture = resource.bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        assertEquals(1, fixture.getValue("schema_version").jsonPrimitive.int)
        assertEquals("simulator-default", fixture.string("case_id"))
        assertEquals("simulator", fixture.string("family"))
        val parameters = fixture.getValue("live_parameters").jsonArray.map { it.jsonObject }
        assertEquals(11, parameters.size, "Fixture must contain all 11 measurements")
        assertEquals(
            LiveParameter.entries.map { it.identifier }.toSet(),
            parameters.map { it.string("identifier") }.toSet(),
            "Fixture identifiers must be complete and unique",
        )

        val client = SimulatorClient()
        client.connect().value()
        try {
            parameters.forEach { expected ->
                val identifier = expected.string("identifier")
                val measurement = client.liveParameter(identifier).value()
                assertEquals(identifier, measurement.parameter.identifier)
                assertEquals(expected.string("unit"), measurement.unit.symbol, identifier)
                val number = expected.getValue("value").jsonPrimitive
                assertFalse(number.isString, "$identifier must be a JSON number")
                if (identifier == "runtime") {
                    assertEquals("integer", expected.string("type"))
                    // long rejects decimal/exponent forms; never coerce 842.0 into an integer.
                    assertEquals(number.long, assertIs<DiagnosticValue.IntegerValue>(measurement.value).value)
                } else {
                    assertEquals("decimal", expected.string("type"))
                    assertTrue(number.content.toLongOrNull() == null, "$identifier must use decimal JSON representation")
                    assertTrue(number.double.isFinite(), "$identifier must be finite")
                    assertEquals(number.double, assertIs<DiagnosticValue.DecimalValue>(measurement.value).value)
                }
            }
            val expectedVehicle = fixture.getValue("vehicle").jsonObject
            val vehicle = client.vehicleInformation().value()
            assertEquals(expectedVehicle.string("vin"), vehicle.vin)
            assertEquals(expectedVehicle.string("manufacturer"), vehicle.manufacturer)
            assertEquals(expectedVehicle.string("model"), vehicle.model)
            assertEquals(expectedVehicle.getValue("model_year").jsonPrimitive.int, vehicle.modelYear)
            assertEquals(IdentityKind.SIMULATED, vehicle.identityKind)

            val pids = fixture.getValue("supported_pids").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(11, pids.size)
            assertEquals(pids.distinct().sorted(), pids, "Fixture PIDs must be sorted and unique")
            val report = client.supportedPids().value()
            assertEquals(pids, report.supportedPids)
            val nextRange = fixture.getValue("next_unqueried_range")
            assertEquals(if (nextRange == JsonNull) null else nextRange.jsonPrimitive.int, report.nextUnqueriedRange)
        } finally {
            client.disconnect().value()
        }
    }

    private fun JsonObject.string(key: String): String {
        val value = getValue(key).jsonPrimitive
        assertTrue(value.isString, "$key must be a JSON string")
        return value.content
    }
}
