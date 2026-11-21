package org.openwrench.frontend.android.transport

/** Host UART settings; these rates are independent of the vehicle's CAN bus bitrate. */
object UsbConnectionSettings {
    const val AUTO_BAUD = 0
    val SUPPORTED_BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 500000)
    internal val PROBE_BAUD_RATES = listOf(38400, 115200, 500000, 230400, 57600, 19200, 9600)
}

enum class UsbControlLines(val dtr: Boolean, val rts: Boolean, val title: String) {
    ASSERTED(true, true, "DTR and RTS asserted"),
    DTR_ONLY(true, false, "DTR asserted, RTS deasserted"),
    RTS_ONLY(false, true, "DTR deasserted, RTS asserted"),
    DEASSERTED(false, false, "DTR and RTS deasserted"),
}
