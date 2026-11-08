package dev.tvapkpush.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbProtocolTest {
    @Test fun authenticationSignsExistingSha1Digest() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val challengeSource = "adb authorization challenge".toByteArray()
        val token = MessageDigest.getInstance("SHA-1").digest(challengeSource)
        val signed = Signature.getInstance("NONEwithRSA").run {
            initSign(key.private)
            update(AdbAuth.digestInfo(token))
            sign()
        }
        assertTrue(Signature.getInstance("SHA1withRSA").run {
            initVerify(key.public)
            update(challengeSource)
            verify(signed)
        })
        assertEquals(256, signed.size)
    }

    @Test fun publicKeyUsesAndroidLittleEndianRsaFormat() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val encoded = AdbAuth.publicKey(key).toString(Charsets.UTF_8)
        assertTrue(encoded.endsWith(" APKPush@android\u0000"))
        val packed = Base64.getDecoder().decode(encoded.substringBefore(' '))
        assertEquals(524, packed.size)
        val data = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, data.int)
        val n0inv = data.int.toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (key.modulus.toLong() * n0inv) and 0xffffffffL)
        val modulus = ByteArray(256).also { data.get(it) }.reversedArray()
        assertEquals(key.modulus, java.math.BigInteger(1, modulus))
        val rr = ByteArray(256).also { data.get(it) }.reversedArray()
        assertEquals(java.math.BigInteger.ONE.shiftLeft(4096).mod(key.modulus), java.math.BigInteger(1, rr))
        assertEquals(65537, data.int)
    }

    @Test fun packetsSurviveFragmentedReadsAndUnsignedChecksums() {
        val data = ByteArray(4097) { (it * 17).toByte() }
        val output = ByteArrayOutputStream()
        AdbPacket("WRTE", 5, 7, data).write(output)
        val fragmented = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(3, length))
        }
        val parsed = AdbPacket.read(fragmented)
        assertEquals("WRTE", parsed.command)
        assertEquals(5, parsed.arg0)
        assertEquals(7, parsed.arg1)
        assertArrayEquals(data, parsed.data)
    }

    @Test fun corruptChecksumIsRejected() {
        val output = ByteArrayOutputStream()
        AdbPacket("WRTE", 1, 1, byteArrayOf(1, 2)).write(output)
        val data = output.toByteArray().apply { this[lastIndex] = 4 }
        assertThrows(IOException::class.java) { AdbPacket.read(data.inputStream()) }
    }

    @Test fun oversizedPayloadIsRejectedBeforeAllocation() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbPacket.commandValue("WRTE")).putInt(1).putInt(1).putInt(Int.MAX_VALUE)
            .putInt(0).putInt(AdbPacket.commandValue("WRTE").inv()).array()
        assertThrows(IOException::class.java) { AdbPacket.read(header.inputStream()) }
    }

    @Test fun incompleteAndInvalidMagicPacketsAreRejected() {
        assertThrows(IOException::class.java) { AdbPacket.read(byteArrayOf(1, 2, 3).inputStream()) }
        val output = ByteArrayOutputStream()
        AdbPacket("OKAY", 1, 2).write(output)
        val bytes = output.toByteArray().apply { this[23] = 0 }
        assertThrows(IOException::class.java) { AdbPacket.read(bytes.inputStream()) }
    }
}
