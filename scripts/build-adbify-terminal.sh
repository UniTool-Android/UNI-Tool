#!/usr/bin/env bash
set -euo pipefail

# Build the pinned Adbify terminalview Android library from source and stage its AAR
# for UniTool. This keeps upstream code reproducible and avoids an unpublished JitPack
# coordinate. The resulting AAR is generated locally/inside CI and is not committed.

readonly ADBIFY_REPO="https://github.com/CrackerCat/Adbify.git"
readonly ADBIFY_COMMIT="85381ff30f3f358911bafca136f83219f3bf2e14"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly BUILD_ROOT="${ROOT_DIR}/.build/adbify"
readonly SOURCE_DIR="${BUILD_ROOT}/Adbify"
readonly OUTPUT_AAR="${SOURCE_DIR}/terminalview/build/outputs/aar/terminalview-release.aar"
readonly DEST_DIR="${ROOT_DIR}/app/libs"
readonly DEST_AAR="${DEST_DIR}/adbify-terminalview-release.aar"

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v java >/dev/null || { echo "Java is required" >&2; exit 1; }

mkdir -p "${BUILD_ROOT}" "${DEST_DIR}"

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  rm -rf "${SOURCE_DIR}"
  git clone --filter=blob:none "${ADBIFY_REPO}" "${SOURCE_DIR}"
fi

git -C "${SOURCE_DIR}" fetch --depth 1 origin "${ADBIFY_COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach "${ADBIFY_COMMIT}"

echo "Building Adbify terminalview at ${ADBIFY_COMMIT}"
(
  cd "${SOURCE_DIR}"
  chmod +x ./gradlew
  ./gradlew --no-daemon --stacktrace :terminalview:assembleRelease
)

if [[ ! -s "${OUTPUT_AAR}" ]]; then
  echo "Expected AAR was not produced: ${OUTPUT_AAR}" >&2
  exit 1
fi

cp "${OUTPUT_AAR}" "${DEST_AAR}"
echo "Adbify terminal AAR staged at: ${DEST_AAR}"
