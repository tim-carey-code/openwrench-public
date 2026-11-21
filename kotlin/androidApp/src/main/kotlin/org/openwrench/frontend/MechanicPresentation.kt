package org.openwrench.frontend

import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.DecodedReading
import org.openwrench.nativecore.DiagnosticReplyStatus
import kotlin.math.ceil

internal val mechanicTopIds = listOf("rpm", "coolant", "speed", "load", "intake-air", "control-module-voltage")
internal val mechanicAirIds = listOf("maf", "map", "throttle", "timing-advance", "commanded-equivalence-ratio")
internal val mechanicTrimIds = listOf("stft", "ltft", "stft-bank-2", "ltft-bank-2")

/** Display selection only: no controller, capability or polling writes. */
internal fun mechanicDefaultSelection(rows: List<DashboardSignal>, pins: List<String>): List<String> {
    val numeric = rows.filter { it.definition.isGraphable() }.map { it.definition.id }
    return (mechanicTopIds + mechanicTrimIds + mechanicAirIds + defaultWorkspacePlotIds(rows, pins))
        .distinct().filter { it in numeric }.take(GraphFirstLayout.MAX_SIGNALS)
}

/** Matches the active view's initial display contract; legacy variants keep their old reset. */
internal fun workspaceResetSelection(rows: List<DashboardSignal>, pins: List<String>, mechanic: Boolean): List<String> =
    if (mechanic) mechanicDefaultSelection(rows, pins) else defaultWorkspacePlotIds(rows, emptyList())

internal fun mechanicCardIds(layout: GraphFirstLayout, rows: List<DashboardSignal>): List<String> =
    layout.visible(WorkspaceModuleKind.COMPACT_VALUE, rows.filter {
        !layout.hideUnavailable || it.availability == SignalAvailability.AVAILABLE
    }.map { it.definition.id }).map { it.sensorId }.take(6)

internal fun mechanicCardColumns(width: Float, fontScale: Float, density: WorkspaceCardDensity): Int {
    val effective = if (width.isFinite()) width / (fontScale.takeIf(Float::isFinite) ?: 1f).coerceAtLeast(1f) else 0f
    val safe = when { effective >= 1050f -> 6; effective >= 620f -> 3; effective >= 340f -> 2; else -> 1 }
    return minOf(safe, when (density) { WorkspaceCardDensity.COMPACT -> 6; WorkspaceCardDensity.STANDARD -> 2; WorkspaceCardDensity.LARGE -> 1 })
}
internal fun mechanicPanelColumns(width: Float, fontScale: Float): Int = when {
    width / fontScale.coerceAtLeast(1f) >= 820f -> 3
    width / fontScale.coerceAtLeast(1f) >= 560f -> 2
    else -> 1
}
internal fun mechanicPlotHeight(height: WorkspaceGraphHeight, expanded: Boolean, fontScale: Float): Float =
    (if (expanded) 280f else when (height) {
        WorkspaceGraphHeight.COMPACT -> 80f; WorkspaceGraphHeight.STANDARD -> 100f; WorkspaceGraphHeight.TALL -> 200f
    }) * fontScale.coerceIn(1f, 2f)

internal data class MechanicCell(val id: String, val label: String, val value: String, val unit: String,
    val status: String, val available: Boolean, val reason: String)

/** Missing, hidden and unselected are separate from a measured zero. */
internal fun mechanicCell(id: String, all: List<DashboardSignal>, shown: List<DashboardSignal>,
    layout: GraphFirstLayout, imperial: Boolean): MechanicCell {
    val row = all.firstOrNull { it.definition.id == id }
        ?: return MechanicCell(id, id, "—", "", "Not supplied", false, "This source supplies no channel; ECU capability is unknown.")
    val module = layout.modules.firstOrNull { it.sensorId == id && it.kind == WorkspaceModuleKind.COMPACT_VALUE }
    val status = when {
        module?.visible == false -> "Hidden"
        row.availability != SignalAvailability.AVAILABLE -> row.status
        shown.none { it.definition.id == id } -> "Not selected for display"
        else -> null
    }
    val value = workspaceValuePresentation(row, imperial)
    // Engine speed keeps every supplied digit; the gauge never replaces the numeric reading.
    val exactRpm = if (id == "rpm" && value.available) row.value?.display()?.let(::groupInstrumentNumber) else null
    return MechanicCell(id, row.definition.label, if (status != null) "—" else exactRpm ?: value.value,
        row.instrumentUnit(imperial), (status ?: value.status) + if (status != null && row.ageMillis != null) " · " + row.shortAge() else "", status == null && value.available,
        row.reason + if (row.retainedReplay) " · Retained channel; ECU capability unverified" else "")
}
internal data class MechanicTrimBank(val bank: Int, val short: MechanicCell, val long: MechanicCell)
internal fun mechanicTrimBanks(all: List<DashboardSignal>, shown: List<DashboardSignal>, layout: GraphFirstLayout,
    imperial: Boolean): List<MechanicTrimBank> = listOf(
    MechanicTrimBank(1, mechanicCell("stft", all, shown, layout, imperial), mechanicCell("ltft", all, shown, layout, imperial)),
    MechanicTrimBank(2, mechanicCell("stft-bank-2", all, shown, layout, imperial), mechanicCell("ltft-bank-2", all, shown, layout, imperial)),
)

