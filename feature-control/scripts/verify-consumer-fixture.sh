#!/usr/bin/env bash
set -euo pipefail

boundary_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="$boundary_root/library/build/repo"
fixture="$boundary_root/consumer-fixture"

"$boundary_root/gradlew" -p "$boundary_root" \
  publishLibraryToLocalBuildRepository verifyLocalPublication --no-daemon

python3 "$boundary_root/scripts/check_consumer_fixture.py" \
  --repository "$repository" \
  --fixture "$fixture"

python3 "$boundary_root/scripts/verify_api_surface.py" \
  --jar "$repository/com/example/platform/feature-control/0.1.0/feature-control-0.1.0.jar" \
  --baseline "$fixture/api-surface-baseline.txt"

"$boundary_root/gradlew" -p "$fixture" test --no-daemon --refresh-dependencies \
  -PartifactRepository="$repository"

missing_repository="$(mktemp -d)"
set +e
"$boundary_root/gradlew" -p "$fixture" compileTestJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$missing_repository" >/dev/null 2>&1
missing_exit=$?
set -e
if [[ "$missing_exit" -eq 0 ]]; then
  echo "ERROR: fixture resolved the feature-control GAV without a published artifact" >&2
  exit 1
fi

echo "feature-control-consumer-gate: PASS (artifact round-trip, API surface, missing GAV fails)"
