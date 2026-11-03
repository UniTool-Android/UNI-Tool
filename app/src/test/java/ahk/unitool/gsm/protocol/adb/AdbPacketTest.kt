package ahk.unitool.gsm.protocol.adb

import ahk.unitool.gsm.protocol.common.AdbPacket
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class AdbPacketTest {

    @Test
    fun encodeCnxnHeaderLayout() {
        val banner = "host::\u0000".toByteArray(StandardCharsets.UTF_8)
        val raw = AdbPacket.encode(AdbPacket.A_CNXN, AdbPacket.A_VERSION, AdbPacket.A_MAXDATA, banner)
        assertThat(raw.size).isEqualTo(24 + banner.size)

        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(bb.int).isEqualTo(AdbPacket.A_CNXN)
        assertThat(bb.int).isEqualTo(AdbPacket.A_VERSION)
        assertThat(bb.int).isEqualTo(AdbPacket.A_MAXDATA)
        assertThat(bb.int).isEqualTo(banner.size)
        val check = bb.int
        assertThat(check).isEqualTo(AdbPacket.checksum(banner))
        assertThat(bb.int).isEqualTo(AdbPacket.A_CNXN.inv())
    }

    @Test
    fun checksumEmpty() {
        assertThat(AdbPacket.checksum(ByteArray(0))).isEqualTo(0)
    }

    @Test
    fun checksumKnown() {
        // 'A' = 65
        assertThat(AdbPacket.checksum(byteArrayOf(65))).isEqualTo(65)
        assertThat(AdbPacket.checksum(byteArrayOf(1, 2, 3))).isEqualTo(6)
    }

    @Test
    fun decodeValidHeader() {
        val data = "x".toByteArray()
        val encoded = AdbPacket.encode(AdbPacket.A_OKAY, 1, 2, data)
        val header = encoded.copyOf(24)
        val pkt = AdbPacket.decodeHeader(header)
        assertThat(pkt).isNotNull()
        assertThat(pkt!!.command).isEqualTo(AdbPacket.A_OKAY)
        assertThat(pkt.arg0).isEqualTo(1)
        assertThat(pkt.arg1).isEqualTo(2)
        assertThat(pkt.data.size).isEqualTo(1)
    }

    @Test
    fun decodeRejectsShortHeader() {
        assertThat(AdbPacket.decodeHeader(ByteArray(10))).isNull()
    }

    @Test
    fun decodeRejectsBadMagic() {
        val encoded = AdbPacket.encode(AdbPacket.A_CLSE, 0, 0)
        encoded[20] = 0 // corrupt magic
        encoded[21] = 0
        encoded[22] = 0
        encoded[23] = 0
        assertThat(AdbPacket.decodeHeader(encoded)).isNull()
    }

    @Test
    fun cmdNames() {
        assertThat(AdbPacket.cmdName(AdbPacket.A_CNXN)).isEqualTo("CNXN")
        assertThat(AdbPacket.cmdName(AdbPacket.A_AUTH)).isEqualTo("AUTH")
        assertThat(AdbPacket.cmdName(0x1234)).startsWith("0x")
    }

    @Test
    fun adbInterfaceMatch() {
        assertThat(AdbPacket.isAdbInterface(0xFF, 0x42, 0x01)).isTrue()
        assertThat(AdbPacket.isAdbInterface(0x08, 0x06, 0x50)).isFalse()
    }

    @Test
    fun packetEquals() {
        val a = AdbPacket.Packet(1, 2, 3, byteArrayOf(4))
        val b = AdbPacket.Packet(1, 2, 3, byteArrayOf(4))
        val c = AdbPacket.Packet(1, 2, 3, byteArrayOf(5))
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }
}
