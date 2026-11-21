package org.openwrench.frontend

import org.openwrench.core.*
import org.openwrench.frontend.android.*
import org.openwrench.nativecore.*

enum class HardwareConnection { DISCONNECTED, CONNECTING, DISCOVERING, CONNECTED, FAULTED }
enum class Acquisition { STOPPED, STARTING, RUNNING, STOPPING, STOP_UNCONFIRMED, EXPIRED, FAULTED }
const val DASHBOARD_RECEIPT_STALE_MILLIS = 5_000L

/** Presentation state only. All acquisition stays with NativeController's single reader. */
data class WatchUiState(
    val connection: HardwareConnection = HardwareConnection.DISCONNECTED,
    val acquisition: Acquisition = Acquisition.STOPPED,
    val catalog: List<SignalDefinition> = emptyList(),
    val selection: List<String> = emptyList(),
    val busy: Boolean = false,
    val cleanupUncertain: Boolean = false,
    val detail: String = "Disconnected. Choose an adapter in Session.",
)

/** Selection admits a new connection; an owned session can always be canceled/released. */
internal fun WatchUiState.canUseConnectionAction(adapterSelected: Boolean): Boolean = when {
    cleanupUncertain -> !busy
    connection in setOf(HardwareConnection.CONNECTED, HardwareConnection.CONNECTING, HardwareConnection.DISCOVERING) -> true
    else -> !busy && adapterSelected
}

data class DashboardActions(
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val retryCleanup: () -> Unit,
    val startLive: () -> Unit,
    val stopLive: () -> Unit,
    val select: (String, Boolean) -> Unit,
    val selectAllSupported: () -> Unit,
    val clearSelection: () -> Unit,
    val prepareFuelTrims: (() -> ControllerAdmission)? = null,
)

