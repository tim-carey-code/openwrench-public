package org.openwrench.frontend

/** Reference roles are display slots, never additions to a source catalog or read plan. */
internal data class OverviewRole(val id: String, val label: String, val unit: String)
internal val overviewRoles = listOf(
    OverviewRole("rpm", "RPM", "rpm"), OverviewRole("coolant", "Coolant", "°C"),
    OverviewRole("speed", "Speed", "km/h"), OverviewRole("control-module-voltage", "Voltage", "V"),
)

/** Adapt to logical dp, not pixel resolution: ordinary landscape tablets retain a two-column dashboard. */
internal fun referenceWorkspaceColumns(widthDp: Float, fontScale: Float): Int {
    if (!widthDp.isFinite()) return 1
    val effective = widthDp / (fontScale.takeIf { it.isFinite() } ?: 1f).coerceAtLeast(1f)
    return when { effective > 900f -> 3; effective > 640f -> 2; else -> 1 }
}
internal fun referenceWorkspaceWide(widthDp: Float, fontScale: Float): Boolean = referenceWorkspaceColumns(widthDp, fontScale) == 3

/** Reference roles are defaults. Any saved card order/pin/visibility edit takes precedence. */
internal fun referenceOverviewRoles(layout: GraphFirstLayout, defaults: GraphFirstLayout, rows: List<DashboardSignal>): List<OverviewRole> {
    val ids = rows.filter { !layout.hideUnavailable || it.availability == SignalAvailability.AVAILABLE }.map { it.definition.id }
    val visible = layout.visible(WorkspaceModuleKind.COMPACT_VALUE, ids)
    val defaultIds = defaults.visible(WorkspaceModuleKind.COMPACT_VALUE, ids).map { it.sensorId }
    val customized = visible.map { it.sensorId } != defaultIds || visible.any { it.pinned }
    val eligibleRoles = visible.map { module ->
        overviewRoles.firstOrNull { it.id == module.sensorId } ?: rows.first { it.definition.id == module.sensorId }.let {
            OverviewRole(it.definition.id, it.definition.label, it.definition.unit.orEmpty())
        }
    }
    if (customized) return eligibleRoles.take(4)
    // Retain the reference roles when present, filling unused slots only from visible selected modules.
    return (overviewRoles.filter { role -> eligibleRoles.any { it.id == role.id } } + eligibleRoles).distinctBy { it.id }.take(4)
}

internal data class ReceiptSummary(val count: Int, val segments: Int, val minimum: Double?, val maximum: Double?)
internal fun receiptSummary(trace: InstrumentTracePresentation): ReceiptSummary {
    val values = trace.segments.flatten().mapNotNull { it.value?.takeIf(Double::isFinite) }
    return ReceiptSummary(values.size, trace.segments.count { it.isNotEmpty() }, values.minOrNull(), values.maxOrNull())
}

/** The same bounded presets, with denser plots in the overview only; Graphs remains full width. */
internal fun overviewPlotHeightDp(height: WorkspaceGraphHeight, expanded: Boolean): Float =
    if (expanded) 280f else when (height) {
        WorkspaceGraphHeight.COMPACT -> 80f
        WorkspaceGraphHeight.STANDARD -> 120f
        WorkspaceGraphHeight.TALL -> 220f
    }

/** The top strip and supplemental panel partition visible value modules without duplicates or lost order. */
internal fun referenceSupplementalModules(visible: List<WorkspaceModule>, roles: List<OverviewRole>): List<WorkspaceModule> =
    visible.filter { module -> roles.none { it.id == module.sensorId } }

/** Figma density is bounded and reflows with accessible text size. */
internal fun workspaceCardColumns(width: Float, fontScale: Float, density: WorkspaceCardDensity): Int {
    val effective = if (width.isFinite()) width / fontScale.coerceAtLeast(1f) else 0f
    val safe = when { effective > 640f -> 4; effective >= 300f -> 2; else -> 1 }
    return minOf(safe, density.columns)
}
internal fun workspaceGraphColumns(width: Float, fontScale: Float, fullWidth: Boolean): Int =
    if (!fullWidth && width.isFinite() && width / fontScale.coerceAtLeast(1f) > 900f) 2 else 1

/** Header and rows share the 1100dp table limit, 9dp outer and 14dp cell padding per side. */
internal fun workspaceValuesShowLastSample(viewportWidth: Float, fontScale: Float): Boolean =
    viewportWidth.isFinite() && (minOf(viewportWidth, 1118f) - 46f) / (fontScale.takeIf { it.isFinite() } ?: 1f).coerceAtLeast(1f) >= 620f