internal data class MechanicGauge(val fraction: Float?, val maximum: Double)
internal fun mechanicRpmGauge(row: DashboardSignal?): MechanicGauge {
    val number = row?.takeIf { it.definition.id == "rpm" && it.definition.unit == "rpm" &&
        it.availability == SignalAvailability.AVAILABLE }?.numericValue()?.takeIf { it.isFinite() && it >= 0 }
    val maximum = maxOf(8000.0, number?.let { ceil(it / 1000) * 1000 } ?: 8000.0)
    return MechanicGauge(number?.let { (it / maximum).toFloat().coerceIn(0f, 1f) }, maximum)
}

/** A scan from another source must never become this replay's fault or fuel-system evidence. */
internal fun mechanicSourceScan(source: DashboardSource, scan: DiagnosticScanView?): DiagnosticScanView? = scan?.takeIf {
    when (source) {
        DashboardSource.HARDWARE -> it.status in setOf(DiagnosticScanStatus.CAPTURED, DiagnosticScanStatus.HISTORICAL)
        DashboardSource.SNAPSHOT -> it.status in setOf(DiagnosticScanStatus.IMPORTED, DiagnosticScanStatus.IMPORTED_SIMULATED)
        DashboardSource.DEMO, DashboardSource.SCRIPTED -> it.status == DiagnosticScanStatus.SIMULATED
        DashboardSource.CIVIC_REPLAY -> false // Saved-drive formats contain no diagnostic scan.
    }
}
internal fun mechanicScanAge(scan: DiagnosticScanView?, now: Long): String {
    if (scan == null) return "No scan supplied"
    val age = scan.capturedMillis?.takeIf { it in 0..now && scan.status in setOf(DiagnosticScanStatus.CAPTURED, DiagnosticScanStatus.HISTORICAL) }?.let { now - it }
    return scan.status.name.lowercase().replace('_', ' ') + " · " + (age?.let { "${it / 1000} s since capture" } ?: "capture age unknown")
}
internal fun mechanicFuelStatus(source: DashboardSource, rows: List<DashboardSignal>, scan: DiagnosticScanView?): List<String> {
    val row = rows.firstOrNull { it.definition.id == "fuel-system-status" || it.definition.pid == "0103" }
    val current = row?.takeIf { it.availability == SignalAvailability.AVAILABLE }?.value as? SignalValue.Structured
    val archivedScan = mechanicSourceScan(source, scan)
    val captured = archivedScan?.rows?.firstOrNull { it.pid == 0x03 }?.value as? DecodedReading.Structured
    val fields = current?.fields ?: captured?.fields
    if (fields == null) return listOf("Fuel system: " + (row?.status ?: if (source == DashboardSource.CIVIC_REPLAY) "Not retained in this drive" else "Not supplied"))
    val prefix = if (current != null) "Supplied" else "Last scan"
    // OBD fuel-system numbers do not establish engine bank mapping.
    return (1..2).map { "$prefix fuel system $it: ${fields["system_$it"] ?: "Unknown"}" }
}
internal data class MechanicFaultSummary(val context: String, val mil: String, val count: String, val replies: List<String>)
internal fun mechanicFaultSummary(source: DashboardSource, scan: DiagnosticScanView?, now: Long): MechanicFaultSummary {
    val selected = mechanicSourceScan(source, scan)
    if (selected == null) return MechanicFaultSummary("No diagnostic scan for this source", "MIL unknown", "Code list unknown", emptyList())
    val stored = selected.storedDtcs
    val storedCodes = stored?.codes
    val replies = selected.warningContext?.codes?.map { reply ->
        reply.service.label + ": " + when (reply.status) {
            DiagnosticReplyStatus.AVAILABLE -> reply.codes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "No codes returned in this response"
            else -> reply.status.name.lowercase().replace('_', ' ')
        }
    } ?: listOf("Stored: " + when {
        stored == null -> "Not captured"
        storedCodes == null -> "NO DATA · unknown"
        storedCodes.isEmpty() -> "No codes returned in this response"
        else -> storedCodes.joinToString(", ")
    })
    return MechanicFaultSummary(mechanicScanAge(selected, now),
        "Last scan MIL: " + (selected.milOn?.let { if (it) "ON" else "OFF" } ?: "unknown"),
        "ECU-reported count: " + (selected.reportedDtcCount?.toString() ?: "unknown") + " (not a code list)", replies)
}

