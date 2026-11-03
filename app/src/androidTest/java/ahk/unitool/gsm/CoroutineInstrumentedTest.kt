package ahk.unitool.gsm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineInstrumentedTest {

    @Test
    fun runTestOnDevice() = runTest {
        var x = 0
        delay(10)
        x = 1
        advanceUntilIdle()
        assertThat(x).isEqualTo(1)
    }
}
