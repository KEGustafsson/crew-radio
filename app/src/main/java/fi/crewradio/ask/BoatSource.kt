package fi.crewradio.ask

/**
 * Where a question's readings come from and where a "Whole crew" answer goes: the boat's Signal K
 * server over HTTP on its own network ([SignalKClient]), or the Crew Radio plugin on that server
 * over the channel's Reticulum links, for a phone ashore ([ReticulumBoat]). Both block, so both
 * are called on the `ptt-ask` thread.
 */
interface BoatSource {
    /** The given top-level branches of `vessels/self` as one tree. */
    fun read(branches: List<String>): SignalKClient.Result<SignalKTree>

    /** Has the boat say [text] to the whole crew on the channel. */
    fun say(text: String): SignalKClient.Result<Unit>
}