/** Only the explicit offline Demo route uses this fixture; import/replay/hardware never call it. */
internal fun mechanicDemoRows(scenario: DemoScenario): List<DashboardSignal> {
    if (scenario !in setOf(DemoScenario.COMPLETE, DemoScenario.TIMED_WORKSPACE)) return demoRows(scenario)
    val timed = scenario == DemoScenario.TIMED_WORKSPACE
    val base = syntheticWorkspaceFixture()
    val trims = mechanicTrimIds.mapIndexed { index, id ->
        val pid = listOf(0x06, 0x07, 0x08, 0x09)[index]
        val value = listOf(0.0, 2.34375, -1.5625, .78125)[index]
        val points = (0..60).map { second -> TrendPoint(second * 1000L, value.takeUnless { second in 35..38 }, "Synthetic receipt +${second}s", second * 1000L + 3000) }
        DashboardSignal(nativeSignalDefinition(pid, SignalSupport.SUPPORTED), SignalAvailability.AVAILABLE,
            SignalValue.Number(value), reason = SYNTHETIC_TRACE_REASON,
            trend = SignalTrend(points), trendNowMillis = 60000, observedAt = "Synthetic receipt +60s", ageMillis = 0)
    }
    return (base + trims).map { if (timed) it else it.copy(trend = SignalTrend(), trendNowMillis = null,
        observedAt = null, ageMillis = null, reason = "Fictional static mechanic fixture; no receipt time or history supplied") }
}

internal val MechanicCell.needsDataAttention: Boolean get() = !available &&
    !status.startsWith("Hidden") && !status.startsWith("Not selected for display")
internal data class MechanicDataCounts(val total: Int, val available: Int, val stale: Int,
    val unavailable: Int, val disconnected: Int, val errors: Int)
/** Count selected source rows before any card/availability visibility filter. */
internal fun mechanicDataCounts(selected: List<DashboardSignal>) = MechanicDataCounts(selected.size,
    selected.count { it.availability == SignalAvailability.AVAILABLE },
    selected.count { it.availability == SignalAvailability.STALE },
    selected.count { it.availability in setOf(SignalAvailability.UNAVAILABLE, SignalAvailability.NOT_SAMPLED) },
    selected.count { it.availability == SignalAvailability.DISCONNECTED },
    selected.count { it.availability == SignalAvailability.ERROR })
internal fun mechanicDataProvenance(source: DashboardSource, rows: List<DashboardSignal>): String = when (source) {
    DashboardSource.DEMO -> if (rows.any { it.reason == SYNTHETIC_TRACE_REASON && it.trend.points.isNotEmpty() })
        "Synthetic timed observations · not vehicle receipts" else "Fictional static values · receipt times not supplied"
    DashboardSource.SCRIPTED -> "Scripted fictional observations · not a vehicle"
    DashboardSource.CIVIC_REPLAY -> "Retained replay · not live · ECU capability unverified"
    DashboardSource.SNAPSHOT -> "Historical snapshot · not live"
    DashboardSource.HARDWARE -> "Adapter receipt data · ECU sample time unknown"
}

/** Promotion changes only unsaved defaults. Existing v1 layouts retain the prior implicit
 * selection when no per-PID override was written; explicit and dormant choices still win.
 * Reading never rewrites preferences or changes acquisition. Reset is a separate user action. */
internal fun regularWorkspacePlotIds(rows: List<DashboardSignal>, pins: List<String>, raw: String?): List<String> =
    persistedWorkspacePlotIds(rows, pins, raw, if (raw == null) mechanicDefaultSelection(rows, pins) else null)
