# Termux integration (Uni Tool)

## Goal

Shell environment matching **applicationId `ahk.unitool.gsm`**, same model as:

- [ahksoft/termux-generator](https://github.com/ahksoft/termux-generator) — rebuild Termux with `--name`
- [VHEditor-Android](https://github.com/vhqtvn/VHEditor-Android) — embedded Termux with custom package + own package builds

## PREFIX layout

```text
/data/data/ahk.unitool.gsm/files/usr   ← PREFIX (bin, lib, etc.)
/data/data/ahk.unitool.gsm/files/home  ← HOME
```

(`Context.filesDir` is already `.../files`.)

## Bootstrap sources (priority)

1. **Asset** `app/src/main/assets/termux/bootstrap-aarch64.zip`  
   - If `bootstrap.meta` contains `custom` / `ahk.unitool.gsm` → **native** (no proot)
2. **Release** `https://github.com/ahksoft/UNI-Tool/releases/download/termux-bootstrap/bootstrap-aarch64.zip`
3. **Stock** Termux packages release → **proot path bind** fallback only

## Build custom bootstrap

GitHub → Actions → **Build Termux bootstrap (ahk.unitool.gsm)** → Run workflow.

Or locally (Docker, hours):

```bash
git clone https://github.com/ahksoft/termux-generator.git
cd termux-generator
./build-termux.sh --name ahk.unitool.gsm --architectures aarch64
```

Copy resulting `bootstrap-aarch64.zip` into Uni Tool assets and add:

```text
# app/src/main/assets/termux/bootstrap.meta
custom ahk.unitool.gsm
```

## USB

Unrelated to Termux prefix. Use in-app `UsbTransport` + `USB_FD` / native USBDEVFS. Do not expect stock `adb devices` usbfs paths inside the shell.

## Terminal UI

Current: PTY + terminal-style activity (title bar shows USB device).  
Future: full Termux `TerminalView` module (VHEditor-style).
