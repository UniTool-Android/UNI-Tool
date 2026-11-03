package ahk.unitool.gsm.shell
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ShellRuntimeTest {
    @Test
    fun createsPrivateRuntimeAndBashEnvironment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = ShellRuntime.get(context)
        assertThat(runtime.root.isDirectory).isTrue()
        assertThat(runtime.bin.isDirectory).isTrue()
        assertThat(runtime.lib.isDirectory).isTrue()
        assertThat(runtime.home.isDirectory).isTrue()
        assertThat(runtime.tmp.isDirectory).isTrue()
        assertThat(runtime.init.isFile).isTrue()
        assertThat(runtime.shellCommand().first)
            .isEqualTo(File(context.applicationInfo.nativeLibraryDir, "libbash.so").absolutePath)
        assertThat(runtime.shellCommand().second.toList()).containsExactly("-i")
        assertThat(File(runtime.home, ".bashrc").readText()).contains(runtime.init.absolutePath)
        val env = runtime.environment().toSet()
        assertThat(env).contains("UNITOOL=1")
        assertThat(env).contains("HOME=\${runtime.home.absolutePath}")
        assertThat(env).contains("BASH_ENV=\${runtime.init.absolutePath}")
        assertThat(env.any { it.startsWith("LD_LIBRARY_PATH=") }).isTrue()
    }
    @Test
    fun usbDescriptorIsExposedOnlyWhenValid() {
        val runtime = ShellRuntime.get(ApplicationProvider.getApplicationContext<Context>())
        assertThat(runtime.environment(12).toSet()).contains("USB_FD=12")
        assertThat(runtime.environment(-1).toSet()).doesNotContain("USB_FD=-1")
    }
}