internal fun saturatingAdd(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Stable Python-reference signal identifiers preserve pins and replay catalog identity. */
val nativeSignalIds: Map<Int, String> = NativeSignalCatalog.nativeSignalIds

fun nativeSignalDefinition(pid: Int, support: SignalSupport): SignalDefinition =
    NativeSignalCatalog.definition(pid, support)

fun NativeUiState.watchPresentation() = WatchUiState(
    connection = when { faulted || cleanupUncertain -> HardwareConnection.FAULTED; connected -> HardwareConnection.CONNECTED
        busy && identity == null -> HardwareConnection.CONNECTING; else -> HardwareConnection.DISCONNECTED },
    acquisition = when { faulted || cleanupUncertain -> Acquisition.FAULTED; stopPending -> Acquisition.STOPPING; liveRunning -> Acquisition.RUNNING; else -> Acquisition.STOPPED },
    catalog = ObdDecoder.scalarPids.sorted().map { pid -> nativeSignalDefinition(pid, when {
        !connected -> SignalSupport.UNKNOWN; pid in supported -> SignalSupport.SUPPORTED; else -> SignalSupport.UNSUPPORTED }) },
    selection = watched.keys.mapNotNull(nativeSignalIds::get), busy = busy, cleanupUncertain = cleanupUncertain,
    detail = detail,
)

private fun DecodedReading.signalValue(): SignalValue = when (this) {
    is DecodedReading.Scalar -> if (integer) SignalValue.Integer(value.toLong()) else SignalValue.Number(value)
    is DecodedReading.Structured -> SignalValue.Structured(fields)
}

/** Values expire from a monotonic receipt clock; UTC/ECU timestamps are never fabricated. */
fun NativeUiState.dashboardSignals(now: Long): List<DashboardSignal> {
    if (origin == ReadingOrigin.REPLAY) return snapshot.orEmpty().map { reading ->
        val metadata = nativeSignalDefinition(reading.pid, SignalSupport.SUPPORTED)
        // Snapshot/1 preserves the supplied numeric type, which can differ from the live decoder's type.
        val definition = (reading.value as? DecodedReading.Scalar)?.let {
            metadata.copy(type = if (it.integer) SignalType.INTEGER else SignalType.NUMBER)
        } ?: metadata
        DashboardSignal(definition, if (reading.value != null) SignalAvailability.AVAILABLE else SignalAvailability.UNAVAILABLE,
            reading.value?.signalValue(), reason = "Historical saved snapshot; no vehicle is connected and no capture time is supplied",
            ageLabel = "Capture age")
    }
    return watchPresentation().catalog.map { definition ->
        val pid = checkNotNull(definition.pid).substring(2).toInt(16)
        val sample = readings[pid]
        val age = sample?.receivedMillis?.takeIf { it in 0..now }?.let { now - it }
        val active = connected && liveRunning
        val availability = when {
            faulted || cleanupUncertain -> SignalAvailability.ERROR
            !connected -> SignalAvailability.DISCONNECTED
            definition.support != SignalSupport.SUPPORTED -> SignalAvailability.NOT_SAMPLED
            sample != null && (!active || age == null || age >= DASHBOARD_RECEIPT_STALE_MILLIS) -> SignalAvailability.STALE
            active && sample?.reading?.value != null -> SignalAvailability.AVAILABLE
            sample != null && sample.reading.value == null -> SignalAvailability.UNAVAILABLE
            else -> SignalAvailability.NOT_SAMPLED
        }
        DashboardSignal(definition, availability,
            sample?.reading?.value?.signalValue()?.takeIf { availability == SignalAvailability.AVAILABLE },
            ageMillis = age, reason = when {
                faulted || cleanupUncertain -> detail
                !connected -> "Session disconnected; no current reading"
                definition.support == SignalSupport.UNSUPPORTED -> "Not advertised by this vehicle; never requested"
                availability == SignalAvailability.STALE -> if (age == null) "Receipt clock is incompatible; waiting for a new adapter read" else if (!active) "Acquisition is not running" else "Observation expired; waiting for a new adapter read"
                availability == SignalAvailability.UNAVAILABLE -> "Adapter returned NO DATA"
                sample != null -> "Actual completed adapter read; ECU sample time is unknown"
                pid !in watched -> "Supported but not selected for acquisition. Pinning does not select it."
                else -> "Selected but not sampled yet; Start live data explicitly"
            }, ageLabel = "Receipt age")
    }
}

/** Receipt identities deduplicate clock ticks while every lost/expired observation creates a gap. */
class NativeTrendHistory(private val receiptClock: () -> Long) {
    private var traces = emptyMap<String, SignalTrend>()
    private var receipts = emptyMap<String, Long>()
    private var cadence = emptyMap<String, Long>()
    private var live = false
    fun present(state: NativeUiState): List<DashboardSignal> {
        // Sample after receiving this state. A cached redraw tick can precede a
        // new receipt and incorrectly classify it as an incompatible future clock.
        val now = receiptClock()
        val active = state.origin == ReadingOrigin.HARDWARE && state.connected && state.liveRunning
        if (active != live || !state.connected || state.origin != ReadingOrigin.HARDWARE) {
            traces = emptyMap(); receipts = emptyMap(); cadence = emptyMap()
        }
        live = active
        val rows = state.dashboardSignals(now)
        if (state.origin != ReadingOrigin.HARDWARE) return rows
        return rows.map { row ->
            val pid = checkNotNull(row.definition.pid).substring(2).toInt(16)
            val receipt = state.readings[pid]?.receivedMillis
            if (receipt != null && receipts[row.definition.id] != receipt) {
                receipts[row.definition.id]?.let { previous ->
                    if (receipt > previous) cadence = cadence + (row.definition.id to receipt - previous)
                }
                receipts = receipts + (row.definition.id to receipt)
            }
            val trace = (traces[row.definition.id] ?: SignalTrend()).receive(now, row.availability, row.value,
                receipt?.toString(), row.ageMillis)
            traces = traces + (row.definition.id to trace)
            row.copy(trend = trace, trendNowMillis = now, cadenceMillis = cadence[row.definition.id])
        }
    }
}
