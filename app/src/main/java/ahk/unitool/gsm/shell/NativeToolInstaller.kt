package ahk.unitool.gsm.shell

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Copies packaged ARM64 command payloads from APK assets into app-private runtime directories. */
object NativeToolInstaller {
    private const val TAG = "NativeToolInstaller"
    private const val EXECUTABLE_ASSETS = "tools/arm64-v8a"
    private const val LIBRARY_ASSETS = "tools/lib/arm64-v8a"

    fun install(context: Context): List<File> {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            Log.w(TAG, "No arm64-v8a runtime payloads for this device")
            return emptyList()
        }
        val runtime = ShellRuntime.get(context)
        val installed = mutableListOf<File>()
        copyAssets(context, EXECUTABLE_ASSETS, runtime.bin, executable = true, installed)
        copyAssets(context, LIBRARY_ASSETS, runtime.lib, executable = false, installed)
        return installed
    }

    private fun copyAssets(context: Context, assetDir: String, destination: File, executable: Boolean, installed: MutableList<File>) {
        val names = try { context.assets.list(assetDir).orEmpty() } catch (_: Exception) { emptyArray() }
        names.filter { it.isNotBlank() && !it.startsWith(".") && it != "README.md" && !it.contains('/') }.forEach { name ->
            val target = File(destination, name)
            try {
                context.assets.open("$assetDir/$name").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                target.setReadable(true, true)
                target.setWritable(true, true)
                if (executable) target.setExecutable(true, true)
                installed += target
            } catch (e: Exception) {
                Log.e(TAG, "Unable to stage $name: ${e.message}", e)
                target.delete()
            }
        }
    }
}
