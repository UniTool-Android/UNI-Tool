package ahk.unitool.gsm.protocol.common

import java.nio.charset.StandardCharsets

/**
 * Pure Fastboot response parsing — unit-testable without USB hardware.
 */
object FastbootProtocol {

    data class Response(val status: String, val payload: String) {
        val ok: Boolean get() = status == "OKAY"
        val failed: Boolean get() = status == "FAIL"
        val isData: Boolean get() = status == "DATA"
        val isInfo: Boolean get() = status == "INFO"
    }

    fun parseResponse(raw: ByteArray): Response {
        val text = raw.toString(StandardCharsets.UTF_8)
        return parseResponse(text)
    }

    fun parseResponse(text: String): Response {
        val t = text.trimEnd('\u0000')
        return when {
            t.startsWith("OKAY") -> Response("OKAY", t.removePrefix("OKAY"))
            t.startsWith("FAIL") -> Response("FAIL", t.removePrefix("FAIL"))
            t.startsWith("DATA") -> Response("DATA", t.removePrefix("DATA"))
            t.startsWith("INFO") -> Response("INFO", t.removePrefix("INFO"))
            else -> Response("UNKNOWN", t)
        }
    }

    fun buildCommand(cmd: String): ByteArray =
        cmd.toByteArray(StandardCharsets.UTF_8)

    fun downloadSizeToken(byteCount: Int): String =
        "download:%08x".format(byteCount)

    fun parseDataSize(dataToken: String): Long? {
        val hex = dataToken.trim().take(8)
        return hex.toLongOrNull(16)
    }
}
