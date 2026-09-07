package fi.crewradio.transport

import fi.crewradio.Packet

/**
 * One Bluetooth link per pair when both phones name each other as peer.
 *
 * Both dial and both accept, so a mutual pair ends up with two links and every frame goes
 * out twice (PCM is 64 kB/s on RFCOMM). Adapter addresses cannot break the tie: since Android 6
 * an app reads its own as `02:00:00:00:00:00`. The node ids can, the same rule as Aware: the
 * lower id is the initiator and keeps the link it dialled, the higher id keeps the link it
 * accepted and does not dial while that link is alive. The peer's id is learnt from the first
 * frame that reached us straight from it — `ttl == hops`; a relayed packet has been decremented.
 *
 * Pure, so the rule is unit-tested; [BluetoothTransport] applies it.
 */
internal object BluetoothTieBreak {

    /** With a dialled and an accepted link to the same phone: true keeps the dialled one, false the accepted one. */
    fun keepsDialled(localId: Int, peerId: Int): Boolean = localId < peerId

    /**
     * Whether to (re)dial the peer now. Not while it is the one to hold the link and its link to
     * us is alive; always while its id is still unknown (a duplicate is reconciled once it is).
     */
    fun shouldDial(localId: Int, peerId: Int?, acceptedAlive: Boolean): Boolean =
        !acceptedAlive || peerId == null || keepsDialled(localId, peerId)

    /** The id of the phone that sent [packet] to us directly, or null for a relayed or malformed frame. */
    fun directSender(packet: ByteArray): Int? =
        Packet.parse(packet)?.let { if (it.ttl == it.hops) it.senderId else null }
}
