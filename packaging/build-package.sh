#!/bin/bash
# ============================================================
# Iteration Board - offline package builder (Linux build host)
#
# Usage:  bash packaging/build-package.sh [--skip-downloads]
#
# Output: packaging/dist/iteration-board-offline-1.0.1.zip
#
# What it does:
#   1. Builds the backend jar (offline Maven wrapper).
#   2. Builds the real frontend (npm run build) and embeds
#      frontend/dist INTO the jar under BOOT-INF/classes/static/
#      (the backend project itself keeps static/ empty except
#      openapi.yaml, so the jar alone does NOT contain the UI;
#      embedding happens here, at packaging time only, and the
#      repository sources are not modified).
#   3. Downloads / collects the Windows runtime components
#      (official sources only, see URLS below) into staging.
#      Components are cached in packaging/cache/ across runs.
#   4. Assembles the package tree and zips it.
#
# Components are NOT committed to git (see .gitignore):
# packaging/cache/, packaging/staging/, packaging/dist/ are
# all ignored. Only scripts and docs live in the repository.
# ============================================================
set -euo pipefail

VERSION="1.0.1"
PKG_NAME="iteration-board-offline-${VERSION}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CACHE_DIR="${SCRIPT_DIR}/cache"
STAGING_DIR="${SCRIPT_DIR}/staging/${PKG_NAME}"
DIST_DIR="${SCRIPT_DIR}/dist"
SKIP_DOWNLOADS=0
[ "${1:-}" = "--skip-downloads" ] && SKIP_DOWNLOADS=1

# --- Official runtime component sources ----------------------
# PostgreSQL 18.7 Windows x64 binaries zip (EnterpriseDB archive;
# same version as the dev/test lock, per M5 acceptance fix 1)
PG_URL="https://get.enterprisedb.com/postgresql/postgresql-18.7-1-windows-x64-binaries.zip"
# Eclipse Temurin JRE 17 Windows x64 (Adoptium official API)
JRE_URL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse"
# Microsoft VC++ 2015-2022 x64 redistributable (Microsoft aka.ms)
VC_URL="https://aka.ms/vs/17/release/vc_redist.x64.exe"
# WinSW v2.12.0 x64 (GitHub releases; stable line, MIT. The 3.x
# alpha line is NOT used for long-running deployments, per M5
# acceptance fix 2. v2 requires .NET Framework 4.6.1+, which
# Windows 10/11 ship by default.)
WINSW_URL="https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe"

mkdir -p "${CACHE_DIR}" "${DIST_DIR}"
MISSING=()

fetch() { # fetch <cache-file> <url>
  local out="$1" url="$2"
  if [ -s "${CACHE_DIR}/${out}" ]; then
    echo "[cache] ${out} already downloaded, reusing."
    return 0
  fi
  if [ "${SKIP_DOWNLOADS}" = "1" ]; then
    echo "[skip ] ${out} not in cache and downloads disabled."
    return 1
  fi
  echo "[fetch] ${url}"
  if curl -fL --retry 2 --connect-timeout 30 -o "${CACHE_DIR}/${out}.part" "${url}"; then
    mv "${CACHE_DIR}/${out}.part" "${CACHE_DIR}/${out}"
    return 0
  fi
  echo "[warn ] download failed: ${url}"
  rm -f "${CACHE_DIR}/${out}.part"
  return 1
}

echo "=== 1/5 Backend jar ==="
cd "${REPO_ROOT}/backend"
"${REPO_ROOT}/toolchain/mvn-ib.sh" -o package -DskipTests
cd "${REPO_ROOT}"
JAR_SRC="$(ls "${REPO_ROOT}"/backend/target/iteration-board-*.jar | head -1)"
[ -f "${JAR_SRC}" ] || { echo "[error] backend jar not found"; exit 1; }
echo "[ok] ${JAR_SRC}"

echo "=== 2/5 Frontend build + embed into jar ==="
cd "${REPO_ROOT}/frontend"
npm run build
cd "${REPO_ROOT}"
[ -f "${REPO_ROOT}/frontend/dist/index.html" ] || { echo "[error] frontend dist missing"; exit 1; }
BUILD_TMP="${SCRIPT_DIR}/.tmp"
mkdir -p "${BUILD_TMP}"
WORK_JAR="${BUILD_TMP}/work.jar"
cp "${JAR_SRC}" "${WORK_JAR}"
# Embed the built UI into the jar's static resources.
EMBED_DIR="${BUILD_TMP}/embed"
rm -rf "${EMBED_DIR}"
mkdir -p "${EMBED_DIR}/BOOT-INF/classes/static"
cp -r "${REPO_ROOT}/frontend/dist/." "${EMBED_DIR}/BOOT-INF/classes/static/"
(cd "${EMBED_DIR}" && zip -qr "${WORK_JAR}" BOOT-INF)
rm -rf "${EMBED_DIR}"
echo "[ok] frontend embedded into jar (BOOT-INF/classes/static/)"

