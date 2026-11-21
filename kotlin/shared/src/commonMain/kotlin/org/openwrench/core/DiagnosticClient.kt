package org.openwrench.core

sealed interface DiagnosticError {
    data object NotConnected : DiagnosticError
    data object UnsupportedParameter : DiagnosticError
}

sealed interface DiagnosticResult<out T> {
    data class Success<T>(val value: T) : DiagnosticResult<T>
    data class Failure(val error: DiagnosticError) : DiagnosticResult<Nothing>
}

/** Synchronous, read-only contract. Call from one owner; no queue or session manager. */
interface DiagnosticClient {
    val status: AdapterStatus
    fun connect(): DiagnosticResult<Unit>
    fun disconnect(): DiagnosticResult<Unit>
    fun vehicleInformation(): DiagnosticResult<VehicleInformation>
    fun supportedPids(): DiagnosticResult<SupportedPidReport>
    fun liveParameter(parameter: LiveParameter): DiagnosticResult<LiveMeasurement>

    /** Identifier lookup only; never a command or transport entry point. */
    fun liveParameter(identifier: String): DiagnosticResult<LiveMeasurement> {
        val parameter = LiveParameter.fromIdentifier(identifier)
            ?: return DiagnosticResult.Failure(DiagnosticError.UnsupportedParameter)
        return liveParameter(parameter)
    }
}
