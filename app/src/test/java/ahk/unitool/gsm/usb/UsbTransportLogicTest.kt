package ahk.unitool.gsm.usb

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Logic-level tests that do not need a real USB device.
 * Hardware paths are covered by instrumented tests when a device is present.
 */
class UsbTransportLogicTest {

    @Test
    fun permissionActionConstant() {
        assertThat(UsbTransport.ACTION_PERMISSION)
            .isEqualTo("ahk.unitool.gsm.USB_PERMISSION")
    }

    @Test
    fun sessionFdDelegatesToConnection() {
        val connection = mockk<android.hardware.usb.UsbDeviceConnection>()
        every { connection.fileDescriptor } returns 42
        val device = mockk<android.hardware.usb.UsbDevice>()
        every { device.deviceName } returns "/dev/bus/usb/001/002"
        every { device.vendorId } returns 0x18D1
        every { device.productId } returns 0x4EE0
        val intf = mockk<android.hardware.usb.UsbInterface>()

        val session = UsbTransport.Session(
            device = device,
            connection = connection,
            usbInterface = intf,
            epIn = null,
            epOut = null,
        )
        assertThat(session.fileDescriptor).isEqualTo(42)
        assertThat(session.vendorId).isEqualTo(0x18D1)
        assertThat(session.productId).isEqualTo(0x4EE0)
        verify { connection.fileDescriptor }
    }
}
