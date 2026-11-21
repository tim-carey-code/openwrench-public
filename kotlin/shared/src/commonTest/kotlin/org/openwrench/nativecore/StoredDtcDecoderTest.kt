package org.openwrench.nativecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoredDtcDecoderTest {
    @Test
    fun decodesFamiliesAndSkipsOnlyUnaddressedZeroPadding() {
        assertEquals(listOf("P0133", "P1131", "C2345", "B3ABC", "U1016"),
            StoredDtcDecoder.decode("43013311316345BABC D016".replace(" ", ""), can = false))
        assertEquals(emptyList(), StoredDtcDecoder.decode("43000000000000", can = false))
        assertEquals(listOf("P0133", "U0123"),
            StoredDtcDecoder.decode("43020133C123", can = true))
    }

    @Test
    fun rejectsWrongModeOddLengthCountMismatchAndEmptyCountedCode() {
        for (payload in listOf("440133", "43013", "43020133", "43010000", "4301GG33")) {
            assertFailsWith<IllegalArgumentException> { StoredDtcDecoder.decode(payload, can = true) }
        }
        assertFailsWith<IllegalArgumentException> { StoredDtcDecoder.decode("43013300", can = false) }
        assertFailsWith<IllegalArgumentException> { StoredDtcDecoder.decode("43", can = false) }
    }
}
