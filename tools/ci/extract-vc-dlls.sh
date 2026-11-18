#!/bin/bash
# ============================================================
# Extract the three app-local VC++ runtime DLLs from Microsoft's
# official vc_redist.x64.exe into packaging/cache/vc-dlls/.
#
# Why: build-package.sh needs the DLLs in cache but binaries are
# never committed to git, so a fresh CI runner starts with an
# empty cache. This script reproduces the one-off manual
# extraction deterministically:
#
# vc_redist.x64.exe is a WiX Burn bundle. Its attached payload
# container is a CAB appended after the PE image and the UX
# container CAB; its entries are payloads named a0, a1, ...
# The vcRuntimeAdditional amd64 payload is itself a CAB holding
# the runtime files with an _amd64 suffix
# (vcruntime140.dll_amd64, ...). We locate that payload by
# content (a CAB containing vcruntime140.dll_amd64), not by a
# hard-coded payload index, so the script survives bundle
# layout changes. build-package.sh then verifies the pinned
# SHA-256 of every DLL and fails the build on any mismatch -
# if Microsoft ships a newer redist, the failure is loud and
# the pins must be re-done deliberately.
#
# Requires: curl, python3, 7z (p7zip-full) or 7zz.
# Usage: bash tools/ci/extract-vc-dlls.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEST="${REPO_ROOT}/packaging/cache/vc-dlls"
URL="https://aka.ms/vs/17/release/vc_redist.x64.exe"

SEVENZ="$(command -v 7z || command -v 7zz || true)"
[ -n "${SEVENZ}" ] || { echo "[error] 7z/7zz not found (install p7zip-full)"; exit 1; }

mkdir -p "${DEST}"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "[fetch] ${URL}"
curl -fL --retry 2 --connect-timeout 30 -o "${WORK}/vc_redist.x64.exe" "${URL}"

# Locate the attached payload container: scan for CAB signatures
# (MSCF) from the END of the file backwards; the payload container
# is the CAB whose entries are named a<number>. (The first CAB in
# the file is the UX container with u<number> entries.)
PAYLOAD_CAB=""
python3 - "${WORK}/vc_redist.x64.exe" "${WORK}" <<'PY'
import sys
data = open(sys.argv[1], 'rb').read()
offs, i = [], 0
while True:
 j = data.find(b'MSCF', i)
 if j < 0:
 break
 offs.append(j)
 i = j + 1
for k, off in enumerate(reversed(offs)):
 open(f"{sys.argv[2]}/cand{k}.cab", 'wb').write(data[off:])
 print(k, off)
PY
for cand in "${WORK}"/cand*.cab; do
 if "${SEVENZ}" l "${cand}" 2>/dev/null | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2} .* a[0-9]+$'; then
 PAYLOAD_CAB="${cand}"
 break
 fi
done
[ -n "${PAYLOAD_CAB}" ] || { echo "[error] attached payload container not found in vc_redist bundle"; exit 1; }
echo "[ok] payload container: ${PAYLOAD_CAB}"

mkdir -p "${WORK}/payloads"
"${SEVENZ}" x -y "${PAYLOAD_CAB}" -o"${WORK}/payloads" >/dev/null

# Find the vcRuntimeAdditional payload by content.
ADDITIONAL=""
for p in "${WORK}/payloads"/a*; do
 if "${SEVENZ}" l "${p}" 2>/dev/null | grep -q 'vcruntime140\.dll_amd64'; then
 ADDITIONAL="${p}"
 break
 fi
done
[ -n "${ADDITIONAL}" ] || { echo "[error] vcRuntimeAdditional payload not found"; exit 1; }
echo "[ok] vcRuntimeAdditional payload: ${ADDITIONAL}"

mkdir -p "${WORK}/dlls"
"${SEVENZ}" x -y "${ADDITIONAL}" -o"${WORK}/dlls" >/dev/null
for dll in vcruntime140.dll vcruntime140_1.dll msvcp140.dll; do
 src="${WORK}/dlls/${dll}_amd64"
 [ -s "${src}" ] || { echo "[error] ${dll}_amd64 missing in payload"; exit 1; }
 cp "${src}" "${DEST}/${dll}"
 echo "[ok] ${dll} sha256=$(sha256sum "${DEST}/${dll}" | awk '{print $1}')"
done
echo "Done. build-package.sh will verify the pinned hashes."
