#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repository_root/scripts/artifacts/consumer-fixture"
artifact_repository="$repository_root/feature-control/build/repo"

"$repository_root/gradlew" -p "$repository_root" \
  :feature-control:publishMavenPublicationToLocalBuildRepository --no-daemon

python3 "$repository_root/scripts/artifacts/check_artifact_flow.py" \
  --repository "$artifact_repository" \
  --fixture "$fixture"

"$repository_root/gradlew" -p "$fixture" compileJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$artifact_repository"

missing_repository="$(mktemp -d)"
trap 'rm -rf "$missing_repository"' EXIT
set +e
"$repository_root/gradlew" -p "$fixture" compileJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$missing_repository" >/dev/null 2>&1
missing_exit=$?
set -e
if [[ "$missing_exit" -eq 0 ]]; then
  echo "ERROR: consumer resolved the GAV without the published artifact" >&2
  exit 1
fi

echo "artifact-only-consumer: PASS (published GAV resolves; missing GAV fails)"
