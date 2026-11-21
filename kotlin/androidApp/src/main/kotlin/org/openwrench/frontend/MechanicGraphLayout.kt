package org.openwrench.frontend

/** Width is the actual graph content area after workspace padding, not the device width. */
internal fun mechanicGraphColumns(availableWidthDp: Float, fontScale: Float, fullWidth: Boolean): Int {
    if (fullWidth || !availableWidthDp.isFinite() || !fontScale.isFinite() || fontScale <= 0f) return 1
    // Reserve a readable axis/plot/header area per card; enlarged text reflows instead of shrinking.
    val minimumCardWidth = 320f * fontScale.coerceAtLeast(1f)
    return if (availableWidthDp >= minimumCardWidth * 2f + 8f) 2 else 1
}

/** Pair existing ordered modules only. No selection, settings, samples or acquisition are changed. */
internal fun mechanicGraphRows(modules: List<WorkspaceModule>, availableWidthDp: Float,
    fontScale: Float, fullWidth: Boolean, paired: Boolean): List<List<WorkspaceModule>> =
    modules.chunked(if (paired) mechanicGraphColumns(availableWidthDp, fontScale, fullWidth) else 1)
