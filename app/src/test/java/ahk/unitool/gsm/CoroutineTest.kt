package ahk.unitool.gsm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Examples of kotlinx-coroutines-test patterns used by Uni Tool async USB/shell work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTest {

    @Test
    fun runTest_completesSuspendedWork() = runTest {
        val result = async {
            delay(1_000)
            "done"
        }
        advanceTimeBy(1_000)
        assertThat(result.await()).isEqualTo("done")
    }

    @Test
    fun standardDispatcher_controlsScheduling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var value = 0
        scope.launch {
            delay(500)
            value = 1
        }
        assertThat(value).isEqualTo(0)
        advanceTimeBy(500)
        advanceUntilIdle()
        assertThat(value).isEqualTo(1)
    }

    @Test
    fun sharedFlow_emitAndCollect() = runTest {
        // replay=1 so a late subscriber still sees the event (avoids UncompletedCoroutinesError
        // when async collector is scheduled after emit under StandardTestDispatcher).
        val flow = MutableSharedFlow<String>(replay = 1)
        flow.emit("usb-attached")
        assertThat(flow.first()).isEqualTo("usb-attached")
    }

    @Test
    fun concurrentProbes_collectResults() = runTest {
        suspend fun probe(name: String): String {
            delay(100)
            return "ok:$name"
        }
        val a = async { probe("fastboot") }
        val b = async { probe("adb") }
        advanceUntilIdle()
        assertThat(a.await()).isEqualTo("ok:fastboot")
        assertThat(b.await()).isEqualTo("ok:adb")
    }
}
