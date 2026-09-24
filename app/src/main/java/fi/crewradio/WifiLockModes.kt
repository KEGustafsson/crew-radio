package fi.crewradio

/**
 * The Wi-Fi lock modes a session holds (see PttService.acquireLocks): low latency, which only acts
 * while the screen is on and the app in front, and below API 34 high performance too, which keeps
 * the radio out of power save with the screen off. API 34 deprecates high performance and turns it
 * into a low-latency lock, so there it adds nothing and is left out. Its value is written out here
 * rather than read from the deprecated constant, and WifiLockModesTest pins both.
 */
object WifiLockModes {
    /** `WifiManager.WIFI_MODE_FULL_HIGH_PERF`. */
    const val FULL_HIGH_PERF = 3
    /** `WifiManager.WIFI_MODE_FULL_LOW_LATENCY`. */
    const val FULL_LOW_LATENCY = 4
    /** Android 14. */
    private const val API_34 = 34

    fun forSdk(sdk: Int): List<Int> =
        if (sdk < API_34) listOf(FULL_LOW_LATENCY, FULL_HIGH_PERF) else listOf(FULL_LOW_LATENCY)
}
