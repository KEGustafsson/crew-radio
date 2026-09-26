package fi.crewradio.rns

import java.math.BigInteger
import java.security.MessageDigest

/**
 * X25519 (RFC 7748) and Ed25519 (RFC 8032) by hand, because the platform has neither below
 * API 33 and minSdk is 29, and a crypto dependency is not worth one Reticulum transport.
 *
 * The arithmetic is the public-domain TweetNaCl design: a field element is sixteen 16-bit limbs
 * in a LongArray, multiplication folds the high half back with 38 (2^256 = 38 mod p), and every
 * secret-dependent choice is a masked swap ([sel]) rather than a branch, so the ladder and the
 * base-point multiplication take the same path for every key. The curve constants are not
 * typed in: they are computed once from their definitions (d = -121665/121666, sqrt(-1), the
 * base point from y = 4/5) with BigInteger, which only ever sees public values. Tested against
 * the RFC vectors and against Node's OpenSSL through the cross-language vector.
 */
internal object Curve25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val ORDER: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

    private fun gf(): LongArray = LongArray(16)
    private fun gf(v: BigInteger): LongArray {
        val bytes = le32(v)
        return LongArray(16) { i -> (bytes[2 * i].toLong() and 0xff) or ((bytes[2 * i + 1].toLong() and 0xff) shl 8) }
    }

    private val GF0 = gf()
    private val GF1 = LongArray(16).also { it[0] = 1 }
    private val A24 = LongArray(16).also { it[0] = 0xdb41; it[1] = 1 }      // 121665
    private val D: LongArray
    private val D2: LongArray
    private val SQRTM1: LongArray
    private val BX: LongArray
    private val BY: LongArray
    private val L: LongArray = LongArray(32).also { val b = le32(ORDER); for (i in 0 until 32) it[i] = b[i].toLong() and 0xff }

    init {
        val d = P.subtract(BigInteger.valueOf(121665)).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        D = gf(d)
        D2 = gf(d.shiftLeft(1).mod(P))
        val i = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        SQRTM1 = gf(i)
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        // x^2 = (y^2 - 1) / (d y^2 + 1); the base point's x is the even root.
        val y2 = y.multiply(y).mod(P)
        val xx = y2.subtract(BigInteger.ONE).multiply(d.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(i).mod(P)
        if (x.testBit(0)) x = P.subtract(x)
        BX = gf(x)
        BY = gf(y)
    }

    private fun le32(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        for (k in 0 until minOf(32, be.size)) out[k] = be[be.size - 1 - k]
        return out
    }

    // ---- field arithmetic mod 2^255 - 19 ----

    private fun car(o: LongArray) {
        var c = 1L
        for (i in 0 until 16) {
            val v = o[i] + c + 65535
            c = v shr 16
            o[i] = v - (c shl 16)
        }
        o[0] += c - 1 + 37 * (c - 1)
    }

    /** Swaps p and q when b is 1, leaves them when 0, without a branch. */
    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(o: ByteArray, n: LongArray) {
        val t = n.copyOf()
        val m = gf()
        car(t); car(t); car(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun neq(a: LongArray, b: LongArray): Boolean {
        val c = ByteArray(32)
        val d = ByteArray(32)
        pack(c, a)
        pack(d, b)
        return !MessageDigest.isEqual(c, d)
    }

    private fun par(a: LongArray): Int = ByteArray(32).also { pack(it, a) }[0].toInt() and 1

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o)
        car(o)
    }

    private fun sq(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sq(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            sq(c, c)
            if (a != 1) mul(c, c, i)
        }
        c.copyInto(o)
    }

    // ---- X25519 ----

    /** X25519(scalar, u): the shared secret, or the public key when [u] is the base point 9. */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val z = scalar.copyOf()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = gf()
        unpack(x, u)
        val a = gf(); val b = x.copyOf(); val c = gf(); val d = gf(); val e = gf(); val f = gf()
        a[0] = 1
        d[0] = 1
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            sq(d, e); sq(f, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); sq(b, a); sub(c, d, f)
            mul(a, c, A24); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); sq(b, e)
            sel(a, b, r); sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        return ByteArray(32).also { pack(it, a) }
    }

    fun x25519Public(scalar: ByteArray): ByteArray = x25519(scalar, ByteArray(32).also { it[0] = 9 })

    // ---- Ed25519 ----

    private class Point(val x: LongArray = LongArray(16), val y: LongArray = LongArray(16), val z: LongArray = LongArray(16), val t: LongArray = LongArray(16)) {
        val parts get() = arrayOf(x, y, z, t)
    }

    private fun padd(p: Point, q: Point) {
        val a = gf(); val b = gf(); val c = gf(); val d = gf(); val e = gf(); val f = gf(); val g = gf(); val h = gf(); val t = gf()
        sub(a, p.y, p.x); sub(t, q.y, q.x); mul(a, a, t)
        add(b, p.x, p.y); add(t, q.x, q.y); mul(b, b, t)
        mul(c, p.t, q.t); mul(c, c, D2)
        mul(d, p.z, q.z); add(d, d, d)
        sub(e, b, a); sub(f, d, c); add(g, d, c); add(h, b, a)
        mul(p.x, e, f); mul(p.y, h, g); mul(p.z, g, f); mul(p.t, e, h)
    }

    private fun cswap(p: Point, q: Point, b: Int) {
        val pp = p.parts
        val qq = q.parts
        for (i in 0 until 4) sel(pp[i], qq[i], b)
    }

    private fun packPoint(r: ByteArray, p: Point) {
        val tx = gf(); val ty = gf(); val zi = gf()
        inv(zi, p.z)
        mul(tx, p.x, zi)
        mul(ty, p.y, zi)
        pack(r, ty)
        r[31] = (r[31].toInt() xor (par(tx) shl 7)).toByte()
    }

    private fun scalarMult(p: Point, q: Point, s: ByteArray) {
        GF0.copyInto(p.x); GF1.copyInto(p.y); GF1.copyInto(p.z); GF0.copyInto(p.t)
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() shr (i and 7)) and 1
            cswap(p, q, b)
            padd(q, p)
            padd(p, p)
            cswap(p, q, b)
        }
    }

    private fun scalarBase(p: Point, s: ByteArray) {
        val q = Point(BX.copyOf(), BY.copyOf(), GF1.copyOf(), gf())
        mul(q.t, BX, BY)
        scalarMult(p, q, s)
    }

    /** Reduces the 64 limbs of [x] modulo the group order into 32 bytes of [r] at [off]. */
    private fun modL(r: ByteArray, off: Int, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            val k = i - 12
            while (j < k) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[off + i] = (x[i] and 255).toByte()
        }
    }

    private fun reduce(h: ByteArray): ByteArray {
        val x = LongArray(64) { h[it].toLong() and 0xff }
        return ByteArray(32).also { modL(it, 0, x) }
    }

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

    private fun expand(seed: ByteArray): ByteArray = sha512(seed).also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    fun ed25519Public(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val p = Point()
        scalarBase(p, expand(seed))
        return ByteArray(32).also { packPoint(it, p) }
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val d = expand(seed)
        val pub = ed25519Public(seed)
        val r = reduce(sha512(d.copyOfRange(32, 64), message))
        val p = Point()
        scalarBase(p, r)
        val sig = ByteArray(64)
        packPoint(sig, p)
        val h = reduce(sha512(sig.copyOf(32), pub, message))
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xff
        for (i in 0 until 32) for (j in 0 until 32) x[i + j] += (h[i].toLong() and 0xff) * (d[j].toLong() and 0xff)
        modL(sig, 32, x)
        return sig
    }

    private fun unpackNeg(r: Point, p: ByteArray): Boolean {
        val t = gf(); val chk = gf(); val num = gf(); val den = gf(); val den2 = gf(); val den4 = gf(); val den6 = gf()
        GF1.copyInto(r.z)
        unpack(r.y, p)
        sq(num, r.y)
        mul(den, num, D)
        sub(num, num, r.z)
        add(den, r.z, den)
        sq(den2, den); sq(den4, den2); mul(den6, den4, den2)
        mul(t, den6, num); mul(t, t, den)
        pow2523(t, t)
        mul(t, t, num); mul(t, t, den); mul(t, t, den); mul(r.x, t, den)
        sq(chk, r.x); mul(chk, chk, den)
        if (neq(chk, num)) mul(r.x, r.x, SQRTM1)
        sq(chk, r.x); mul(chk, chk, den)
        if (neq(chk, num)) return false
        if (par(r.x) == (p[31].toInt() and 0xff) shr 7) sub(r.x, GF0, r.x)
        mul(r.t, r.x, r.y)
        return true
    }

    /** True only for a canonical signature by [pub] over [message]; S at or above the group order is refused, as OpenSSL does. */
    fun ed25519Verify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
        if (s >= ORDER) return false
        val q = Point()
        if (!unpackNeg(q, pub)) return false
        val h = reduce(sha512(sig.copyOf(32), pub, message))
        val p = Point()
        scalarMult(p, q, h)
        val b = Point()
        scalarBase(b, sig.copyOfRange(32, 64))
        padd(p, b)
        val t = ByteArray(32)
        packPoint(t, p)
        return MessageDigest.isEqual(t, sig.copyOf(32))
    }
}
