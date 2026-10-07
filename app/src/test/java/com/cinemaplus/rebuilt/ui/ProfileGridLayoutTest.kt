package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test

class ProfileGridLayoutTest {
    @Test fun narrowPhonesUseOneColumnAndTypicalPhonesFitTwo() {
        assertEquals(1, ProfileGridLayout.columns(320f))
        assertEquals(2, ProfileGridLayout.columns(360f))
        assertEquals(2, ProfileGridLayout.columns(432f))
    }

    @Test fun thirdColumnRequiresItsFullWidthIncludingPaddingAndGaps() {
        assertEquals(2, ProfileGridLayout.columns(527f))
        assertEquals(3, ProfileGridLayout.columns(528f))
        assertEquals(6, ProfileGridLayout.columns(1920f))
    }

    @Test fun computedRowsFitAcrossPhoneTabletAndTvWidths() {
        for (width in 192..1920) {
            val columns = ProfileGridLayout.columns(width.toFloat())
            val required = columns * ProfileGridLayout.TileWidth + (columns - 1) * ProfileGridLayout.Gap +
                2 * ProfileGridLayout.EdgePadding
            assertTrue("$columns columns overflow ${width}dp", required <= width)
        }
    }
}
