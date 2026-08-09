#!/usr/bin/env bash
set -euo pipefail

boundary_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="$boundary_root/build/repository"
fixture="$boundary_root/consumer-fixture"

"$boundary_root/gradlew" -p "$boundary_root" \
  publishAllToLocalBuildRepository verifyLocalPublication --no-daemon

python3 "$boundary_root/scripts/check_consumer_fixture.py" \
  --repository "$repository" \
  --fixture "$fixture"

"$boundary_root/gradlew" -p "$fixture" test --no-daemon --refresh-dependencies \
  -PartifactRepository="$repository"

missing_repository="$(mktemp -d)"
set +e
"$boundary_root/gradlew" -p "$fixture" compileTestJava --no-daemon --refresh-dependencies \
  -PartifactRepository="$missing_repository" >/dev/null 2>&1
missing_exit=$?
set -e
if [[ "$missing_exit" -eq 0 ]]; then
  echo "ERROR: fixture resolved contract GAVs without published artifacts" >&2
  exit 1
fi

echo "contracts-consumer-gate: PASS (artifact round-trip; missing GAV fails)"
