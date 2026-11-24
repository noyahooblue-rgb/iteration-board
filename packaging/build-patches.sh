#!/bin/bash
# ============================================================
# Iteration Board - patch zip assembler (1.1.0)
#
# Usage:  bash packaging/build-patches.sh
#
# Assembles the three 1.1.0 patch zips from the SAME staging
# tree the full offline package was built from (A4 same-source
# rule, root-cause ledger #11: patch payloads are never taken
# from anywhere else, so the jar inside every patch is
# byte-identical to the jar inside the full package - this
# script prints all four sha256 values at the end).
#
# Inputs : packaging/staging/iteration-board-offline-1.1.0/
#          (produced by packaging/build-package.sh; run that
#          first - this script refuses to run without it and
#          checks the staging version.txt says 1.1.0)
#          packaging/patch-notes/note-<from>-to-1.1.0.txt
# Outputs: packaging/dist/iteration-board-patch-1.1.0-rc4-to-1.1.0.zip
#          packaging/dist/iteration-board-patch-1.0.4-to-1.1.0.zip
#          packaging/dist/iteration-board-patch-1.0.1-to-1.1.0.zip
#
# Per patch (rc3/rc4 precedent):
#   - apply-patch.bat is stamped with IB_EXPECTED_FROM=<from>
#     (the source-version gate); every other file is copied
#     verbatim from staging.
#   - 补丁说明.txt ships as UTF-8 with BOM + CRLF (the repo
#     template is plain UTF-8/LF; converted here).
#   - SHA256SUMS.txt (LF, "<sha256>  <relpath>") lists every
#     payload file in the fixed order below; the patch's own
#     apply-patch verifies against it before touching anything.
#   - The zip has NO directory entries (zip -D), like the
#     rc3/rc4 patches.
# The 1.0.1 cumulative patch additionally carries the files
# 1.0.1 lacks: uninstall.bat, the app-local VC++ runtime DLLs
# and the service definition XML.
# ============================================================
set -euo pipefail

VERSION="1.1.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAGING_DIR="${SCRIPT_DIR}/staging/iteration-board-offline-${VERSION}"
DIST_DIR="${SCRIPT_DIR}/dist"
WORK_DIR="${SCRIPT_DIR}/.tmp/patches"

[ -d "${STAGING_DIR}" ] || { echo "[error] staging tree missing: ${STAGING_DIR} - run build-package.sh first"; exit 1; }
STAGING_VER="$(head -1 "${STAGING_DIR}/version.txt")"
[ "${STAGING_VER}" = "${VERSION}" ] || { echo "[error] staging version.txt says '${STAGING_VER}', expected '${VERSION}'"; exit 1; }

# Payload files common to all three patches, in SHA256SUMS order.
COMMON_FILES=(
  "app/iteration-board.jar"
  "apply-patch.bat"
  "backup.bat"
  "install.bat"
  "migrate-export.bat"
  "migrate-import.bat"
  "reset-admin.bat"
  "restore.bat"
  "rollback.bat"
  "start.bat"
  "stop.bat"
  "upgrade.bat"
  "docs/用户手册.md"
  "docs/部署手册.md"
  "version.txt"
)
# Extra payload only for the 1.0.1 cumulative patch (appended
# to the sums in this order, before 补丁说明.txt).
EXTRA_101_FILES=(
  "uninstall.bat"
  "runtime/pgsql/bin/msvcp140.dll"
  "runtime/pgsql/bin/vcruntime140.dll"
  "runtime/pgsql/bin/vcruntime140_1.dll"
  "runtime/pgsql/lib/msvcp140.dll"
  "runtime/pgsql/lib/vcruntime140.dll"
  "runtime/pgsql/lib/vcruntime140_1.dll"
  "service/IterationBoardApp.xml"
)

