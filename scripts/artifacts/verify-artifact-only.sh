#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repository_root/scripts/artifacts/consumer-fixture"
artifact_repository="$repository_root/feature-control/build/repo"
# There is no root Gradle wrapper (T59 removed the transitional aggregator) — the fixture
# project has its own build.gradle/settings.gradle but no wrapper of its own, so it borrows
# feature-control's wrapper as a generic launcher via `-p`, same as always: the wrapper is a
# version-locked launcher, not tied to a specific project.
launcher="$repository_root/feature-control/gradlew"

"$launcher" -p "$repository_root/feature-control" \
  :feature-control:publishMavenPublicationToLocalBuildRepository --no-daemon

python3 "$repository_root/scripts/artifacts/check_artifact_flow.py" \
  --repository "$artifact_repository" \
  --fixture "$fixture"

"$launcher" -p "$fixture" compileJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$artifact_repository"

missing_repository="$(mktemp -d)"
trap 'rm -rf "$missing_repository"' EXIT
set +e
"$launcher" -p "$fixture" compileJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$missing_repository" >/dev/null 2>&1
missing_exit=$?
set -e
if [[ "$missing_exit" -eq 0 ]]; then
  echo "ERROR: consumer resolved the GAV without the published artifact" >&2
  exit 1
fi

echo "artifact-only-consumer: PASS (published GAV resolves; missing GAV fails)"
