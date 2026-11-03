package ahk.unitool.gsm.protocol.fastboot

import ahk.unitool.gsm.usb.UsbTransport
import ahk.unitool.gsm.usb.NativeUsb
import ahk.unitool.gsm.protocol.common.FastbootProtocol
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Minimal Fastboot host over USB bulk endpoints.
 * All writes are completed explicitly because USB bulk transfers may be short.
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
        val payload = FastbootProtocol.buildCommand(cmd)
        val sent = writeFully(payload, timeoutMs)
        if (sent < 0) return Result("FAIL", "bulkOut failed at byte ${-sent}")
        Log.d(TAG, ">> $cmd ($sent bytes)")
        return readFinalResponse(timeoutMs)
    }

    /** Send a payload after the device accepts the matching DATA size. */
    fun download(data: ByteArray, timeoutMs: Int = 30_000): Result {
        val start = command("download:%08x".format(data.size), timeoutMs)
        if (start.status != "DATA") return start

        val accepted = FastbootProtocol.parseDataSize(start.data)
            ?: return Result("FAIL", "invalid DATA size: ${start.data}")
        if (accepted != data.size.toLong()) {
            return Result("FAIL", "device accepted $accepted bytes; payload is ${data.size} bytes")
        }

        var offset = 0
        val chunkSize = 64 * 1024
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val chunk = data.copyOfRange(offset, end)
            val written = writeFully(chunk, timeoutMs)
            if (written < 0) return Result("FAIL", "bulkOut failed at byte $offset")
            if (written != chunk.size) return Result("FAIL", "incomplete write at byte $offset")
            offset += written
        }
        return readFinalResponse(timeoutMs)
    }

    /** Returns bytes written, or a negative value encoding the failing offset. */
    private fun writeFully(bytes: ByteArray, timeoutMs: Int): Int {
        var offset = 0
        while (offset < bytes.size) {
            val chunk = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
            val n = NativeUsb.bulkOutPreferNative(transport, session, chunk, timeoutMs)
            if (n <= 0) return -offset - 1
            if (n > chunk.size) return -offset - 1
            offset += n
        }
        return offset
    }

    private fun readFinalResponse(timeoutMs: Int): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        val info = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val raw = NativeUsb.bulkInPreferNative(transport, session, 256, remaining)
                ?: continue
            if (raw.isEmpty()) continue
            val response = FastbootProtocol.parseResponse(raw)
            Log.d(TAG, "<< ${response.status} ${response.payload}")
            when (response.status) {
                "INFO" -> info.append(response.payload).append('\n')
                "OKAY", "FAIL" -> return Result(response.status, response.payload + info)
                "DATA" -> return Result("DATA", response.payload)
                else -> info.append(response.payload).append('\n')
            }
        }
        return Result("FAIL", "timeout; partial=$info")
    }

    companion object {
        private const val TAG = "FastbootClient"

        fun isLikelyFastboot(vid: Int, pid: Int, product: String?): Boolean {
            val p = product?.lowercase().orEmpty()
            return p.contains("fastboot") || p.contains("android")
        }
    }
}
