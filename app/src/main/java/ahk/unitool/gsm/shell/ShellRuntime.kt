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
    fun shellCommand(): Pair<String, Array<String>> = "/system/bin/sh" to arrayOf("-i")
    fun environment(usbFd: Int? = null): Array<String> {
        val env = linkedMapOf(
            "HOME" to home.absolutePath, "TMPDIR" to tmp.absolutePath,
            "PATH" to "${bin.absolutePath}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8",
            "UNITOOL" to "1", "UNITOOL_HOME" to root.absolutePath,
            "UNITOOL_BIN" to bin.absolutePath, "UNITOOL_LIB" to lib.absolutePath,
            "UNITOOL_INIT" to init.absolutePath, "ENV" to init.absolutePath, "LD_LIBRARY_PATH" to lib.absolutePath
        )
        System.getenv("ANDROID_ROOT")?.let { env["ANDROID_ROOT"] = it }
        System.getenv("ANDROID_DATA")?.let { env["ANDROID_DATA"] = it }
        if (usbFd != null && usbFd >= 0) env["USB_FD"] = usbFd.toString()
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }
    fun executable(name: String): File? = File(bin, name).takeIf { it.isFile && it.canExecute() }
    companion object {
        private const val INIT_SCRIPT = "export PATH=\\\"${UNITOOL_BIN}:/system/bin:/system/xbin\\\"\\n" +
            "help() {\\n" +
            "  echo 'Uni Tool native shell';\\n" +
            "  echo 'Available commands:';\\n" +
            "  for tool in adb fastboot mtk qdl; do\\n" +
            "    if command -v \\\"$tool\\\" >/dev/null 2>&1; then echo \\\"  $tool: installed\\\"; else echo \\\"  $tool: not installed (binary not bundled)\\\"; fi;\\n" +
            "  done;\\n" +
            "  echo 'USB_FD is informational; external CLI USB forwarding is not enabled.';\\n" +
            "}\\n" +
            "unitool-version() { echo 'Uni Tool native shell 0.3.0'; }\\n"
        @Volatile private var instance: ShellRuntime? = null
        fun get(context: Context): ShellRuntime = instance ?: synchronized(this) { instance ?: ShellRuntime(context.applicationContext).also { instance = it } }
    }
}
