#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("validate_docs", SCRIPT_DIR / "validate_docs.py")
validate_docs = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validate_docs)


class DocsValidationTest(unittest.TestCase):
    def test_the_real_documentation_tree_has_no_errors(self) -> None:
        self.assertEqual([], validate_docs.validate(validate_docs.ROOT))

    def test_a_missing_required_document_is_reported(self) -> None:
        errors = validate_docs.validate(Path("/nonexistent-root-for-this-test"))

        self.assertTrue(any("missing required document" in error for error in errors))
        self.assertEqual(len(validate_docs.REQUIRED), len(errors))


if __name__ == "__main__":
    unittest.main()
