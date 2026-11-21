package org.openwrench.frontend

import org.openwrench.frontend.android.DiagnosisPhase

internal enum class DiagnosisConnectionAction { CONNECT, SETUP, NONE }

internal data class DiagnosisDriveHomePresentation(
    val connected: Boolean,
    val recording: Boolean,
    val subtitle: String,
    val connectionStatus: String,
    val connectionAction: DiagnosisConnectionAction,
    val connectionButton: String,
    val connectionHint: String,
    val startEnabled: Boolean,
    val endEnabled: Boolean,
    val retainedFinishEnabled: Boolean,
    val continueEnabled: Boolean,
)

/** Connection ownership and diagnosis admission are different facts. No transport work happens here. */
internal fun diagnosisDriveHomePresentation(
    phase: DiagnosisPhase,
    source: DashboardSource,
    hardware: WatchUiState,
    adapterSelected: Boolean,
    canStart: Boolean,
    busy: Boolean,
): DiagnosisDriveHomePresentation {
    val hardwareSource = source == DashboardSource.HARDWARE
    val connected = hardwareSource && hardware.connection == HardwareConnection.CONNECTED
    val recording = phase == DiagnosisPhase.RECORDING
    val active = phase in setOf(DiagnosisPhase.STARTING, DiagnosisPhase.RECORDING,
        DiagnosisPhase.PAUSING, DiagnosisPhase.FINISHING)
    val connecting = hardware.connection in setOf(HardwareConnection.CONNECTING, HardwareConnection.DISCOVERING)
    val stopping = hardware.acquisition in setOf(Acquisition.STOPPING, Acquisition.STOP_UNCONFIRMED)
    val action = when {
        busy || hardware.busy || active || connecting || stopping -> DiagnosisConnectionAction.NONE
        !hardwareSource || hardware.cleanupUncertain || connected || !adapterSelected -> DiagnosisConnectionAction.SETUP
        else -> DiagnosisConnectionAction.CONNECT
    }
    val subtitle = when (phase) {
        DiagnosisPhase.STARTING -> "Preparing your drive · collecting the initial scan."
        DiagnosisPhase.FINISHING -> "Ending your drive · collecting final evidence and saving."
        DiagnosisPhase.PAUSING -> "Pausing your drive · saving received readings."
        DiagnosisPhase.RECORDING -> if (connected) "Your drive is being recorded."
            else "Connection lost · retaining received evidence."
        DiagnosisPhase.PAUSED -> "Drive paused · saved evidence retained."
        DiagnosisPhase.INTERRUPTED -> "Drive interrupted · saved evidence retained."
        DiagnosisPhase.COMPLETED -> "Drive complete · historical evidence saved."
        DiagnosisPhase.IDLE -> when {
            busy -> "Loading or updating diagnosis…"
            connected && canStart -> "Your adapter is ready for a drive."
            connected -> "Your adapter is connected. Finish the current operation before starting."
            connecting -> "Connecting to your adapter…"
            else -> "Connect your adapter to get started."
        }
    }
    val connectionStatus = when {
        !hardwareSource -> when (source) {
            DashboardSource.DEMO -> "Offline demo · No dongle connected"
            DashboardSource.SNAPSHOT -> "Historical snapshot · Not live"
            else -> "Historical replay · Not live"
        }
        hardware.cleanupUncertain -> "Adapter release unconfirmed"
        connected -> "Dongle connected"
        connecting -> "Connecting to dongle…"
        hardware.connection == HardwareConnection.FAULTED -> "Adapter connection error"
        else -> "Dongle disconnected"
    }
    val retained = phase in setOf(DiagnosisPhase.PAUSED, DiagnosisPhase.INTERRUPTED)
    return DiagnosisDriveHomePresentation(
        connected = connected, recording = recording, subtitle = subtitle,
        connectionStatus = connectionStatus, connectionAction = action,
        connectionButton = when {
            connected -> "Connection settings"
            action == DiagnosisConnectionAction.SETUP -> "Set up connection"
            else -> "Connect Dongle"
        },
        connectionHint = when {
            active -> "Finish or pause the current drive before changing the connection"
            busy || hardware.busy || connecting || stopping -> "Wait for the current operation"
            hardware.cleanupUncertain -> "Review adapter cleanup before reconnecting"
            !hardwareSource -> "Choose a hardware source in connection settings"
            connected -> "Adapter connected · review configuration"
            adapterSelected -> "Use selected adapter settings"
            else -> "Choose an adapter and connection settings"
        },
        startEnabled = canStart && !busy && !active,
        endEnabled = recording && !busy,
        retainedFinishEnabled = retained && !busy,
        continueEnabled = retained && canStart && !busy,
    )
}

/** Unlike the source-strip toggle, this action never disconnects an owned session. */
internal fun performDiagnosisConnectionAction(
    action: DiagnosisConnectionAction,
    connect: () -> Unit,
    setup: () -> Unit,
) {
    when (action) {
        DiagnosisConnectionAction.CONNECT -> connect()
        DiagnosisConnectionAction.SETUP -> setup()
        DiagnosisConnectionAction.NONE -> Unit
    }
}

internal fun diagnosisDriveHomeWide(widthDp: Float, fontScale: Float): Boolean =
    widthDp / fontScale.coerceAtLeast(1f) >= 720f
