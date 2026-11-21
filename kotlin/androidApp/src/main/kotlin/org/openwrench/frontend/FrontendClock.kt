package org.openwrench.frontend

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface FrontendClock {
    fun elapsedMillis(): Long
    fun utc(): String
}

/** Monotonic playback clock; wall time is never substituted for capture facts. */
fun platformClock(elapsedMillis: () -> Long = android.os.SystemClock::elapsedRealtime): FrontendClock = object : FrontendClock {
    override fun elapsedMillis(): Long = elapsedMillis()
    override fun utc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
