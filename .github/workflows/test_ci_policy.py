#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location("ci_policy", SCRIPT_DIR / "check_ci_policy.py")
ci_policy = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ci_policy)


class CiPolicyTest(unittest.TestCase):
    def test_current_ci_policy_is_complete(self) -> None:
        self.assertEqual([], ci_policy.validate(REPOSITORY_ROOT))

    def test_all_boundaries_are_in_the_matrix(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertEqual(
            set(),
            {boundary for boundary in ci_policy.BOUNDARIES if f"boundary: {boundary}" not in workflow},
        )

    def test_required_integration_suites_are_explicit(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertIn(":api-service:test -PwithIT", workflow)
        self.assertIn(":sbus-service:test -PwithIT", workflow)
        self.assertIn(":async-redis-service:test -PwithIT", workflow)
        self.assertIn(":feature-demo:test :pilot-app:test -PwithIT", workflow)

    def test_not_run_integration_is_blocking(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertIn('echo "result=NOT_RUN"', workflow)
        self.assertIn('test "$CI_GATE_RESULT" = PASS', workflow)

    def test_quality_policies_are_explicit(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertIn("actionlint:1.7.7", workflow)
        self.assertIn("github/codeql-action/analyze@v3", workflow)
        self.assertIn("COVERAGE_GATE_STATUS: NOT_RUN", workflow)

    def test_dependency_updates_cover_gradle_and_actions(self) -> None:
        dependabot = (REPOSITORY_ROOT / ".github/dependabot.yml").read_text(encoding="utf-8")

        self.assertIn("package-ecosystem: gradle", dependabot)
        self.assertIn("package-ecosystem: github-actions", dependabot)


if __name__ == "__main__":
    unittest.main()
