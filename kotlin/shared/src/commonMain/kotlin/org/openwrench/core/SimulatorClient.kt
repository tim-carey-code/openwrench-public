package org.openwrench.core

/** Fictional, in-process data. Imports no platform, network, or hardware API. */
class SimulatorClient : DiagnosticClient {
    private val source = DiagnosticSource("kotlin-simulator", "simulator", SourceKind.SIMULATED)
    private val identity = AdapterIdentity(
        source,
        "OpenWrench Simulator",
        "Deterministic fictional vehicle; no hardware access",
    )
    private var connection = ConnectionStatus.DISCONNECTED
    override val status: AdapterStatus get() = AdapterStatus(identity, connection)

    override fun connect(): DiagnosticResult<Unit> {
        connection = ConnectionStatus.CONNECTED
        return DiagnosticResult.Success(Unit)
    }

    override fun disconnect(): DiagnosticResult<Unit> {
        connection = ConnectionStatus.DISCONNECTED
        return DiagnosticResult.Success(Unit)
    }

    override fun vehicleInformation(): DiagnosticResult<VehicleInformation> = connected {
        VehicleInformation(source, "SIMULATED00000001", "OpenWrench", "Fictional Test Vehicle", 2099, IdentityKind.SIMULATED)
    }

    override fun supportedPids(): DiagnosticResult<SupportedPidReport> = connected {
        SupportedPidReport(
            source,
            listOf(SupportedPidRange(0, LiveParameter.entries.map { it.pid }.sorted())),
            null,
        )
    }

    override fun liveParameter(parameter: LiveParameter): DiagnosticResult<LiveMeasurement> = connected {
        val value = when (parameter) {
            LiveParameter.LOAD -> DiagnosticValue.DecimalValue(40.0)
            LiveParameter.RPM -> DiagnosticValue.DecimalValue(1726.0)
            LiveParameter.COOLANT -> DiagnosticValue.DecimalValue(91.0)
            LiveParameter.STFT -> DiagnosticValue.DecimalValue(-2.34375)
            LiveParameter.LTFT -> DiagnosticValue.DecimalValue(3.125)
            LiveParameter.MAP -> DiagnosticValue.DecimalValue(72.0)
            LiveParameter.SPEED -> DiagnosticValue.DecimalValue(42.0)
            LiveParameter.INTAKE_AIR -> DiagnosticValue.DecimalValue(27.0)
            LiveParameter.MAF -> DiagnosticValue.DecimalValue(12.34)
            LiveParameter.THROTTLE -> DiagnosticValue.DecimalValue(20.0)
            LiveParameter.RUNTIME -> DiagnosticValue.IntegerValue(842L)
        }
        LiveMeasurement(source, parameter, value)
    }

    private inline fun <T> connected(read: () -> T): DiagnosticResult<T> =
        if (connection == ConnectionStatus.CONNECTED) DiagnosticResult.Success(read())
        else DiagnosticResult.Failure(DiagnosticError.NotConnected)
}
