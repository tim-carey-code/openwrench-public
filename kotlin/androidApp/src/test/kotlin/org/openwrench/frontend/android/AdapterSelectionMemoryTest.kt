package org.openwrench.frontend.android

import org.openwrench.frontend.android.transport.UsbConnectionSettings
import org.openwrench.frontend.android.transport.UsbControlLines
import kotlin.test.*

class AdapterSelectionMemoryTest {
    private val bluetooth = AdapterOption("AA:BB:CC:DD:EE:FF", "Synthetic paired adapter")
    private fun usb(id: String, vendor: Int = 0x0403, product: Int = 0x6001) =
        AdapterOption(id, "Synthetic USB adapter", vendor, product)

    @Test fun rememberedChoicesSurviveCodecRoundTripWithoutPersistingTransientUsbIdsOrLabels() {
        val memory = AdapterSelectionMemory().remember(AdapterMode.BLUETOOTH, bluetooth)
            .remember(AdapterMode.USB, usb("ephemeral-device-id"))
            .copy(baud = 115200, usbControlLines = UsbControlLines.DTR_ONLY)
        val encoded = AdapterSelectionMemoryCodec.encode(memory)
        assertEquals(memory, AdapterSelectionMemoryCodec.decode(encoded))
        assertFalse(encoded.contains("ephemeral-device-id"))
        assertFalse(encoded.contains("Synthetic"))
        assertEquals("new-attachment-id", AdapterSelectionMemoryCodec.decode(encoded)
            .select(AdapterMode.USB, listOf(usb("new-attachment-id")), null))
    }

    @Test fun keepsExplicitCurrentSelectionBeforeApplyingRememberedChoices() {
        val memory = AdapterSelectionMemory().remember(AdapterMode.BLUETOOTH, bluetooth)
            .remember(AdapterMode.USB, usb("old-id"))
        val other = AdapterOption("11:22:33:44:55:66", "Other paired device")
        assertEquals(other.id, memory.select(AdapterMode.BLUETOOTH, listOf(bluetooth, other), other.id))
        assertEquals("chosen", memory.select(AdapterMode.USB, listOf(usb("saved"), usb("chosen", 0x1234)), "chosen"))
    }

    @Test fun selectsSoleAvailableAdapterOnlyWhenNoChoiceWasRememberedForThatTransport() {
        val defaults = AdapterSelectionMemory()
        assertEquals(bluetooth.id, defaults.select(AdapterMode.BLUETOOTH, listOf(bluetooth), null))
        assertEquals("1", defaults.select(AdapterMode.USB, listOf(usb("1")), null))
        assertNull(defaults.select(AdapterMode.BLUETOOTH, emptyList(), null))
        assertNull(defaults.select(AdapterMode.USB, listOf(usb("1"), usb("2")), null))
        assertNull(defaults.select(AdapterMode.DEMO, listOf(bluetooth), bluetooth.id))
        val bluetoothOnly = defaults.remember(AdapterMode.BLUETOOTH, bluetooth)
        assertEquals("1", bluetoothOnly.select(AdapterMode.USB, listOf(usb("1")), null))
    }

    @Test fun doesNotSubstituteAnAvailableAdapterWhenRememberedOneIsMissing() {
        val memory = AdapterSelectionMemory().remember(AdapterMode.BLUETOOTH, bluetooth)
            .remember(AdapterMode.USB, usb("old"))
        assertNull(memory.select(AdapterMode.BLUETOOTH, listOf(AdapterOption("11:22:33:44:55:66", "Other")), null))
        assertNull(memory.select(AdapterMode.USB, listOf(usb("other", 0x1234)), null))
        assertNull(memory.select(AdapterMode.USB, listOf(usb("other", product = 0x6010)), "stale"))
        assertEquals(bluetooth.id, memory.select(AdapterMode.BLUETOOTH, listOf(bluetooth), "stale"))
    }

    @Test fun usbAmbiguityRequiresAnExplicitChoiceAndDoesNotUseOldAttachmentId() {
        val memory = AdapterSelectionMemory().remember(AdapterMode.USB, usb("old"))
        assertNull(memory.select(AdapterMode.USB, listOf(usb("1"), usb("2")), null))
        assertEquals("2", memory.select(AdapterMode.USB, listOf(usb("1"), usb("2")), "2"))
        assertEquals("fresh", memory.select(AdapterMode.USB, listOf(usb("fresh"), usb("other", 0x1234)), null))
    }

    @Test fun invalidOrFuturePreferencesReturnDefaultsWithoutPartialIdentityRestoration() {
        val memory = AdapterSelectionMemory().remember(AdapterMode.BLUETOOTH, bluetooth)
            .remember(AdapterMode.USB, usb("1"))
        val valid = AdapterSelectionMemoryCodec.encode(memory)
        val invalid = listOf(null, "", "{broken", "[]", " ".repeat(AdapterSelectionMemoryCodec.MAX_BYTES + 1),
            valid.replace("adapter-selection/1", "adapter-selection/99"),
            valid.replace("\"mode\":\"USB\"", "\"mode\":\"INTERNET\""),
            valid.replace("AA:BB:CC:DD:EE:FF", "not-an-address"),
            valid.replace("\"usb_vendor\":1027", "\"usb_vendor\":65536"),
            valid.replace("\"usb_product\":24577", "\"usb_product\":null"),
            valid.replace("\"baud\":0", "\"baud\":12345"),
            valid.replace("\"baud\":0", "\"baud\":\"0\""),
            valid.replace("\"baud\":0", "\"baud\":0.5"),
            valid.replace("\"usb_lines\":\"ASSERTED\"", "\"usb_lines\":\"UNKNOWN\""),
            valid.dropLast(1) + ",\"extra\":true}",
            valid.dropLast(1) + ",\"mode\":\"DEMO\"}")
        invalid.forEach { assertEquals(AdapterSelectionMemory(), AdapterSelectionMemoryCodec.decode(it), "Invalid input: $it") }
    }

    @Test fun defaultAutoBaudAndAllSupportedExplicitSettingsRoundTrip() {
        assertEquals(AdapterSelectionMemory(), AdapterSelectionMemoryCodec.decode(AdapterSelectionMemoryCodec.encode(AdapterSelectionMemory())))
        for (baud in UsbConnectionSettings.SUPPORTED_BAUD_RATES + UsbConnectionSettings.AUTO_BAUD)
            for (lines in UsbControlLines.entries) {
                val memory = AdapterSelectionMemory(lastMode = AdapterMode.USB, baud = baud, usbControlLines = lines)
                assertEquals(memory, AdapterSelectionMemoryCodec.decode(AdapterSelectionMemoryCodec.encode(memory)))
            }
        assertFails { AdapterSelectionMemory(usbVendorId = 1) }
        assertFails { AdapterSelectionMemory(usbVendorId = -1, usbProductId = 1) }
        assertFails { AdapterSelectionMemory(baud = 12345) }
    }
}
