package ahk.unitool.gsm.protocol.fastboot

import ahk.unitool.gsm.usb.UsbTransport
import ahk.unitool.gsm.usb.NativeUsb
import ahk.unitool.gsm.protocol.common.FastbootProtocol
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Minimal Fastboot host over [UsbTransport] bulk endpoints.
 * Protocol: command string on OUT, response tokens INFO/OKAY/FAIL/DATA on IN.
 */
class FastbootClient(private val transport: UsbTransport, private val session: UsbTransport.Session) {

    data class Result(val status: String, val data: String) {
        val ok: Boolean get() = status == "OKAY"
        val failed: Boolean get() = status == "FAIL"
    }

    fun getVar(name: String, timeoutMs: Int = 5000): Result =
        command("getvar:$name", timeoutMs)

    fun devices(): Result = command("getvar:product", timeoutMs = 3000)

    fun command(cmd: String, timeoutMs: Int = 8000): Result {
        val payload = cmd.toByteArray(StandardCharsets.UTF_8)
        val sent = NativeUsb.bulkOutPreferNative(transport, session, payload, timeoutMs)
        if (sent < 0) {
            return Result("FAIL", "bulkOut failed ($sent)")
        }
        Log.d(TAG, ">> $cmd ($sent bytes)")

        val infos = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)
            val raw = NativeUsb.bulkInPreferNative(transport, session, 256, remaining) ?: continue
            if (raw.isEmpty()) continue
            val text = raw.toString(StandardCharsets.UTF_8)
            Log.d(TAG, "<< $text")
            when {
                text.startsWith("INFO") -> infos.append(text.removePrefix("INFO").trim()).append('\n')
                text.startsWith("OKAY") -> return Result("OKAY", text.removePrefix("OKAY") + infos.toString())
                text.startsWith("FAIL") -> return Result("FAIL", text.removePrefix("FAIL") + infos.toString())
                text.startsWith("DATA") -> {
                    // DATA%08x — size of forthcoming download; caller handles
                    return Result("DATA", text.removePrefix("DATA"))
                }
                else -> infos.append(text).append('\n')
            }
        }
        return Result("FAIL", "timeout; partial=${infos}")
    }

    /** Send sparse/raw payload after a DATA response (size hex). */
    fun download(data: ByteArray, timeoutMs: Int = 30_000): Result {
        val sizeHex = "%08x".format(data.size)
        val start = command("download:$sizeHex", timeoutMs)
        if (start.status != "DATA") return start
        var offset = 0
        val chunk = 1_048_576
        while (offset < data.size) {
            val end = (offset + chunk).coerceAtMost(data.size)
            val n = NativeUsb.bulkOutPreferNative(transport, session, data.copyOfRange(offset, end), timeoutMs)
            if (n < 0) return Result("FAIL", "bulkOut at $offset")
            offset += n
        }
        // final OKAY/FAIL
        val raw = NativeUsb.bulkInPreferNative(transport, session, 64, timeoutMs) ?: return Result("FAIL", "no final status")
        val text = raw.toString(StandardCharsets.UTF_8)
        return when {
            text.startsWith("OKAY") -> Result("OKAY", text.removePrefix("OKAY"))
            text.startsWith("FAIL") -> Result("FAIL", text.removePrefix("FAIL"))
            else -> Result("FAIL", text)
        }
    }

    companion object {
        private const val TAG = "FastbootClient"

        /** USB class/subclass/protocol for Google Fastboot (often vendor-specific). */
        fun isLikelyFastboot(vid: Int, pid: Int, product: String?): Boolean {
            val p = product?.lowercase().orEmpty()
            if (p.contains("fastboot") || p.contains("android")) return true
            // common Google / OEM fastboot PIDs are diverse; leave open
            return false
        }
    }
}
