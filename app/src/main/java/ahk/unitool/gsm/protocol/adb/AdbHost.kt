package ahk.unitool.gsm.protocol.adb

import ahk.unitool.gsm.usb.UsbTransport
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Minimal ADB USB host (CNXN handshake + unauthenticated shell service).
 * Packet framing is strict; AUTH is reported as unsupported until RSA key support exists.
 */
class AdbHost(private val transport: UsbTransport, private val session: UsbTransport.Session) {

    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var localId = 1

    fun connect(timeoutMs: Int = 5000): Boolean {
        val banner = "host::\u0000".toByteArray(StandardCharsets.UTF_8)
        if (!writeFully(buildPacket(A_CNXN, A_VERSION, A_MAXDATA, banner), timeoutMs)) return false
        val resp = readPacket(timeoutMs) ?: return false
        Log.i(TAG, "CNXN resp cmd=${cmdName(resp.command)} arg0=${resp.arg0}")
        return when (resp.command) {
            A_CNXN -> true
            A_AUTH -> {
                Log.w(TAG, "ADB AUTH required; RSA authentication is not implemented")
                false
            }
            else -> false
        }
    }

    fun shell(cmd: String, timeoutMs: Int = 15_000): String? {
        val local = localId++
        val dest = "shell:$cmd\u0000".toByteArray(StandardCharsets.UTF_8)
        if (!writeFully(buildPacket(A_OPEN, local, 0, dest), timeoutMs)) return null
        val ok = readPacket(timeoutMs) ?: return null
        if (ok.command != A_OKAY || ok.arg1 != local) {
            Log.w(TAG, "OPEN not accepted: ${cmdName(ok.command)}")
            return null
        }
        val remote = ok.arg0
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var closed = false
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val packet = readPacket(remaining) ?: break
            when (packet.command) {
                A_WRTE -> {
                    if (packet.arg0 != remote || packet.arg1 != local) {
                        Log.w(TAG, "ignoring WRTE for unexpected stream")
                        continue
                    }
                    out.append(String(packet.data, StandardCharsets.UTF_8))
                    if (!writeFully(buildPacket(A_OKAY, local, remote, ByteArray(0)), 2000)) break
                }
                A_CLSE -> {
                    closed = true
                    break
                }
                A_OKAY -> Unit
                else -> Log.d(TAG, "shell packet ${cmdName(packet.command)}")
            }
        }
        if (!closed) writeFully(buildPacket(A_CLSE, local, remote, ByteArray(0)), 2000)
        return out.toString()
    }

    private fun readPacket(timeoutMs: Int): Packet? {
        val header = readExact(24, timeoutMs) ?: return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        val expectedChecksum = bb.int
        val magic = bb.int

        if (magic != command.inv()) {
            Log.e(TAG, "invalid ADB magic for ${cmdName(command)}")
            return null
        }
        if (length < 0 || length > A_MAXDATA) {
            Log.e(TAG, "invalid ADB payload length=$length")
            return null
        }
        val payload = if (length == 0) ByteArray(0) else readExact(length, timeoutMs) ?: return null
        val actualChecksum = payload.fold(0) { acc, byte -> acc + (byte.toInt() and 0xff) }
        if (actualChecksum != expectedChecksum) {
            Log.e(TAG, "ADB checksum mismatch for ${cmdName(command)}")
            return null
        }
        return Packet(command, arg0, arg1, payload)
    }

    /** USB bulk reads may return fewer bytes than requested; assemble complete protocol fields. */
    private fun readExact(size: Int, timeoutMs: Int): ByteArray? {
        if (size < 0 || size > A_MAXDATA + 24) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        val result = ByteArray(size)
        var offset = 0
        while (offset < size && System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val part = transport.bulkIn(session, size - offset, remaining) ?: continue
            if (part.isEmpty()) continue
            val count = minOf(part.size, size - offset)
            part.copyInto(result, offset, 0, count)
            offset += count
        }
        return if (offset == size) result else null
    }

    private fun writeFully(packet: ByteArray, timeoutMs: Int): Boolean {
        var offset = 0
        while (offset < packet.size) {
            val remaining = packet.copyOfRange(offset, packet.size)
            val n = transport.bulkOut(session, remaining, timeoutMs)
            if (n <= 0 || n > remaining.size) return false
            offset += n
        }
        return true
    }

    private fun buildPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        require(data.size <= A_MAXDATA) { "ADB payload exceeds negotiated maximum" }
        val checksum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }
        return ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(data.size)
            putInt(checksum)
            putInt(command.inv())
            put(data)
        }.array()
    }

    companion object {
        private const val TAG = "AdbHost"
        const val A_VERSION = 0x01000001
        const val A_MAXDATA = 256 * 1024
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

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
}
