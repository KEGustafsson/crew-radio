package fi.crewradio.audio

/**
 * The SCO state broadcast AudioRoute watches on API 29 and 30, where OnCommunicationDeviceChangedListener
 * does not exist yet. The platform's names for these are deprecated from API 31; the values are
 * written out here instead (they are the AOSP ones, and a broadcast's action and extra are part of
 * its contract, so they do not move) and ScoBroadcastTest pins them.
 */
object ScoBroadcast {
    /** `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED`. */
    const val ACTION = "android.media.ACTION_SCO_AUDIO_STATE_UPDATED"
    /** `AudioManager.EXTRA_SCO_AUDIO_STATE`. */
    const val EXTRA_STATE = "android.media.extra.SCO_AUDIO_STATE"
    /** `AudioManager.SCO_AUDIO_STATE_DISCONNECTED`. */
    const val STATE_DISCONNECTED = 0

    /** True when the broadcast's [state] extra says the SCO link went down; a missing extra (-1) is not that. */
    fun dropped(state: Int): Boolean = state == STATE_DISCONNECTED
}
