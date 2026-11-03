package ahk.unitool.gsm.protocol.mtk

import ahk.unitool.gsm.usb.UsbTransport
import android.util.Log

/**
 * Minimal MediaTek BROM / Preloader handshake over bulk.
 * Based on public mtkclient / Antumbra flow (start command + echo).
 *
 * Full DA upload is out of scope here; this proves the transport path.
 */
class MtkBrom(private val transport: UsbTransport, private val session: UsbTransport.Session) {

    data class HandshakeResult(
        val ok: Boolean,
        val message: String,
        val raw: ByteArray = ByteArray(0),
    )

    /**
     * Send BROM start sequence and read response.
     * Classic: host sends 0xA0, expects 0x5A; then 0x0A expects 0xA5; etc.
     * (Exact bytes vary by chip; this is a connectivity probe.)
     */
    fun probe(timeoutMs: Int = 3000): HandshakeResult {
        // Many MTK devices in BROM expose a CDC-like or vendor bulk pair.
        // Probe with a short write + read.
        val probes = listOf(
            byteArrayOf(0xA0.toByte()),
            byteArrayOf(0x0A),
            byteArrayOf(0xFE.toByte()),
        )
        val responses = mutableListOf<String>()
        for (p in probes) {
            val n = transport.bulkOut(session, p, timeoutMs)
            val r = transport.bulkIn(session, 64, timeoutMs)
            val hex = r?.joinToString("") { "%02x".format(it) } ?: "null"
            responses += "out=${p.joinToString("") { "%02x".format(it) }}($n) in=$hex"
            Log.i(TAG, responses.last())
        }
        val any = responses.any { it.contains("in=") && !it.endsWith("in=null") }
        return HandshakeResult(
            ok = any,
            message = responses.joinToString("; "),
        )
    }

    companion object {
        private const val TAG = "MtkBrom"

        /** Common MediaTek VID */
        const val VID_MEDIATEK = 0x0E8D

        fun isLikelyMtk(vid: Int, pid: Int): Boolean {
            if (vid != VID_MEDIATEK) return false
            // BROM / preloader PIDs often 0x0003, 0x2000, 0x2001, 0x2007, ...
            return true
        }
    }
}
