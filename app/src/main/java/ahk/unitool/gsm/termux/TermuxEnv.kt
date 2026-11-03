package ahk.unitool.gsm.termux

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Termux environment for applicationId [ahk.unitool.gsm].
 *
 * Correct model (termux-generator / VHEditor):
 *  - Bootstrap + packages built with package name ahk.unitool.gsm
 *  - PREFIX = <dataDir>/files/usr  (no com.termux paths, no proot bind)
 *
 * Fallback:
 *  - Stock Termux bootstrap (com.termux hardcoded paths) + thin proot bind
 *  - Used only until a custom bootstrap is shipped/downloaded
 *
 * Custom bootstrap URL can be set via BuildConfig or default GitHub release.
 */
object TermuxEnv {

    private const val TAG = "TermuxEnv"
    private const val ASSET = "termux/bootstrap-aarch64.zip"
    /** Official stock bootstrap (requires proot path bind). */
    private const val STOCK_BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    /**
     * Preferred: bootstrap rebuilt for ahk.unitool.gsm (termux-generator CI artifact / release).
     * Falls back to stock if this 404s.
     */
    private const val CUSTOM_BOOTSTRAP_URL =
        "https://github.com/UniTool-Android/UNI-Tool/releases/download/termux-bootstrap/bootstrap-aarch64.zip"
    private const val STAMP_CUSTOM = "bootstrap-custom-ahk.unitool.gsm"
    private const val STAMP_STOCK = "bootstrap-stock-2026.11.01-r1"
    /** Only for stock bootstrap RPATH compatibility. */
    private const val STOCK_FAKE_FILES = "/data/data/com.termux/files"

    lateinit var filesDir: File
        private set
    lateinit var prefix: File
        private set
    lateinit var home: File
        private set

    /** True when bootstrap was built for this app id (native paths, no proot). */
    @Volatile var nativePrefix: Boolean = false
        private set

    @Volatile var ready: Boolean = false
        private set

    fun isReady(ctx: Context): Boolean {
        ensure(ctx)
        return ready || File(prefix, "bin/bash").canExecute()
    }

    @Synchronized
    fun install(ctx: Context): Boolean {
        ensure(ctx)
        val customMarker = File(filesDir, ".$STAMP_CUSTOM")
        val stockMarker = File(filesDir, ".$STAMP_STOCK")
        if (File(prefix, "bin/bash").exists()) {
            nativePrefix = customMarker.exists()
            if (!nativePrefix) installProot(ctx)
            ready = true
            Log.i(TAG, "Termux already installed nativePrefix=$nativePrefix")
            return true
        }

        Log.i(TAG, "extracting Termux bootstrap → $prefix")
        return try {
            val (stream, isCustom) = openBootstrapStream(ctx)
            stream.use { input ->
                extractZip(input)
            }
            if (isCustom) {
                customMarker.writeText(STAMP_CUSTOM)
                stockMarker.delete()
                nativePrefix = true
                Log.i(TAG, "Custom bootstrap for ahk.unitool.gsm — native PREFIX, no proot")
            } else {
                stockMarker.writeText(STAMP_STOCK)
                customMarker.delete()
                nativePrefix = false
                installProot(ctx)
                Log.i(TAG, "Stock bootstrap — proot path bind enabled")
            }
            ready = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap failed: ${e.message}", e)
            ready = false
            false
        }
    }

