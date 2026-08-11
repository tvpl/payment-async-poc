#!/usr/bin/env python3
"""Prove that no standalone boundary resolves a cross-boundary dependency from sibling source.

MIG-05 forbids reintroducing a source-level shared build once a boundary is extracted: every
cross-boundary dependency (payment-contracts, feature-control) must be declared as a Maven GAV
resolved from a published repository, never via `includeBuild`, a `project(':...')` reference, or
a raw filesystem path into a sibling root's `src/`. `scripts/artifacts/verify-artifact-only.sh`
already proves the mechanism generically with a throwaway fixture (T1/T6); this checks the real
consumer boundaries that ship it.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


# Each boundary's settings.gradle/build.gradle, checked for text that would pull in a sibling
# boundary's source tree instead of its published artifact.
CONSUMER_BOUNDARIES = ("payment-api", "payment-sbus", "payment-core-mock", "async-redis-service")
FORBIDDEN_PATTERNS = ("includeBuild", "project(':payment-", "project(':feature-control'")
SIBLING_SOURCE_REFERENCES = (
    "../payment-contracts/contract-model/src",
    "../payment-contracts/contract-avro-apicurio/src",
    "../feature-control/library/src",
)


def boundary_errors(root: Path, boundary: str) -> list[str]:
    boundary_dir = root / boundary
    errors = []
    for filename in ("settings.gradle", "build.gradle"):
        path = boundary_dir / filename
        if not path.is_file():
            errors.append(f"{boundary}/{filename}: missing")
            continue
        text = path.read_text(encoding="utf-8")
        for forbidden in (*FORBIDDEN_PATTERNS, *SIBLING_SOURCE_REFERENCES):
            if forbidden in text:
                errors.append(f"{boundary}/{filename}: reads cross-boundary source via {forbidden!r}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    errors = []
    for boundary in CONSUMER_BOUNDARIES:
        errors.extend(boundary_errors(args.root, boundary))

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"no-composite-build: PASS ({len(CONSUMER_BOUNDARIES)} boundaries checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
