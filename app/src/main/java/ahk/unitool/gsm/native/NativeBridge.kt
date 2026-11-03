package ahk.unitool.gsm.native

import android.util.Log

/**
 * JNI facade: PTY + USBDEVFS on Host-API file descriptors.
 * Load once; [available] is false if the .so is missing.
 */
object NativeBridge {

    private const val TAG = "NativeBridge"

    val available: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("unitool")
            ok = true
            Log.i(TAG, selfTest())
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libunitool.so not loaded: ${e.message}")
        }
        available = ok
    }

    external fun nativeSelfTest(): String
    external fun nativeUsbBulk(fd: Int, endpoint: Int, data: ByteArray, length: Int, timeoutMs: Int): Int
    external fun nativeUsbControl(
        fd: Int, requestType: Int, request: Int, value: Int, index: Int,
        data: ByteArray?, length: Int, timeoutMs: Int
    ): Int
    external fun nativeUsbClaim(fd: Int, interfaceNum: Int): Int
    external fun nativeUsbRelease(fd: Int, interfaceNum: Int): Int
    external fun nativeUsbClearHalt(fd: Int, endpoint: Int): Int
    external fun nativeUsbReset(fd: Int): Int

    /** @return intArrayOf(masterFd, pid) or null */
    external fun nativePtyStart(argv: Array<String>, env: Array<String>, cwd: String?): IntArray?
    external fun nativePtyRead(masterFd: Int, buf: ByteArray): Int
    external fun nativePtyWrite(masterFd: Int, buf: ByteArray, length: Int): Int
    external fun nativePtySetSize(masterFd: Int, rows: Int, cols: Int)
    external fun nativePtyClose(masterFd: Int, pid: Int)
    external fun nativeDupFd(fd: Int): Int

    fun selfTest(): String = if (available) nativeSelfTest() else "native unavailable"

    fun usbBulk(fd: Int, endpoint: Int, data: ByteArray, timeoutMs: Int = 5000): Int {
        if (!available) return -1
        return nativeUsbBulk(fd, endpoint, data, data.size, timeoutMs)
    }

    fun usbBulkIn(fd: Int, endpoint: Int, maxLen: Int, timeoutMs: Int = 5000): ByteArray? {
        if (!available) return null
        val buf = ByteArray(maxLen)
        val n = nativeUsbBulk(fd, endpoint, buf, maxLen, timeoutMs)
        return if (n > 0) buf.copyOf(n) else null
    }
}
