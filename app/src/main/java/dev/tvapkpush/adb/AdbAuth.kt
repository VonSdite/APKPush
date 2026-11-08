package dev.tvapkpush.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

interface AdbSigner {
    fun sign(token: ByteArray): ByteArray
    fun publicKey(): ByteArray
}

object AdbAuth {
    fun digestInfo(token: ByteArray): ByteArray {
        require(token.size == 20) { "Invalid ADB authentication token length" }
        return byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token
    }

    fun publicKey(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048) { "ADB requires a 2048-bit RSA key" }
        val radix = BigInteger.ONE.shiftLeft(32)
        fun littleEndian(number: BigInteger): ByteArray = number.toByteArray().reversedArray().copyOf(256)
        val data = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64)
            .putInt(radix.subtract(key.modulus.modInverse(radix)).toInt())
            .put(littleEndian(key.modulus))
            .put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(key.modulus)))
            .putInt(key.publicExponent.toInt())
            .array()
        return (Base64.getEncoder().encodeToString(data) + " APKPush@android\u0000").toByteArray(Charsets.UTF_8)
    }
}
