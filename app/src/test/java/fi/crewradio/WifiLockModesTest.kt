package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiLockModesTest {

    @Test
    fun valuesAreThePlatformOnes() {
        // WifiManager's own, from the SDK: createWifiLock takes the number.
        assertEquals(3, WifiLockModes.FULL_HIGH_PERF)
        assertEquals(4, WifiLockModes.FULL_LOW_LATENCY)
    }

    @Test
    fun highPerformanceOnlyBeforeAndroid14() {
        assertEquals(listOf(4, 3), WifiLockModes.forSdk(29))
        assertEquals(listOf(4, 3), WifiLockModes.forSdk(33))
        assertEquals(listOf(4), WifiLockModes.forSdk(34))
        assertEquals(listOf(4), WifiLockModes.forSdk(37))
    }
}
