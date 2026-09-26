package fi.crewradio.rns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-written curves against RFC 7748 and RFC 8032's own test vectors. */
class Curve25519Test {
    @Test
    fun x25519MatchesRfc7748() {
        assertArrayEquals(
            h("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            Curve25519.x25519(h("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), h("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
        )
        val alice = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = Curve25519.x25519Public(alice)
        val bobPub = Curve25519.x25519Public(bob)
        assertArrayEquals(h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), alicePub)
        assertArrayEquals(h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), bobPub)
        val shared = h("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertArrayEquals(shared, Curve25519.x25519(alice, bobPub))
        assertArrayEquals(shared, Curve25519.x25519(bob, alicePub))
    }

    @Test
    fun ed25519MatchesRfc8032() {
        val seed1 = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub1 = h("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig1 = h("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertArrayEquals(pub1, Curve25519.ed25519Public(seed1))
        assertArrayEquals(sig1, Curve25519.ed25519Sign(seed1, ByteArray(0)))
        assertTrue(Curve25519.ed25519Verify(pub1, ByteArray(0), sig1))

        val seed2 = h("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val pub2 = h("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val msg2 = h("72")
        val sig2 = h("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        assertArrayEquals(pub2, Curve25519.ed25519Public(seed2))
        assertArrayEquals(sig2, Curve25519.ed25519Sign(seed2, msg2))
        assertTrue(Curve25519.ed25519Verify(pub2, msg2, sig2))
    }

    @Test
    fun ed25519RefusesWhatIsNotASignature() {
        val seed = h("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val pub = Curve25519.ed25519Public(seed)
        val msg = "crew".toByteArray()
        val sig = Curve25519.ed25519Sign(seed, msg)
        assertTrue(Curve25519.ed25519Verify(pub, msg, sig))
        assertFalse(Curve25519.ed25519Verify(pub, "crow".toByteArray(), sig))
        for (i in listOf(0, 31, 32, 63)) {
            val bad = sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", Curve25519.ed25519Verify(pub, msg, bad))
        }
        assertFalse(Curve25519.ed25519Verify(Curve25519.ed25519Public(ByteArray(32) { 7 }), msg, sig))
        assertFalse(Curve25519.ed25519Verify(pub.copyOf(31), msg, sig))
        assertFalse(Curve25519.ed25519Verify(pub, msg, sig.copyOf(63)))
        // S + L: the same point equation, a different (non-canonical) encoding. Refused.
        val s = java.math.BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
            .add(java.math.BigInteger.ONE.shiftLeft(252).add(java.math.BigInteger("27742317777372353535851937790883648493")))
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        assertFalse(Curve25519.ed25519Verify(pub, msg, sig.copyOf(32) + sBytes))
        // A point that is not on the curve.
        assertFalse(Curve25519.ed25519Verify(ByteArray(32) { 0xff.toByte() }.also { it[31] = 0x7f }, msg, sig))
    }

    private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
