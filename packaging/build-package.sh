#!/bin/bash
# ============================================================
# Iteration Board - offline package builder (Linux build host)
#
# Usage:  bash packaging/build-package.sh [--skip-downloads]
#
# Output: packaging/dist/iteration-board-offline-1.1.0.zip
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

# Single source of truth for the package version (1.1.0).
# It is also written into the package as version.txt, which
# install/upgrade/migrate scripts read at runtime.
VERSION="1.1.0"
PKG_NAME="iteration-board-offline-${VERSION}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CACHE_DIR="${SCRIPT_DIR}/cache"
STAGING_DIR="${SCRIPT_DIR}/staging/${PKG_NAME}"
DIST_DIR="${SCRIPT_DIR}/dist"
SKIP_DOWNLOADS=0
[ "${1:-}" = "--skip-downloads" ] && SKIP_DOWNLOADS=1
# IB_CI=1: build on a generic CI runner instead of the local dev
# host - system Maven online (no toolchain/mvn-ib.sh wrapper, no
# offline repo) and `npm ci` for the frontend. Used by
# .github/workflows/windows-install.yml. Default stays local.
IB_CI="${IB_CI:-0}"

# --- Official runtime component sources ----------------------
# PostgreSQL 18.7 Windows x64 binaries zip (EnterpriseDB archive;
# same version as the dev/test lock, per M5 acceptance fix 1)
PG_URL="https://get.enterprisedb.com/postgresql/postgresql-18.7-1-windows-x64-binaries.zip"
# Eclipse Temurin JRE 17 Windows x64 (Adoptium official API)
JRE_URL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse"
# Microsoft VC++ 2015-2022 x64 runtime: APP-LOCAL deployment.
# vc_redist.x64.exe is no longer bundled or run (its silent
# install can hang indefinitely on managed machines). Instead,
# the redistributable DLLs themselves ship next to the binaries
# that import them. The DLLs were extracted ONCE from the
# official installer (Microsoft aka.ms, URL below) - from the
# vcRuntimeAdditional amd64 payload cab inside the bundle -
# and are kept in cache/vc-dlls/ with pinned SHA-256 hashes
# (verified on every build; a mismatch fails the build).
# PE import analysis (objdump -p over every .exe/.dll in the
# PostgreSQL tree, incl. delay-load tables) shows exactly three
# non-system runtime DLLs are imported: vcruntime140.dll (63
# files in bin alone), vcruntime140_1.dll (ICU libraries) and
# msvcp140.dll (ICU libraries). The api-ms-win-crt-* imports
# are UCRT API sets, in-box on Windows 10/11. The Temurin JRE
# needs no placement: Adoptium already bundles its own VC++
# runtime DLLs in jre/bin (verified in the 1.0.1 tree).
VC_INSTALLER_URL="https://aka.ms/vs/17/release/vc_redist.x64.exe"
VC_DLL_DIR="${CACHE_DIR}/vc-dlls"
declare -A VC_DLL_SHA256=(
  [vcruntime140.dll]="d5e4d9a3e835fa679450145d6a7d94e36573a509317111904d9b3712c30d9066"
  [vcruntime140_1.dll]="1f2d41c4aa5db0bc33ebf7b66d72943a817d7ce6cbe880502a9403823633093f"
  [msvcp140.dll]="0f885b509a685d2bbfa652fed26b5fb31d88fbdab0a978c641d1c7b8aa460aa9"
)
# Placement: dir (relative to staging root) -> DLL list.
# pgsql/bin + pgsql/lib: all three (postgres.exe, initdb.exe,
# pg_ctl.exe, libpq.dll, ICU and plugin DLLs import them).
VC_PLACEMENTS=(
  "runtime/pgsql/bin:vcruntime140.dll vcruntime140_1.dll msvcp140.dll"
  "runtime/pgsql/lib:vcruntime140.dll vcruntime140_1.dll msvcp140.dll"
)
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

