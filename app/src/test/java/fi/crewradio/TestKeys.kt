package fi.crewradio

/**
 * Keys the tests share. Deriving one costs [ChannelCrypto.ITERATIONS] rounds of HMAC (that is
 * the point of it), so each is derived once per JVM rather than once per test.
 */
object TestKeys {
    const val CHANNEL_KEY = "north-star-2026"
    val crypto: ChannelCrypto by lazy { ChannelCrypto.forChannelKey(CHANNEL_KEY) }
    val other: ChannelCrypto by lazy { ChannelCrypto.forChannelKey("north-star-2027") }
}
