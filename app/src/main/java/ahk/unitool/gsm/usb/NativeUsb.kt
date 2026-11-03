package ahk.unitool.gsm.usb

import ahk.unitool.gsm.native.NativeBridge
import android.util.Log

/**
 * USB bulk/control via native USBDEVFS on the Host-API FD.
 * Java [UsbTransport] must keep [UsbDeviceConnection] open.
 */
object NativeUsb {
    private const val TAG = "NativeUsb"

    fun bulkOutPreferNative(
        transport: UsbTransport,
        session: UsbTransport.Session,
        data: ByteArray,
        timeoutMs: Int = 5000,
    ): Int {
        val ep = session.epOut?.address
        if (NativeBridge.available && ep != null) {
            val n = NativeBridge.usbBulk(session.fileDescriptor, ep, data, timeoutMs)
            if (n >= 0) {
                Log.d(TAG, "native bulkOut ep=0x${ep.toString(16)} len=${data.size} → $n")
                return n
            }
            Log.w(TAG, "native bulkOut failed $n, fallback Java")
        }
        return transport.bulkOut(session, data, timeoutMs)
    }

    fun bulkInPreferNative(
        transport: UsbTransport,
        session: UsbTransport.Session,
        maxLen: Int,
        timeoutMs: Int = 5000,
    ): ByteArray? {
        val ep = session.epIn?.address
        if (NativeBridge.available && ep != null) {
            val r = NativeBridge.usbBulkIn(session.fileDescriptor, ep, maxLen, timeoutMs)
            if (r != null) {
                Log.d(TAG, "native bulkIn ep=0x${ep.toString(16)} → ${r.size}")
                return r
            }
        }
        return transport.bulkIn(session, maxLen, timeoutMs)
    }
}