/** Uploaded Figma CSS uses icon-only actions through the inclusive 900px breakpoint. */
internal fun workspaceToolbarCompactActions(viewportWidth: Float, fontScale: Float): Boolean =
    !viewportWidth.isFinite() || viewportWidth / (fontScale.takeIf { it.isFinite() } ?: 1f).coerceAtLeast(1f) <= 900f

/** Occupied receipt bins, never inferred ECU sample rate or loss percentage. */
internal fun receiptBins(trace: InstrumentTracePresentation, window: Long): List<Int> {
    val counts = MutableList(8) { 0 }
    trace.segments.flatten().forEach { point ->
        val offset = point.atMillis - (trace.nowMillis - window)
        if (offset in 0..window && point.value?.isFinite() == true)
            counts[((offset.toDouble() / window.coerceAtLeast(1) * 8).toInt()).coerceIn(0, 7)]++
    }
    return counts
}

/** One explicit partition prevents duplicates and preserves temperature overflow. */
internal fun overviewTemperatureModules(modules: List<WorkspaceModule>, rows: List<DashboardSignal>): List<WorkspaceModule> =
    modules.drop(1).filter { m -> rows.any { it.definition.id == m.sensorId && it.definition.unit == "°C" } }.take(3)
internal fun overviewRemainingGraphs(modules: List<WorkspaceModule>, rows: List<DashboardSignal>): List<WorkspaceModule> {
    val consumed = (modules.take(1) + overviewTemperatureModules(modules, rows)).map { it.key }.toSet()
    return modules.filter { it.key !in consumed }
}

internal fun workspaceSupportLabel(row: DashboardSignal, archived: Boolean): String =
    if (archived) "Retained replay channel · ECU capability unverified" else row.supportDescription

internal fun temperaturePlotHeightDp(height: WorkspaceGraphHeight, expanded: Boolean, fontScale: Float): Float =
    (if (expanded) 280f else when (height) { WorkspaceGraphHeight.COMPACT -> 60f; WorkspaceGraphHeight.STANDARD -> 70f; WorkspaceGraphHeight.TALL -> 145f }) *
        (fontScale.takeIf { it.isFinite() } ?: 1f).coerceIn(1f, 2f)

internal data class WorkspaceScatterPoint(val atMillis: Long, val throttle: Double, val rpm: Double)
/** Exact, unique receipt-time intersections only. No nearest-neighbor pairing or interpolation. */
internal fun workspaceScatterPoints(rpm: InstrumentTracePresentation?, throttle: InstrumentTracePresentation?, window: Long): List<WorkspaceScatterPoint> {
    if (rpm == null || throttle == null || window <= 0) return emptyList()
    val end = maxOf(rpm.nowMillis, throttle.nowMillis)
    fun unique(trace: InstrumentTracePresentation) = trace.segments.flatten()
        .filter { it.atMillis in (end - window)..end && it.value?.isFinite() == true }
        .groupBy { it.atMillis }.filterValues { it.size == 1 }.mapValues { it.value.single().value!! }
    val engines = unique(rpm); val pedals = unique(throttle)
    return engines.keys.intersect(pedals.keys).sorted().takeLast(240).mapNotNull { at ->
        val x = pedals.getValue(at); val y = engines.getValue(at)
        if (x in 0.0..100.0 && y >= 0) WorkspaceScatterPoint(at, x, y) else null
    }
}

/** Display defaults include actual supplied/retained numeric channels, never broaden the read plan. */
internal fun defaultWorkspacePlotIds(rows: List<DashboardSignal>, pins: List<String>): List<String> {
    val eligible = rows.filter { it.definition.isGraphable() &&
        (it.definition.id in pins || it.availability == SignalAvailability.AVAILABLE || it.trend.points.isNotEmpty()) }.map { it.definition.id }
    return (overviewRoles.map { it.id } + pins + eligible).distinct().filter { it in eligible }.take(GraphFirstLayout.MAX_SIGNALS)
}

/** Native chart atMillis is UI presentation time, not a verified per-channel receipt clock. */
internal fun workspaceSourceScatter(source: DashboardSource, rows: List<DashboardSignal>, frozen: List<DashboardSignal>?,
    imperial: Boolean, range: GraphTimeRange, fitScale: Boolean): List<WorkspaceScatterPoint> {
    val rpm = rows.firstOrNull { it.definition.id == "rpm" && it.definition.unit == "rpm" } ?: return emptyList()
    val throttle = rows.firstOrNull { it.definition.id == "throttle" && it.definition.unit == "%" } ?: return emptyList()
    val allowed = source == DashboardSource.CIVIC_REPLAY || source == DashboardSource.DEMO &&
        rpm.reason == SYNTHETIC_TRACE_REASON && throttle.reason == SYNTHETIC_TRACE_REASON
    if (!allowed) return emptyList()
    return workspaceScatterPoints(graphFocusTrace(rpm, frozen, imperial, range, fitScale),
        graphFocusTrace(throttle, frozen, imperial, range, fitScale), range.millis)
}