    private fun extractZip(input: InputStream) {
        ZipInputStream(input).use { zis ->
            val buf = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            var symFile: File? = null
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw SecurityException("Unsafe bootstrap ZIP entry: $name")
                }
                if (name == "SYMLINKS.txt") {
                    symFile = File(filesDir, "SYMLINKS.txt")
                    FileOutputStream(symFile).use { out ->
                        var n: Int
                        while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                    }
                } else {
                    val out = File(prefix, name).canonicalFile
                    val root = prefix.canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw SecurityException("Bootstrap ZIP entry escapes PREFIX: $name")
                    }
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

    /**
     * Prefer: asset → custom release (this app id) → stock Termux release.
     * @return stream to zip and whether it is a custom (native prefix) bootstrap
     */
    private fun openBootstrapStream(ctx: Context): Pair<InputStream, Boolean> {
        try {
            val asset = ctx.assets.open(ASSET)
            // Asset may be custom or stock; detect via marker file next to zip if present
            val meta = try {
                ctx.assets.open("termux/bootstrap.meta").bufferedReader().readText().trim()
            } catch (_: Exception) {
                ""
            }
            val custom = meta.contains("ahk.unitool.gsm") || meta.contains("custom")
            Log.i(TAG, "using asset bootstrap custom=$custom")
            return asset to custom
        } catch (_: Exception) {
            // fall through
        }
        // Custom release first
        try {
            Log.i(TAG, "trying custom bootstrap $CUSTOM_BOOTSTRAP_URL")
            return httpStream(CUSTOM_BOOTSTRAP_URL) to true
        } catch (e: Exception) {
            Log.w(TAG, "custom bootstrap unavailable: ${e.message}")
        }
        Log.i(TAG, "downloading stock bootstrap $STOCK_BOOTSTRAP_URL")
        return httpStream(STOCK_BOOTSTRAP_URL) to false
    }

    private fun httpStream(urlStr: String): InputStream {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code for $urlStr")
        }
        return conn.inputStream
    }

    private fun ensure(ctx: Context) {
        // Android filesDir == <dataDir>/files  → Termux PREFIX = filesDir/usr
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
            if (linkRel.startsWith("/") || linkRel.split('/').any { it == ".." }) {
                Log.w(TAG, "rejecting unsafe symlink path $linkRel")
                return@forEach
            }
            val link = File(prefix, linkRel).canonicalFile
            val root = prefix.canonicalFile
            if (link != root && !link.path.startsWith(root.path + File.separator)) {
                Log.w(TAG, "rejecting symlink outside PREFIX: $linkRel")
                return@forEach
            }
            try {
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete()
                Os.symlink(targetName, link.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "symlink $linkRel: ${e.message}")
            }
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
                Log.w(TAG, "proot asset missing: ${e.message}")
            }
        }
        if (!talloc.exists()) {
            try {
                ctx.assets.open("termux/libtalloc.so.2").use { i ->
                    FileOutputStream(talloc).use { o -> i.copyTo(o) }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Shell argv: native bash when custom bootstrap; else proot bind for stock.
     */
    fun shellCommand(ctx: Context): Pair<String, Array<String>> {
        install(ctx)
        val bash = File(prefix, "bin/bash")
        val proot = File(filesDir, "proot")
        return if (nativePrefix && bash.exists()) {
            // VHEditor / termux-generator style — direct exec
            bash.absolutePath to arrayOf("-l")
        } else if (proot.canExecute() && bash.exists()) {
            proot.absolutePath to arrayOf(
                "-b", "${filesDir.absolutePath}:$STOCK_FAKE_FILES",
                "-w", "$STOCK_FAKE_FILES/home",
                "$STOCK_FAKE_FILES/usr/bin/bash",
                "--login",
            )
        } else if (bash.exists()) {
            bash.absolutePath to arrayOf("-l")
        } else {
            "/system/bin/sh" to emptyArray()
        }
    }

    fun buildEnv(ctx: Context, usbFd: Int? = null): Array<String> {
        ensure(ctx)
        val p = prefix.absolutePath
        val h = home.absolutePath
        val list = mutableListOf(
            "HOME=$h",
            "PREFIX=$p",
            "TMPDIR=$p/tmp",
            "PATH=$p/bin:/system/bin",
            "LD_LIBRARY_PATH=$p/lib:${filesDir.absolutePath}",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "EXTERNAL_STORAGE=/sdcard",
            "UNITOOL=1",
            "TERMUX_VERSION=unitool",
            // Match Termux app identity for scripts that check package
            "TERMUX_APP__PACKAGE_NAME=ahk.unitool.gsm",
        )
        if (usbFd != null && usbFd >= 0) {
            list += "USB_FD=$usbFd"
            list += "ADBIFY_USB_FD=$usbFd"
        }
        return list.toTypedArray()
    }
}
