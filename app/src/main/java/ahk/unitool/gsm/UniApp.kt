package ahk.unitool.gsm

import android.app.Application
import ahk.unitool.gsm.shell.ShellRuntime
import ahk.unitool.gsm.usb.UsbTransport

class UniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UsbTransport.get(this)
        ShellRuntime.get(this)
    }
}
