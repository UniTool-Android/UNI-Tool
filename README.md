# Uni Tool (ahk.unitool.gsm)

Android USB Host toolkit with an app-private native shell. No Termux bootstrap, package manager, proot, or first-run download is required.

## Architecture

- UsbTransport: USB permission, open, claim, bulk/control, and descriptor handling.
- In-process protocol implementations: FastbootClient, AdbHost, and MtkBrom.
- App-private shell: files/unitool/{bin,lib,home,tmp}, using Android /system/bin/sh.
- PTY terminal UI and USB diagnostics.

## Build

Requires Android SDK, NDK r27, and JDK 17.

    ./gradlew :app:assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk.

## Shell runtime

The shell uses Android's built-in /system/bin/sh. It creates working directories inside app-private storage and exposes its bin and lib paths. No root access, external storage permission, bootstrap archive, or network installation is needed.

Android-compatible command binaries placed in app/src/main/assets/tools/arm64-v8a/ are copied to app-private bin at startup. Shared libraries placed in app/src/main/assets/tools/lib/arm64-v8a/ are copied to app-private lib. Only compatible, legally redistributable binaries should be packaged. Executables and JNI shared libraries are different artifact types; do not load command-line executables with System.loadLibrary. This repository does not import proprietary binaries from third-party APKs.

## USB and protocol notes

- ADB, Fastboot, and MTK protocol code remains in-process and uses the existing USB Host transport.
- USB_FD is exposed as a diagnostic environment value only. A child process cannot automatically use a Java-owned USB descriptor; descriptor duplication/inheritance and protocol-specific transport integration must be implemented and tested before CLI tools can use OTG.
- Stock adb devices is not expected to discover Android USB Host sessions without an explicit bridge.

## Tests and CI

    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:assembleDebug
    ./gradlew :app:lintDebug

See TESTING.md.
