package ahk.unitool.gsm.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellRuntimeTest {
    @Test
    fun createsPrivateRuntimeAndShellEnvironment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = ShellRuntime.get(context)
        assertThat(runtime.root).isDirectory()
        assertThat(runtime.bin).isDirectory()
        assertThat(runtime.lib).isDirectory()
        assertThat(runtime.home).isDirectory()
        assertThat(runtime.tmp).isDirectory()
        assertThat(runtime.init).isFile()
        assertThat(runtime.shellCommand().first).isEqualTo("/system/bin/sh")
        val env = runtime.environment().toSet()
        assertThat(env).contains("UNITOOL=1")
        assertThat(env).contains("HOME=${runtime.home.absolutePath}")
        assertThat(env).contains("ENV=${runtime.init.absolutePath}")
    }

    @Test
    fun usbDescriptorIsExposedOnlyWhenValid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = ShellRuntime.get(context)
        assertThat(runtime.environment(12).toSet()).contains("USB_FD=12")
        assertThat(runtime.environment(-1).toSet()).doesNotContain("USB_FD=-1")
    }
}
