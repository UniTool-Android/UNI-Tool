package ahk.unitool.gsm.shell
import android.content.Context
import java.io.File

/** App-private runtime that launches the bundled ARM64 Bash executable. */
class ShellRuntime private constructor(private val context: Context) {
    val root = File(context.filesDir, "unitool")
    val bin = File(root, "bin")
    val lib = File(root, "lib")
    val home = File(root, "home")
    val tmp = File(root, "tmp")
    val init = File(root, "init.sh")
    private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

    init {
        listOf(bin, lib, home, tmp).forEach { it.mkdirs() }
        init.writeText(INIT_SCRIPT)
        File(home, ".bashrc").writeText("source \"\${init.absolutePath}\"\n")
    }

    /** libbash.so is an executable ELF; execute it directly, never dlopen it. */
    fun shellCommand(): Pair<String, Array<String>> =
        File(nativeLibDir, "libbash.so").absolutePath to arrayOf("-i")

    fun environment(usbFd: Int? = null): Array<String> {
        val env = linkedMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PATH" to "\${bin.absolutePath}:\${nativeLibDir.absolutePath}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "UNITOOL" to "1",
            "UNITOOL_HOME" to root.absolutePath,
            "UNITOOL_BIN" to bin.absolutePath,
            "UNITOOL_LIB" to lib.absolutePath,
            "UNITOOL_INIT" to init.absolutePath,
            "BASH_ENV" to init.absolutePath,
            "LD_LIBRARY_PATH" to "\${nativeLibDir.absolutePath}:\${lib.absolutePath}"
        )
        System.getenv("ANDROID_ROOT")?.let { env["ANDROID_ROOT"] = it }
        System.getenv("ANDROID_DATA")?.let { env["ANDROID_DATA"] = it }
        if (usbFd != null && usbFd >= 0) env["USB_FD"] = usbFd.toString()
        return env.map { "\${it.key}=\${it.value}" }.toTypedArray()
    }

    fun executable(name: String): File? = File(bin, name).takeIf { it.isFile && it.canExecute() }

    companion object {
        private const val INIT_SCRIPT =
            "export PATH=\"\${UNITOOL_BIN}:\${PATH}\"\n" +
            "help() {\n" +
            "  echo 'Uni Tool native Bash shell'\n" +
            "  echo 'Available commands:'\n" +
            "  for tool in adb fastboot mtk qdl; do\n" +
            "    if command -v \"\$tool\" >/dev/null 2>&1; then echo \"  \$tool: installed\"; else echo \"  \$tool: not installed\"; fi\n" +
            "  done\n" +
            "}\n" +
            "unitool-version() { echo 'Uni Tool native shell 0.3.0'; }\n"

        @Volatile private var instance: ShellRuntime? = null
        fun get(context: Context): ShellRuntime =
            instance ?: synchronized(this) {
                instance ?: ShellRuntime(context.applicationContext).also { instance = it }
            }
    }
}
