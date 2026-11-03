package ahk.unitool.gsm

import android.app.Application
import android.util.Log
import ahk.unitool.gsm.termux.TermuxEnv
import ahk.unitool.gsm.usb.UsbTransport
import kotlin.concurrent.thread

class UniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UsbTransport.get(this)
        // Install Termux bootstrap off UI thread (first launch ~30s for extract)
        thread(name = "termux-bootstrap") {
            try {
                val ok = TermuxEnv.install(this)
                Log.i("UniApp", "Termux bootstrap ok=$ok")
            } catch (e: Exception) {
                Log.e("UniApp", "Termux bootstrap: ${e.message}", e)
            }
        }
    }
}
