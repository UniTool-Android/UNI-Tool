#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/termux/bootstrap-aarch64.zip"
URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
mkdir -p "$(dirname "$DEST")"
if [[ -f "$DEST" ]]; then
  echo "Already present: $DEST"
  ls -lh "$DEST"
  exit 0
fi
echo "Downloading Termux bootstrap…"
curl -fSL --retry 3 -o "$DEST" "$URL"
ls -lh "$DEST"
