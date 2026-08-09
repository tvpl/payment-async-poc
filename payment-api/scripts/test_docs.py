#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
SPEC = importlib.util.spec_from_file_location("api_docs", SCRIPT_DIR / "validate_docs.py")
api_docs = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(api_docs)


class ApiDocumentationTest(unittest.TestCase):
    def test_current_documentation_is_complete_and_consistent(self):
        self.assertEqual([], api_docs.validate(ROOT))

    def test_missing_package_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            errors = api_docs.validate(Path(directory))
            self.assertEqual(len(api_docs.REQUIRED), len(errors))

    def test_broken_link_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in api_docs.REQUIRED:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("# doc\n", encoding="utf-8")
            (root / "README.md").write_text("[broken](missing.md)\n", encoding="utf-8")
            self.assertTrue(any("broken link" in error for error in api_docs.validate(root)))


if __name__ == "__main__":
    unittest.main()
