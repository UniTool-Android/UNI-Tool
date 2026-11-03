package ahk.unitool.gsm.ui

import ahk.unitool.gsm.R
import ahk.unitool.gsm.native.NativeBridge
import ahk.unitool.gsm.protocol.adb.AdbHost
import ahk.unitool.gsm.protocol.fastboot.FastbootClient
import ahk.unitool.gsm.protocol.mtk.MtkBrom
import ahk.unitool.gsm.shell.PtySession
import ahk.unitool.gsm.shell.ShellSession
import ahk.unitool.gsm.termux.TermuxEnv
import ahk.unitool.gsm.usb.UsbTransport
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Terminal-first UI: Termux shell by default, USB device info on the title bar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var transport: UsbTransport
    private lateinit var toolbar: MaterialToolbar
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var deviceSpinner: Spinner

    private var devices: List<UsbDevice> = emptyList()
    private var shell: ShellSession? = null
    private var pty: PtySession? = null
    private var shellStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transport = UsbTransport.get(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        logView = findViewById(R.id.logView)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.btnPermission).setOnClickListener { requestSelectedPermission() }
        findViewById<Button>(R.id.btnOpen).setOnClickListener { openSelected() }
        findViewById<Button>(R.id.btnFastboot).setOnClickListener { runFastbootProbe() }
        findViewById<Button>(R.id.btnAdb).setOnClickListener { runAdbProbe() }
        findViewById<Button>(R.id.btnMtk).setOnClickListener { runMtkProbe() }
        findViewById<Button>(R.id.btnNativeUsb).setOnClickListener { runNativeUsbTest() }
        findViewById<Button>(R.id.btnShell).setOnClickListener { startShell(force = true) }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendLine() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendLine()
                true
            } else false
        }

        append("Uni Tool — Termux shell + USB Host\n")
        append("native=${NativeBridge.selfTest()}\n")
        updateTitleBar(null)

        lifecycleScope.launch {
            append("Installing Termux bootstrap (first run may take a minute)…\n")
            val ok = withContext(Dispatchers.IO) { TermuxEnv.install(this@MainActivity) }
            append(if (ok) "Termux ready\n" else "Termux install failed — missing bootstrap asset\n")
            refreshDevices()
            startShell(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroy() {
        shell?.stop()
        pty?.stop()
        super.onDestroy()
    }

    private fun sendLine() {
        val line = input.text?.toString().orEmpty()
        if (line.isEmpty()) return
        append("$ $line\n")
        if (pty != null) pty?.write(line + "\n") else shell?.write(line)
        input.setText("")
    }

    private fun updateTitleBar(device: UsbDevice?) {
        toolbar.title = "Uni Tool"
        if (device == null) {
            val open = transport.allSessions().firstOrNull()
            if (open != null) {
                toolbar.subtitle = "%04x:%04x  fd=%d  %s".format(
                    open.vendorId, open.productId, open.fileDescriptor, open.deviceName
                )
            } else if (devices.isNotEmpty()) {
                val d = devices.first()
                val perm = if (transport.hasPermission(d)) "OK" else "NEED_PERM"
                toolbar.subtitle = "%04x:%04x  %s  [%s]".format(d.vendorId, d.productId, d.deviceName, perm)
            } else {
                toolbar.subtitle = "No USB device"
            }
            return
        }
        val perm = if (transport.hasPermission(device)) "OK" else "NEED_PERM"
        val session = transport.session(device.deviceName)
        toolbar.subtitle = if (session != null) {
            "%04x:%04x  fd=%d  [%s]".format(device.vendorId, device.productId, session.fileDescriptor, perm)
        } else {
            "%04x:%04x  %s  [%s]".format(device.vendorId, device.productId, device.deviceName, perm)
        }
    }

    private fun refreshDevices() {
        devices = transport.devices()
        val labels = devices.map { d ->
            val perm = if (transport.hasPermission(d)) "OK" else "NEED_PERM"
            "%04x:%04x %s [%s]".format(d.vendorId, d.productId, d.deviceName, perm)
        }
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf("(no USB devices)") else labels
        )
        deviceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateTitleBar(devices.getOrNull(position))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateTitleBar(null)
            }
        }
        updateTitleBar(devices.firstOrNull())
        if (devices.isNotEmpty()) {
            append("USB devices: ${devices.size}\n")
            labels.forEach { append("  $it\n") }
        }
    }

    private fun selectedDevice(): UsbDevice? =
        devices.getOrNull(deviceSpinner.selectedItemPosition)

    private fun requestSelectedPermission() {
        val d = selectedDevice() ?: return append("No device\n")
        transport.requestPermission(d) { granted ->
            runOnUiThread {
                append("permission ${d.deviceName}: $granted\n")
                refreshDevices()
            }
        }
    }

    private fun openSelected() {
        val d = selectedDevice() ?: return append("No device\n")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
            if (session == null) append("open failed (permission?)\n")
            else {
                append("opened fd=${session.fileDescriptor} in=${session.epIn?.address} out=${session.epOut?.address}\n")
                updateTitleBar(d)
                startShell(force = true)
            }
        }
    }

    private fun runFastbootProbe() {
        val d = selectedDevice() ?: return append("No device\n")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("open failed\n")
            updateTitleBar(d)
            val client = FastbootClient(transport, session)
            val r = withContext(Dispatchers.IO) { client.getVar("product") }
            append("fastboot getvar:product → ${r.status} ${r.data}\n")
        }
    }

    private fun runAdbProbe() {
        val d = selectedDevice() ?: return append("No device\n")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("open failed\n")
            updateTitleBar(d)
            val host = AdbHost(transport, session)
            val ok = withContext(Dispatchers.IO) { host.connect() }
            append("adb CNXN → $ok\n")
            if (ok) {
                val out = withContext(Dispatchers.IO) { host.shell("echo unitool") }
                append("adb shell → ${out?.trim()}\n")
            }
        }
    }

    private fun runMtkProbe() {
        val d = selectedDevice() ?: return append("No device\n")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("open failed\n")
            updateTitleBar(d)
            val mtk = MtkBrom(transport, session)
            val r = withContext(Dispatchers.IO) { mtk.probe() }
            append("mtk probe → ok=${r.ok} ${r.message}\n")
        }
    }

    private fun runNativeUsbTest() {
        val d = selectedDevice() ?: return append("No device\n")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("open failed\n")
            updateTitleBar(d)
            append("native test fd=${session.fileDescriptor} available=${NativeBridge.available}\n")
            if (!NativeBridge.available) return@launch
            val epOut = session.epOut?.address ?: -1
            val epIn = session.epIn?.address ?: -1
            append("endpoints out=0x${epOut.toString(16)} in=0x${epIn.toString(16)}\n")
            if (epOut >= 0) {
                val n = withContext(Dispatchers.IO) {
                    NativeBridge.usbBulk(session.fileDescriptor, epOut, byteArrayOf(0), 500)
                }
                append("native bulkOut probe → $n\n")
            }
            if (epIn >= 0) {
                val r = withContext(Dispatchers.IO) {
                    NativeBridge.usbBulkIn(session.fileDescriptor, epIn, 64, 500)
                }
                append("native bulkIn → ${r?.size ?: "null"}\n")
            }
        }
    }

    private fun startShell(force: Boolean) {
        if (shellStarted && !force) return
        shell?.stop()
        pty?.stop()
        shellStarted = true
        val fd = PtySession.currentUsbFd(this) ?: ShellSession.currentUsbFd(this)
        append("starting Termux shell USB_FD=${fd ?: "-"} native=${NativeBridge.available}…\n")
        lifecycleScope.launch(Dispatchers.IO) {
            TermuxEnv.install(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (NativeBridge.available) {
                    pty = PtySession(
                        this@MainActivity,
                        onOutput = { s -> runOnUiThread { append(s) } },
                        onExit = { code ->
                            runOnUiThread {
                                append("\n[pty exit $code]\n")
                                shellStarted = false
                            }
                        },
                    )
                    pty?.start(fd)
                } else {
                    shell = ShellSession(
                        this@MainActivity,
                        onOutput = { s -> runOnUiThread { append(s) } },
                        onExit = { code ->
                            runOnUiThread {
                                append("\n[shell exit $code]\n")
                                shellStarted = false
                            }
                        },
                    )
                    shell?.start(fd)
                }
            }
        }
    }

    private fun append(s: String) {
        logView.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
