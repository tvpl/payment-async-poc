#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repository_root/scripts/artifacts/consumer-fixture"
artifact_repository="$(mktemp -d)"
trap 'rm -rf "$artifact_repository"' EXIT

"$repository_root/gradlew" -p "$fixture" compileJava --no-daemon \
  -PartifactRepository="$artifact_repository" \
  --include-build "$repository_root"
