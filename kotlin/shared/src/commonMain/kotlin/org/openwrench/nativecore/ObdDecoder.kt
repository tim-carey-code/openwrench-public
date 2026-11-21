package org.openwrench.nativecore

/** Standard Mode 01 catalog, ported from the Python reference; no platform I/O. */
data class PidMetadata(val pid: Int, val name: String, val unit: String?, val structured: Boolean)

sealed interface DecodedReading {
    data class Scalar(val value: Double, val integer: Boolean = false) : DecodedReading
    /** Python field names; nested structures use dotted paths; absent values are explicit. */
    data class Structured(val fields: Map<String, String>) : DecodedReading
}

object ObdDecoder {
    private val structuredPids = setOf(0x01, 0x03, 0x13, 0x15, 0x1C, 0x24, 0x41, 0x4F, 0x51, 0x55, 0x56, 0x66, 0x67, 0x68)
    private val integerPids = setOf(0x1F, 0x21, 0x30, 0x31, 0x4D, 0x4E, 0x63)
    fun isIntegerScalar(pid: Int): Boolean = pid in integerPids
    val rangeBases: Set<Int> = (0x00..0xE0 step 0x20).toSet()
    val snapshotPids: Set<Int> = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x21, 0x23, 0x24, 0x2E, 0x2F,
        0x30, 0x31, 0x32, 0x33, 0x3C, 0x41, 0x42, 0x43, 0x44, 0x47, 0x49,
        0x4A, 0x4F, 0x51, 0x55, 0x56, 0x66, 0x67, 0x68,
    )
    val catalog: Map<Int, PidMetadata> = listOf(
        PidMetadata(0x01, "Monitor status since DTC clear", null, 0x01 in structuredPids),
        PidMetadata(0x03, "Fuel system status", null, 0x03 in structuredPids),
        PidMetadata(0x04, "Calculated engine load", "%", 0x04 in structuredPids),
        PidMetadata(0x05, "Engine coolant temperature", "°C", 0x05 in structuredPids),
        PidMetadata(0x06, "Short-term fuel trim, Bank 1", "%", 0x06 in structuredPids),
        PidMetadata(0x07, "Long-term fuel trim, Bank 1", "%", 0x07 in structuredPids),
        PidMetadata(0x08, "Short-term fuel trim, Bank 2", "%", 0x08 in structuredPids),
        PidMetadata(0x09, "Long-term fuel trim, Bank 2", "%", 0x09 in structuredPids),
        PidMetadata(0x0A, "Fuel pressure", "kPa", 0x0A in structuredPids),
        PidMetadata(0x0B, "Intake manifold absolute pressure", "kPa", 0x0B in structuredPids),
        PidMetadata(0x0C, "Engine speed", "rpm", 0x0C in structuredPids),
        PidMetadata(0x0D, "Vehicle speed", "km/h", 0x0D in structuredPids),
        PidMetadata(0x0E, "Timing advance", "°", 0x0E in structuredPids),
        PidMetadata(0x0F, "Intake air temperature", "°C", 0x0F in structuredPids),
        PidMetadata(0x10, "Mass air flow", "g/s", 0x10 in structuredPids),
        PidMetadata(0x11, "Absolute throttle position", "%", 0x11 in structuredPids),
        PidMetadata(0x13, "Oxygen sensor presence", null, 0x13 in structuredPids),
        PidMetadata(0x15, "Narrow-range oxygen sensor 1", null, 0x15 in structuredPids),
        PidMetadata(0x1C, "OBD standard", null, 0x1C in structuredPids),
        PidMetadata(0x1F, "Engine runtime since start", "s", 0x1F in structuredPids),
        PidMetadata(0x21, "Distance traveled with MIL on", "km", 0x21 in structuredPids),
        PidMetadata(0x22, "Fuel rail pressure relative to manifold vacuum", "kPa", 0x22 in structuredPids),
        PidMetadata(0x23, "Fuel rail gauge pressure", "kPa", 0x23 in structuredPids),
        PidMetadata(0x24, "Wide-range oxygen sensor 1", null, 0x24 in structuredPids),
        PidMetadata(0x2E, "Commanded evaporative purge", "%", 0x2E in structuredPids),
        PidMetadata(0x2F, "Fuel tank level", "%", 0x2F in structuredPids),
        PidMetadata(0x30, "Warm-ups since DTCs cleared", "count", 0x30 in structuredPids),
        PidMetadata(0x31, "Distance since DTCs cleared", "km", 0x31 in structuredPids),
        PidMetadata(0x32, "Evaporative system vapor pressure", "Pa", 0x32 in structuredPids),
        PidMetadata(0x33, "Barometric pressure", "kPa", 0x33 in structuredPids),
        PidMetadata(0x3C, "Catalyst temperature", "°C", 0x3C in structuredPids),
        PidMetadata(0x41, "Monitor status this drive cycle", null, 0x41 in structuredPids),
        PidMetadata(0x42, "Control module voltage", "V", 0x42 in structuredPids),
        PidMetadata(0x43, "Absolute load", "%", 0x43 in structuredPids),
        PidMetadata(0x44, "Commanded equivalence ratio", "ratio", 0x44 in structuredPids),
        PidMetadata(0x45, "Relative throttle position", "%", 0x45 in structuredPids),
        PidMetadata(0x46, "Ambient air temperature", "°C", 0x46 in structuredPids),
        PidMetadata(0x47, "Absolute throttle position B", "%", 0x47 in structuredPids),
        PidMetadata(0x48, "Absolute throttle position C", "%", 0x48 in structuredPids),
        PidMetadata(0x49, "Accelerator pedal position D", "%", 0x49 in structuredPids),
        PidMetadata(0x4A, "Accelerator pedal position E", "%", 0x4A in structuredPids),
        PidMetadata(0x4B, "Accelerator pedal position F", "%", 0x4B in structuredPids),
        PidMetadata(0x4C, "Commanded throttle actuator", "%", 0x4C in structuredPids),
        PidMetadata(0x4D, "Time run with MIL on", "min", 0x4D in structuredPids),
        PidMetadata(0x4E, "Time since DTCs cleared", "min", 0x4E in structuredPids),
        PidMetadata(0x4F, "Maximum sensor values", null, 0x4F in structuredPids),
        PidMetadata(0x50, "Maximum mass-airflow rate", "g/s", 0x50 in structuredPids),
        PidMetadata(0x51, "Fuel type", null, 0x51 in structuredPids),
        PidMetadata(0x52, "Ethanol fuel percentage", "%", 0x52 in structuredPids),
        PidMetadata(0x53, "Absolute evaporative-system vapor pressure", "kPa", 0x53 in structuredPids),
        PidMetadata(0x55, "Secondary oxygen sensor fuel trim, banks 1 and 3", "%", 0x55 in structuredPids),
        PidMetadata(0x56, "Secondary oxygen sensor fuel trim, banks 1 and 3", "%", 0x56 in structuredPids),
        PidMetadata(0x59, "Fuel rail absolute pressure", "kPa", 0x59 in structuredPids),
        PidMetadata(0x5A, "Relative accelerator pedal position", "%", 0x5A in structuredPids),
        PidMetadata(0x5B, "Hybrid battery remaining life", "%", 0x5B in structuredPids),
        PidMetadata(0x5C, "Engine oil temperature", "°C", 0x5C in structuredPids),
        PidMetadata(0x5D, "Fuel injection timing", "°", 0x5D in structuredPids),
        PidMetadata(0x5E, "Engine fuel rate", "L/h", 0x5E in structuredPids),
        PidMetadata(0x61, "Driver-demanded engine torque", "%", 0x61 in structuredPids),
        PidMetadata(0x62, "Actual engine torque", "%", 0x62 in structuredPids),
        PidMetadata(0x63, "Engine reference torque", "N·m", 0x63 in structuredPids),
        PidMetadata(0x66, "Mass airflow sensors A and B", null, 0x66 in structuredPids),
        PidMetadata(0x67, "Engine coolant temperature sensors 1 and 2", "°C", 0x67 in structuredPids),
        PidMetadata(0x68, "Intake air temperature sensors 1 and 2", "°C", 0x68 in structuredPids),
        PidMetadata(0x8D, "Throttle position G", "%", 0x8D in structuredPids),
        PidMetadata(0x8E, "Engine friction torque", "%", 0x8E in structuredPids),
        PidMetadata(0xA4, "Transmission actual gear ratio", "ratio", 0xA4 in structuredPids),
        PidMetadata(0xA5, "Commanded diesel exhaust-fluid dosing", "%", 0xA5 in structuredPids),
        PidMetadata(0xA6, "Odometer", "km", 0xA6 in structuredPids),
    ).associateBy { it.pid }
    val scalarPids: Set<Int> = catalog.keys - structuredPids

    private val payloadLengths: Map<Int, Int> = mapOf(
        0x01 to 4,
        0x03 to 2,
        0x04 to 1,
        0x05 to 1,
        0x06 to 1,
        0x07 to 1,
        0x08 to 1,
        0x09 to 1,
        0x0A to 1,
        0x0B to 1,
        0x0C to 2,
        0x0D to 1,
        0x0E to 1,
        0x0F to 1,
        0x10 to 2,
        0x11 to 1,
        0x13 to 1,
        0x15 to 2,
        0x1C to 1,
        0x1F to 2,
        0x21 to 2,
        0x22 to 2,
        0x23 to 2,
        0x24 to 4,
        0x2E to 1,
        0x2F to 1,
        0x30 to 1,
        0x31 to 2,
        0x32 to 2,
        0x33 to 1,
        0x3C to 2,
        0x41 to 4,
        0x42 to 2,
        0x43 to 2,
        0x44 to 2,
        0x45 to 1,
        0x46 to 1,
        0x47 to 1,
        0x48 to 1,
        0x49 to 1,
        0x4A to 1,
        0x4B to 1,
        0x4C to 1,
        0x4D to 2,
        0x4E to 2,
        0x4F to 4,
        0x50 to 1,
        0x51 to 1,
        0x52 to 1,
        0x53 to 2,
        0x55 to 2,
        0x56 to 2,
        0x59 to 2,
        0x5A to 1,
        0x5B to 1,
        0x5C to 1,
        0x5D to 2,
        0x5E to 2,
        0x61 to 1,
        0x62 to 1,
        0x63 to 2,
        0x66 to 5,
        0x67 to 3,
        0x68 to 3,
        0x8D to 1,
        0x8E to 1,
        0xA4 to 2,
        0xA5 to 1,
        0xA6 to 4,
    )
    private val percentagePids = setOf(0x04, 0x11, 0x2E, 0x2F, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x52, 0x5A, 0x5B, 0x8D)
    private val temperaturePids = setOf(0x05, 0x0F, 0x46, 0x5C)
    private val fuelTrimPids = setOf(0x06, 0x07, 0x08, 0x09)
    private val torquePids = setOf(0x61, 0x62, 0x8E)

    /** Exact response mode, PID and payload length are required; headers/extra ECUs are rejected. */
    fun decode(pid: Int, response: String): DecodedReading {
        if (pid !in 0..0xFF) malformed("PID must fit in one byte")
        val length = payloadLengths[pid] ?: unsupported("Unsupported Mode 01 PID ${hex(pid)}")
        val raw = parse(response)
        validateHeader(pid, raw)
        if (raw.size != length + 2) malformed("PID ${hex(pid)} requires $length data byte(s); received ${raw.size - 2}")
        val data = raw.drop(2)
        if (pid in structuredPids) return structured(pid, data)
        val unsigned = unsigned(data)
        val value = when (pid) {
            in percentagePids -> data[0] * 100.0 / 255
            in temperaturePids -> data[0] - 40.0
            in fuelTrimPids -> trim(data[0])
            in torquePids -> data[0] - 125.0
            0x0A -> data[0] * 3.0
            0x0B, 0x0D, 0x33 -> data[0].toDouble()
            0x0C -> unsigned / 4.0
            0x0E -> data[0] / 2.0 - 64
            0x10 -> unsigned / 100.0
            0x1F, 0x21, 0x30, 0x31, 0x4D, 0x4E, 0x63 -> unsigned.toDouble()
            0x22 -> unsigned * 0.079
            0x23, 0x59 -> unsigned * 10.0
            0x32 -> (if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned) / 4.0
            0x3C -> unsigned / 10.0 - 40
            0x42, 0xA4 -> unsigned / 1000.0
            0x43 -> unsigned * 100.0 / 255
            0x44 -> unsigned * 2.0 / 65_536
            0x50 -> data[0] * 10.0
            0x53 -> unsigned * 0.005
            0x5D -> unsigned / 128.0 - 210
            0x5E -> unsigned * 0.05
            0xA5 -> data[0] / 2.0
            0xA6 -> unsigned / 10.0
            else -> unsupported("Unsupported Mode 01 PID ${hex(pid)}")
        }
        return DecodedReading.Scalar(value, pid in integerPids)
    }

    /** Continuation PIDs are preserved; the last range cannot advertise a PID beyond 0xFF. */
    fun supported(base: Int, response: String): Set<Int> {
        if (base !in rangeBases) unsupported("Unsupported capability range ${hex(base)}")
        val raw = parse(response)
        if (raw.size != 6) malformed("Capability response requires four data bytes")
        validateHeader(base, raw)
        val bitmap = unsigned(raw.drop(2))
        return (1..minOf(32, 0xFF - base)).filter { offset ->
            bitmap and (1L shl (32 - offset)) != 0L
        }.map { base + it }.toSet()
    }

    private fun structured(pid: Int, data: List<Int>): DecodedReading.Structured {
        val fields = when (pid) {
            0x01, 0x41 -> monitorStatus(pid, data)
            0x03 -> mapOf("system_1" to fuelSystem(data[0]), "system_2" to fuelSystem(data[1]))
            0x13 -> buildMap {
                for (bank in 1..2) for (sensor in 0..3) {
                    put("bank_$bank.$sensor", (data[0] and (1 shl ((bank - 1) * 4 + sensor)) != 0).toString())
                }
            }
            0x15 -> mapOf(
                "voltage_volts" to (data[0] / 200.0).toString(),
                "short_term_fuel_trim_percent" to optional(if (data[1] == 0xFF) null else trim(data[1])),
            )
            0x1C, 0x51 -> mapOf("pid" to pid.toString(), "code" to data[0].toString(), "meaning" to enumeration(pid, data[0]))
            0x24 -> mapOf(
                "equivalence_ratio" to (unsigned(data.take(2)) * 2.0 / 65_536).toString(),
                "voltage_volts" to (unsigned(data.drop(2)) * 8.0 / 65_536).toString(),
            )
            0x4F -> mapOf(
                "equivalence_ratio" to data[0].toString(),
                "oxygen_sensor_voltage_volts" to data[1].toString(),
                "oxygen_sensor_current_milliamps" to data[2].toString(),
                "intake_manifold_pressure_kilopascals" to (data[3] * 10).toString(),
            )
            0x55, 0x56 -> mapOf("bank_1_percent" to trim(data[0]).toString(), "bank_3_percent" to trim(data[1]).toString())
            0x66 -> mapOf(
                "sensor_a_grams_per_second" to optional(if (data[0] and 1 != 0) unsigned(data.subList(1, 3)) / 32.0 else null),
                "sensor_b_grams_per_second" to optional(if (data[0] and 2 != 0) unsigned(data.subList(3, 5)) / 32.0 else null),
            )
            0x67, 0x68 -> mapOf(
                "sensor_1_celsius" to optional(if (data[0] and 1 != 0) data[1] - 40.0 else null),
                "sensor_2_celsius" to optional(if (data[0] and 2 != 0) data[2] - 40.0 else null),
            )
            else -> unsupported("Unsupported structured PID ${hex(pid)}")
        }
        return DecodedReading.Structured(fields)
    }

    private fun monitorStatus(pid: Int, data: List<Int>): Map<String, String> = buildMap {
        if (pid == 0x41 && data[0] != 0) malformed("PID 0x41 first data byte must be zero")
        val compression = data[1] and 0x08 != 0
        put("mil_on", (data[0] and 0x80 != 0).toString())
        put("dtc_count", (data[0] and 0x7F).toString())
        put("ignition_type", if (compression) "compression" else "spark")
        var index = 0
        fun addMonitor(name: String, available: Boolean, incomplete: Boolean) {
            put("monitors.$index.name", name)
            put("monitors.$index.available", available.toString())
            put("monitors.$index.complete", if (available) (!incomplete).toString() else "unavailable")
            index++
        }
        listOf("comprehensive components" to 4, "fuel system" to 2, "misfire" to 1).forEach { (name, mask) ->
            addMonitor(name, data[1] and mask != 0, data[1] and (mask shl 4) != 0)
        }
        val names = if (compression) listOf("NMHC catalyst", "NOx/SCR aftertreatment", null, "boost pressure", null, "exhaust gas sensor", "particulate filter", "EGR/VVT system")
            else listOf("catalyst", "heated catalyst", "evaporative system", "secondary air", "gasoline particulate filter", "oxygen sensor", "oxygen sensor heater", "EGR/VVT system")
        names.forEachIndexed { bit, name ->
            if (name != null) addMonitor(name, data[2] and (1 shl bit) != 0, data[3] and (1 shl bit) != 0)
        }
    }

    private fun fuelSystem(code: Int): String = when (code) {
        0x00 -> "engine off"
        0x01 -> "open loop: insufficient engine temperature"
        0x02 -> "closed loop: oxygen-sensor feedback"
        0x04 -> "open loop: load or deceleration fuel cut"
        0x08 -> "open loop: system fault"
        0x10 -> "closed loop: oxygen-sensor feedback fault"
        else -> malformed("Invalid fuel-system status ${hex(code)}")
    }

    private val obdStandards = mapOf(
        0x01 to "OBD-II (CARB)",
        0x02 to "OBD (EPA)",
        0x03 to "OBD and OBD-II",
        0x04 to "OBD-I",
        0x05 to "not OBD compliant",
        0x06 to "EOBD",
        0x07 to "EOBD and OBD-II",
        0x08 to "EOBD and OBD",
        0x09 to "EOBD, OBD, and OBD-II",
        0x0A to "JOBD",
        0x0B to "JOBD and OBD-II",
        0x0C to "JOBD and EOBD",
        0x0D to "JOBD, EOBD, and OBD-II",
        0x0E to "OBD, EOBD, and KOBD",
        0x0F to "OBD, OBD-II, EOBD, and KOBD",
        0x10 to "reserved",
        0x11 to "Engine Manufacturer Diagnostics (EMD)",
        0x12 to "Engine Manufacturer Diagnostics Enhanced (EMD+)",
        0x13 to "HD OBD-C",
        0x14 to "HD OBD",
        0x15 to "World Wide Harmonized OBD (WWH OBD)",
        0x16 to "reserved",
        0x17 to "HD EOBD-I without NOx control",
        0x18 to "HD EOBD-I with NOx control",
        0x19 to "HD EOBD-II without NOx control",
        0x1A to "HD EOBD-II with NOx control",
        0x1B to "HD ZEV",
        0x1C to "Brazil OBD Phase 1 (OBDBr-1)",
        0x1D to "Brazil OBD Phase 2 (OBDBr-2)",
        0x1E to "Korean OBD (KOBD)",
        0x1F to "India OBD I (IOBD I)",
        0x20 to "India OBD II (IOBD II)",
        0x21 to "HD EOBD Stage VI",
        0x22 to "OBD, OBD-II, and HD OBD",
        0x23 to "Brazil OBD Phase 3 (OBDBr-3)",
    )
    private val fuelTypes = mapOf(
        0x00 to "not available",
        0x01 to "gasoline",
        0x02 to "methanol",
        0x03 to "ethanol",
        0x04 to "diesel",
        0x05 to "LPG",
        0x06 to "CNG",
        0x07 to "propane",
        0x08 to "electric",
        0x09 to "bifuel gasoline",
        0x0A to "bifuel methanol",
        0x0B to "bifuel ethanol",
        0x0C to "bifuel LPG",
        0x0D to "bifuel CNG",
        0x0E to "bifuel propane",
        0x0F to "bifuel electricity",
        0x10 to "bifuel electric and combustion engine",
        0x11 to "hybrid gasoline",
        0x12 to "hybrid ethanol",
        0x13 to "hybrid diesel",
        0x14 to "hybrid electric",
        0x15 to "hybrid electric and combustion engine",
        0x16 to "hybrid regenerative",
        0x17 to "bifuel diesel",
    )
    private fun enumeration(pid: Int, code: Int): String = if (pid == 0x51) {
        fuelTypes[code] ?: "reserved by ISO/SAE"
    } else {
        obdStandards[code] ?: when (code) {
            in 0x35..0xFA -> "reserved"
            in 0xFB..0xFF -> "not available for assignment (SAE J1939 special meaning)"
            else -> "unavailable"
        }
    }

    private fun parse(response: String): List<Int> {
        val compact = response.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.length % 2 != 0) malformed("Response must contain complete hexadecimal bytes")
        if (compact.any { it !in "0123456789abcdefABCDEF" }) malformed("Response contains non-hexadecimal data")
        return compact.chunked(2).map { it.toInt(16) }
    }
    private fun validateHeader(pid: Int, raw: List<Int>) {
        if (raw.size < 2) malformed("Response is missing mode or PID")
        if (raw[0] != 0x41) malformed("Expected Mode 01 response 0x41, received ${hex(raw[0])}")
        if (raw[1] != pid) malformed("Expected PID ${hex(pid)}, received ${hex(raw[1])}")
    }
    private fun unsigned(bytes: List<Int>): Long = bytes.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
    private fun trim(byte: Int): Double = (byte - 128) * 100.0 / 128
    private fun optional(value: Double?): String = value?.toString() ?: "unavailable"
    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')
    private fun malformed(message: String): Nothing = throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, message)
    private fun unsupported(message: String): Nothing = throw NativeDiagnosticException(ErrorKind.UNSUPPORTED, message)
}
