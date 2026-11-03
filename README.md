# Uni Tool (`ahk.unitool.gsm`)

No-root OTG flash toolkit + Termux-style shell.

## Architecture

```
Android app (host, no root)
  1) UsbTransport — permission, open, claim, bulk/control, FD
  2) Protocols in-process
       FastbootClient  ·  AdbHost  ·  MtkBrom
  3) Termux bootstrap shell (pkg/apt)
       proot path-bind: app filesDir ↔ /data/data/com.termux/files
       USB tools: USB_FD=<n> + wrap_sys_device (not stock adb devices)
  4) Test UI (this release) — device list, probes, shell
```

## Build

```bash
# requires Android SDK + JDK 17
cd UniTool
# generate wrapper if needed, or open in Android Studio
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Test flow

1. Connect target phone via OTG  
2. **Refresh** → select device → **Permission** → **Open**  
3. **Fastboot** / **ADB** / **MTK** probes (device must be in matching mode)  
4. **Shell** → Termux bash (`pkg update` after first extract)  
5. Env includes `USB_FD` when a session is open  

## Phases

| Phase | Status |
|-------|--------|
| 1 USB core | Implemented (`UsbTransport`) |
| 2 Protocols | Minimal Fastboot / ADB / MTK probe |
| 3 Termux shell | Bootstrap + proot path bind |
| 4 Flash UI | Not yet — terminal harness only |

## Notes

- Official Termux packages hardcode `/data/data/com.termux/files`; proot bind maps our `filesDir` there so `pkg` works under `ahk.unitool.gsm`.  
- Stock `adb` inside the shell will **not** see OTG usbfs paths. Use in-app protocol engines or FD + wrap.  
- First launch extracts ~30MB bootstrap (once).  


## CI & testing

### GitHub Actions

**Build only (APK artifact):** [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)  
→ job builds debug APK and uploads artifact `UniTool-debug-apk` (30 days).  
On GitHub Release publish, also attaches `UniTool-debug.apk` to the release.

Local:
```bash
./scripts/build-apk.sh   # → dist/UniTool-debug.apk
```

### GitHub Actions (full CI)

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

| Job | Action |
|-----|--------|
| **unit-test** | `./gradlew :app:testDebugUnitTest` |
| **build-debug** | NDK + `assembleDebug`, upload APK artifact |
| **lint** | `lintDebug` (non-blocking) |

Triggers: push/PR to `main` / `master` / `develop`, and manual `workflow_dispatch`.

### Local scripts

```bash
# Unit tests
./scripts/test.sh unit

# One class
CLASS=ahk.unitool.gsm.protocol.fastboot.FastbootProtocolTest ./scripts/test.sh unit-class

# Debug APK
./scripts/test.sh build

# Unit + build (CI-like)
./scripts/ci-local.sh

# Instrumented (device required)
./scripts/test.sh instrumented
```

See [TESTING.md](TESTING.md) for the full test map.


## Terminal UI

- Termux shell starts by default after bootstrap extract
- Toolbar subtitle shows connected USB `vid:pid`, path, permission, and `fd` when open
- Bootstrap asset: `app/src/main/assets/termux/bootstrap-aarch64.zip`


## Termux (custom package name)

See [docs/TERMUX.md](docs/TERMUX.md).

Bootstrap for **ahk.unitool.gsm** is built via workflow **Build Termux bootstrap** (termux-generator style).  
Until that artifact exists, the app falls back to stock Termux zip + proot path bind.
