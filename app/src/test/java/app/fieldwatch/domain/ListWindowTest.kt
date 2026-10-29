package app.fieldwatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListWindowTest {
    @Test
    fun aRowInsideTheWindowCounts() {
        assertTrue(rowFillsWindow(offset = 40, size = 80, windowStart = 0, windowEnd = 400))
    }

    @Test
    fun aRowThatFillsTheWindowCounts() {
        assertTrue(rowFillsWindow(offset = 0, size = 400, windowStart = 0, windowEnd = 400))
    }

    @Test
    fun aSliverPastTheBottomDoesNotCount() {
        assertFalse(rowFillsWindow(offset = 360, size = 80, windowStart = 0, windowEnd = 400))
    }

    @Test
    fun aRowCutOffAtTheTopDoesNotCount() {
        assertFalse(rowFillsWindow(offset = -8, size = 80, windowStart = 0, windowEnd = 400))
    }

    @Test
    fun contentPaddingStillCountsARowInThatBand() {
        assertTrue(rowFillsWindow(offset = 0, size = 80, windowStart = -16, windowEnd = 416))
    }

    @Test
    fun anEmptyRowOrWindowDoesNotCount() {
        assertFalse(rowFillsWindow(offset = 0, size = 0, windowStart = 0, windowEnd = 400))
        assertFalse(rowFillsWindow(offset = 0, size = 40, windowStart = 20, windowEnd = 20))
    }
}
