package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive pipeline against the claims made for it: what is charged to whom and when, what
 * never reaches the seen-cache, how far a packet may be relayed, and which recordings are refused.
 * No crypto here: [Ingress] is handed the outcome of the AEAD check.
 */
class IngressTest {

    private val now = 1_788_739_200L
    private val body = byteArrayOf(1, 2, 3)
    private val opens = { body }
    private val fails = { null }

    private fun header(sender: Int = 1, seq: Int = 0, codec: Packet.Codec = Packet.Codec.OPUS, ttl: Int = 4, hops: Int = ttl, time: Long = now) =
        Packet.Header(sender, seq, codec, ttl, hops, time)

    private fun Ingress.Result.accepted() = this is Ingress.Result.Accept
    private fun Ingress.Result.rejectedFor(why: Ingress.Why) = this is Ingress.Result.Rejected && this.why == why

    @Test
    fun aNewPacketIsAcceptedWithItsPayloadAndOnlyOnce() {
        val i = Ingress()
        val r = i.admit(header(seq = 5), 0, now, 4, opens)
        assertTrue(r is Ingress.Result.Accept)
        assertTrue(body.contentEquals((r as Ingress.Result.Accept).plain))
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 5), 1, now, 4, opens))     // the WLAN broadcast copy
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 5), 2, now, 4, opens))     // and the one relayed back over Bluetooth
        assertTrue(i.admit(header(seq = 6), 3, now, 4, opens).accepted())
    }

    @Test
    fun hellosAndAudioNumberThemselvesIndependently() {
        val i = Ingress()
        assertTrue(i.admit(header(seq = 0), 0, now, 4, opens).accepted())
        assertTrue(i.admit(header(seq = 0, codec = Packet.Codec.HELLO), 0, now, 4, opens).accepted())
        assertTrue(i.admit(header(seq = 0, codec = Packet.Codec.PCM), 0, now, 4, opens) is Ingress.Result.Duplicate)   // PCM and Opus share the audio sequence
    }

    /** The hello sequence is measured, not gated: how many went missing before this one, per sender, and late ones say so. */
    @Test
    fun helloGapsCountTheMissingOnes() {
        val i = Ingress()
        assertEquals(0, i.helloGap(1, 10))
        assertEquals(0, i.helloGap(1, 11))
        assertEquals(2, i.helloGap(1, 14))                 // 12 and 13 never came
        assertEquals(-1, i.helloGap(1, 13))                // and 13 turning up now is late
        assertEquals(0, i.helloGap(2, 100))                // another sender, its own numbers
        assertEquals(0, i.helloGap(1, 15))
    }

    /** Every frame arrives twice on WLAN and again over each other link: the copies must not spend the sender's 75/s budget. */
    @Test
    fun copiesAreNotChargedToTheSender() {
        val i = Ingress(RateLimiter(perSecond = 75.0, burst = 150.0))
        var t = 0L
        var accepted = 0
        for (seq in 0 until 60 * 50) {                      // a minute of talk at 50 frames a second
            repeat(4) { if (i.admit(header(seq = seq), t, now, 4, opens).accepted()) accepted++ }   // four copies of each
            t += 20
        }
        assertEquals(3000, accepted)                        // every frame once, none refused for budget
    }

    /** A sender over its budget writes nothing into the cache, so it cannot churn it, and its packet is not remembered as seen. */
    @Test
    fun aSenderOverBudgetIsRefusedWithoutTouchingTheCache() {
        val i = Ingress(RateLimiter(perSecond = 10.0, burst = 10.0))
        repeat(10) { assertTrue(i.admit(header(seq = it), 0, now, 4, opens).accepted()) }
        assertTrue(i.admit(header(seq = 10), 0, now, 4, opens).rejectedFor(Ingress.Why.SENDER_BUDGET))
        assertTrue(i.admit(header(seq = 10), 1000, now, 4, opens).accepted())                   // not a duplicate: it was never seen
    }

    @Test
    fun theGlobalBudgetIsChargedBeforeThePacketIsOpened() {
        val i = Ingress(RateLimiter(globalPerSecond = 1.0, globalBurst = 2.0))
        var opened = 0
        val counting = { opened++; body }
        assertTrue(i.admit(header(seq = 0), 0, now, 4, counting).accepted())
        assertTrue(i.admit(header(seq = 1), 0, now, 4, counting).accepted())
        assertTrue(i.admit(header(seq = 2), 0, now, 4, counting).rejectedFor(Ingress.Why.GLOBAL_BUDGET))
        assertEquals(2, opened)
    }

    /** A packet without the key is refused before the cache: a forged (sender, seq) cannot shadow the real packet, nor cost the sender. */
    @Test
    fun aForgeryNeverEntersTheSeenCacheNorChargesTheSender() {
        val i = Ingress(RateLimiter(perSecond = 10.0, burst = 10.0))
        repeat(50) { assertTrue(i.admit(header(seq = 7), 0, now, 4, fails).rejectedFor(Ingress.Why.UNREADABLE)) }
        assertTrue(i.admit(header(seq = 7), 0, now, 4, opens).accepted())                       // the real one still gets through
        repeat(9) { assertTrue(i.admit(header(seq = 8 + it), 0, now, 4, opens).accepted()) }    // with its whole budget
    }

    @Test
    fun aFloodOfUnreadablePacketsIsNoticed() {
        val i = Ingress(RateLimiter(junkPerSecond = 10.0, junkBurst = 10.0))
        repeat(10) { assertTrue(i.admit(header(seq = it), 0, now, 4, fails).rejectedFor(Ingress.Why.UNREADABLE)) }
        assertTrue(i.admit(header(seq = 11), 0, now, 4, fails).rejectedFor(Ingress.Why.JUNK_FLOOD))
        assertTrue(i.admit(header(seq = 12), 0, now, 4, opens).accepted())                      // readable traffic is unaffected
    }

    @Test
    fun aPacketOutsideTheReplayWindowIsStaleAndTouchesNoCache() {
        val i = Ingress()
        val w = Packet.REPLAY_WINDOW_S
        assertEquals(Ingress.Result.Stale, i.admit(header(seq = 1, time = now - w - 1), 0, now, 4, opens))   // a recording
        assertEquals(Ingress.Result.Stale, i.admit(header(seq = 1, time = now + w + 1), 0, now, 4, opens))   // a clock ahead of ours
        assertTrue(i.admit(header(seq = 1, time = now - w), 0, now, 4, opens).accepted())                  // the edges are in
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 1, time = now + w), 0, now, 4, opens))  // (same seq: now a duplicate)
        assertTrue(i.admit(header(seq = 2, time = now + w), 0, now, 4, opens).accepted())
    }

    @Test
    fun theReplayWindowWrapsWithTheWireCounter() {
        val i = Ingress()
        val top = 0xFFFF_FFFFL
        assertTrue(i.admit(header(seq = 1, time = top), 0, 3, 4, opens).accepted())             // sent a few seconds before the wrap
        assertTrue(i.admit(header(seq = 2, time = 3), 0, top, 4, opens).accepted())             // and a receiver still before it
        assertEquals(Ingress.Result.Stale, i.admit(header(seq = 3, time = top - 100), 0, 3, 4, opens))
    }

    /** A stale packet must not be checked for the tag's sake only: it is opened (so junk cannot probe the clock), but never cached. */
    @Test
    fun staleIsDecidedAfterTheTagAndBeforeTheCache() {
        val i = Ingress()
        assertTrue(i.admit(header(seq = 1, time = 0), 0, now, 4, fails).rejectedFor(Ingress.Why.UNREADABLE))   // unreadable wins over stale
        assertEquals(Ingress.Result.Stale, i.admit(header(seq = 1, time = 0), 0, now, 4, opens))
        assertTrue(i.admit(header(seq = 1), 0, now, 4, opens).accepted())                       // the stale copy left no trace
    }

    /**
     * The replay H1 described: a recording of a sender's packets, played back after the sender
     * has gone quiet long enough to drop off the roster (or after this phone left and rejoined
     * the channel). Both are inside the process lifetime of the caches and the sequence marks,
     * so both copies are refused - the seen-cache first, and the sequence mark for anything the
     * cache has since forgotten.
     */
    @Test
    fun aReplayAfterTheSenderExpiredIsRefused() {
        val i = Ingress(audioCache = 8)                           // a tiny cache, to show the sequence mark holding on its own
        for (seq in 0 until 100) {
            assertTrue(i.admit(header(seq = seq), seq * 20L, now, 4, opens).accepted())
            assertTrue(i.admitAudio(1, seq) {})
        }
        // ... the sender goes quiet for longer than the roster keeps it; nothing is forgotten ...
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 99), 60_000, now, 4, opens))   // still in the cache
        val r = i.admit(header(seq = 3), 60_000, now, 4, opens)   // long fallen out of the 8-entry cache
        assertTrue(r.accepted())                                   // the cache cannot say, so it passes to the audio admission
        assertFalse(i.admitAudio(1, 3) {})                         // which knows it is late
        assertTrue(i.admitAudio(1, 100) {})                        // the sender's next real frame is fine
    }

    @Test
    fun sequenceGapsAreReportedAtomicallyWithTheAdmission() {
        val i = Ingress()
        val gaps = mutableListOf<Int>()
        assertTrue(i.admitAudio(1, 10) { gaps += it })
        assertTrue(i.admitAudio(1, 11) { gaps += it })
        assertTrue(i.admitAudio(1, 14) { gaps += it })            // 12 and 13 never came
        assertFalse(i.admitAudio(1, 12) { gaps += it })           // and here is 12, late: its slot was concealed already
        assertTrue(i.admitAudio(2, 5) { gaps += it })             // another sender starts fresh
        assertEquals(listOf(2), gaps)
    }

    @Test
    fun relayTtlIsDecrementedFromWhatArrivedAndCappedAtTheSignedBudget() {
        assertEquals(3, Ingress.relayTtl(header(ttl = 4, hops = 4), 4))          // fresh from the sender
        assertEquals(2, Ingress.relayTtl(header(ttl = 3, hops = 4), 4))          // one hop travelled
        assertEquals(0, Ingress.relayTtl(header(ttl = 1, hops = 4), 4))          // the last hop: not forwarded
        assertEquals(0, Ingress.relayTtl(header(ttl = 0, hops = 4), 4))
        assertEquals(3, Ingress.relayTtl(header(ttl = 255, hops = 4), 16))       // a bumped ttl rides no further than the sender allowed
        assertEquals(0, Ingress.relayTtl(header(ttl = 255, hops = 1), 16))
    }

    /**
     * A relay's own limit bounds what it forwards by how far the packet has come, never by
     * rewriting the ttl to a smaller number, so the ttl on the wire stays exact for every
     * roster; and the limit means what it does for the phone's own packets: at most that many
     * hops from the origin (a limit of 4 lets a packet pass three relays).
     */
    @Test
    fun aRelayForwardsOnlyPacketsWithinItsOwnHopLimit() {
        assertEquals(3, Ingress.relayTtl(header(ttl = 4, hops = 4), 3))          // direct from the sender: forwarded, ttl exact
        assertEquals(2, Ingress.relayTtl(header(ttl = 3, hops = 4), 3))          // one relay so far: we are the second, still within 3 hops
        assertEquals(0, Ingress.relayTtl(header(ttl = 2, hops = 4), 3))          // two relays already: a third hop is our limit, not sent on
        assertEquals(3, Ingress.relayTtl(header(ttl = 4, hops = 4), 2))          // limit 2: only the direct packet gets one relay
        assertEquals(0, Ingress.relayTtl(header(ttl = 3, hops = 4), 2))
        assertEquals(0, Ingress.relayTtl(header(ttl = 4, hops = 4), 1))          // a one-hop phone relays nothing
        assertEquals(1, Ingress.relayTtl(header(ttl = 2, hops = 4), 4))          // the default: a third relay lands it at hop 4, the sender's own reach
        assertEquals(0, Ingress.relayTtl(header(ttl = 1, hops = 4), 4))          // and there it stops
        assertEquals(15, Ingress.relayTtl(header(ttl = 16, hops = 16), 16))
        for (ttl in 0..255) for (hops in 0..255) Ingress.relayTtl(header(ttl = ttl, hops = hops), 4)   // never throws for any byte value
    }

    @Test
    fun theAcceptedResultCarriesTheRelayTtl() {
        val i = Ingress()
        val r = i.admit(header(seq = 1, ttl = 3, hops = 4), 0, now, 4, opens) as Ingress.Result.Accept
        assertEquals(2, r.relayTtl)
    }

    /**
     * The same frame arriving on two transports at once must cost its sender one token, not two:
     * the look, the charge and the mark are one step. With them apart both threads passed the look
     * before either marked, and the copy was charged like a first sighting.
     */
    @Test
    fun aCopyRacingItsTwinIsChargedOnce() {
        repeat(200) {
            // A budget of exactly one token: a second charge would leave nothing for the next frame.
            val limiter = RateLimiter(perSecond = 0.0, burst = 1.0)
            val ingress = Ingress(limiter = limiter)
            val results = java.util.Collections.synchronizedList(mutableListOf<Ingress.Result>())
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = (1..4).map {
                Thread {
                    start.await()
                    results.add(ingress.admit(header(), 0L, now, 4, opens))
                }.also { t -> t.start() }
            }
            start.countDown()
            threads.forEach { it.join(2_000) }
            assertEquals("one thread accepts the frame", 1, results.count { it.accepted() })
            assertEquals("the others see a duplicate", 3, results.count { it is Ingress.Result.Duplicate })
            // The one token went to the frame, so a different sender's frame still has the budget.
            assertTrue("a copy must not have spent the budget", limiter.allowSender(2, 0L))
        }
    }

    /**
     * The attack the ttl-aware duplicate exists to stop. The ttl is the one header byte outside
     * the AAD, because relays rewrite it - so anyone in radio range can capture a frame, lower it
     * and re-send. Arriving first, that copy used to take the packet's place in the seen-cache
     * with a ttl that relays nothing, and the genuine copy behind it was merely a duplicate: the
     * far side of the mesh went silent while this phone played the audio and noticed nothing.
     */
    @Test
    fun aCopyWithALoweredTtlDoesNotStopTheGenuineOneBeingRelayed() {
        val i = Ingress()
        val forged = i.admit(header(seq = 1, ttl = 1, hops = 4), 0, now, 4, opens) as Ingress.Result.Accept
        assertEquals(0, forged.relayTtl)                          // as the attacker intended: forwarded nowhere
        val real = i.admit(header(seq = 1, ttl = 4, hops = 4), 1, now, 4, opens)
        assertTrue(real is Ingress.Result.RelayOnly)              // played once, but still relayed
        assertEquals(3, (real as Ingress.Result.RelayOnly).relayTtl)
    }

    /** A copy that would reach no further than the one already forwarded is only a duplicate. */
    @Test
    fun aCopyThatReachesNoFurtherIsJustADuplicate() {
        val i = Ingress()
        assertEquals(3, (i.admit(header(seq = 1, ttl = 4, hops = 4), 0, now, 4, opens) as Ingress.Result.Accept).relayTtl)
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 1, ttl = 4, hops = 4), 1, now, 4, opens))   // the WLAN broadcast copy
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 1, ttl = 1, hops = 4), 2, now, 4, opens))   // and a lowered one
        assertEquals(Ingress.Result.Duplicate, i.admit(header(seq = 1, ttl = 3, hops = 4), 3, now, 4, opens))   // one that came the long way round
    }

    /** Each copy must beat the last, so the extra forwards are bounded by the sender's own budget. */
    @Test
    fun risingTtlCopiesAreBoundedByTheSignedHopBudget() {
        val i = Ingress()
        var relays = 0
        for (ttl in 1..255) {                                     // an attacker walking the ttl up as far as the byte goes
            val r = i.admit(header(seq = 1, ttl = ttl, hops = 4), ttl.toLong(), now, 4, opens)
            if (r is Ingress.Result.Accept && r.relayTtl > 0) relays++
            if (r is Ingress.Result.RelayOnly) relays++
        }
        assertEquals(3, relays)                                   // hops = 4: three forwards, however many copies arrive
    }

    /**
     * Our own frame, relayed back by a peer. It is a duplicate - but the sender id is in the clear
     * in every packet we send, so a flood can claim it; dropping it before the global budget would
     * be a way in that costs the attacker nothing. It is charged, and never opened.
     */
    @Test
    fun ourOwnFrameComingBackIsADuplicateThatStillCostsTheGlobalBudget() {
        var opened = 0
        val counting = { opened++; body }
        val i = Ingress(RateLimiter(globalPerSecond = 0.0, globalBurst = 2.0))
        assertEquals(Ingress.Result.Duplicate, i.admit(header(sender = 7, seq = 1), 0, now, 4, counting, selfId = 7))
        assertEquals(Ingress.Result.Duplicate, i.admit(header(sender = 7, seq = 2), 0, now, 4, counting, selfId = 7))
        assertEquals(0, opened)                                   // never decrypted: it is ours
        assertTrue(i.admit(header(sender = 8, seq = 1), 0, now, 4, counting, selfId = 7).rejectedFor(Ingress.Why.GLOBAL_BUDGET))
    }

    /** Without a self id nothing is dropped for it: the parameter is opt-in, as the plugin needs. */
    @Test
    fun withoutASelfIdEverySenderIsAStranger() {
        val i = Ingress()
        assertTrue(i.admit(header(sender = 7, seq = 1), 0, now, 4, opens).accepted())
    }
}
