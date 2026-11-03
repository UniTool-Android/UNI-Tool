package ahk.unitool.gsm.protocol.common

/** Pure MTK VID/PID helpers — JVM unit tests. */
object MtkIds {
    const val VID_MEDIATEK = 0x0E8D

    fun isMediaTek(vid: Int): Boolean = vid == VID_MEDIATEK

    fun isLikelyBromPid(pid: Int): Boolean =
        pid in setOf(0x0003, 0x2000, 0x2001, 0x2006, 0x2007, 0x201c, 0x20ff)
}
