package org.openwrench.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorClientTest {
    private fun <T> DiagnosticResult<T>.value(): T = assertIs<DiagnosticResult.Success<T>>(this).value
    private fun simulator() = SimulatorClient().also { it.connect().value() }

    @Test
    fun allElevenReadingsMatchPythonFixtures() {
        // Independent expectations from tests/test_api.py VALUES, not implementation metadata.
        val expected = listOf(
            Triple("load", 40.0, "%"), Triple("rpm", 1726.0, "rpm"),
            Triple("coolant", 91.0, "°C"), Triple("stft", -2.34375, "%"),
            Triple("ltft", 3.125, "%"), Triple("map", 72.0, "kPa"),
            Triple("speed", 42.0, "km/h"), Triple("intake-air", 27.0, "°C"),
            Triple("maf", 12.34, "g/s"), Triple("throttle", 20.0, "%"),
              // The simulator now uses a runtime of 842 (int) as per the updated fixture.
              Triple("runtime", 842L, "s"),
        )
        val client = simulator()
        assertEquals(11, LiveParameter.entries.size)
        assertEquals(expected.map { it.first }, LiveParameter.entries.map { it.identifier })
        expected.forEach { (identifier, value, unit) ->
            val reading = client.liveParameter(identifier).value()
            assertEquals(identifier, reading.parameter.identifier)
            // The simulator should return DecimalValue for most parameters, but runtime
            // is an integer value.  Use a runtime check to assert the correct type.
            if (value is Long) {
                assertEquals(value, assertIs<DiagnosticValue.IntegerValue>(reading.value).value)
            } else {
                assertEquals(value, assertIs<DiagnosticValue.DecimalValue>(reading.value).value)
            }
            assertEquals(unit, reading.unit.symbol)
        }
        val runtime = client.liveParameter("runtime").value()
        assertEquals(842L, assertIs<DiagnosticValue.IntegerValue>(runtime.value).value)
        assertEquals("s", runtime.unit.symbol)
    }

    @Test
    fun supportedPidsMatchPythonReport() {
        val report = simulator().supportedPids().value()
        assertEquals(listOf(4, 5, 6, 7, 11, 12, 13, 15, 16, 17, 31), report.supportedPids)
        assertEquals(1, report.ranges.size)
        assertEquals(0, report.ranges.single().basePid)
        assertEquals(32, report.ranges.single().continuationPid)
        assertFalse(report.ranges.single().hasContinuation)
        assertNull(report.nextUnqueriedRange)
        assertEquals(LiveParameter.entries.toList(), report.usableParameters)
    }

    @Test
    fun vehicleMatchesFictionalPythonIdentity() {
        val vehicle = simulator().vehicleInformation().value()
        assertEquals("SIMULATED00000001", vehicle.vin)
        assertEquals("OpenWrench", vehicle.manufacturer)
        assertEquals("Fictional Test Vehicle", vehicle.model)
        assertEquals(2099, vehicle.modelYear)
        assertEquals(IdentityKind.SIMULATED, vehicle.identityKind)
    }

    @Test
    fun readsRequireExplicitConnection() {
        val client = SimulatorClient()
        assertEquals(ConnectionStatus.DISCONNECTED, client.status.connection)
        val expected = DiagnosticResult.Failure(DiagnosticError.NotConnected)
        assertEquals(expected, client.vehicleInformation())
        assertEquals(expected, client.supportedPids())
        LiveParameter.entries.forEach { assertEquals(expected, client.liveParameter(it)) }
        assertEquals(ConnectionStatus.DISCONNECTED, client.status.connection)
    }

    @Test
    fun connectDisconnectAreIdempotentAndReconnectIsExplicit() {
        val client = SimulatorClient()
        repeat(2) { client.connect().value() }
        assertEquals(ConnectionStatus.CONNECTED, client.status.connection)
        val first = client.vehicleInformation().value()
        repeat(2) { client.disconnect().value() }
        assertEquals(ConnectionStatus.DISCONNECTED, client.status.connection)
        assertEquals(DiagnosticResult.Failure(DiagnosticError.NotConnected), client.vehicleInformation())
        client.connect().value()
        assertEquals(first, client.vehicleInformation().value())
    }

    @Test
    fun unknownIdentifiersAreTypedFailuresNotCommands() {
        val client = simulator()
        listOf("RPM", "fuel-pressure", "ATZ", "04", "", "/dev/ttyUSB0", "elm327").forEach {
            assertEquals(DiagnosticResult.Failure(DiagnosticError.UnsupportedParameter), client.liveParameter(it))
            assertEquals(ConnectionStatus.CONNECTED, client.status.connection)
        }
        client.disconnect()
        // Unknown identifiers are rejected before connection checks, as lookup failures.
        assertEquals(DiagnosticResult.Failure(DiagnosticError.UnsupportedParameter), client.liveParameter("ATZ"))
    }

    @Test
    fun everyResultHasExplicitSimulatedOrigin() {
        val client = simulator()
        val source = client.status.identity.source
        assertEquals(DiagnosticSource("kotlin-simulator", "simulator", SourceKind.SIMULATED), source)
        assertTrue(source.simulated)
        assertEquals(source, client.vehicleInformation().value().source)
        assertEquals(source, client.supportedPids().value().source)
        LiveParameter.entries.forEach { assertEquals(source, client.liveParameter(it).value().source) }
        assertFalse(DiagnosticSource("unimplemented", "unimplemented", SourceKind.HARDWARE).simulated)
        client.disconnect()
        assertEquals(source, client.status.identity.source)
    }

    @Test
    fun resultsAreDeterministicAcrossClientsAndLifecycles() {
        val first = simulator()
        val second = simulator()
        repeat(3) {
            assertEquals(first.vehicleInformation(), second.vehicleInformation())
            assertEquals(first.supportedPids(), second.supportedPids())
            LiveParameter.entries.forEach { assertEquals(first.liveParameter(it), second.liveParameter(it)) }
            second.disconnect()
            second.connect()
        }
    }

    @Test
    fun clientsDoNotShareConnectionState() {
        val first = simulator()
        val second = SimulatorClient()
        assertEquals(ConnectionStatus.DISCONNECTED, second.status.connection)
        first.disconnect()
        second.connect()
        assertEquals(DiagnosticResult.Failure(DiagnosticError.NotConnected), first.liveParameter("rpm"))
        assertIs<DiagnosticResult.Success<LiveMeasurement>>(second.liveParameter("rpm"))
    }

    @Test
    fun invalidNumbersAndRuntimeTypesFailClosed() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { DiagnosticValue.DecimalValue(it) }
        }
        assertFailsWith<IllegalArgumentException> { DiagnosticValue.IntegerValue(Long.MAX_VALUE) }
        val source = simulator().status.identity.source
        assertFailsWith<IllegalArgumentException> {
            LiveMeasurement(source, LiveParameter.RUNTIME, DiagnosticValue.DecimalValue(842.0))
        }
        assertFailsWith<IllegalArgumentException> {
            LiveMeasurement(source, LiveParameter.RPM, DiagnosticValue.IntegerValue(842))
        }
        listOf(-1L, 65536L).forEach {
            assertFailsWith<IllegalArgumentException> {
                LiveMeasurement(source, LiveParameter.RUNTIME, DiagnosticValue.IntegerValue(it))
            }
        }
    }

    @Test
    fun unknownVehicleFieldsStayUnknown() {
        val source = DiagnosticSource("unimplemented", "unimplemented", SourceKind.HARDWARE)
        val vehicle = VehicleInformation(source, null, null, null, null, IdentityKind.UNKNOWN)
        assertNull(vehicle.vin)
        assertNull(vehicle.modelYear)
        assertEquals(IdentityKind.UNKNOWN, vehicle.identityKind)
    }

    @Test
    fun continuationBitsDoNotAuthorizeNewParameters() {
        val report = SupportedPidReport(
            simulator().status.identity.source,
            listOf(
                SupportedPidRange(0, listOf(4, 32)),
                SupportedPidRange(32, listOf(64)),
                SupportedPidRange(64, listOf(96)),
            ),
            96,
        )
        assertEquals(listOf(4, 32, 64, 96), report.supportedPids)
        assertEquals(listOf(LiveParameter.LOAD), report.usableParameters)
        assertEquals(96, report.nextUnqueriedRange)
    }

    @Test
    fun malformedCapabilityReportsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SupportedPidRange(96, emptyList()) }
        assertFailsWith<IllegalArgumentException> { SupportedPidRange(0, listOf(12, 4)) }
        assertFailsWith<IllegalArgumentException> { SupportedPidRange(0, listOf(4, 4)) }
        assertFailsWith<IllegalArgumentException> { SupportedPidRange(0, listOf(33)) }
        val source = simulator().status.identity.source
        assertFailsWith<IllegalArgumentException> { SupportedPidReport(source, emptyList(), null) }
        assertFailsWith<IllegalArgumentException> {
            SupportedPidReport(source, listOf(SupportedPidRange(0, listOf(4))), 32)
        }
        assertFailsWith<IllegalArgumentException> {
            SupportedPidReport(source, listOf(SupportedPidRange(0, listOf(4)), SupportedPidRange(32, listOf(40))), null)
        }
    }
}
