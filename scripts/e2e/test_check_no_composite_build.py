#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location(
    "check_no_composite_build", SCRIPT_DIR / "check_no_composite_build.py"
)
check_no_composite_build = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(check_no_composite_build)


class NoCompositeBuildTest(unittest.TestCase):
    def test_real_boundaries_declare_no_cross_boundary_source(self) -> None:
        errors = []
        for boundary in check_no_composite_build.CONSUMER_BOUNDARIES:
            errors.extend(check_no_composite_build.boundary_errors(REPOSITORY_ROOT, boundary))

        self.assertEqual([], errors)

    def test_include_build_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            boundary = root / "payment-api"
            boundary.mkdir()
            (boundary / "settings.gradle").write_text(
                "includeBuild('../payment-contracts')\n", encoding="utf-8"
            )
            (boundary / "build.gradle").write_text("", encoding="utf-8")

            errors = check_no_composite_build.boundary_errors(root, "payment-api")

        self.assertEqual(1, len(errors))
        self.assertIn("includeBuild", errors[0])

    def test_sibling_source_reference_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            boundary = root / "payment-sbus"
            boundary.mkdir()
            (boundary / "settings.gradle").write_text("", encoding="utf-8")
            (boundary / "build.gradle").write_text(
                "sourceSets { main { java { srcDir '../payment-contracts/contract-model/src' } } }\n",
                encoding="utf-8",
            )

            errors = check_no_composite_build.boundary_errors(root, "payment-sbus")

        self.assertEqual(1, len(errors))
        self.assertIn("../payment-contracts/contract-model/src", errors[0])

    def test_missing_build_files_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "payment-core-mock").mkdir()

            errors = check_no_composite_build.boundary_errors(root, "payment-core-mock")

        self.assertEqual(2, len(errors))
        self.assertTrue(all("missing" in error for error in errors))

    def test_clean_gav_declaration_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            boundary = root / "async-redis-service"
            boundary.mkdir()
            (boundary / "settings.gradle").write_text("rootProject.name = 'async-redis-service'\n", encoding="utf-8")
            (boundary / "build.gradle").write_text(
                "dependencies { implementation \"com.example.payments:payment-contract-model:1.0.0\" }\n",
                encoding="utf-8",
            )

            errors = check_no_composite_build.boundary_errors(root, "async-redis-service")

        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
