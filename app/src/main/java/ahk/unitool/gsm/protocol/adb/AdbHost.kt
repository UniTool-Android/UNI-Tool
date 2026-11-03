package ahk.unitool.gsm.protocol.adb

import ahk.unitool.gsm.usb.UsbTransport
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal ADB USB host (CNXN handshake + shell service).
 * Enough to prove transport; not a full adb replacement.
 *
 * ADB USB: bulk OUT/IN, packets = 24-byte header + optional payload.
 * Magic commands: A_CNXN, A_AUTH, A_OPEN, A_WRTE, A_CLSE, A_OKAY.
 */
class AdbHost(private val transport: UsbTransport, private val session: UsbTransport.Session) {

    data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    private var localId = 1

    fun connect(timeoutMs: Int = 5000): Boolean {
        // CNXN system: version, maxdata, "host::\u0000"
        val banner = "host::\u0000".toByteArray(StandardCharsets.UTF_8)
        val pkt = buildPacket(A_CNXN, A_VERSION, A_MAXDATA, banner)
        if (transport.bulkOut(session, pkt, timeoutMs) < 0) return false
        val resp = readPacket(timeoutMs) ?: return false
        Log.i(TAG, "CNXN resp cmd=${cmdName(resp.command)} arg0=${resp.arg0}")
        return when (resp.command) {
            A_CNXN -> true
            A_AUTH -> {
                // Device wants auth — without keys we cannot proceed fully
                Log.w(TAG, "AUTH required (pair device or use RSA keys) payload=${resp.data.size}")
                false
            }
            else -> false
        }
    }

    /** Open `shell:<cmd>` service and collect stdout until close. */
    fun shell(cmd: String, timeoutMs: Int = 15_000): String? {
        val local = localId++
        val dest = "shell:$cmd\u0000".toByteArray(StandardCharsets.UTF_8)
        val open = buildPacket(A_OPEN, local, 0, dest)
        if (transport.bulkOut(session, open, timeoutMs) < 0) return null
        val ok = readPacket(timeoutMs) ?: return null
        if (ok.command != A_OKAY) {
            Log.w(TAG, "OPEN not OKAY: ${cmdName(ok.command)}")
            return null
        }
        val remote = ok.arg0
        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = readPacket((deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100))
                ?: break
            when (p.command) {
                A_WRTE -> {
                    out.append(String(p.data, StandardCharsets.UTF_8))
                    // OKAY back
                    val ack = buildPacket(A_OKAY, local, remote, ByteArray(0))
                    transport.bulkOut(session, ack, 2000)
                }
                A_CLSE -> break
                A_OKAY -> { /* ignore */ }
                else -> Log.d(TAG, "shell pkt ${cmdName(p.command)}")
            }
        }
        // close
        transport.bulkOut(session, buildPacket(A_CLSE, local, remote, ByteArray(0)), 2000)
        return out.toString()
    }

    private fun readPacket(timeoutMs: Int): Packet? {
        val hdr = transport.bulkIn(session, 24, timeoutMs) ?: return null
        if (hdr.size < 24) return null
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val length = bb.int
        val check = bb.int
        val magic = bb.int
        if (magic != command.inv()) {
            Log.w(TAG, "bad magic cmd=$command magic=$magic")
        }
        val data = if (length > 0) {
            transport.bulkIn(session, length, timeoutMs) ?: ByteArray(0)
        } else ByteArray(0)
        return Packet(command, arg0, arg1, data)
    }

    private fun buildPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        val length = data.size
        val checksum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }
        val buf = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(length)
        buf.putInt(checksum)
        buf.putInt(command.inv())
        buf.put(data)
        return buf.array()
    }

    companion object {
        private const val TAG = "AdbHost"
        const val A_VERSION = 0x01000001
        const val A_MAXDATA = 256 * 1024
        const val A_SYNC = 0x434e5953
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

        /** ADB interface: class 0xFF, subclass 0x42, protocol 0x01 */
        fun isAdbInterface(cls: Int, sub: Int, proto: Int): Boolean =
            cls == 0xFF && sub == 0x42 && proto == 0x01
    }
}
