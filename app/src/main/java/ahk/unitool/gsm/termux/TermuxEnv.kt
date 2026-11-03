package ahk.unitool.gsm.termux

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Official Termux aarch64 bootstrap (bash, apt/pkg, coreutils, …).
 *
 * Termux binaries hardcode `/data/data/com.termux/files`. We extract into our
 * [Context.getFilesDir] and run under a thin proot bind so those paths resolve
 * without root and without Ubuntu.
 *
 * USB tools must use [ahk.unitool.gsm.usb.UsbTransport] + `USB_FD`, not stock
 * `/dev/bus/usb` or unpatched `adb devices`.
 */
object TermuxEnv {

    private const val TAG = "TermuxEnv"
    private const val ASSET = "termux/bootstrap-aarch64.zip"
    private const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    private const val STAMP = "bootstrap-2026.11.01-r1"
    const val FAKE_FILES = "/data/data/com.termux/files"

    lateinit var filesDir: File
        private set
    lateinit var prefix: File
        private set
    lateinit var home: File
        private set

    @Volatile var ready: Boolean = false
        private set

    fun isReady(ctx: Context): Boolean {
        ensure(ctx)
        return ready || File(prefix, "bin/bash").exists()
    }

    @Synchronized
    fun install(ctx: Context): Boolean {
        ensure(ctx)
        val marker = File(filesDir, ".$STAMP")
        if (marker.exists() && File(prefix, "bin/bash").exists()) {
            installProot(ctx)
            ready = true
            return true
        }
        Log.i(TAG, "extracting Termux bootstrap → $prefix")
        try {
            openBootstrapStream(ctx).use { input ->
                ZipInputStream(input).use { zis ->
                    val buf = ByteArray(64 * 1024)
                    var entry = zis.nextEntry
                    var symFile: File? = null
                    while (entry != null) {
                        val name = entry.name
                        if (name == "SYMLINKS.txt") {
                            symFile = File(filesDir, "SYMLINKS.txt")
                            FileOutputStream(symFile).use { out ->
                                var n: Int
                                while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                            }
                        } else {
                            val out = File(prefix, name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { o ->
                                    var n: Int
                                    while (zis.read(buf).also { n = it } > 0) o.write(buf, 0, n)
                                }
                                out.setExecutable(true, false)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    symFile?.let { applySymlinks(it) }
                }
            }
            installProot(ctx)
            marker.writeText(STAMP)
            ready = true
            Log.i(TAG, "Termux ready (pkg/apt)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap failed: ${e.message}", e)
            ready = false
            return false
        }
    }

    private fun ensure(ctx: Context) {
        filesDir = ctx.filesDir
        prefix = File(filesDir, "usr")
        home = File(filesDir, "home")
        listOf(prefix, home, File(prefix, "bin"), File(prefix, "tmp"), File(prefix, "var")).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }

    private fun applySymlinks(sym: File) {
        sym.readLines().forEach { line ->
            val p = line.split("←", limit = 2)
            if (p.size != 2) return@forEach
            val targetName = p[0].trim()
            val linkRel = p[1].trim().removePrefix("./")
            val link = File(prefix, linkRel)
            try {
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete()
                Os.symlink(targetName, link.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "symlink $linkRel: ${e.message}")
            }
        }
    }


    private fun openBootstrapStream(ctx: Context): java.io.InputStream {
        return try {
            ctx.assets.open(ASSET)
        } catch (e: Exception) {
            Log.i(TAG, "asset missing, downloading $BOOTSTRAP_URL")
            val url = java.net.URL(BOOTSTRAP_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code downloading bootstrap")
            }
            conn.inputStream
        }
    }

    private fun installProot(ctx: Context) {
        val proot = File(filesDir, "proot")
        val talloc = File(filesDir, "libtalloc.so.2")
        if (!proot.exists()) {
            try {
                ctx.assets.open("termux/proot-arm64-v8a").use { i ->
                    FileOutputStream(proot).use { o -> i.copyTo(o) }
                }
                proot.setExecutable(true, false)
            } catch (e: Exception) {
                Log.w(TAG, "proot missing: ${e.message}")
            }
        }
        if (!talloc.exists()) {
            try {
                ctx.assets.open("termux/libtalloc.so.2").use { i ->
                    FileOutputStream(talloc).use { o -> i.copyTo(o) }
                }
            } catch (_: Exception) {}
        }
    }

    /** argv: executable + args for interactive login shell. */
    fun shellCommand(ctx: Context): Pair<String, Array<String>> {
        install(ctx)
        val bash = File(prefix, "bin/bash")
        val proot = File(filesDir, "proot")
        return if (proot.canExecute() && bash.exists()) {
            proot.absolutePath to arrayOf(
                "-b", "${filesDir.absolutePath}:$FAKE_FILES",
                "-w", "$FAKE_FILES/home",
                "$FAKE_FILES/usr/bin/bash",
                "--login",
            )
        } else if (bash.exists()) {
            bash.absolutePath to arrayOf("--login")
        } else {
            "/system/bin/sh" to emptyArray()
        }
    }

    fun buildEnv(ctx: Context, usbFd: Int? = null): Array<String> {
        ensure(ctx)
        val list = mutableListOf(
            "HOME=${home.absolutePath}",
            "PREFIX=${prefix.absolutePath}",
            "TMPDIR=${prefix.absolutePath}/tmp",
            "PATH=${prefix.absolutePath}/bin:/system/bin",
            "LD_LIBRARY_PATH=${prefix.absolutePath}/lib:${filesDir.absolutePath}",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "EXTERNAL_STORAGE=/sdcard",
            "UNITOOL=1",
        )
        if (usbFd != null && usbFd >= 0) {
            list += "USB_FD=$usbFd"
            list += "ADBIFY_USB_FD=$usbFd"
        }
        return list.toTypedArray()
    }
}
