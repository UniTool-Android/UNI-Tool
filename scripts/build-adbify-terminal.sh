#!/usr/bin/env bash
set -euo pipefail

# Build the pinned Adbify terminalview Android library from source and stage its AAR.
# Adbify's checked-in gradlew is malformed at the pinned revision, so use the exact
# Gradle distribution declared by its wrapper properties instead of executing it.

readonly ADBIFY_REPO="https://github.com/CrackerCat/Adbify.git"
readonly ADBIFY_COMMIT="85381ff30f3f358911bafca136f83219f3bf2e14"
readonly GRADLE_VERSION="8.1.1"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly BUILD_ROOT="${ROOT_DIR}/.build/adbify"
readonly SOURCE_DIR="${BUILD_ROOT}/Adbify"
readonly GRADLE_HOME="${BUILD_ROOT}/gradle-${GRADLE_VERSION}"
readonly GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
readonly GRADLE_ZIP="${BUILD_ROOT}/gradle-${GRADLE_VERSION}-bin.zip"
readonly OUTPUT_AAR="${SOURCE_DIR}/terminalview/build/outputs/aar/terminalview-release.aar"
readonly DEST_DIR="${ROOT_DIR}/app/libs"
readonly DEST_AAR="${DEST_DIR}/adbify-terminalview-release.aar"

for tool in git java curl unzip; do
  command -v "${tool}" >/dev/null || { echo "${tool} is required" >&2; exit 1; }
done

mkdir -p "${BUILD_ROOT}" "${DEST_DIR}"

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  rm -rf "${SOURCE_DIR}"
  git clone --filter=blob:none "${ADBIFY_REPO}" "${SOURCE_DIR}"
fi

git -C "${SOURCE_DIR}" fetch --depth 1 origin "${ADBIFY_COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach "${ADBIFY_COMMIT}"

# Do not invoke Adbify's malformed gradlew script. Download the pinned wrapper version.
if [[ ! -x "${GRADLE_BIN}" ]]; then
  rm -rf "${GRADLE_HOME}" "${GRADLE_ZIP}"
  curl --fail --location --retry 3 \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    --output "${GRADLE_ZIP}"
  unzip -q "${GRADLE_ZIP}" -d "${BUILD_ROOT}"
  rm -f "${GRADLE_ZIP}"
fi

echo "Building Adbify terminalview at ${ADBIFY_COMMIT} with Gradle ${GRADLE_VERSION}"
(
  cd "${SOURCE_DIR}"
  "${GRADLE_BIN}" --no-daemon --stacktrace :terminalview:assembleRelease
)

if [[ ! -s "${OUTPUT_AAR}" ]]; then
  echo "Expected AAR was not produced: ${OUTPUT_AAR}" >&2
  exit 1
fi

cp "${OUTPUT_AAR}" "${DEST_AAR}"
echo "Adbify terminal AAR staged at: ${DEST_AAR}"
