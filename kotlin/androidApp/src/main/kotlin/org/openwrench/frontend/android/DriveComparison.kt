package org.openwrench.frontend.android

data class DriveComparison(val driveIds: List<String>, val startedEpochMillis: List<Long>, val observations: List<String>) {
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH comparison of saved drives · historical")
        appendLine("Same vehicle: explicitly confirmed by the user; not verified by VIN or ECU address")
        driveIds.zip(startedEpochMillis).forEach { (id, time) -> appendLine("Drive $id; started at epoch ms $time") }
        observations.forEach { appendLine(it) }
        appendLine("When typed operating windows are available, supporting RPM/load/coolant receipts may precede each trim receipt by up to 10 seconds; these are not synchronized ECU snapshots.")
        appendLine("Matching broad operating ranges does not establish equal load histories, complete warm-up, the same fault cause, or a successful repair. The observations above state whether recorded categories, aggregate ranges or qualified windows were compared. Missing operating context is not reconstructed; this is not a comparison of every point in either drive. VIN omitted.")
    }
}

/** No automatic vehicle matching, min/max reconstruction, trend scoring, or cleared-fault verdict. */
object DriveComparisonBuilder {
    fun compare(first: SavedDrive, second: SavedDrive, sameVehicleConfirmed: Boolean): DriveComparison {
        require(sameVehicleConfirmed) { "Confirm both drives came from the same vehicle" }
        require(first.id != second.id) { "Select two different drives" }
        require(first.status == SavedDriveStatus.COMPLETED && second.status == SavedDriveStatus.COMPLETED) {
            "Only completed drives can be compared"
        }
        val drives = listOf(first, second)
        val assessments = drives.map { it.review.assessment }
        val observations = buildList {
            if (drives.any { it.sourceEcu == null } || drives[0].sourceEcu != drives[1].sourceEcu) {
                add("Comparison unavailable: a matching observed ECU response source is required in both drives, in addition to the user's same-vehicle confirmation.")
            } else if (assessments.any { it?.packId != "standard-obd-drive" || it.packVersion !in setOf("2", "3") }) {
                add("Recorded-findings review only: matching operating conditions cannot be established from these older archives. Original assessment results are not reevaluated or upgraded to newer rules.")
                for (bank in 1..2) {
                    val pid = if (bank == 1) 0x07 else 0x09
                    val oldFindings = assessments.map { a -> a?.takeIf { it.packId == "standard-obd-drive" && it.packVersion in setOf("1", "2", "3") }
                        ?.findings?.singleOrNull { it.checkId == "trim-bank-$bank" } }
                    if (oldFindings.all { it?.disposition == DriveHealthDisposition.REVIEW })
                        add("Both original assessments marked Bank $bank learned fuel correction for review. This repeats a recorded review category; correction direction, operating conditions and fault cause have not been matched.")
                    else add("Bank $bank: the original reports do not establish the same recorded review category in both drives. No improvement or repair verdict follows.")
                    drives.forEachIndexed { index, d -> d.review.signals.singleOrNull { it.pid == pid }?.let { s ->
                        add("Selected drive ${index + 1}, Bank $bank: ${s.sampleCount} valid LTFT receipts; full-run observed range ${s.minimum}..${s.maximum}%. These aggregate ranges do not establish a sustained window.")
                    } }
                }
            } else if (assessments.all { it!!.packVersion == "3" }) {
                addAll(comparePairedIntervals(assessments.filterNotNull()))
            } else {
                if (assessments.map { it!!.packVersion }.distinct().size > 1)
                    add("Mixed pack 2/3 comparison: only their common typed LTFT-window evidence is compared. STFT/LTFT relationships cannot be matched without paired intervals in both archives.")
                for (bank in 1..2) {
                    val findings = assessments.map { a -> a!!.findings.singleOrNull { it.checkId == "trim-bank-$bank" } }
                    val windows = findings.map { it?.comparisonWindow }
                    val a = windows[0]; val b = windows[1]
                    val eligible = findings.all { it?.disposition in setOf(DriveHealthDisposition.REVIEW, DriveHealthDisposition.OBSERVED) }
                    if (!eligible || a == null || b == null) {
                        add("Bank $bank: not enough recorded, qualified context to compare fuel correction. Both windows need fresh RPM, load and coolant readings throughout at least 30 seconds.")
                        continue
                    }
                    if (a.condition != b.condition || !overlap(a.rpmMinimum, a.rpmMaximum, b.rpmMinimum, b.rpmMaximum) ||
                        !overlap(a.loadMinimum, a.loadMaximum, b.loadMinimum, b.loadMaximum) ||
                        !overlap(a.coolantMinimum, a.coolantMaximum, b.coolantMinimum, b.coolantMaximum)) {
                        add("Bank $bank: selected fuel-correction windows have different motion/fuel-control context or non-overlapping RPM, load or temperature ranges. No recurring-pattern conclusion is made.")
                    } else {
                        val sameCue = findings.all { it!!.disposition == DriveHealthDisposition.REVIEW } && a.direction == b.direction &&
                            (a.trimMinimum > 10 || a.trimMaximum < -10) && (b.trimMinimum > 10 || b.trimMaximum < -10)
                        add(if (sameCue) "Bank $bank: sustained correction that ${if (a.direction == "adding") "added" else "removed"} fuel beyond the screening cue was recorded in both drives, in windows with matching motion/fuel-control state and overlapping RPM, load and coolant ranges. This supports a repeatable follow-up observation, not a confirmed fault."
                            else "Bank $bank: recorded windows share motion/fuel-control state and overlapping RPM, load and coolant ranges. Their correction patterns differ or no repeated screening cue was recorded; this does not confirm improvement, deterioration or a repair.")
                    }
                    add("First selected drive, Bank $bank: ${a.reportText()}")
                    add("Second selected drive, Bank $bank: ${b.reportText()}")
                }
                val warnings = assessments.map { a -> a!!.findings.singleOrNull { it.checkId == "ecu-warning" } }
                if (warnings.all { it?.disposition == DriveHealthDisposition.REVIEW })
                    add("Both drives recorded an ECU warning. Code identifiers must be compared separately; repeated MIL/count observations cannot establish the same fault.")
                else add("ECU-warning findings differ or lack comparable evidence. No absence of pending/permanent faults or repair success is inferred.")
            }
        }
        return DriveComparison(drives.map { it.id }, drives.map { it.startedAtEpochMillis }, observations)
    }
    private fun comparePairedIntervals(assessments: List<DriveHealthAssessment>): List<String> = buildList {
        add("Pack 3 paired-evidence comparison: retained policy-1 intervals only; original findings remain unchanged.")
        for (bank in 1..2) {
            val intervals = assessments.map { a -> a.findings.singleOrNull { it.checkId == "fuel-pattern-bank-$bank" }?.fuelIntervals.orEmpty() }
            val candidates = intervals[0].flatMap { a -> intervals[1].map { b -> a to b } }
            val compatible = candidates.firstOrNull { (a, b) ->
                a.policyVersion == 1 && b.policyVersion == 1 && a.condition == b.condition && a.system1 == b.system1 && a.system2 == b.system2 &&
                    overlap(a.contextBounds.rpm.minimum.value, a.contextBounds.rpm.maximum.value, b.contextBounds.rpm.minimum.value, b.contextBounds.rpm.maximum.value) &&
                    overlap(a.contextBounds.load.minimum.value, a.contextBounds.load.maximum.value, b.contextBounds.load.minimum.value, b.contextBounds.load.maximum.value) &&
                    overlap(a.contextBounds.coolant.minimum.value, a.contextBounds.coolant.maximum.value, b.contextBounds.coolant.minimum.value, b.contextBounds.coolant.maximum.value)
            }
            if (compatible == null) {
                add("Bank $bank: no compatible paired intervals retained in both drives. Needs same policy, motion and closed-loop state with overlapping recorded RPM/load/coolant ranges; missing intervals are not reconstructed.")
            } else {
                val (a, b) = compatible
                val same = a.pattern == b.pattern && org.openwrench.core.FuelCorrectionRecorder.direction(a.pairs.first().long.value) == org.openwrench.core.FuelCorrectionRecorder.direction(b.pairs.first().long.value)
                add("Bank $bank: earliest compatible retained interval pair ${if (same) "shows the same STFT/LTFT relationship and learned direction" else "shows differing STFT/LTFT relationships or learned direction"}. This compares sampled conditions, not all intervals, a cause, improvement or repair success.")
                add("First selected drive: ${a.evidenceText()}")
                add("Second selected drive: ${b.evidenceText()}")
            }
        }
        val warnings = assessments.map { a -> a.findings.singleOrNull { it.checkId == "ecu-warning" } }
        add(if (warnings.all { it?.disposition == DriveHealthDisposition.REVIEW })
            "Both drives recorded an ECU warning; code identifiers need separate comparison before suggesting the same fault."
            else "ECU-warning evidence does not establish absence of pending/permanent faults or repair success.")
    }
    private fun overlap(a: Double, b: Double, c: Double, d: Double) = maxOf(a, c) <= minOf(b, d)
}
