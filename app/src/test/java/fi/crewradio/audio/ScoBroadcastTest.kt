package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoBroadcastTest {

    @Test
    fun valuesAreThePlatformOnes() {
        // AudioManager's own, from the SDK: the receiver matches the broadcast by these strings.
        assertEquals("android.media.ACTION_SCO_AUDIO_STATE_UPDATED", ScoBroadcast.ACTION)
        assertEquals("android.media.extra.SCO_AUDIO_STATE", ScoBroadcast.EXTRA_STATE)
        assertEquals(0, ScoBroadcast.STATE_DISCONNECTED)
    }

    @Test
    fun onlyADisconnectIsADrop() {
        assertTrue(ScoBroadcast.dropped(0))
        assertFalse(ScoBroadcast.dropped(1))       // connected
        assertFalse(ScoBroadcast.dropped(2))       // connecting
        assertFalse(ScoBroadcast.dropped(-1))      // error, or the extra missing
    }
}
