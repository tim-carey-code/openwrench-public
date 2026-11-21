package org.openwrench.frontend.android

import java.util.Locale

/** Pure offline review of retained facts. It never creates timed cohorts from aggregate ranges. */
data class SavedEvidenceReview(val observations: List<String>, val limitations: List<String>) {
    fun reportText(): String = buildString {
        appendLine("OPENWRENCH offline review of saved evidence · historical · review format 1")
        observations.forEach { appendLine(it) }
        limitations.forEach { appendLine("Coverage: $it") }
    }
    companion object {
        fun build(drive: SavedDrive): SavedEvidenceReview {
            val r = drive.review
            val observations = buildList {
                add("This saved run contains ${r.elapsedMillis / 1_000} seconds of monitored evidence, ${r.signals.size} scalar signals and ${r.signals.sumOf { it.sampleCount }} valid scalar receipts. Counts do not measure continuous time spent driving.")
                add("Recorded response source: ${drive.sourceEcu ?: "unverified"}. This review does not describe the vehicle's current condition.")
                val a = r.assessment
                if (a == null) add("No original assessment was stored. The retained measurements can be reviewed, but missing timed context cannot be recreated.")
                else {
                    add("Original assessment retained unchanged: ${a.packId}/${a.packVersion}.")
                    a.findings.filter { it.disposition == DriveHealthDisposition.REVIEW }.forEach {
                        add("Original assessment marked for review: ${it.title}.")
                    }
                    if (a.findings.none { it.disposition == DriveHealthDisposition.REVIEW })
                        add("No review-category finding was retained by the original assessment. This does not establish whole-vehicle health.")
                }
                for (bank in 1..2) {
                    val pid = if (bank == 1) 0x07 else 0x09
                    val buckets = r.conditions.filter { it.condition in setOf("Moving", "Stationary with engine running") }
                    val rows = buckets.mapNotNull { b -> b.signals.singleOrNull { it.pid == pid }?.let { b.condition to it } }
                    rows.forEach { (condition, s) ->
                        val direction = when {
                            s.maximum < 0 -> "All retained valid LTFT values in this bucket were negative: learned correction removed fuel relative to base fueling."
                            s.minimum > 0 -> "All retained valid LTFT values in this bucket were positive: learned correction added fuel relative to base fueling."
                            else -> "The retained LTFT range included zero or more than one correction direction."
                        }
                        add("Bank $bank · $condition: ${s.sampleCount} recorded LTFT values, ${format(s.minimum)} to ${format(s.maximum)}%. $direction")
                    }
                    if (rows.isEmpty()) r.signals.singleOrNull { it.pid == pid }?.let {
                        add("Bank $bank: ${it.sampleCount} valid LTFT values, ${format(it.minimum)} to ${format(it.maximum)}%; motion-specific aggregates were not retained.")
                    }
                }
                if (r.conditions.any { it.condition == "Moving" && it.sampleCount > 0 })
                    add("Moving-operation evidence was recorded. This review is not based solely on a parked idle capture.")
                else add("No moving-operation evidence was retained; driving-condition conclusions remain unavailable.")
            }
            val limitations = buildList {
                add("This offline review does not perform RPM/load-specific good-or-bad judgments. It reviews recorded facts and original findings.")
                add("Aggregate ranges cannot establish a continuous closed-loop window or combine short- and long-term trims at the same instant. Fuel-cut, warm-up and differing loads can affect recorded values.")
                if (r.assessment?.findings.orEmpty().none { it.comparisonWindow != null })
                    add("This archive lacks the newer typed operating windows. Original findings and ranges can be compared descriptively, but matching RPM/load/temperature conditions cannot be reconstructed.")
                add("Pending/permanent code identifiers and ECU freeze frames are not stored in this drive archive. A separate scan report may exist in Scans; it is not automatically matched to this drive.")
                add("Missing evidence stays unknown. No new vehicle reads, new fault diagnosis, repair verdict, or whole-vehicle grade is produced by offline review.")
            }
            return SavedEvidenceReview(observations, limitations)
        }
        private fun format(value: Double) = String.format(Locale.US, "%.2f", value)
    }
}
