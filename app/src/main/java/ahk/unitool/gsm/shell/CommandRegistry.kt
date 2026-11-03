package ahk.unitool.gsm.shell

/** Known tool names; a tool is available only when its executable is packaged in app-private bin. */
object CommandRegistry {
    data class Tool(val command: String, val description: String, val group: String)
    val tools = listOf(
        Tool("adb", "Android Debug Bridge client", "Android"),
        Tool("fastboot", "Android bootloader client", "Android"),
        Tool("mtk", "MediaTek command adapter", "MediaTek"),
        Tool("qdl", "Qualcomm Download utility", "Qualcomm"),
        Tool("simg2img", "Android sparse image converter", "Images"),
        Tool("payload-dumper-go", "OTA payload image extractor", "Images"),
        Tool("7zz", "Archive utility", "Files")
    )
    fun available(runtime: ShellRuntime): List<Tool> = tools.filter { runtime.executable(it.command) != null }
}
