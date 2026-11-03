package ahk.unitool.gsm.shell

import ahk.unitool.gsm.termux.TermuxEnv
import ahk.unitool.gsm.usb.UsbTransport
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interactive shell process (Termux bash via proot path-bind, or system sh).
 * Not a full PTY — line-buffered stdin/stdout for testing.
 */
class ShellSession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private val alive = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    fun start(usbFd: Int? = null) {
        stop()
        val (cmd, args) = TermuxEnv.shellCommand(context)
        val env = TermuxEnv.buildEnv(context, usbFd)
        val pb = ProcessBuilder(listOf(cmd) + args.toList())
        pb.redirectErrorStream(true)
        pb.directory(TermuxEnv.home)
        val environment = pb.environment()
        environment.clear()
        env.forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) environment[line.substring(0, i)] = line.substring(i + 1)
        }
        // Keep essential Android env
        System.getenv("ANDROID_DATA")?.let { environment["ANDROID_DATA"] = it }
        System.getenv("ANDROID_ROOT")?.let { environment["ANDROID_ROOT"] = it }

        Log.i(TAG, "start $cmd ${args.joinToString(" ")}")
        process = pb.start()
        stdin = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
        alive.set(true)
        onOutput("Uni Tool shell\n$cmd\nUSB_FD=${usbFd ?: "-"}\n\n")

        pool.execute {
            try {
                BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8)).use { reader ->
                    val buf = CharArray(4096)
                    while (alive.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(String(buf, 0, n))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reader: ${e.message}")
            } finally {
                val code = try { process?.waitFor() ?: -1 } catch (_: Exception) { -1 }
                alive.set(false)
                onExit(code)
            }
        }
    }

    fun write(line: String) {
        try {
            stdin?.write(line)
            if (!line.endsWith("\n")) stdin?.write("\n")
            stdin?.flush()
        } catch (e: Exception) {
            onOutput("\n[stdin error: ${e.message}]\n")
        }
    }

    fun stop() {
        alive.set(false)
        try { stdin?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        stdin = null
    }

    companion object {
        private const val TAG = "ShellSession"

        /** Prefer FD of first open USB session for env. */
        fun currentUsbFd(ctx: Context): Int? =
            UsbTransport.get(ctx).allSessions().firstOrNull()?.fileDescriptor
    }
}
