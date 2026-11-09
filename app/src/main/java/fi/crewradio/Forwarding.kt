package fi.crewradio

/**
 * Where an audio frame goes, between the local transports (WLAN, Bluetooth, Wi-Fi Aware) and
 * Reticulum: the rule set every client follows, so that one talker on a boat with several
 * bridges puts each frame through the hub once, not once per bridge. Hellos keep the plain relay
 * rules; this is for audio only. Pure, and unit-tested as a table.
 *
 *  1. Own voice: on the local transports as always, and on Reticulum to every confirmed link
 *     except those whose far end is a local sender ([linkGets], split horizon: it hears us on
 *     the boat already). A link whose far end is not known yet gets it.
 *  2. Local to Reticulum: a frame first heard on a local transport from another sender goes to
 *     Reticulum only from the boat's forwarder ([LocalPeers.forwarder]), and only when the talker
 *     is not on Reticulum itself (it sends its own voice there; nobody repeats it); to every
 *     link whose far end is not local, as in 1.
 *  3. Reticulum to local: only the forwarder puts it on the WLAN, which every bridge aboard shares.
 *     Bluetooth and Wi-Fi Aware get it from whoever heard it, as before: a peer reached only that
 *     way would otherwise miss a frame whose WLAN copy reached its relay after the Reticulum one,
 *     a duplicate by then.
 *  4. Reticulum to Reticulum: never (Reticulum carries it to every node itself), except a local
 *     talker's own frame (hops == ttl) that came over Reticulum before its local copy: such a
 *     talker sends there until it knows our link is local, and that copy goes on as rule 2 says
 *     the local copy would have (which is then the duplicate).
 *
 * Relays between local transports are unchanged (the hop budget still applies, [Ingress.relayTtl]),
 * and a change of forwarder needs nothing flushed: the rules apply to the next frame. Views of the
 * forwarder can disagree for a moment; under these rules that makes a duplicate, which the
 * seen-cache drops, never a gap.
 */
object Forwarding {
    /** Where the frame was first heard: our own microphone, a local transport, or Reticulum. */
    enum class Origin { OWN, LOCAL, RETICULUM }

    /**
     * Whether the frame goes to the WLAN ([lan]), to Bluetooth and Wi-Fi Aware ([mesh]), and to
     * Reticulum (its non-local links, [linkGets]).
     */
    data class Route(val lan: Boolean, val mesh: Boolean, val reticulum: Boolean)

    /**
     * The route for an audio frame from [sender] (we are [me]) heard first from [origin]; [forwarder]
     * is whether we are the boat's forwarder, [senderRnsUp] whether the sender's last local hello
     * had [Hello.RNS_UP] (false when it has none). [localDirect]: a [Origin.RETICULUM] frame that
     * came straight from a local sender (hops == ttl). Such a talker sends its voice over Reticulum
     * until it knows our link is local; when that copy comes first it goes on as the local copy
     * would have, or the local copy, a duplicate by then, never reaches the links ashore.
     */
    fun route(origin: Origin, sender: Int, me: Int, forwarder: Boolean, senderRnsUp: Boolean, localDirect: Boolean = false): Route = when (origin) {
        Origin.OWN -> Route(lan = true, mesh = true, reticulum = true)
        Origin.LOCAL -> Route(lan = true, mesh = true, reticulum = forwarder && sender != me && !senderRnsUp)
        Origin.RETICULUM -> Route(lan = forwarder, mesh = true, reticulum = forwarder && localDirect && sender != me && !senderRnsUp)
    }

    /**
     * True when this node bridges the local transports and Reticulum at all, a condition of its
     * hello's [Hello.RNS_UP] (with a confirmed link off the boat): [relay] on, a hop limit
     * ([maxHops]) that lets [Ingress.relayTtl] forward a packet its sender sent itself, and a
     * local transport up ([localUp]). A node that would be elected forwarder and then forward
     * nothing must not be a candidate.
     */
    fun bridges(relay: Boolean, maxHops: Int, localUp: Boolean): Boolean = relay && maxHops >= 2 && localUp

    /** Split horizon: a Reticulum link gets an audio frame unless its far end is a local sender; one not known yet ([farEndLocal] null) gets it. */
    fun linkGets(farEndLocal: Boolean?): Boolean = farEndLocal != true
}