# A3 helper (rc4, ledger #11): backend payload fingerprint of a jar.
# Hashes the sorted "entry-path CRC" list of every entry EXCEPT the
# embedded frontend (BOOT-INF/classes/static/**), which packaging adds
# in step 2; two jars with the same fingerprint carry the same backend
# code/resources regardless of zip metadata or the embedded UI. Used
# to prove the jar shipped in the package is the jar built in step 1.
jar_fingerprint() { # jar_fingerprint <jar-file>
  unzip -lv "$1" \
    | awk '$1 ~ /^[0-9]+$/ && $7 ~ /^[0-9a-fA-F]{8}$/ {
        name = $8; for (i = 9; i <= NF; i++) name = name " " $i
        if (name !~ /^BOOT-INF\/classes\/static\//) print name " " $7
      }' \
    | LC_ALL=C sort \
    | sha256sum | awk '{print $1}'
}

# A5 helpers (rc4 C2 review follow-up, ledger #11): frontend static
# fingerprint. A3 deliberately excludes BOOT-INF/classes/static/**
# (packaging embeds the frontend in step 2), which left the mirror
# image of the rc3 incident unchecked: a stale or wrong frontend
# embedded next to a correct backend. dist_fingerprint hashes the
# sorted "relpath CRC32" list of the frontend/dist directory built
# in step 2 of THIS run; jar_static_fingerprint computes the same
# recipe over the static entries inside a jar (paths made relative
# to the static root). CRC32 is zlib.crc32 on both sides so the two
# values are directly comparable. The single entry openapi.yaml is
# excluded on the jar side: it is a backend resource that ships in
# the Maven jar under static/ (springdoc), not part of frontend/dist.
dist_fingerprint() { # dist_fingerprint <dir>
  python3 - "$1" <<'PYEOF'
import hashlib, os, sys, zlib
root = sys.argv[1]
lines = []
for dirpath, _dirnames, filenames in os.walk(root):
    for fn in filenames:
        full = os.path.join(dirpath, fn)
        rel = os.path.relpath(full, root).replace(os.sep, '/')
        crc = 0
        with open(full, 'rb') as f:
            for chunk in iter(lambda: f.read(1 << 20), b''):
                crc = zlib.crc32(chunk, crc)
        lines.append('%s %08x' % (rel, crc & 0xffffffff))
lines.sort()
print(hashlib.sha256(('\n'.join(lines) + '\n').encode()).hexdigest())
PYEOF
}
jar_static_fingerprint() { # jar_static_fingerprint <jar-file>
  unzip -lv "$1" \
    | awk '$1 ~ /^[0-9]+$/ && $7 ~ /^[0-9a-fA-F]{8}$/ {
        name = $8; for (i = 9; i <= NF; i++) name = name " " $i
        if (name ~ /^BOOT-INF\/classes\/static\// && name !~ /\/$/) {
          sub(/^BOOT-INF\/classes\/static\//, "", name)
          if (name != "openapi.yaml") print name " " tolower($7)
        }
      }' \
    | LC_ALL=C sort \
    | sha256sum | awk '{print $1}'
}

echo "=== 1/5 Backend jar ==="
# A1 (rc4, ledger #11): select the jar by EXACT version name, never by
# glob. The old `ls iteration-board-*.jar | head -1` picked the
# lexicographically first jar, so a stale rc2 jar left in target/ was
# silently packaged into the rc3 offline zip while CI only ever
# tested the fresh Maven output. Delete every OTHER version's
# iteration-board-*.jar BEFORE the build (only this glob family, so
# no stale jar can survive into the package), then assert afterwards
# that exactly one such jar exists and it is the expected file.
EXPECTED_JAR="${REPO_ROOT}/backend/target/iteration-board-${VERSION}.jar"
for stale in "${REPO_ROOT}"/backend/target/iteration-board-*.jar; do
  [ -e "${stale}" ] || continue
  if [ "${stale}" != "${EXPECTED_JAR}" ]; then
    echo "[clean] removing stale jar before build (prevents old-version jars leaking into the package): ${stale}"
    rm -f "${stale}"
  fi
