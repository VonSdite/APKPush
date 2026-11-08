package dev.tvapkpush.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AdbPacket(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf()) {
    fun write(output: OutputStream) {
        val commandValue = commandValue(command)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(commandValue).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 255 }).putInt(commandValue.inv()).array()
        output.write(header)
        output.write(data)
        output.flush()
    }

    companion object {
        const val MAX_PAYLOAD = 1024 * 1024

        fun read(input: InputStream, skipChecksum: Boolean = false): AdbPacket {
            val header = ByteBuffer.wrap(readExactly(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            if (header.int != command.inv() || length !in 0..MAX_PAYLOAD) throw IOException("Invalid ADB packet header")
            val data = readExactly(input, length)
            if (!skipChecksum && data.sumOf { it.toInt() and 255 } != checksum) throw IOException("Invalid ADB packet checksum")
            val name = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array().toString(Charsets.US_ASCII)
            return AdbPacket(name, arg0, arg1, data)
        }

        fun commandValue(command: String): Int {
            require(command.length == 4) { "Invalid ADB command" }
            return ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun readExactly(input: InputStream, length: Int): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(result, offset, length - offset)
                if (count < 0) throw EOFException("ADB stream ended before the expected length")
                offset += count
            }
            return result
        }
    }
}
