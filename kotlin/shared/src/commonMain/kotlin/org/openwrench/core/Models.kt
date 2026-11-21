package org.openwrench.core

/** Origin is always explicit; describing hardware does not implement access to it. */
enum class SourceKind { SIMULATED, HARDWARE }

data class DiagnosticSource(
    val backendId: String,
    val adapterId: String,
    val kind: SourceKind,
) {
    val simulated: Boolean get() = kind == SourceKind.SIMULATED
}

enum class ConnectionStatus { DISCONNECTED, CONNECTED }
enum class IdentityKind { SIMULATED, REPORTED, UNKNOWN }

data class AdapterIdentity(
    val source: DiagnosticSource,
    val name: String,
    val description: String,
)

data class AdapterStatus(val identity: AdapterIdentity, val connection: ConnectionStatus)

data class VehicleInformation(
    val source: DiagnosticSource,
    val vin: String?,
    val manufacturer: String?,
    val model: String?,
    val modelYear: Int?,
    val identityKind: IdentityKind,
)

enum class MeasurementUnit(val symbol: String) {
    PERCENT("%"), RPM("rpm"), CELSIUS("°C"), KPA("kPa"),
    KM_PER_HOUR("km/h"), GRAMS_PER_SECOND("g/s"), SECONDS("s"),
}

enum class LiveParameter(val identifier: String, val unit: MeasurementUnit, val pid: Int) {
    LOAD("load", MeasurementUnit.PERCENT, 0x04),
    RPM("rpm", MeasurementUnit.RPM, 0x0C),
    COOLANT("coolant", MeasurementUnit.CELSIUS, 0x05),
    STFT("stft", MeasurementUnit.PERCENT, 0x06),
    LTFT("ltft", MeasurementUnit.PERCENT, 0x07),
    MAP("map", MeasurementUnit.KPA, 0x0B),
    SPEED("speed", MeasurementUnit.KM_PER_HOUR, 0x0D),
    INTAKE_AIR("intake-air", MeasurementUnit.CELSIUS, 0x0F),
    MAF("maf", MeasurementUnit.GRAMS_PER_SECOND, 0x10),
    THROTTLE("throttle", MeasurementUnit.PERCENT, 0x11),
    RUNTIME("runtime", MeasurementUnit.SECONDS, 0x1F),
    ;

    companion object {
        fun fromIdentifier(identifier: String): LiveParameter? =
            entries.firstOrNull { it.identifier == identifier }
    }
}

sealed interface DiagnosticValue {
    data class IntegerValue(val value: Long) : DiagnosticValue {
        init { require(value in -9_007_199_254_740_991L..9_007_199_254_740_991L) }
    }
    data class DecimalValue(val value: Double) : DiagnosticValue {
        init { require(value.isFinite()) }
    }
}

data class LiveMeasurement(
    val source: DiagnosticSource,
    val parameter: LiveParameter,
    val value: DiagnosticValue,
) {
    init {
        require((parameter == LiveParameter.RUNTIME) == (value is DiagnosticValue.IntegerValue))
        if (value is DiagnosticValue.IntegerValue) require(value.value in 0L..65_535L)
    }
    val unit: MeasurementUnit get() = parameter.unit
}

/** Advertised PIDs include continuation bits, not permission to execute commands. */
data class SupportedPidRange(val basePid: Int, val supportedPids: List<Int>) {
    init {
        require(basePid in listOf(0x00, 0x20, 0x40))
        require(supportedPids == supportedPids.distinct().sorted())
        require(supportedPids.all { it in (basePid + 1)..(basePid + 32) })
    }
    val continuationPid: Int get() = basePid + 32
    val hasContinuation: Boolean get() = continuationPid in supportedPids
}

data class SupportedPidReport(
    val source: DiagnosticSource,
    val ranges: List<SupportedPidRange>,
    val nextUnqueriedRange: Int?,
) {
    init {
        require(ranges.isNotEmpty() && ranges.first().basePid == 0)
        require(ranges.map { it.basePid } == ranges.map { it.basePid }.distinct().sorted())
        require(ranges.zipWithNext().all { (a, b) -> a.hasContinuation && b.basePid == a.continuationPid })
        val last = ranges.last()
        require(nextUnqueriedRange == if (last.hasContinuation) last.continuationPid else null)
    }
    val supportedPids: List<Int> get() = ranges.flatMap { it.supportedPids }.distinct().sorted()
    val usableParameters: List<LiveParameter> get() = LiveParameter.entries.filter { it.pid in supportedPids }
}