/** Nullable per-signal overrides preserve legacy/default selection and dormant source choices. */
internal fun persistedWorkspacePlotIds(rows: List<DashboardSignal>, pins: List<String>, raw: String?, initialIds: List<String>? = null): List<String> {
    val defaults = (initialIds ?: defaultWorkspacePlotIds(rows, pins)).toSet()
    val ids = rows.map { it.definition.id }
    val numeric = rows.filter { it.definition.isGraphable() }.map { it.definition.id }
    val layout = WorkspaceLayoutCodec.decode(raw, ids, numeric).layout
    return layout.ordered(WorkspaceModuleKind.GRAPH).filter { it.displaySelected ?: (it.sensorId in defaults) }.map { it.sensorId }
}
internal fun GraphFirstLayout.withDisplaySelection(ids: List<String>, selected: List<String>): GraphFirstLayout =
    copy(modules = modules.map { if (it.kind == WorkspaceModuleKind.GRAPH && it.sensorId in ids) it.copy(displaySelected = it.sensorId in selected) else it })


internal data class TemperatureLayer(val id: String, val trace: InstrumentTracePresentation)
internal data class TemperatureOverlay(val layers: List<TemperatureLayer>, val axis: InstrumentTracePresentation, val compatibleClock: Boolean)

/** Only histories sharing an explicit display-window end are overlaid; never retime or interpolate. */
internal fun workspaceTemperatureOverlay(rows: List<DashboardSignal>, modules: List<WorkspaceModule>, frozen: List<DashboardSignal>?,
    imperial: Boolean, range: GraphTimeRange, fitScale: Boolean): TemperatureOverlay {
    val layers = modules.filter { it.visible && it.expanded }.mapNotNull { module ->
        rows.firstOrNull { it.definition.id == module.sensorId && it.definition.unit == "°C" }?.let { row ->
            TemperatureLayer(module.sensorId, graphFocusTrace(row, frozen, imperial, range, false))
        }
    }
    val ends = layers.filter { it.trace.segments.isNotEmpty() }.map { it.trace.nowMillis }.distinct()
    val compatible = ends.size <= 1
    val end = ends.singleOrNull() ?: 0L
    val visible = if (compatible) layers else layers.map { it.copy(trace = it.trace.copy(segments = emptyList())) }
    val values = visible.flatMap { it.trace.segments.flatten() }.mapNotNull { it.value?.takeIf(Double::isFinite) }
    val bounds = if (fitScale && values.isNotEmpty()) {
        val low = values.min(); val high = values.max()
        val pad = maxOf((high - low) * .1, kotlin.math.abs(high) * .01, .1)
        low - pad to high + pad
    } else (layers.minOfOrNull { it.trace.bounds.first } ?: if (imperial) 32.0 else 0.0) to
        (layers.maxOfOrNull { it.trace.bounds.second } ?: if (imperial) 212.0 else 100.0)
    return TemperatureOverlay(visible, InstrumentTracePresentation(end, emptyList(), bounds), compatible)
}


/** Measured constraints can briefly be narrower than the composition's responsive choice. */
internal data class ReferenceGridWidths(val columns: Int, val gap: Int, val main: Int,
    val left: Int, val middle: Int, val rail: Int)
internal fun referenceGridWidths(widthPx: Int, columns: Int, gapPx: Int, railPx: Int): ReferenceGridWidths {
    val width = widthPx.coerceAtLeast(0)
    val gap = gapPx.coerceAtLeast(0).coerceAtMost(width)
    val rail = railPx.coerceAtLeast(0)
    // Preserve ordinary geometry; reflow before a fixed rail/gutter can exhaust the body.
    val count = when {
        columns >= 3 && width.toLong() >= rail.toLong() + 3L * gap + 2L -> 3
        columns >= 2 && width.toLong() >= gap.toLong() + 2L -> 2
        else -> 1
    }
    if (count == 1) return ReferenceGridWidths(1, gap, width, width, width, width)
    val main = if (count == 3) width - rail - gap else width
    val left = if (count == 3) ((main - gap) / 2.25f * 1.25f).toInt() else (width - gap) / 2
    return ReferenceGridWidths(count, gap, main, left, main - left - gap, if (count == 3) rail else left)
}
