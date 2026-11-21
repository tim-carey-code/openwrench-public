package org.openwrench.frontend.android

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One immutable evidence string for both the consent preview and the relay's evidence field. */
internal class DiagnosticAiEvidence(driverNote: String, savedReport: String) {
    val text = "Driver report: ${driverNote.ifBlank { "Not supplied" }}\n\nSaved report:\n$savedReport"
    val body = buildJsonObject { put("evidence", text) }.toString().toByteArray(Charsets.UTF_8)
        .also { require(it.size <= MAX_REQUEST_BYTES) { "Evidence exceeds the relay request limit" } }

    companion object {
        const val MAX_REQUEST_BYTES = 512 * 1024
    }
}

/** Keep the frozen reports and their limits. Add structure only where the archive actually has it. */
internal fun diagnosisAiReport(record: DiagnosisRecord, readDrive: (String) -> DiagnosisDriveEvidence): String = buildString {
    appendLine("Saved diagnosis · historical evidence")
    appendLine("Only report evidence is included; the archive, charts and readings files are not attached.")
    appendLine(record.reportText())
    record.segments.forEach { segment ->
        appendLine("Segment ${segment.id} · guided interval evidence (segment-relative offsets)")
        val guidance = segment.guidance
        if (guidance == null) appendLine("No guided screening record stored for this segment.")
        else {
            appendLine("Guided policy ${guidance.policyVersion}; evaluated at +${guidance.evaluatedAtOffsetMillis} ms.")
            guidance.banks.forEach { bank ->
                appendLine("Bank ${bank.bank} · ${bank.status}: ${bank.interval?.evidenceText() ?: bank.requirement}")
            }
        }
        if (segment.driveId == null) appendLine("No linked drive report for this segment.")
    }
    record.segments.mapNotNull { it.driveId }.distinct().forEach { id ->
        val evidence = readDrive(id)
        appendLine("\nLinked drive: $id (drive-relative offsets; not aligned to segment offsets)")
        evidence.warning?.let { appendLine("Retention warning: $it") }
        val drive = evidence.drive
        if (drive == null) appendLine("Linked drive evidence unavailable. No assessment was reconstructed.")
        else appendDriveEvidence(drive)
        // Do not accumulate all drives of a large diagnosis in memory just to reject it later.
        require(length <= DiagnosticAiEvidence.MAX_REQUEST_BYTES) { "Evidence exceeds the relay request limit" }
    }
}

internal fun importedDriveAiReport(drive: SavedDrive): String = buildString {
    appendLine("Imported drive · historical evidence")
    appendLine("Drive: ${drive.id}. Only this selected drive is included; separate scans, archives and full readings are not attached.")
    appendDriveEvidence(drive)
}

private fun StringBuilder.appendDriveEvidence(drive: SavedDrive) {
    appendLine("Recorded origin: ${drive.origin.name} (as stored); status: ${drive.status.name}; session ${drive.generation}; source ECU: ${drive.sourceEcu ?: "unknown"}.")
    val review = drive.review
    appendLine("Drive-relative coverage: ${review.elapsedMillis} ms; ${review.signals.sumOf { it.sampleCount }} valid scalar receipts across ${review.signals.size} signals.")
    val assessment = review.assessment
    if (assessment == null) appendLine("No original drive assessment stored; qualified operating conditions cannot be inferred from aggregate ranges.")
    else {
        appendLine("Stored assessment: ${assessment.packId}/${assessment.packVersion} at +${assessment.evaluatedAtOffsetMillis} ms.")
        for (bank in 1..2) {
            val intervals = assessment.findings.flatMap { it.fuelIntervals }.filter { it.bank == bank }
            appendLine("Bank $bank: ${intervals.size} stored paired fuel-correction intervals (drive-relative).")
            intervals.forEach { interval ->
                appendLine("  +${interval.firstOffsetMillis}..+${interval.lastOffsetMillis} ms: ${interval.condition}; " +
                    "${interval.pairs.size} independent pairs; ${interval.observationText()}")
            }
        }
    }
    val retainedPoints = review.series.sumOf { it.points.size }
    val missingPoints = review.series.sumOf { series -> series.points.count { it.value == null } }
    val breaks = review.series.sumOf { series -> series.points.withIndex().count { (i, point) -> i > 0 && point.breakBefore } }
    appendLine("Retained charts only: $retainedPoints points; $missingPoints unavailable points; $breaks marked breaks after the first point of each series. These are not full-stream loss counts.")
    appendChartGapTiming(review.series)
    appendLine("Recorded drive assessment and evidence")
    appendLine(drive.copyText())
}

private const val MAX_GAP_MARKERS_PER_PID = 4

private fun StringBuilder.appendChartGapTiming(series: List<LiveHealthChartSeries>) {
    for (signal in series) {
        val details = mutableListOf<String>()
        var markers = 0
        for ((index, point) in signal.points.withIndex()) {
            val previous = signal.points.getOrNull(index - 1)
            val unavailable = point.value == null
            val unjoined = previous?.value != null && (point.breakBefore ||
                point.offsetMillis - previous.offsetMillis > signal.maximumLinkGapMillis)
            if (!unavailable && !unjoined) continue
            markers++
            if (markers > MAX_GAP_MARKERS_PER_PID) continue
            details += if (unavailable) "unavailable at +${point.offsetMillis} ms"
                else "unjoined receipts +${checkNotNull(previous).offsetMillis}..+${point.offsetMillis} ms"
        }
        if (markers == 0) continue
        val pid = signal.pid.toString(16).uppercase().padStart(2, '0')
        append("Retained chart PID 01$pid (${signal.name}), drive-relative: ")
        append(details.joinToString("; "))
        if (markers > details.size) append("; ${markers - details.size} additional markers omitted")
        appendLine(". These chart markers do not establish a vehicle-wide outage or continuous coverage elsewhere.")
    }
}
