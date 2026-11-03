package ahk.unitool.gsm.shell

import ahk.unitool.gsm.native.NativeBridge
import ahk.unitool.gsm.termux.TermuxEnv
import ahk.unitool.gsm.usb.UsbTransport
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real PTY-backed Termux shell. Passes USB_FD in environment for native tools.
 */
class PtySession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var masterFd = -1
    private var pid = -1
    private val alive = AtomicBoolean(false)
    private val pool = Executors.newSingleThreadExecutor()

    fun start(usbFd: Int? = null, rows: Int = 24, cols: Int = 80) {
        stop()
        if (!NativeBridge.available) {
            onOutput("[PTY unavailable — libunitool.so missing, use ShellSession fallback]\n")
            onExit(-1)
            return
        }
        TermuxEnv.install(context)
        val (cmd, args) = TermuxEnv.shellCommand(context)
        val argv = arrayOf(cmd) + args
        val env = TermuxEnv.buildEnv(context, usbFd).toMutableList()
        // Expose FD number for scripts
        if (usbFd != null && usbFd >= 0) {
            env += "USB_FD=$usbFd"
            // Optional: dup kept in parent only; child sees same number if we don't close
        }
        val result = NativeBridge.nativePtyStart(
            argv,
            env.toTypedArray(),
            TermuxEnv.home.absolutePath,
        )
        if (result == null || result.size < 2) {
            onOutput("[pty start failed]\n")
            onExit(-1)
            return
        }
        masterFd = result[0]
        pid = result[1]
        NativeBridge.nativePtySetSize(masterFd, rows, cols)
        alive.set(true)
        onOutput("PTY shell pid=$pid master=$masterFd USB_FD=${usbFd ?: "-"}\n")

        pool.execute {
            val buf = ByteArray(4096)
            while (alive.get()) {
                val n = NativeBridge.nativePtyRead(masterFd, buf)
                when {
                    n > 0 -> onOutput(String(buf, 0, n, Charsets.UTF_8))
                    n == 0 -> try { Thread.sleep(20) } catch (_: Exception) {}
                    else -> {
                        Log.w(TAG, "pty read error $n")
                        break
                    }
                }
            }
            alive.set(false)
            onExit(0)
        }
    }

    fun write(text: String) {
        if (masterFd < 0 || !alive.get()) return
        val data = text.toByteArray(Charsets.UTF_8)
        NativeBridge.nativePtyWrite(masterFd, data, data.size)
    }

    fun setSize(rows: Int, cols: Int) {
        if (masterFd >= 0) NativeBridge.nativePtySetSize(masterFd, rows, cols)
    }

    fun stop() {
        alive.set(false)
        if (masterFd >= 0 || pid > 0) {
            NativeBridge.nativePtyClose(masterFd, pid)
        }
        masterFd = -1
        pid = -1
    }

    companion object {
        private const val TAG = "PtySession"
        fun currentUsbFd(ctx: Context): Int? =
            UsbTransport.get(ctx).allSessions().firstOrNull()?.fileDescriptor
    }
}
