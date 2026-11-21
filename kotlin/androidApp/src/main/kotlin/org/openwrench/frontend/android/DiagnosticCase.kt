package org.openwrench.frontend.android

import org.openwrench.nativecore.*
import java.util.UUID

/** Copies of the selected records, independent of the source histories and their retention. */
data class DiagnosticCase(val id: String, val savedAtEpochMillis: Long,
    val sameVehicleConfirmed: Boolean, val scan: SavedScan, val drive: SavedDrive,
    val sections: List<String>, val reportVersion: Int = 1) {
    fun reportText() = "OPENWRENCH diagnostic case · historical\nCase $id; saved at epoch ms $savedAtEpochMillis\n" +
        sections.joinToString("\n\n") + "\nVIN omitted.\n"
}

object DiagnosticCaseBuilder {
    val headings = listOf("Scan results", "Freeze-frame conditions", "Recorded drive findings", "Interpretation limits and next useful check")
    fun create(scan: SavedScan, drive: SavedDrive, sameVehicleConfirmed: Boolean,
        nowMillis: Long = System.currentTimeMillis()): DiagnosticCase {
        require(sameVehicleConfirmed) { "Confirm the selected scan and drive came from the same vehicle" }
        require(scan.capture != null) { "Legacy scan: structured evidence unavailable; select a new saved scan" }
        require(drive.status == SavedDriveStatus.COMPLETED) { "Select a completed saved drive" }
        // Freeze and validate all collections, and intentionally exclude an explicitly saved scan VIN.
        val frozenScan = ScanHistoryCodec.decode(ScanHistoryCodec.encode(listOf(scan.copy(vin = null)))).single()
        val frozenDrive = DriveHistoryCodec.decode(DriveHistoryCodec.encode(drive))
        val c = frozenScan.capture!!
        val frame = c.frameZero
        val a = frozenDrive.review.assessment
        val sections = listOf(
            buildString {
                appendLine(headings[0])
                appendLine("Scan ${scan.id}; capture ${c.captureId}; origin ${c.origin}; saved at epoch ms ${scan.savedAtEpochMillis}")
                appendLine("Acquisition started/completed at epoch ms: ${c.startedAtEpochMillis ?: "unknown"} / ${c.completedAtEpochMillis ?: "unknown"}; monotonic duration ${c.durationMillis ?: "unknown"} ms")
                appendLine("Local publication at epoch ms ${c.publishedAtEpochMillis}; connection ${c.sessionId ?: "unknown"}; observed source ${c.sourceEcu ?: "unverified"}. Connection IDs are not vehicle identities.")
                DtcService.entries.forEach { service ->
                    val s = c.services.singleOrNull { it.service == service }
                    appendLine("${service.label} (${service.request}): ${s?.status ?: "NOT CAPTURED"}; source ${s?.sourceEcu ?: "unverified"}")
                    if (s?.status == DiagnosticReplyStatus.AVAILABLE) appendLine(if (s.codes.isEmpty()) "Available response: zero codes returned" else "Returned: ${s.codes.joinToString()}")
                    s?.note?.let { appendLine("Service note: $it") }
                }
                if (c.readings.none { it.pid == 1 }) appendLine("MIL / readiness: not captured")
                c.readings.forEach { appendLine(readingText(it, "Mode 01")) }
            }.trimEnd(),
            buildString {
                appendLine(headings[1])
                appendLine("Frame zero: ${frame?.status ?: "NOT CAPTURED"}; source ${frame?.sourceEcu ?: "unverified"}; trigger ${frame?.triggerCode ?: "not returned / unknown"}")
                appendLine("Original ECU capture time: unknown. Retrieval time is not the fault-event time.")
                frame?.let { f ->
                    appendLine("Advertised frame PIDs: ${f.advertisedPids.sorted().joinToString { it.toString(16).uppercase().padStart(2, '0') }}")
                    f.note?.let { appendLine(it) }
                    f.readings.forEach { appendLine(readingText(it, "Frame 0", f.unavailablePids[it.pid]?.name)) }
                    if (f.readings.isEmpty()) appendLine("No frame-zero measurements captured; conditions remain unknown.")
                    val missing = f.advertisedPids.intersect(setOf(3,4,5,6,7,8,9,11,12,13,15,16,17)) - f.readings.map { it.pid }.toSet()
                    if (missing.isNotEmpty()) appendLine("Advertised but not captured: ${missing.sorted().joinToString()}")
                }
                appendLine("The trigger association applies only to the returned trigger, never every returned code or a drive bookmark.")
            }.trimEnd(),
            buildString {
                appendLine(headings[2])
                appendLine("Drive ${drive.id}; origin ${drive.origin}; observed source ${drive.sourceEcu ?: "unverified"}")
                appendLine("Acquisition start epoch ms ${drive.startedAtEpochMillis}; saved epoch ms ${drive.savedAtEpochMillis}; recorded duration ${drive.review.elapsedMillis} ms")
                appendLine("Assessment ${a?.let { "${it.packId}/${it.packVersion}" } ?: "unavailable"}; original findings retained without reevaluation.")
                appendLine("Qualified fuel-correction evidence policies: ${a?.findings?.flatMap { it.fuelIntervals }?.map { it.policyVersion }?.distinct()?.joinToString().orEmpty().ifEmpty { "no paired intervals retained" }}")
                appendLine(a?.reportText() ?: "No original typed assessment available. No new assessment inferred.")
                a?.findings?.flatMap { it.fuelIntervals }?.forEach { interval ->
                    appendLine("Retained policy ${interval.policyVersion}, bank ${interval.bank}, ${interval.condition}; ${interval.system1}; ${interval.system2}")
                    appendLine("Retained ${interval.pairs.size} complete paired receipts in case evidence. Report shows the first three and last three receipts per interval.")
                    val excerpt = (interval.pairs.take(3) + interval.pairs.takeLast(3)).distinct()
                    excerpt.forEach { p -> appendLine(p.caseReceiptText()) }
                }
                appendLine("These are adapter receipt offsets, not synchronized ECU sample times. Missing qualified intervals are not reconstructed from charts.")
            }.trimEnd(),
            buildString {
                appendLine(headings[3])
                appendLine("Same vehicle: explicitly confirmed by the user for these two selected historical records. Vehicle identity is not verified by ECU address, adapter or process generation.")
                val sources = c.readings.filter { it.value != null }.map { it.sourceEcu } + c.services.filter { it.status == DiagnosticReplyStatus.AVAILABLE }.map { it.sourceEcu } + listOfNotNull(frame).filter { it.status == DiagnosticReplyStatus.AVAILABLE }.map { it.sourceEcu } +
                    frame?.readings.orEmpty().filter { it.value != null }.map { it.sourceEcu }
                val matching = c.origin == ScanOrigin.HARDWARE && drive.origin == DriveEvidenceOrigin.HARDWARE &&
                    c.sourceEcu != null && c.sourceEcu == drive.sourceEcu && sources.all { it == c.sourceEcu }
                appendLine(if (matching) "Observed response addresses agree. This supports attribution to matching response addresses only; it does not verify the same ECU, fault or event."
                    else "Source identity differs or is unverified, synthetic or imported. Observations remain separately attributed; no unified same-ECU or same-fault conclusion is available.")
                appendLine("The scan and drive are not established as the same event. Historical evidence does not describe the vehicle's current condition. A code or fuel pattern does not identify a failed component, and differences do not prove a repair. Pattern evidence is separate from causal confidence.")
                if (frame == null || frame.status != DiagnosticReplyStatus.AVAILABLE || frame.triggerCode == null || frame.unavailablePids.isNotEmpty())
                    appendLine("Next useful check — frame gap: when parked, use the existing explicit warning-context scan to check whether the ECU supplies a frame-zero trigger and missing conditions. Availability is not guaranteed.")
                if (a == null || a.findings.flatMap { it.fuelIntervals }.isEmpty())
                    appendLine("Next useful check — qualified-drive gap: record another completed drive using the existing fuel-trim workflow, if safe. Qualified paired evidence is missing in this saved drive; chart points cannot supply it.")
                a?.findings?.forEach { f -> f.nextSteps.forEach { appendLine("Original finding ${f.checkId} next check: $it") } }
                appendLine("Case preserves copies of selected evidence. Source deletion and drive retention do not change this case. No vehicle writes are performed.")
            }.trimEnd())
        return DiagnosticCase(UUID.randomUUID().toString(), nowMillis, true, frozenScan, frozenDrive, sections)
    }
    private fun readingText(r: NativeReading, prefix: String, missing: String? = null): String {
        val m = ObdDecoder.catalog.getValue(r.pid)
        return "$prefix ${r.pid.toString(16).uppercase().padStart(2, '0')} ${m.name}; source ${r.sourceEcu ?: "unverified"}: " + when (val v = r.value) {
            null -> "${missing ?: "NO DATA"}; no measurement established"
            is DecodedReading.Scalar -> "${v.value} ${m.unit.orEmpty()}"
            is DecodedReading.Structured -> v.fields.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}

/** Full retained receipt detail is also available as a lazy, expandable case view. */
internal fun org.openwrench.core.FuelCorrectionPair.caseReceiptText(): String =
    "Receipt offsets ms from drive start: STFT ${short.value}% @+${short.offsetMillis}; LTFT ${long.value}% @+${long.offsetMillis}; " +
        "RPM ${rpm.value} @+${rpm.offsetMillis}; load ${load.value}% @+${load.offsetMillis}; coolant ${coolant.value} C @+${coolant.offsetMillis}; " +
        "speed ${speed.value} @+${speed.offsetMillis}; fuel state @+${fuelOffsetMillis}"
