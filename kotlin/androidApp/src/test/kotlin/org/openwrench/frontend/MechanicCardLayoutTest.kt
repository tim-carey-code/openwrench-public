package org.openwrench.frontend

import kotlin.test.*

class MechanicCardLayoutTest {
    @Test fun airColumnsUseInnerWidthAndRespectTextScale() {
        assertEquals(1, mechanicAirColumns(359f, 1f))
        assertEquals(2, mechanicAirColumns(360f, 1f))
        assertEquals(2, mechanicAirColumns(444f, 1f))
        assertEquals(1, mechanicAirColumns(444f, 2f))
        assertEquals(1, mechanicAirColumns(711f, 2f))
        assertEquals(2, mechanicAirColumns(712f, 2f))
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(1, mechanicAirColumns(width, 1f))
    }

    @Test fun cardPartitionsKeepTheOddFinalSlotAndBoundResizeGaps() {
        val three = mechanicRowWidths(500, 3, 8)
        assertEquals(listOf(162, 161, 161), three.widths)
        assertEquals(8, three.gap)
        // Five Air readings keep the sixth slot empty; their last value is not stretched.
        val pair = mechanicRowWidths(444, 2, 8)
        assertEquals(listOf(218, 218), pair.widths)
        assertEquals(listOf(1, 1, 1), mechanicRowWidths(3, 3, 8).widths)
        assertEquals(0, mechanicRowWidths(3, 3, 8).gap)
        assertTrue(mechanicRowWidths(2, 3, 8).widths.isEmpty())
        assertTrue(mechanicRowWidths(0, 1, 8).widths.isEmpty())
    }

    @Test fun allNarrowAndTabletPartitionsFitWithoutNegativeWidths() {
        for (width in 1..1920) for (columns in 1..6) {
            val row = mechanicRowWidths(width, columns, 8)
            if (width < columns) assertTrue(row.widths.isEmpty())
            else {
                assertEquals(columns, row.widths.size)
                assertTrue(row.widths.all { it > 0 })
                assertEquals(width, row.widths.sum() + row.gap * (columns - 1))
                assertTrue(row.widths.max() - row.widths.min() <= 1)
            }
        }
    }
}
