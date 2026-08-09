#!/usr/bin/env python3
"""Validate the transitional CI contract without a YAML dependency."""

from __future__ import annotations

import sys
from pathlib import Path


BOUNDARIES = {
    "payment-contracts",
    "payment-api",
    "payment-sbus",
    "payment-core-mock",
    "feature-control",
    "async-redis-service",
    "sandbox",
}


def validate(root: Path) -> list[str]:
    workflow = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    dependabot = (root / ".github/dependabot.yml").read_text(encoding="utf-8")
    errors = []
    missing = sorted(boundary for boundary in BOUNDARIES if f"boundary: {boundary}" not in workflow)
    if missing:
        errors.append("missing boundary matrix entries: " + ", ".join(missing))
    for result in ("PASS", "FAIL", "NOT_RUN"):
        if result not in workflow:
            errors.append(f"missing result state: {result}")
    required_integration = (
        ":api-service:test -PwithIT",
        ":sbus-service:test -PwithIT",
        ":async-redis-service:test -PwithIT",
        ":feature-demo:test :pilot-app:test -PwithIT",
    )
    for command in required_integration:
        if command not in workflow:
            errors.append(f"missing integration command: {command}")
    if 'echo "result=NOT_RUN"' not in workflow or 'test "$CI_GATE_RESULT" = PASS' not in workflow:
        errors.append("NOT_RUN is not explicit and blocking for required integration")
    for marker in ("actionlint:1.7.7", "github/codeql-action/analyze@v3", "COVERAGE_GATE_STATUS: NOT_RUN"):
        if marker not in workflow:
            errors.append(f"missing quality policy: {marker}")
    for ecosystem in ("gradle", "github-actions"):
        if f"package-ecosystem: {ecosystem}" not in dependabot:
            errors.append(f"missing dependency update policy: {ecosystem}")
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("ci-policy: PASS (matrix, integration, outcomes, quality policies)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
