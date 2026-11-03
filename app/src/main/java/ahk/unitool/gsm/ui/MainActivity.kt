package ahk.unitool.gsm.ui

import ahk.unitool.gsm.R
import ahk.unitool.gsm.native.NativeBridge
import ahk.unitool.gsm.protocol.adb.AdbHost
import ahk.unitool.gsm.protocol.fastboot.FastbootClient
import ahk.unitool.gsm.protocol.mtk.MtkBrom
import ahk.unitool.gsm.shell.ShellRuntime
import ahk.unitool.gsm.usb.UsbTransport
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.adbify.terminal.TerminalContextProvider
import com.adbify.terminal.TerminalSession
import com.adbify.terminal.TerminalSessionClient
import com.adbify.terminal.view.TerminalView
import com.adbify.terminal.view.TerminalViewClient
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Uni Tool terminal screen using Adbify's native PTY-backed terminal implementation.
 * The terminal emulator, renderer, IME connection and PTY JNI layer are supplied by
 * the pinned Adbify terminalview module; UniTool retains its own USB/protocol controls.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var transport: UsbTransport
    private lateinit var toolbar: MaterialToolbar
    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var deviceSpinner: Spinner
    private var devices: List<UsbDevice> = emptyList()
    private var terminalSession: TerminalSession? = null
    private var ctrlPressed = false
    private var altPressed = false
    private var terminalFontSizeDp = 12f

    private val terminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            runOnUiThread { terminalView.invalidate() }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            runOnUiThread {
                toolbar.subtitle = changedSession.title ?: toolbar.subtitle
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            runOnUiThread { append("Shell exited") }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)
                ?.coerceToText(this@MainActivity)?.toString().orEmpty()
            if (text.isNotEmpty()) writeTerminal(text)
        }

        override fun onBell(session: TerminalSession) {
            terminalView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onColorsChanged(session: TerminalSession) {
            runOnUiThread { terminalView.invalidate() }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            runOnUiThread { terminalView.invalidate() }
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            Log.i("UniToolTerminal", "PTY shell pid=$pid")
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
    }

    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale > 1.08f) {
                terminalFontSizeDp = (terminalFontSizeDp + 1f).coerceAtMost(22f)
                applyTerminalFontSize()
                return 1f
            }
            if (scale < 0.92f) {
                terminalFontSizeDp = (terminalFontSizeDp - 1f).coerceAtLeast(8f)
                applyTerminalFontSize()
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) {
            terminalView.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = ctrlPressed
        override fun readAltKey() = altPressed
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() { runOnUiThread { terminalView.invalidate() } }
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // Adbify's JNI fallback loader resolves its application Context through this provider.
        TerminalContextProvider.init(applicationContext)

        setContentView(R.layout.activity_main)
        applyWindowInsets()
        transport = UsbTransport.get(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        terminalView = findViewById(R.id.terminalView)
        statusText = findViewById(R.id.statusText)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        terminalView.setTerminalViewClient(terminalViewClient)
        applyTerminalFontSize()
        terminalView.setKeepScreenOn(true)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.btnPermission).setOnClickListener { requestSelectedPermission() }
        findViewById<Button>(R.id.btnOpen).setOnClickListener { openSelected() }
        findViewById<Button>(R.id.btnFastboot).setOnClickListener { runFastbootProbe() }
        findViewById<Button>(R.id.btnAdb).setOnClickListener { runAdbProbe() }
        findViewById<Button>(R.id.btnMtk).setOnClickListener { runMtkProbe() }
        findViewById<Button>(R.id.btnNativeUsb).setOnClickListener { runNativeUsbTest() }
        findViewById<Button>(R.id.btnShell).setOnClickListener { restartTerminal() }

        bindTerminalKey(R.id.keyEsc, "\u001b")
        bindTerminalKey(R.id.keyTab, "\t")
        bindTerminalKey(R.id.keyHome, "\u001b[H")
        bindTerminalKey(R.id.keyEnd, "\u001b[F")
        bindTerminalKey(R.id.keyUp, "\u001b[A")
        bindTerminalKey(R.id.keyDown, "\u001b[B")
        bindTerminalKey(R.id.keyLeft, "\u001b[D")
        bindTerminalKey(R.id.keyRight, "\u001b[C")
        bindTerminalKey(R.id.keyMinus, "-")
        bindTerminalKey(R.id.keyPipe, "|")
        bindTerminalKey(R.id.keySlash, "/")
        findViewById<Button>(R.id.keyCtrl).setOnClickListener {
            ctrlPressed = !ctrlPressed
            it.isSelected = ctrlPressed
        }
        findViewById<Button>(R.id.keyAlt).setOnClickListener {
            altPressed = !altPressed
            it.isSelected = altPressed
        }

        append("Adbify terminal initializing")
        append("Native bridge: " + NativeBridge.selfTest())
        updateTitleBar(null)
        refreshDevices()
        startTerminal()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroy() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        super.onDestroy()
    }

    /** Keep the UniTool toolbar clear of system bars and the extra-key row above the IME. */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** TerminalRenderer uses Paint.setTextSize(), so convert the Adbify dp preference to px. */
    private fun applyTerminalFontSize() {
        val px = (terminalFontSizeDp * resources.displayMetrics.density + 0.5f).toInt()
        terminalView.setTextSize(px)
    }

    private fun startTerminal() {
        val runtime = ShellRuntime.get(this)
        val command = runtime.shellCommand()
        val usbFd = transport.allSessions().firstOrNull()?.fileDescriptor
        val session = TerminalSession(
            command.first,
            runtime.home.absolutePath,
            command.second,
            runtime.environment(usbFd),
            2000,
            terminalSessionClient
        )
        terminalSession = session
        terminalView.attachSession(session)
        terminalView.requestFocus()
        terminalView.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }, 350)
        append("Adbify PTY terminal ready")
    }

    private fun restartTerminal() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        startTerminal()
    }

    private fun writeTerminal(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        terminalSession?.write(bytes, 0, bytes.size)
        terminalView.requestFocus()
    }

    private fun bindTerminalKey(viewId: Int, sequence: String) {
        findViewById<Button>(viewId).setOnClickListener { writeTerminal(sequence) }
    }

    private fun append(message: String) {
        statusText.text = message.replace('\n', ' ').takeLast(180)
    }

    private fun updateTitleBar(device: UsbDevice?) {
        toolbar.title = "Uni Tool"
        if (device == null) {
            val open = transport.allSessions().firstOrNull()
            toolbar.subtitle = when {
                open != null -> "%04x:%04x  %s".format(open.vendorId, open.productId, open.deviceName)
                devices.isNotEmpty() -> {
                    val d = devices.first()
                    val perm = if (transport.hasPermission(d)) "OK" else "NEED_PERM"
                    "%04x:%04x  %s  [%s]".format(d.vendorId, d.productId, d.deviceName, perm)
                }
                else -> "No USB device"
            }
            return
        }
        val perm = if (transport.hasPermission(device)) "OK" else "NEED_PERM"
        toolbar.subtitle = "%04x:%04x  %s  [%s]".format(device.vendorId, device.productId, device.deviceName, perm)
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
    }

    private fun selectedDevice(): UsbDevice? = devices.getOrNull(deviceSpinner.selectedItemPosition)

    private fun requestSelectedPermission() {
        val d = selectedDevice() ?: return append("No USB device")
        transport.requestPermission(d) { granted ->
            runOnUiThread {
                append("USB permission: " + granted)
                refreshDevices()
            }
        }
    }

    private fun openSelected() {
        val d = selectedDevice() ?: return append("No USB device")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
            if (session == null) append("USB open failed (permission required)")
            else {
                append("USB opened · fd=" + session.fileDescriptor)
                updateTitleBar(d)
            }
        }
    }

    private fun runFastbootProbe() {
        val d = selectedDevice() ?: return append("No USB device")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("Fastboot open failed")
            val result = withContext(Dispatchers.IO) {
                FastbootClient(transport, session).getVar("product")
            }
            append("Fastboot: " + result.status + " " + result.data)
        }
    }

    private fun runAdbProbe() {
        val d = selectedDevice() ?: return append("No USB device")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("ADB open failed")
            val host = AdbHost(transport, session)
            val connected = withContext(Dispatchers.IO) { host.connect() }
            append("ADB protocol CNXN: " + connected + " · shell adb CLI is not bundled")
            if (connected) {
                val output = withContext(Dispatchers.IO) { host.shell("echo unitool") }
                output?.let { append("ADB shell: " + it.trim()) }
            }
        }
    }

    private fun runMtkProbe() {
        val d = selectedDevice() ?: return append("No USB device")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("MTK open failed")
            val result = withContext(Dispatchers.IO) { MtkBrom(transport, session).probe() }
            append("MTK probe: ok=" + result.ok + " " + result.message)
        }
    }

    private fun runNativeUsbTest() {
        val d = selectedDevice() ?: return append("No USB device")
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { transport.open(d) }
                ?: return@launch append("USB open failed")
            if (!NativeBridge.available) return@launch append("Native USB bridge unavailable")
            val out = session.epOut?.address ?: -1
            val input = session.epIn?.address ?: -1
            append("USB native test · out=0x" + out.toString(16) + " in=0x" + input.toString(16))
        }
    }
}
