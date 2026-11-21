package org.openwrench.frontend

/** Presentation-only range. Never manufactures evidence before the retained trace. */
internal fun chartWindowStart(retainedStart: Long?, end: Long?, rangeMillis: Long?): Long? {
    if (retainedStart == null || end == null || rangeMillis == null) return retainedStart
    return maxOf(retainedStart, end.coerceAtLeast(0) - rangeMillis.coerceAtLeast(0))
}
