package org.openwrench.frontend.android

/** Main-thread publication gate retained by the process owner; stores no Activity or URI. */
internal class ImportRequestGate {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun cancel() { generation++ }
    fun isCurrent(request: Long): Boolean = request == generation
    fun complete(request: Long, publish: () -> Unit): Boolean {
        if (!isCurrent(request)) return false
        publish()
        return true
    }
}
