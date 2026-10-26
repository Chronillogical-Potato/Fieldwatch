package app.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFixTest {
    @Test
    fun aFixSlightlyAheadOfTheClockStillCounts() {
        val fixAt = 10_000L
        assertTrue(phoneFixIsCurrent(fixAt, fixAt))
        assertTrue(phoneFixIsCurrent(fixAt, fixAt - 1_000L))
        assertTrue(phoneFixIsCurrent(fixAt, fixAt + 30_000L))
        assertFalse(phoneFixIsCurrent(fixAt, fixAt - 5_001L))
        assertFalse(phoneFixIsCurrent(fixAt, fixAt + 30_001L))
    }

    @Test
    fun aFreshHuntKeepsTheAccuracyTheFixReported() {
        val now = 20_000L
        val fix = 36.2 to -115.1
        val tight = huntStartFix(null, fix, now, 6f, now)
        assertEquals(fix.first, tight!!.first, 0.0)
        assertEquals(fix.second, tight.second, 0.0)
        assertEquals(6f, tight.third, 0f)
        val coarse = huntStartFix(null, fix, now, 18f, now)
        assertEquals(18f, coarse!!.third, 0f)
        assertNull(huntStartFix(null, fix, now, Float.NaN, now))
        assertNull(huntStartFix(null, fix, now - 60_000L, 6f, now))
        val seed = 36.3 to -115.2
        val resumed = huntStartFix(seed, fix, now, 18f, now)
        assertEquals(seed.first, resumed!!.first, 0.0)
        assertEquals(8f, resumed.third, 0f)
    }
}