done
cd "${REPO_ROOT}/backend"
if [ "${IB_CI}" = "1" ]; then
  mvn -B package -DskipTests
else
  "${REPO_ROOT}/toolchain/mvn-ib.sh" -o package -DskipTests
fi
cd "${REPO_ROOT}"
[ -f "${EXPECTED_JAR}" ] || { echo "[error] expected backend jar missing after build: ${EXPECTED_JAR}"; exit 1; }
JAR_COUNT="$(ls "${REPO_ROOT}"/backend/target/iteration-board-*.jar | wc -l)"
[ "${JAR_COUNT}" -eq 1 ] || { echo "[error] expected exactly one iteration-board-*.jar in backend/target, found ${JAR_COUNT}"; ls -l "${REPO_ROOT}"/backend/target/iteration-board-*.jar; exit 1; }
JAR_SRC="${EXPECTED_JAR}"
echo "[ok] ${JAR_SRC}"
# A3 (rc4, ledger #11): record the built jar's sha256 + backend
# fingerprint in a manifest shipped NEXT TO the package zip, so CI
# and humans can prove the shipped jar is the built jar.
JAR_SHA256="$(sha256sum "${JAR_SRC}" | awk '{print $1}')"
JAR_FINGERPRINT="$(jar_fingerprint "${JAR_SRC}")"
echo "[ok] backend jar sha256: ${JAR_SHA256}"
echo "[ok] backend jar fingerprint (entry+CRC, excl. BOOT-INF/classes/static): ${JAR_FINGERPRINT}"
cat > "${DIST_DIR}/${PKG_NAME}.jar-manifest.txt" <<EOF
package: ${PKG_NAME}
version: ${VERSION}
jar: iteration-board-${VERSION}.jar
jar-sha256: ${JAR_SHA256}
jar-fingerprint: ${JAR_FINGERPRINT}
fingerprint-method: sha256 of sorted "entry-path CRC" lines from unzip -lv, excluding BOOT-INF/classes/static/**
built: $(date '+%F %T %z')
EOF
echo "[ok] jar manifest written: ${DIST_DIR}/${PKG_NAME}.jar-manifest.txt"

echo "=== 2/5 Frontend build + embed into jar ==="
cd "${REPO_ROOT}/frontend"
if [ "${IB_CI}" = "1" ]; then
  npm ci
fi
npm run build
cd "${REPO_ROOT}"
[ -f "${REPO_ROOT}/frontend/dist/index.html" ] || { echo "[error] frontend dist missing"; exit 1; }
# A5 (rc4 C2, ledger #11): fingerprint the frontend built in THIS
# run; step 4 asserts the static payload embedded in the shipped
# jar is byte-identical to it (see jar_static_fingerprint).
DIST_FINGERPRINT="$(dist_fingerprint "${REPO_ROOT}/frontend/dist")"
echo "[ok] frontend dist fingerprint (entry+CRC): ${DIST_FINGERPRINT}"
echo "frontend-dist-fingerprint: ${DIST_FINGERPRINT}" >> "${DIST_DIR}/${PKG_NAME}.jar-manifest.txt"
echo "frontend-fingerprint-method: sha256 of sorted \"relpath CRC32\" lines; jar side reads BOOT-INF/classes/static/** entries (openapi.yaml excluded, backend resource)" >> "${DIST_DIR}/${PKG_NAME}.jar-manifest.txt"
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
fetch "WinSW-x64.exe" "${WINSW_URL}" || MISSING+=("WinSW v2.12.0 x64 exe | ${WINSW_URL}")

echo "--- VC++ runtime DLLs (app-local, hash-pinned, hard fail) ---"
for dll in "${!VC_DLL_SHA256[@]}"; do
  f="${VC_DLL_DIR}/${dll}"
  if [ ! -s "${f}" ]; then
    echo "[error] ${f} missing. Extract it from the official installer"
    echo "        ${VC_INSTALLER_URL} (vcRuntimeAdditional amd64 payload)"
    echo "        into cache/vc-dlls/ first. Build aborted."
    exit 1
  fi
  actual="$(sha256sum "${f}" | awk '{print $1}')"
  if [ "${actual}" != "${VC_DLL_SHA256[${dll}]}" ]; then
    echo "[error] SHA-256 mismatch for ${dll}: ${actual}"
    echo "        expected ${VC_DLL_SHA256[${dll}]}. Build aborted."
    exit 1
  fi
  echo "[ok] ${dll} hash verified"
done

echo "=== 4/5 Assemble package tree ==="
rm -rf "${STAGING_DIR}"
mkdir -p "${STAGING_DIR}"/{app,runtime/pgsql,runtime/jre,runtime/winsw,config,data,backup,logs,docs,service}
cp "${WORK_JAR}" "${STAGING_DIR}/app/iteration-board.jar"
rm -f "${WORK_JAR}"

echo "--- Shipped-jar assertions (A2/A3, rc4, ledger #11) ---"
# The shipped artifact is what gets tested: assert against the jar as
# it will leave the factory (staging app/iteration-board.jar, frontend
# already embedded), not against the Maven output. Any failure aborts
# the build - a wrong-version jar must never ship again (rc3 did).
SHIPPED_JAR="${STAGING_DIR}/app/iteration-board.jar"
assert_fail() { echo "[ERROR] SHIPPED-JAR ASSERTION FAILED: $1"; echo "[ERROR] Build aborted - do NOT ship this package."; exit 1; }
# Assertion inputs are extracted to FILES first, never piped into
# grep -q / head: under `set -o pipefail`, an early-exiting consumer
# SIGPIPEs the producer (unzip exits 141) and the pipeline reports
# failure even when the pattern matched - that false negative aborted
# the first rc4 verification build at A2-2 with the class present.
ASSERT_DIR="${BUILD_TMP}/assert"
mkdir -p "${ASSERT_DIR}"
unzip -p "${SHIPPED_JAR}" BOOT-INF/classes/application.yml > "${ASSERT_DIR}/app.yml"
unzip -Z1 "${SHIPPED_JAR}" > "${ASSERT_DIR}/listing.txt"
# A2-1: the jar's own application.yml version must equal ${VERSION}.
JAR_VERSION="$(grep -m1 -E '^[[:space:]]*version:' "${ASSERT_DIR}/app.yml" | sed -E 's/^[[:space:]]*version:[[:space:]]*//; s/[[:space:]]*$//')"
[ "${JAR_VERSION}" = "${VERSION}" ] || assert_fail "jar application.yml version is '${JAR_VERSION}', expected '${VERSION}'"
echo "[ok] A2-1 shipped jar version == ${VERSION}"
# A2-2: the rc3 endpoint class must be inside the jar (its absence is
# what made the new endpoints 404 on the real machine).
grep -q '^BOOT-INF/classes/com/iterationboard/item/ItemResolutionController.class$' "${ASSERT_DIR}/listing.txt" \
  || assert_fail "ItemResolutionController.class missing from shipped jar"
