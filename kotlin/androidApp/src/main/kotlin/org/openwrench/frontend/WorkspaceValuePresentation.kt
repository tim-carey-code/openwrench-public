package org.openwrench.frontend

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import org.openwrench.core.SignalValue

/** Overview precision only: receipts, charts and the inspector retain original precision. */
internal fun workspaceValuePresentation(row: DashboardSignal, imperial: Boolean): LiveValuePresentation {
    val original = liveValuePresentation(row, imperial)
    if (!original.available || !original.scalar) return original
    val converted = imperial && row.definition.unit in setOf("km/h", "°C", "kPa", "g/s")
    val supplied = row.value
    val decimals = when (original.unit) {
        "rpm", "°C", "°F", "km/h", "mph" -> 0
        "V", "%", "g/s", "lb/min", "psi", "L/h" -> 1
        else -> 2
    }
    val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
        isGroupingUsed = true
        maximumFractionDigits = decimals
        roundingMode = RoundingMode.HALF_UP
    }
    val text = if (supplied is SignalValue.Integer && !converted) formatter.format(supplied.value) else {
        val number = row.numericValue()?.let { convertDisplayValue(it, row.definition.unit, imperial) }
            ?.takeIf(Double::isFinite) ?: return original
        // Decimal rounding keeps rounded negative zero neutral and avoids binary tie surprises.
        formatter.format(BigDecimal.valueOf(number).setScale(decimals, RoundingMode.HALF_UP))
    }
    return original.copy(value = text)
}
