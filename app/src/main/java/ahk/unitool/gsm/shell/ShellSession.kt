package ahk.unitool.gsm.shell

import ahk.unitool.gsm.usb.UsbTransport
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Line-oriented fallback backed by Android's built-in /system/bin/sh. */
class ShellSession(private val context: Context, private val onOutput: (String) -> Unit, private val onExit: (Int) -> Unit) {
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private val alive = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor()
    fun start(usbFd: Int? = null) {
        stop()
        val runtime = ShellRuntime.get(context)
        val (cmd, args) = runtime.shellCommand()
        val pb = ProcessBuilder(listOf(cmd) + args.toList()).redirectErrorStream(true)
        pb.directory(runtime.home)
        val env = pb.environment(); env.clear()
        runtime.environment(usbFd).forEach { entry -> val i = entry.indexOf('='); if (i > 0) env[entry.substring(0, i)] = entry.substring(i + 1) }
        val child = pb.start(); process = child
        stdin = OutputStreamWriter(child.outputStream, Charsets.UTF_8)
        alive.set(true)
        onOutput("Uni Tool native shell\nHOME=${runtime.home}\nUSB_FD=${usbFd ?: "-"}\n\n")
        pool.execute {
            try { BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).use { reader -> val buf = CharArray(4096); while (alive.get()) { val n = reader.read(buf); if (n < 0) break; if (n > 0) onOutput(String(buf, 0, n)) } } }
            catch (e: Exception) { Log.w(TAG, "shell reader: ${e.message}") }
            finally { val code = try { child.waitFor() } catch (_: Exception) { -1 }; alive.set(false); onExit(code) }
        }
    }
    fun write(line: String) { try { stdin?.write(line); if (!line.endsWith("\n")) stdin?.write("\n"); stdin?.flush() } catch (e: Exception) { onOutput("\n[stdin error: ${e.message}]\n") } }
    fun stop() { alive.set(false); try { stdin?.close() } catch (_: Exception) {}; try { process?.destroy() } catch (_: Exception) {}; process = null; stdin = null }
    companion object {
        private const val TAG = "ShellSession"
        fun currentUsbFd(ctx: Context): Int? = UsbTransport.get(ctx).allSessions().firstOrNull()?.fileDescriptor
    }
}
