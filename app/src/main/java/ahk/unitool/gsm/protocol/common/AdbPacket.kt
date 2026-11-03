package ahk.unitool.gsm.protocol.common

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure ADB USB packet encode/decode (no Android dependency) — unit-testable on JVM.
 */
object AdbPacket {
    const val A_VERSION = 0x01000001
    const val A_MAXDATA = 256 * 1024
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Packet) return false
            return command == other.command && arg0 == other.arg0 &&
                arg1 == other.arg1 && data.contentEquals(other.data)
        }

        override fun hashCode(): Int =
            31 * (31 * (31 * command + arg0) + arg1) + data.contentHashCode()
    }

    fun checksum(data: ByteArray): Int =
        data.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }

    fun encode(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(checksum(data))
        buf.putInt(command.inv())
        buf.put(data)
        return buf.array()
    }

    fun decodeHeader(header: ByteArray): Packet? {
        if (header.size < 24) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        val check = bb.int
        val magic = bb.int
        if (magic != command.inv()) return null
        if (length < 0 || length > A_MAXDATA) return null
        return Packet(command, arg0, arg1, ByteArray(length).also {
            // payload filled by caller after bulkIn
            // checksum validated when payload present
        }).also {
            // stash expected checksum in unused way — return length via data size
        }.let { pkt ->
            // We only decode header here; length is data.size placeholder
            Packet(command, arg0, arg1, ByteArray(length))
        }
    }

    fun validatePayload(packet: Packet, expectedChecksum: Int): Boolean =
        checksum(packet.data) == expectedChecksum

    fun cmdName(c: Int): String = when (c) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        else -> "0x${c.toString(16)}"
    }

    fun isAdbInterface(cls: Int, sub: Int, proto: Int): Boolean =
        cls == 0xFF && sub == 0x42 && proto == 0x01
}
