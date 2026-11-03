package ahk.unitool.gsm.usb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device USB tests. Without a connected OTG device, list may be empty —
 * tests still assert API stability.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class UsbTransportInstrumentedTest {

    private lateinit var transport: UsbTransport

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        transport = UsbTransport.get(ctx)
    }

    @Test
    fun devicesListDoesNotThrow() {
        val list = transport.devices()
        assertThat(list).isNotNull()
        // size may be 0 on emulator without USB host
    }

    @Test
    fun allSessionsStartsEmptyOrConsistent() {
        val sessions = transport.allSessions()
        assertThat(sessions).isNotNull()
    }

    @Test
    fun permissionActionMatchesManifest() {
        assertThat(UsbTransport.ACTION_PERMISSION)
            .isEqualTo("ahk.unitool.gsm.USB_PERMISSION")
    }
}
