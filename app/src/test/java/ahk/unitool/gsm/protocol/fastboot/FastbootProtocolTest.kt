package ahk.unitool.gsm.protocol.fastboot

import ahk.unitool.gsm.protocol.common.FastbootProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FastbootProtocolTest {

    @Test
    fun parseOkay() {
        val r = FastbootProtocol.parseResponse("OKAYproduct")
        assertThat(r.status).isEqualTo("OKAY")
        assertThat(r.payload).isEqualTo("product")
        assertThat(r.ok).isTrue()
        assertThat(r.failed).isFalse()
    }

    @Test
    fun parseFail() {
        val r = FastbootProtocol.parseResponse("FAILunknown command")
        assertThat(r.failed).isTrue()
        assertThat(r.payload).contains("unknown")
    }

    @Test
    fun parseDataSize() {
        val r = FastbootProtocol.parseResponse("DATA00001000")
        assertThat(r.isData).isTrue()
        assertThat(FastbootProtocol.parseDataSize(r.payload)).isEqualTo(0x1000L)
    }

    @Test
    fun parseInfo() {
        val r = FastbootProtocol.parseResponse("INFOwaiting for device")
        assertThat(r.isInfo).isTrue()
        assertThat(r.payload).contains("waiting")
    }

    @Test
    fun buildCommandBytes() {
        val bytes = FastbootProtocol.buildCommand("getvar:product")
        assertThat(String(bytes)).isEqualTo("getvar:product")
    }

    @Test
    fun downloadSizeToken() {
        assertThat(FastbootProtocol.downloadSizeToken(256)).isEqualTo("download:00000100")
        assertThat(FastbootProtocol.downloadSizeToken(0)).isEqualTo("download:00000000")
    }

    @Test
    fun parseResponseFromBytes() {
        val r = FastbootProtocol.parseResponse("OKAY".toByteArray())
        assertThat(r.ok).isTrue()
    }

    @Test
    fun unknownStatus() {
        val r = FastbootProtocol.parseResponse("GARBAGE")
        assertThat(r.status).isEqualTo("UNKNOWN")
    }
}