echo "=== 3/5 Runtime components ==="
fetch "postgresql-win-x64.zip" "${PG_URL}" || MISSING+=("PostgreSQL 18.7 Windows x64 zip | ${PG_URL}")
fetch "temurin-jre17-win-x64.zip" "${JRE_URL}" || MISSING+=("Temurin JRE 17 Windows x64 zip | ${JRE_URL}")
fetch "vc_redist.x64.exe" "${VC_URL}" || MISSING+=("VC++ 2015-2022 x64 redist | ${VC_URL}")
fetch "WinSW-x64.exe" "${WINSW_URL}" || MISSING+=("WinSW v2.12.0 x64 exe | ${WINSW_URL}")

echo "=== 4/5 Assemble package tree ==="
rm -rf "${STAGING_DIR}"
mkdir -p "${STAGING_DIR}"/{app,runtime/pgsql,runtime/jre,runtime/winsw,config,data,backup,logs,docs,service}
cp "${WORK_JAR}" "${STAGING_DIR}/app/iteration-board.jar"
rm -f "${WORK_JAR}"
cp "${SCRIPT_DIR}/config/application.yml" "${STAGING_DIR}/config/application.yml"
cp -r "${REPO_ROOT}/backend/config/prompts" "${STAGING_DIR}/config/prompts"
for f in install start stop uninstall backup restore upgrade; do
  cp "${SCRIPT_DIR}/windows/${f}.bat" "${STAGING_DIR}/${f}.bat"
done
cp "${SCRIPT_DIR}/windows/IterationBoardApp.xml" "${STAGING_DIR}/service/"
cp "${SCRIPT_DIR}/windows/IterationBoardPG.xml" "${STAGING_DIR}/service/"
cp "${REPO_ROOT}/docs/部署手册.md" "${STAGING_DIR}/docs/" 2>/dev/null || echo "[warn] docs/部署手册.md not found, skipped"
cp "${REPO_ROOT}/docs/用户手册.md" "${STAGING_DIR}/docs/" 2>/dev/null || echo "[warn] docs/用户手册.md not found, skipped"
touch "${STAGING_DIR}/data/.keep" "${STAGING_DIR}/backup/.keep" "${STAGING_DIR}/logs/.keep"

if [ -s "${CACHE_DIR}/postgresql-win-x64.zip" ]; then
  unzip -qo "${CACHE_DIR}/postgresql-win-x64.zip" -d "${BUILD_TMP}/pg-extract"
  # EDB zip contains a top-level pgsql/ folder.
  cp -r "${BUILD_TMP}/pg-extract/pgsql/." "${STAGING_DIR}/runtime/pgsql/"
  # pgAdmin / StackBuilder are GUI extras not needed by the service.
  rm -rf "${STAGING_DIR}/runtime/pgsql/pgAdmin 4" "${STAGING_DIR}/runtime/pgsql/StackBuilder"
  rm -rf "${BUILD_TMP}/pg-extract"
  echo "[ok] PostgreSQL binaries in place"
fi
if [ -s "${CACHE_DIR}/temurin-jre17-win-x64.zip" ]; then
  unzip -qo "${CACHE_DIR}/temurin-jre17-win-x64.zip" -d "${BUILD_TMP}/jre-extract"
  JRE_HOME_DIR="$(find "${BUILD_TMP}/jre-extract" -maxdepth 3 -name java.exe -path '*bin*' | head -1)"
  JRE_ROOT="$(dirname "$(dirname "${JRE_HOME_DIR}")")"
  cp -r "${JRE_ROOT}/." "${STAGING_DIR}/runtime/jre/"
  rm -rf "${BUILD_TMP}/jre-extract"
  echo "[ok] JRE in place"
fi
[ -s "${CACHE_DIR}/vc_redist.x64.exe" ] && cp "${CACHE_DIR}/vc_redist.x64.exe" "${STAGING_DIR}/runtime/"
[ -s "${CACHE_DIR}/WinSW-x64.exe" ] && cp "${CACHE_DIR}/WinSW-x64.exe" "${STAGING_DIR}/runtime/winsw/"

echo "=== 5/5 Zip ==="
cd "${SCRIPT_DIR}/staging"
rm -f "${DIST_DIR}/${PKG_NAME}.zip"
zip -qr "${DIST_DIR}/${PKG_NAME}.zip" "${PKG_NAME}"
cd "${REPO_ROOT}"
ls -lh "${DIST_DIR}/${PKG_NAME}.zip"
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo ""
  echo "[INCOMPLETE PACKAGE] The following components are MISSING and"
  echo "must be downloaded from the official URLs before delivery:"
  for m in "${MISSING[@]}"; do echo "  - ${m}"; done
fi
echo "Done: ${DIST_DIR}/${PKG_NAME}.zip"
