#!/usr/bin/env bash
# Build Uni Tool debug APK and copy to dist/ (local or CI helper)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

chmod +x ./gradlew 2>/dev/null || true

echo "==> Building debug APK…"
./gradlew :app:assembleDebug --stacktrace

mkdir -p dist
SRC=$(ls app/build/outputs/apk/debug/*.apk | head -1)
OUT="dist/UniTool-debug.apk"
cp "$SRC" "$OUT"

echo "==> APK ready: $OUT"
ls -lh "$OUT"
echo "$ROOT/$OUT"
