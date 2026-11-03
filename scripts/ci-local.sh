#!/usr/bin/env bash
# Mimic GitHub Actions CI locally (unit tests + debug assemble)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
chmod +x ./gradlew scripts/test.sh 2>/dev/null || true

echo "=== CI local: unit tests ==="
./scripts/test.sh unit

echo "=== CI local: assemble debug ==="
./scripts/test.sh build

echo "=== CI local: OK ==="
