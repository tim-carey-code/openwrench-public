package org.openwrench.frontend.android

import kotlinx.serialization.json.*
import org.openwrench.frontend.android.transport.UsbConnectionSettings
import org.openwrench.frontend.android.transport.UsbControlLines

/** Local choices only. Matching a listed adapter never opens it or grants permission. */
data class AdapterSelectionMemory(
    val lastMode: AdapterMode = AdapterMode.DEMO,
    val bluetoothAddress: String? = null,
    val usbVendorId: Int? = null,
    val usbProductId: Int? = null,
    val baud: Int = UsbConnectionSettings.AUTO_BAUD,
    val usbControlLines: UsbControlLines = UsbControlLines.ASSERTED,
) {
    init {
        require(bluetoothAddress == null || ADDRESS.matches(bluetoothAddress))
        require((usbVendorId == null) == (usbProductId == null))
        require(usbVendorId == null || usbVendorId in 0..0xFFFF)
        require(usbProductId == null || usbProductId in 0..0xFFFF)
        require(baud == UsbConnectionSettings.AUTO_BAUD || baud in UsbConnectionSettings.SUPPORTED_BAUD_RATES)
    }

    /**
     * A USB deviceId is valid only for the current attachment. Match saved VID/PID
     * against the current inventory, and require a choice when that match is ambiguous.
     * If a remembered adapter is absent, never substitute a different one.
     */
    fun select(mode: AdapterMode, options: List<AdapterOption>, currentId: String?): String? {
        if (mode == AdapterMode.DEMO) return null
        if (currentId != null && options.any { it.id == currentId }) return currentId
        return when (mode) {
            AdapterMode.BLUETOOTH -> if (bluetoothAddress == null) options.singleOrNull()?.id
                else options.singleOrNull { it.id == bluetoothAddress }?.id
            AdapterMode.USB -> if (usbVendorId == null) options.singleOrNull()?.id
                else options.singleOrNull { it.usbVendorId == usbVendorId && it.usbProductId == usbProductId }?.id
            AdapterMode.DEMO -> null
        }
    }

    /** Remember an explicit menu choice, retaining the other transport's settings. */
    fun remember(mode: AdapterMode, option: AdapterOption): AdapterSelectionMemory = when (mode) {
        AdapterMode.BLUETOOTH -> copy(lastMode = mode, bluetoothAddress = option.id)
        AdapterMode.USB -> copy(lastMode = mode,
            usbVendorId = requireNotNull(option.usbVendorId), usbProductId = requireNotNull(option.usbProductId))
        AdapterMode.DEMO -> copy(lastMode = mode)
    }

    private companion object { val ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}") }
}

/** Bounded private preference. Invalid or future data falls back to safe initial choices. */
object AdapterSelectionMemoryCodec {
    const val MAX_BYTES = 512
    private const val SCHEMA = "openwrench.adapter-selection/1"
    private val keys = setOf("schema", "mode", "bluetooth", "usb_vendor", "usb_product", "baud", "usb_lines")

    fun encode(memory: AdapterSelectionMemory): String = buildJsonObject {
        put("schema", SCHEMA); put("mode", memory.lastMode.name)
        put("bluetooth", memory.bluetoothAddress?.let(::JsonPrimitive) ?: JsonNull)
        put("usb_vendor", memory.usbVendorId?.let(::JsonPrimitive) ?: JsonNull)
        put("usb_product", memory.usbProductId?.let(::JsonPrimitive) ?: JsonNull)
        put("baud", memory.baud); put("usb_lines", memory.usbControlLines.name)
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(raw: String?): AdapterSelectionMemory {
        if (raw == null || raw.length > MAX_BYTES) return AdapterSelectionMemory()
        return try {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            SnapshotCodec.StrictJsonCheck(raw).check()
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root.keys == keys && root.string("schema") == SCHEMA)
            AdapterSelectionMemory(
                lastMode = AdapterMode.valueOf(root.string("mode")),
                bluetoothAddress = root.getValue("bluetooth").takeUnless { it == JsonNull }?.let { root.string("bluetooth") },
                usbVendorId = root.optionalInteger("usb_vendor"), usbProductId = root.optionalInteger("usb_product"),
                baud = requireNotNull(root.optionalInteger("baud")),
                usbControlLines = UsbControlLines.valueOf(root.string("usb_lines")),
            )
        } catch (_: Exception) { AdapterSelectionMemory() }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.optionalInteger(key: String): Int? = getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive
        ?.also { require(!it.isString) }?.let { requireNotNull(it.intOrNull) }
}
