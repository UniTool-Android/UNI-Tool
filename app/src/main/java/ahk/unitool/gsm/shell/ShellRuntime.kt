package ahk.unitool.gsm.shell

import android.content.Context
import java.io.File

/** App-private runtime using Android's built-in shell; no bootstrap or proot. */
class ShellRuntime private constructor(context: Context) {
    val root = File(context.filesDir, "unitool")
    val bin = File(root, "bin")
    val lib = File(root, "lib")
    val home = File(root, "home")
    val tmp = File(root, "tmp")
    val init = File(root, "init.sh")
    init {
        listOf(bin, lib, home, tmp).forEach { it.mkdirs() }
        if (!init.exists()) init.writeText(INIT_SCRIPT)
    }
    fun shellCommand(): Pair<String, Array<String>> = "/system/bin/sh" to arrayOf("-c", ". \"$" + "{UNITOOL_INIT}\"; exec /system/bin/sh -i")
    fun environment(usbFd: Int? = null): Array<String> {
        val env = linkedMapOf(
            "HOME" to home.absolutePath, "TMPDIR" to tmp.absolutePath,
            "PATH" to "${bin.absolutePath}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8",
            "UNITOOL" to "1", "UNITOOL_HOME" to root.absolutePath,
            "UNITOOL_BIN" to bin.absolutePath, "UNITOOL_LIB" to lib.absolutePath,
            "UNITOOL_INIT" to init.absolutePath
        )
        System.getenv("ANDROID_ROOT")?.let { env["ANDROID_ROOT"] = it }
        System.getenv("ANDROID_DATA")?.let { env["ANDROID_DATA"] = it }
        if (usbFd != null && usbFd >= 0) env["USB_FD"] = usbFd.toString()
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }
    fun executable(name: String): File? = File(bin, name).takeIf { it.isFile && it.canExecute() }
    companion object {
        private const val INIT_SCRIPT = "export PATH=\"${'$'}{UNITOOL_BIN}:/system/bin:/system/xbin\"\nhelp() { echo 'Uni Tool native shell'; echo 'Use command -v adb|fastboot|mtk|qdl to check installed tools'; }\nunitool-version() { echo 'Uni Tool native shell 0.2.0'; }\n"
        @Volatile private var instance: ShellRuntime? = null
        fun get(context: Context): ShellRuntime = instance ?: synchronized(this) { instance ?: ShellRuntime(context.applicationContext).also { instance = it } }
    }
}
