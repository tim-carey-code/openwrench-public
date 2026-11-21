package org.openwrench.core

import org.openwrench.nativecore.ObdDecoder

/** Shared identity used by the Android observation bridge and assessment profiles. */
object NativeSignalCatalog {
    val nativeSignalIds: Map<Int, String> = mapOf(
        0x04 to "load",
        0x05 to "coolant",
        0x06 to "stft",
        0x07 to "ltft",
        0x0B to "map",
        0x0C to "rpm",
        0x0D to "speed",
        0x0E to "timing-advance",
        0x0F to "intake-air",
        0x10 to "maf",
        0x11 to "throttle",
        0x1F to "runtime",
        0x21 to "distance-with-mil",
        0x23 to "fuel-rail-pressure",
        0x2E to "commanded-evaporative-purge",
        0x2F to "fuel-tank-level",
        0x30 to "warmups-since-dtc-clear",
        0x31 to "distance-since-dtc-clear",
        0x32 to "evaporative-vapor-pressure",
        0x33 to "barometric-pressure",
        0x3C to "catalyst-temperature",
        0x42 to "control-module-voltage",
        0x43 to "absolute-load",
        0x44 to "commanded-equivalence-ratio",
        0x47 to "throttle-position-b",
        0x49 to "accelerator-pedal-position-d",
        0x4A to "accelerator-pedal-position-e",
        0x08 to "stft-bank-2",
        0x09 to "ltft-bank-2",
        0x0A to "fuel-pressure",
        0x22 to "fuel-rail-pressure-vacuum",
        0x45 to "relative-throttle-position",
        0x46 to "ambient-air-temperature",
        0x48 to "throttle-position-c",
        0x4B to "accelerator-pedal-position-f",
        0x4C to "commanded-throttle-actuator",
        0x4D to "time-run-with-mil",
        0x4E to "time-since-dtc-clear",
        0x50 to "maximum-maf",
        0x52 to "ethanol-fuel-percentage",
        0x53 to "absolute-evaporative-pressure",
        0x59 to "fuel-rail-absolute-pressure",
        0x5A to "relative-accelerator-pedal-position",
        0x5B to "hybrid-battery-remaining-life",
        0x5C to "engine-oil-temperature",
        0x5D to "fuel-injection-timing",
        0x5E to "engine-fuel-rate",
        0x61 to "driver-demanded-engine-torque",
        0x62 to "actual-engine-torque",
        0x63 to "engine-reference-torque",
        0x8D to "throttle-position-g",
        0x8E to "engine-friction-torque",
        0xA4 to "transmission-actual-gear-ratio",
        0xA5 to "commanded-def-dosing",
        0xA6 to "odometer",
    )
    fun definition(pid: Int, support: SignalSupport): SignalDefinition {
        val metadata = ObdDecoder.catalog.getValue(pid)
        val id = if (pid == 0x03) "fuel-system-status" else
            nativeSignalIds[pid] ?: "pid-${pid.toString(16).padStart(2, '0')}"
        return SignalDefinition(id, metadata.name,
            metadata.unit, when { metadata.structured -> SignalType.STRUCTURED; ObdDecoder.isIntegerScalar(pid) -> SignalType.INTEGER; else -> SignalType.NUMBER },
            support, "01${pid.toString(16).uppercase().padStart(2, '0')}")
    }
}
