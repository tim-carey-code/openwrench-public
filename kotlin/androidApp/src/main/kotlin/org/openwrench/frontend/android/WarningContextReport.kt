package org.openwrench.frontend.android

import org.openwrench.nativecore.*

fun DiagnosticContextRead.reportText(): String = buildString {
    appendLine("Warning context · read-only; captured with this scan, not continuously monitored")
    appendLine("Warning evidence format: warning-context/1")
    codes.forEach { result ->
        appendLine("${result.service.label} codes (${result.service.request}); source ${result.sourceEcu ?: "unverified"}: " +
            when (result.status) {
                DiagnosticReplyStatus.AVAILABLE -> if (result.codes.isEmpty()) "none returned by this response" else result.codes.joinToString(", ")
                DiagnosticReplyStatus.NO_DATA -> "NO DATA; status unknown"
                DiagnosticReplyStatus.UNSUPPORTED -> "ECU rejected this service as unsupported"
                DiagnosticReplyStatus.UNAVAILABLE -> "ECU reported this request unavailable under current conditions"
            })
    }
    appendLine("Pending identifiers are not a confirmed failed part. Permanent identifiers can remain after a fault is no longer present; they do not alone establish a current fault.")
    val frame = freezeFrame
    val frameStatus = when (frame.status) {
        DiagnosticReplyStatus.AVAILABLE -> if (frame.triggerCode == null) "no trigger code returned" else "captured"
        DiagnosticReplyStatus.NO_DATA -> "NO DATA; unknown"
        DiagnosticReplyStatus.UNSUPPORTED -> "not supported by this response"
        DiagnosticReplyStatus.UNAVAILABLE -> "unavailable under current conditions"
    }
    appendLine("Freeze frame 0: $frameStatus; source ${frame.sourceEcu ?: "unverified"}; trigger ${frame.triggerCode ?: "unavailable"}")
    frame.note?.let { appendLine(it) }
    frame.readings.forEach { reading ->
        val m = ObdDecoder.catalog.getValue(reading.pid)
        val value = when (val v = reading.value) {
            null -> "${frame.unavailablePids[reading.pid] ?: DiagnosticReplyStatus.NO_DATA}; no measurement established"
            is DecodedReading.Scalar -> "${v.value}${m.unit?.let { " $it" } ?: ""}"
            is DecodedReading.Structured -> v.fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        appendLine("Frame 0 ${m.name}: $value")
    }
    appendLine("Freeze-frame values were stored by the ECU at an unknown earlier time. They are not live samples or an automatic match to this drive, every code, or a warning bookmark. Only frame 0 and the supported first PID block are covered.")
    appendLine("No code clearing or other vehicle writes are performed. VIN omitted.")
}
