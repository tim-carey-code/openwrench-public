package org.openwrench.frontend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Use the actual inner panel width, including font scaling, rather than the device width. */
internal fun mechanicAirColumns(contentWidth: Float, fontScale: Float): Int {
    val scale = (fontScale.takeIf { it.isFinite() } ?: 1f).coerceAtLeast(1f)
    return if (contentWidth.isFinite() && (contentWidth - 8f) / 2f >= 176f * scale) 2 else 1
}

internal data class MechanicRowWidths(val widths: List<Int>, val gap: Int)

/** Keep every measured child positive-width, including transient resize constraints. */
internal fun mechanicRowWidths(width: Int, columns: Int, requestedGap: Int): MechanicRowWidths {
    require(columns > 0)
    if (width < columns) return MechanicRowWidths(emptyList(), 0)
    val gap = if (columns == 1) 0 else requestedGap.coerceIn(0, (width - columns) / (columns - 1))
    val available = width - gap * (columns - 1)
    return MechanicRowWidths(List(columns) { available / columns + if (it < available % columns) 1 else 0 }, gap)
}

/**
 * Measure natural content first, then give each visible card the row's greatest height.
 * No intrinsic measurement: ScalarValue contains BoxWithConstraints. No fixed-height
 * clipping or retained size state; rows shrink/grow on source, width and font changes.
 * Callers contain presentation only: no launches, I/O or other composition side effects.
 */
@Composable
internal fun MechanicEvenRow(columns: Int, cards: List<@Composable (Modifier) -> Unit>) {
    require(columns >= cards.size && columns > 0)
    // Narrow layouts need no peer-height alignment or measuring copy.
    if (columns == 1) {
        cards.firstOrNull()?.invoke(Modifier.fillMaxWidth())
        return
    }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val partition = mechanicRowWidths(width, columns, 8.dp.roundToPx())
        if (partition.widths.isEmpty() || cards.isEmpty()) return@SubcomposeLayout layout(width, 0) {}
        val natural = cards.mapIndexed { index, card ->
            subcompose("measure-$index") {
                // These copies are never placed, drawn or hit-tested. Hide their semantics
                // explicitly as well; only the placed card exposes accessibility/actions.
                Box(Modifier.clearAndSetSemantics {}) { card(Modifier) }
            }.single()
                .measure(Constraints.fixedWidth(partition.widths[index]))
        }
        val height = constraints.constrainHeight(natural.maxOf { it.height })
        val placed = cards.mapIndexed { index, card ->
            subcompose("card-$index") { Box(propagateMinConstraints = true) { card(Modifier) } }.single()
                .measure(Constraints.fixed(partition.widths[index], height))
        }
        layout(width, height) {
            var x = 0
            placed.forEachIndexed { index, card ->
                card.placeRelative(x, 0)
                x += partition.widths[index] + partition.gap
            }
        }
    }
}