echo "[ok] A2-2 ItemResolutionController.class present in shipped jar"
# A2-3: highest Flyway migration inside the jar must equal the highest
# in the backend sources (sort -V on both sides).
JAR_MIG="$(grep -E '^BOOT-INF/classes/db/migration/V[^/]*\.sql$' "${ASSERT_DIR}/listing.txt" | sed 's|.*/||' | sort -V | tail -1)"
SRC_MIG="$(ls "${REPO_ROOT}/backend/src/main/resources/db/migration/"V*.sql | xargs -n1 basename | sort -V | tail -1)"
[ -n "${JAR_MIG}" ] && [ "${JAR_MIG}" = "${SRC_MIG}" ] \
  || assert_fail "highest Flyway migration in jar is '${JAR_MIG}', sources have '${SRC_MIG}'"
echo "[ok] A2-3 highest Flyway migration matches sources: ${SRC_MIG}"
# A3: the shipped jar's backend fingerprint must equal the step-1
# jar's (embedding/assembly must not swap or corrupt the payload).
SHIPPED_FINGERPRINT="$(jar_fingerprint "${SHIPPED_JAR}")"
[ "${SHIPPED_FINGERPRINT}" = "${JAR_FINGERPRINT}" ] \
  || assert_fail "backend fingerprint mismatch: built jar ${JAR_FINGERPRINT} vs shipped jar ${SHIPPED_FINGERPRINT}"
