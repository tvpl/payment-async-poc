#!/usr/bin/env bash
# SBX-05: the sandbox SHALL load only infrastructure/observability assets belonging to
# applications "por mecanismo documentado e versionável" — application-assets.json is that
# mechanism. Structural check: every declared asset has a real owner, version and (when kind
# requires it) an existing file at `path`, resolved relative to the manifest itself.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib.sh"

MANIFEST="${REPO_ROOT}/sandbox/observability/application-assets.json"
MANIFEST_DIR="$(dirname "$MANIFEST")"

count=$(jq '.assets | length' "$MANIFEST")
if [ "$count" -lt 1 ]; then
  check_fail "application-assets.json declares owned assets" "assets array is empty"
  return 0
fi
check_pass "application-assets.json declares ${count} owned assets"

bad=0
while IFS=$'\t' read -r owner version kind path; do
  if [ -z "$owner" ] || [ -z "$version" ] || [ -z "$kind" ]; then
    log "!! asset missing owner/version/kind: owner=${owner} version=${version} kind=${kind}"
    bad=$((bad + 1))
    continue
  fi
  if [ "$path" != "null" ] && [ -n "$path" ]; then
    if [ ! -f "${MANIFEST_DIR}/${path}" ]; then
      log "!! asset path does not resolve: owner=${owner} path=${path}"
      bad=$((bad + 1))
    fi
  fi
done < <(jq -r '.assets[] | [.owner, .version, .kind, .path] | @tsv' "$MANIFEST")

if [ "$bad" -eq 0 ]; then
  check_pass "every declared asset has owner+version+kind, and path (when present) resolves to a real file"
else
  check_fail "every declared asset has owner+version+kind, and path (when present) resolves to a real file" \
    "${bad} asset(s) failed — see log above"
fi
