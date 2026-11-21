package org.openwrench.frontend

import org.openwrench.core.*

internal enum class DashboardLayout { PHONE, TABLET, DESKTOP }

/** Font scale participates in layout so enlarged text does not retain desktop geometry. */
internal fun dashboardLayout(width: Float, fontScale: Float): DashboardLayout = when {
    width / fontScale.coerceAtLeast(1f) >= 1000 -> DashboardLayout.DESKTOP
    width / fontScale.coerceAtLeast(1f) >= 640 -> DashboardLayout.TABLET
    else -> DashboardLayout.PHONE
}

/** Rendering limits only, never vehicle capabilities, safety thresholds, or decoded values. */
internal fun DashboardSignal.gaugeFraction(): Float? {
    if (availability != SignalAvailability.AVAILABLE || definition.support != SignalSupport.SUPPORTED) return null
    val number = when (val supplied = value) {
        is SignalValue.Number -> supplied.value
        is SignalValue.Integer -> supplied.value.toDouble()
        else -> return null
    }
    val maximum = when {
        definition.id == "rpm" && definition.unit == "rpm" -> 8000.0
        definition.unit == "%" -> 100.0
        else -> return null
    }
    return (number / maximum).coerceIn(0.0, 1.0).toFloat()
}

internal fun DashboardSignal.visibleValue(): String =
    if (availability == SignalAvailability.AVAILABLE && definition.support == SignalSupport.SUPPORTED)
        value?.compactDisplay() ?: "—" else "—"

internal fun DashboardSignal.ageCaption(): String = ageMillis?.let {
    "${ageLabel}: ${it / 1000}.${(it % 1000) / 100} s"
} ?: "$ageLabel unknown"