echo "[ok] A3 shipped jar fingerprint matches built jar: ${SHIPPED_FINGERPRINT}"
# A5: the frontend embedded in the shipped jar must be byte-
# identical to the frontend/dist built in step 2 of this run (A3
# excludes static/** by design; this closes that blind spot).
SHIPPED_STATIC_FINGERPRINT="$(jar_static_fingerprint "${SHIPPED_JAR}")"
[ "${SHIPPED_STATIC_FINGERPRINT}" = "${DIST_FINGERPRINT}" ] \
  || assert_fail "frontend static fingerprint mismatch: built dist ${DIST_FINGERPRINT} vs shipped jar ${SHIPPED_STATIC_FINGERPRINT}"
echo "[ok] A5 shipped jar embedded frontend matches built dist: ${SHIPPED_STATIC_FINGERPRINT}"
cp "${SCRIPT_DIR}/config/application.yml" "${STAGING_DIR}/config/application.yml"
cp -r "${REPO_ROOT}/backend/config/prompts" "${STAGING_DIR}/config/prompts"
for f in install start stop uninstall backup restore upgrade apply-patch rollback reset-admin migrate-export migrate-import; do
  cp "${SCRIPT_DIR}/windows/${f}.bat" "${STAGING_DIR}/${f}.bat"
done
# version.txt: the installed copy is how scripts on the target
# learn the program version (manifest checks, rollback info).
printf '%s\n%s\n' "${VERSION}" "$(date +%F)" > "${STAGING_DIR}/version.txt"
echo "[ok] version.txt written: ${VERSION}" 
cp "${SCRIPT_DIR}/windows/IterationBoardApp.xml" "${STAGING_DIR}/service/"
# The service XML must be well-formed or WinSW refuses to load it
# at service start (an XML comment containing a double hyphen did
# exactly that in the first 1.0.3 runner verification). Parse it
# here so a malformed template fails the build, not the install.
python3 -c "import xml.etree.ElementTree, sys; xml.etree.ElementTree.parse(sys.argv[1])" "${STAGING_DIR}/service/IterationBoardApp.xml" || { echo "[error] IterationBoardApp.xml is not well-formed XML. Build aborted."; exit 1; }
echo "[ok] IterationBoardApp.xml is well-formed"
# No IterationBoardPG.xml since 1.0.3: PostgreSQL is registered
# with pg_ctl register, not wrapped by WinSW.
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
[ -s "${CACHE_DIR}/WinSW-x64.exe" ] && cp "${CACHE_DIR}/WinSW-x64.exe" "${STAGING_DIR}/runtime/winsw/"

echo "--- Placing VC++ runtime DLLs (app-local) ---"
for placement in "${VC_PLACEMENTS[@]}"; do
  dest="${placement%%:*}"
  dlls="${placement#*:}"
  mkdir -p "${STAGING_DIR}/${dest}"
  for dll in ${dlls}; do
    cp "${VC_DLL_DIR}/${dll}" "${STAGING_DIR}/${dest}/${dll}"
    if [ ! -s "${STAGING_DIR}/${dest}/${dll}" ]; then
      echo "[error] failed to place ${dll} into ${dest}. Build aborted."
      exit 1
    fi
  done
  echo "[ok] ${dest}: ${dlls}"
done
# vc_redist.x64.exe must NOT be in the package anymore.
if [ -e "${STAGING_DIR}/runtime/vc_redist.x64.exe" ]; then
  echo "[error] runtime/vc_redist.x64.exe present in staging. Build aborted."
  exit 1
fi

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
