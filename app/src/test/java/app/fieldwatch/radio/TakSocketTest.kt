package app.fieldwatch.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakSocketTest {
    @Test
    fun socketOpenedOfflineIsReplacedWhenANetworkAppears() {
        assertFalse(replaceTakSocket(openedWithoutNetwork = true, networkNow = false))
        assertTrue(replaceTakSocket(openedWithoutNetwork = true, networkNow = true))
    }

    @Test
    fun socketOpenedOnANetworkStaysAfterThatNetworkDrops() {
        assertFalse(replaceTakSocket(openedWithoutNetwork = false, networkNow = true))
        assertFalse(replaceTakSocket(openedWithoutNetwork = false, networkNow = false))
    }
}
