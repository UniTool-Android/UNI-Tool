package ahk.unitool.gsm.protocol.fastboot

import ahk.unitool.gsm.usb.UsbTransport
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test

/**
 * FastbootClient against a mocked [UsbTransport] (no hardware).
 */
class FastbootClientMockTransportTest {

    private fun session(): UsbTransport.Session {
        val connection = mockk<android.hardware.usb.UsbDeviceConnection>(relaxed = true)
        every { connection.fileDescriptor } returns 7
        val device = mockk<android.hardware.usb.UsbDevice>(relaxed = true)
        every { device.vendorId } returns 0x18D1
        every { device.productId } returns 0x4EE0
        val epOut = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true)
        every { epOut.address } returns 0x01
        val epIn = mockk<android.hardware.usb.UsbEndpoint>(relaxed = true)
        every { epIn.address } returns 0x81
        return UsbTransport.Session(
            device = device,
            connection = connection,
            usbInterface = mockk(relaxed = true),
            epIn = epIn,
            epOut = epOut,
        )
    }

    @Test
    fun getVarProduct_returnsOkay() {
        val transport = mockk<UsbTransport>()
        val session = session()
        val outSlot = slot<ByteArray>()
        every { transport.bulkOut(session, capture(outSlot), any()) } returns 14
        every { transport.bulkIn(session, any(), any()) } returns "OKAYsailfish".toByteArray()

        // FastbootClient uses NativeUsb prefer path which falls back to transport
        // when native unavailable — call command via reflection of public API
        val client = FastbootClient(transport, session)
        // Force pure transport by using command which internally may use NativeUsb
        // If NativeUsb falls back, mock must satisfy transport.bulk*
        every {
            transport.bulkOut(any(), any(), any())
        } answers {
            val data = secondArg<ByteArray>()
            data.size
        }
        every { transport.bulkIn(any(), any(), any()) } returns "OKAYsailfish".toByteArray()

        val result = client.getVar("product")
        assertThat(result.status).isEqualTo("OKAY")
        assertThat(result.data).contains("sailfish")
    }

    @Test
    fun bulkOutFailure_returnsFail() {
        val transport = mockk<UsbTransport>()
        val session = session()
        every { transport.bulkOut(any(), any(), any()) } returns -1
        every { transport.bulkIn(any(), any(), any()) } returns null

        val client = FastbootClient(transport, session)
        val result = client.command("getvar:version")
        assertThat(result.failed).isTrue()
    }
}
