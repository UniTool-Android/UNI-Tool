package ahk.unitool.gsm.protocol.mtk

import ahk.unitool.gsm.protocol.common.MtkIds
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MtkIdsTest {

    @Test
    fun mediaTekVid() {
        assertThat(MtkIds.VID_MEDIATEK).isEqualTo(0x0E8D)
        assertThat(MtkIds.isMediaTek(0x0E8D)).isTrue()
        assertThat(MtkIds.isMediaTek(0x18D1)).isFalse()
    }

    @Test
    fun bromPids() {
        assertThat(MtkIds.isLikelyBromPid(0x0003)).isTrue()
        assertThat(MtkIds.isLikelyBromPid(0x2000)).isTrue()
        assertThat(MtkIds.isLikelyBromPid(0x201c)).isTrue()
        assertThat(MtkIds.isLikelyBromPid(0x1234)).isFalse()
    }
}
