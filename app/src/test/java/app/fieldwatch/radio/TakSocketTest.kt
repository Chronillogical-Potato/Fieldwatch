package app.fieldwatch.radio

import app.fieldwatch.domain.TakBirth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

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

    @Test
    fun loopbackOpenedWithoutLanIsReplacedWhenWifiConnects() {
        assertFalse(replaceLoopbackSocket(openedOnLan = false, lanNow = false))
        assertTrue(replaceLoopbackSocket(openedOnLan = false, lanNow = true))
    }

    @Test
    fun loopbackOpenedOnLanStaysAfterThatLanDrops() {
        assertFalse(replaceLoopbackSocket(openedOnLan = true, lanNow = true))
        assertFalse(replaceLoopbackSocket(openedOnLan = true, lanNow = false))
    }

    @Test
    fun cellularIsNotALan() {
        assertFalse(takLanUp(wifi = false, ethernet = false))
        assertTrue(takLanUp(wifi = true, ethernet = false))
        assertTrue(takLanUp(wifi = false, ethernet = true))
        assertEquals(TakBirth.NONE, takBirth(wifi = false, ethernet = false, cellular = false))
        assertEquals(TakBirth.CELLULAR, takBirth(wifi = false, ethernet = false, cellular = true))
        assertEquals(TakBirth.WIFI, takBirth(wifi = true, ethernet = true, cellular = true))
        assertEquals(TakBirth.ETHERNET, takBirth(wifi = false, ethernet = true, cellular = true))
    }

    @Test
    fun bindErrorIsTheExceptionName() {
        val wrapped = InvocationTargetException(NoSuchMethodException("setsockoptIfreq"))
        assertEquals("NoSuchMethodException", takBindError(wrapped))
        assertEquals("refused", takBindError(object : Throwable() {}))
    }
}