assemble_patch() { # assemble_patch <from-version> <note-file> <extra:0|1>
  local from="$1" note="$2" extra="$3"
  local name="iteration-board-patch-${from}-to-${VERSION}"
  local dir="${WORK_DIR}/${name}"
  rm -rf "${dir}"
  mkdir -p "${dir}"
  local files=("${COMMON_FILES[@]}")
  if [ "${extra}" = "1" ]; then files+=("${EXTRA_101_FILES[@]}"); fi
  local rel src dst
  for rel in "${files[@]}"; do
    src="${STAGING_DIR}/${rel}"
    [ -f "${src}" ] || { echo "[error] staging file missing: ${rel}"; exit 1; }
    dst="${dir}/${rel}"
    mkdir -p "$(dirname "${dst}")"
    cp "${src}" "${dst}"
  done
  # Stamp the source-version gate into THIS patch's apply-patch
  # copy only (prefix substitution keeps the trailing quote and
  # the CRLF line ending intact).
  sed -i "s/set \"IB_EXPECTED_FROM=\"/set \"IB_EXPECTED_FROM=${from}\"/" "${dir}/apply-patch.bat"
  grep -q "set \"IB_EXPECTED_FROM=${from}\"" "${dir}/apply-patch.bat" \
    || { echo "[error] IB_EXPECTED_FROM stamp failed for ${from}"; exit 1; }
  # 补丁说明.txt: repo template (UTF-8/LF) -> shipped form (BOM + CRLF).
  { printf '\xef\xbb\xbf'; sed 's/$/\r/' "${SCRIPT_DIR}/patch-notes/${note}"; } > "${dir}/补丁说明.txt"
  # SHA256SUMS.txt in the fixed order, note last.
  : > "${dir}/SHA256SUMS.txt"
  for rel in "${files[@]}" "补丁说明.txt"; do
    (cd "${dir}" && sha256sum "${rel}") >> "${dir}/SHA256SUMS.txt"
  done
  # Self-check: every listed file verifies against the sums.
  (cd "${dir}" && sha256sum -c SHA256SUMS.txt > /dev/null) \
    || { echo "[error] SHA256SUMS self-check failed for ${name}"; exit 1; }
  # Zip without directory entries (rc3/rc4 precedent). Entry
  # order: sums, note, version, then payload in sums order.
  rm -f "${DIST_DIR}/${name}.zip"
  (cd "${dir}" && zip -q -D "${DIST_DIR}/${name}.zip" SHA256SUMS.txt 补丁说明.txt version.txt)
  for rel in "${files[@]}"; do
    [ "${rel}" = "version.txt" ] && continue
    (cd "${dir}" && zip -q -D "${DIST_DIR}/${name}.zip" "${rel}")
  done
  echo "[ok] ${name}.zip ($(stat -c%s "${DIST_DIR}/${name}.zip") bytes, $(unzip -Z1 "${DIST_DIR}/${name}.zip" | wc -l) entries)"
}

mkdir -p "${DIST_DIR}" "${WORK_DIR}"
assemble_patch "1.1.0-rc4" "note-rc4-to-1.1.0.txt" 0
assemble_patch "1.0.4"     "note-1.0.4-to-1.1.0.txt" 0
assemble_patch "1.0.1"     "note-1.0.1-to-1.1.0.txt" 1

echo "--- A4 same-source check: jar sha256 in all four packages ---"
FULL_JAR_SHA="$(unzip -p "${DIST_DIR}/iteration-board-offline-${VERSION}.zip" "iteration-board-offline-${VERSION}/app/iteration-board.jar" | sha256sum | awk '{print $1}')"
echo "full  iteration-board-offline-${VERSION}.zip : ${FULL_JAR_SHA}"
FAIL=0
for from in 1.1.0-rc4 1.0.4 1.0.1; do
  s="$(unzip -p "${DIST_DIR}/iteration-board-patch-${from}-to-${VERSION}.zip" "app/iteration-board.jar" | sha256sum | awk '{print $1}')"
  echo "patch iteration-board-patch-${from}-to-${VERSION}.zip : ${s}"
  [ "${s}" = "${FULL_JAR_SHA}" ] || FAIL=1
done
[ "${FAIL}" = "0" ] || { echo "[error] A4 FAILED: patch jar differs from full-package jar"; exit 1; }
echo "[ok] A4 all four jars byte-identical: ${FULL_JAR_SHA}"
