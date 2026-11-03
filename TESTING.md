# Uni Tool — Testing

## Libraries

| Layer | Libraries |
|-------|-----------|
| Unit (JVM) | JUnit 4, Truth, MockK, kotlinx-coroutines-test, Robolectric |
| Instrumented | AndroidX Test, Espresso, Truth, coroutines-test, MockK Android |

## Layout

```
app/src/test/java/...          # JVM unit tests
app/src/androidTest/java/...   # device/emulator instrumented tests
```

### Unit tests

| Class | Covers |
|-------|--------|
| `FastbootProtocolTest` | OKAY/FAIL/DATA/INFO parse, download token |
| `AdbPacketTest` | encode/decode header, checksum, magic, interface IDs |
| `MtkIdsTest` | MediaTek VID/PID helpers |
| `UsbTransportLogicTest` | Session FD mock, permission action |
| `FastbootClientMockTransportTest` | Client with mocked bulk transport |
| `CoroutineTest` | runTest, StandardTestDispatcher, SharedFlow |
| `NativeBridgeLoadTest` | Safe when .so absent on JVM |

### Instrumented tests

| Class | Covers |
|-------|--------|
| `ExampleInstrumentedTest` | package name |
| `NativeBridgeInstrumentedTest` | libunitool load + selfTest |
| `UsbTransportInstrumentedTest` | device list API |
| `MainActivitySmokeTest` | Espresso UI smoke |
| `CoroutineInstrumentedTest` | coroutines-test on device |

## Commands

```bash
# Unit tests (no device)
./gradlew :app:testDebugUnitTest

# One class
./gradlew :app:testDebugUnitTest --tests "ahk.unitool.gsm.protocol.fastboot.FastbootProtocolTest"

# Instrumented (device/emulator)
./gradlew :app:connectedDebugAndroidTest

# Report
open app/build/reports/tests/testDebugUnitTest/index.html
```

## Pure logic modules (preferred for unit tests)

- `protocol.common.FastbootProtocol`
- `protocol.common.AdbPacket`
- `protocol.common.MtkIds`

USB Host / JNI need instrumented tests or MockK.
