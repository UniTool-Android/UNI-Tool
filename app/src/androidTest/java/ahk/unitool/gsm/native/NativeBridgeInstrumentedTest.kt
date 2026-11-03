package ahk.unitool.gsm.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on device/emulator. Requires libunitool.so packaged in the test APK.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeBridgeInstrumentedTest {

    @Test
    fun libraryLoadsAndSelfTest() {
        assertThat(NativeBridge.available).isTrue()
        val msg = NativeBridge.selfTest()
        assertThat(msg).contains("unitool-native")
        assertThat(msg).contains("pty=")
    }

    @Test
    fun dupFdInvalidReturnsError() {
        if (!NativeBridge.available) return
        val r = NativeBridge.nativeDupFd(-1)
        assertThat(r).isLessThan(0)
    }
}
