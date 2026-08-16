#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location(
    "check_internal_contract", SCRIPT_DIR / "check_internal_contract.py"
)
check_internal_contract = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(check_internal_contract)


class InternalContractTest(unittest.TestCase):
    def test_real_fixtures_are_byte_identical(self) -> None:
        errors = check_internal_contract.compare_fixtures(REPOSITORY_ROOT)

        self.assertEqual([], errors)

    def test_divergent_fixtures_are_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sbus_path = root / check_internal_contract.SBUS_FIXTURE
            api_path = root / check_internal_contract.API_FIXTURE
            sbus_path.parent.mkdir(parents=True)
            api_path.parent.mkdir(parents=True)
            sbus_path.write_text('{"requestId": "a"}\n', encoding="utf-8")
            api_path.write_text('{"requestId": "a", "status": "COMPLETED"}\n', encoding="utf-8")

            errors = check_internal_contract.compare_fixtures(root)

        self.assertEqual(1, len(errors))
        self.assertIn("diverged byte-for-byte", errors[0])

    def test_identical_fixtures_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sbus_path = root / check_internal_contract.SBUS_FIXTURE
            api_path = root / check_internal_contract.API_FIXTURE
            sbus_path.parent.mkdir(parents=True)
            api_path.parent.mkdir(parents=True)
            content = '{"requestId": "a", "status": "COMPLETED"}\n'
            sbus_path.write_text(content, encoding="utf-8")
            api_path.write_text(content, encoding="utf-8")

            errors = check_internal_contract.compare_fixtures(root)

        self.assertEqual([], errors)

    def test_missing_fixture_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            errors = check_internal_contract.compare_fixtures(root)

        self.assertEqual(2, len(errors))
        self.assertTrue(all("missing" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
