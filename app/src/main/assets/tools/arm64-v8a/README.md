Native command payloads belong in this directory by ABI.

Expected initial ABI: arm64-v8a.

Only package binaries that are Android-compatible, legally redistributable, and built or obtained from their official source projects. Keep command executables separate from JNI shared libraries. Do not place executable ELF files in jniLibs expecting System.loadLibrary to run them.

Packaging/staging is intentionally not wired yet: the next implementation step must validate ELF ABI/API compatibility, copy executable payloads into the app-private unitool/bin directory, set executable permissions, and test each command on a device.
