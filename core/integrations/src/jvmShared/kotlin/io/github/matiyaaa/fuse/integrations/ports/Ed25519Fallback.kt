package io.github.matiyaaa.fuse.integrations.ports

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 verification on its own, for a platform without it (Android before 13), following RFC
 * 8032's reference (section 6, `verify`): the curve's points in extended coordinates, SHA-512 from
 * the platform (every Android has it). It is slow beside a native one, which doesn't matter for
 * the one signature (the Ports index's) it checks now and then.
 */
internal object Ed25519Fallback {
    // Not BigInteger.TWO: older Androids, the ones this is for, haven't got it.
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val Q: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * inverse(BigInteger.valueOf(121666)) % P
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    /** A point (X, Y, Z, T) with x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val NEUTRAL = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4) * inverse(BigInteger.valueOf(5)) % P
        val x = recoverX(y, 0)!!
        Point(x, y, BigInteger.ONE, x * y % P)
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val h = littleEndian(MessageDigest.getInstance("SHA-512").run { update(rBytes); update(publicKey); update(message); digest() }) % Q
        return equal(multiply(s, BASE), add(r, multiply(h, a)))
    }

    private fun inverse(v: BigInteger): BigInteger = v.modPow(P - TWO, P)

    private fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun add(p: Point, q: Point): Point {
        val a = (p.y - p.x) * (q.y - q.x) % P
        val b = (p.y + p.x) * (q.y + q.x) % P
        val c = TWO * p.t * q.t * D % P
        val d = TWO * p.z * q.z % P
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun multiply(scalar: BigInteger, point: Point): Point {
        var s = scalar
        var p = point
        var out = NEUTRAL
        while (s.signum() > 0) {
            if (s.testBit(0)) out = add(out, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return out
    }

    private fun equal(p: Point, q: Point): Boolean =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inverse(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
        return x
    }

    private fun decompress(bytes: ByteArray): Point? {
        val raw = littleEndian(bytes)
        val sign = if (raw.testBit(255)) 1 else 0
        val y = raw.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x * y % P)
    }
}
