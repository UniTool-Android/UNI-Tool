#!/usr/bin/env bash
# Uni Tool — local test runner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}==>${NC} $*"; }
warn() { echo -e "${YELLOW}warn:${NC} $*"; }
err()  { echo -e "${RED}error:${NC} $*" >&2; }

usage() {
  cat <<EOF
Usage: $(basename "$0") [command]

Commands:
  unit          Run JVM unit tests (default)
  unit-class    Run one unit test class (needs CLASS=...)
  instrumented  Run connected Android tests (device/emulator required)
  build         Assemble debug APK
  all           unit + build
  clean         Clean build outputs
  help          Show this help

Examples:
  ./scripts/test.sh
  ./scripts/test.sh unit
  CLASS=ahk.unitool.gsm.protocol.fastboot.FastbootProtocolTest ./scripts/test.sh unit-class
  ./scripts/test.sh instrumented
  ./scripts/test.sh all
EOF
}

ensure_gradlew() {
  if [[ ! -x ./gradlew ]]; then
    chmod +x ./gradlew 2>/dev/null || true
  fi
  if [[ ! -f ./gradlew ]]; then
    err "gradlew missing — open project in Android Studio once or run: gradle wrapper"
    exit 1
  fi
}

check_sdk() {
  if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] && [[ ! -f local.properties ]]; then
    warn "ANDROID_HOME / local.properties not set — Gradle may fail to find the SDK"
  fi
}

cmd="${1:-unit}"

case "$cmd" in
  help|-h|--help)
    usage
    ;;

  unit)
    ensure_gradlew
    check_sdk
    log "Running unit tests…"
    ./gradlew :app:testDebugUnitTest --stacktrace
    log "Report: app/build/reports/tests/testDebugUnitTest/index.html"
    ;;

  unit-class)
    ensure_gradlew
    check_sdk
    if [[ -z "${CLASS:-}" ]]; then
      err "Set CLASS=fully.qualified.TestClass"
      exit 1
    fi
    log "Running $CLASS…"
    ./gradlew :app:testDebugUnitTest --tests "$CLASS" --stacktrace
    ;;

  instrumented|androidTest|connected)
    ensure_gradlew
    check_sdk
    if ! command -v adb >/dev/null 2>&1; then
      warn "adb not in PATH"
    else
      devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
      if [[ -z "$devices" ]]; then
        err "No device/emulator (adb devices empty). Start one first."
        exit 1
      fi
      log "Devices: $devices"
    fi
    log "Running instrumented tests…"
    ./gradlew :app:connectedDebugAndroidTest --stacktrace
    log "Report: app/build/reports/androidTests/connected/"
    ;;

  build)
    ensure_gradlew
    check_sdk
    log "Assembling debug APK…"
    ./gradlew :app:assembleDebug --stacktrace
    log "APK: app/build/outputs/apk/debug/"
    ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null || true
    ;;

  all)
    ensure_gradlew
    check_sdk
    log "Unit tests + debug build…"
    ./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
    log "Done."
    ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null || true
    ;;

  clean)
    ensure_gradlew
    log "Cleaning…"
    ./gradlew clean
    ;;

  *)
    err "Unknown command: $cmd"
    usage
    exit 1
    ;;
esac
