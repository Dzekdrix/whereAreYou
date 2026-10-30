package io.github.whereareyou.crypto

import java.math.BigInteger

/**
 * Minimal secp256k1 implementation with BIP-340 Schnorr signatures,
 * which is what Nostr relays require to accept an event.
 *
 * Pure Kotlin on top of BigInteger: no native code, easy to audit and to build
 * from source (F-Droid friendly). Not constant-time; good enough for signing
 * a few events per minute with throw-away per-circle keys.
 */
object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    )
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)

    private class Point(val x: BigInteger, val y: BigInteger)

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        val lambda = if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null // P + (-P) = infinity
            THREE.multiply(a.x).multiply(a.x).multiply(TWO.multiply(a.y).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        }
        val x = lambda.multiply(lambda).subtract(a.x).subtract(b.x).mod(P)
        val y = lambda.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun mul(k: BigInteger, p: Point?): Point? {
        var r: Point? = null
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    fun bytes32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun taggedHash(tag: String, vararg data: ByteArray): ByteArray {
        val t = Crypto.sha256(tag.toByteArray())
        return Crypto.sha256(t, t, *data)
    }

    private fun checkSecret(sk: ByteArray): BigInteger {
        val d = int(sk)
        require(sk.size == 32 && d.signum() > 0 && d < N) { "invalid secret key" }
        return d
    }

    /** 32-byte x-only public key (Nostr "pubkey"). */
    fun xOnlyPublicKey(sk: ByteArray): ByteArray = bytes32(mul(checkSecret(sk), G)!!.x)

    fun sign(sk: ByteArray, msg: ByteArray, aux: ByteArray = Crypto.random(32)): ByteArray {
        val d0 = checkSecret(sk)
        val pPoint = mul(d0, G)!!
        val d = if (pPoint.y.testBit(0)) N.subtract(d0) else d0
        val px = bytes32(pPoint.x)
        val auxHash = taggedHash("BIP0340/aux", aux)
        val t = bytes32(d).mapIndexed { i, b -> (b.toInt() xor auxHash[i].toInt()).toByte() }.toByteArray()
        val k0 = int(taggedHash("BIP0340/nonce", t, px, msg)).mod(N)
        check(k0.signum() != 0)
        val rPoint = mul(k0, G)!!
        val k = if (rPoint.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(rPoint.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, msg)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    fun verify(pk: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pk.size != 32 || sig.size != 64) return false
        val px = int(pk)
        if (px >= P) return false
        val c = px.modPow(THREE, P).add(SEVEN).mod(P)
        val y0 = c.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        if (y0.modPow(TWO, P) != c) return false
        val pPoint = Point(px, if (y0.testBit(0)) P.subtract(y0) else y0)
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32), pk, msg)).mod(N)
        val rPoint = add(mul(s, G), mul(N.subtract(e), pPoint)) ?: return false
        return !rPoint.y.testBit(0) && rPoint.x == r
    }
}
