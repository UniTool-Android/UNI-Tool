package ahk.unitool.gsm.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Single USB Host transport (no root).
 *
 * list → requestPermission → openDevice → claimInterface → bulk/control.
 * FD available via [Session.fileDescriptor] for libusb_wrap_sys_device.
 */
class UsbTransport private constructor(private val app: Context) {

    private val mgr = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sessions = ConcurrentHashMap<String, Session>()

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            device?.let {
                Log.i(TAG, "USB detached: ${it.deviceName}; closing session")
                close(it.deviceName)
            }
        }
    }

    init {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(detachReceiver, filter)
        }
    }

    data class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val epIn: UsbEndpoint?,
        val epOut: UsbEndpoint?,
    ) {
        val fileDescriptor: Int get() = connection.fileDescriptor
        val deviceName: String get() = device.deviceName
        val vendorId: Int get() = device.vendorId
        val productId: Int get() = device.productId
    }

    fun devices(): List<UsbDevice> = mgr.deviceList.values.toList()

    fun hasPermission(device: UsbDevice): Boolean = mgr.hasPermission(device)

    fun requestPermission(device: UsbDevice, onResult: ((Boolean) -> Unit)? = null) {
        if (hasPermission(device)) {
            onResult?.invoke(true)
            return
        }
        val action = ACTION_PERMISSION
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, device.deviceId, Intent(action), flags)
        if (onResult != null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    try { app.unregisterReceiver(this) } catch (_: Exception) {}
                    val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true
                    onResult(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        }
        mgr.requestPermission(device, pi)
    }

    /** Open + claim first bulk interface. Keeps connection alive. */
    @Synchronized
    fun open(device: UsbDevice, forceClaim: Boolean = true): Session? {
        sessions[device.deviceName]?.let { return it }
        if (!hasPermission(device)) {
            Log.w(TAG, "no permission ${device.deviceName}")
            return null
        }
        val conn = mgr.openDevice(device) ?: run {
            Log.e(TAG, "openDevice failed ${device.deviceName}")
            return null
        }

        var claimed: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null || outEp != null) {
                if (!conn.claimInterface(intf, forceClaim)) continue
                claimed = intf
                epIn = inEp
                epOut = outEp
                break
            }
        }
        if (claimed == null && device.interfaceCount > 0) {
            val intf = device.getInterface(0)
            if (conn.claimInterface(intf, forceClaim)) claimed = intf
        }
        if (claimed == null) {
            conn.close()
            Log.e(TAG, "no interface claimed ${device.deviceName}")
            return null
        }

        val session = Session(device, conn, claimed, epIn, epOut)
        sessions[device.deviceName] = session
        Log.i(
            TAG,
            "open ${device.deviceName} vid=${device.vendorId.toString(16)} " +
                "pid=${device.productId.toString(16)} fd=${session.fileDescriptor} " +
                "in=${epIn?.address} out=${epOut?.address}"
        )
        return session
    }

    fun bulkIn(session: Session, maxLen: Int, timeoutMs: Int = 5000): ByteArray? {
        val ep = session.epIn ?: return null
        val buf = ByteArray(maxLen)
        val n = session.connection.bulkTransfer(ep, buf, maxLen, timeoutMs)
        return if (n > 0) buf.copyOf(n) else null
    }

    fun bulkOut(session: Session, data: ByteArray, timeoutMs: Int = 5000): Int {
        val ep = session.epOut ?: return -1
        return session.connection.bulkTransfer(ep, data, data.size, timeoutMs)
    }

    fun control(
        session: Session,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        timeoutMs: Int = 5000,
    ): Int = session.connection.controlTransfer(
        requestType, request, value, index, buffer, buffer?.size ?: 0, timeoutMs
    )

    fun session(deviceName: String): Session? = sessions[deviceName]

    fun allSessions(): List<Session> = sessions.values.toList()

    @Synchronized
    fun close(deviceName: String) {
        sessions.remove(deviceName)?.let { s ->
            try { s.connection.releaseInterface(s.usbInterface) } catch (_: Exception) {}
            try { s.connection.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    companion object {
        private const val TAG = "UsbTransport"
        const val ACTION_PERMISSION = "ahk.unitool.gsm.USB_PERMISSION"

        @Volatile private var inst: UsbTransport? = null

        fun get(context: Context): UsbTransport =
            inst ?: synchronized(this) {
                inst ?: UsbTransport(context.applicationContext).also { inst = it }
            }
    }
}
