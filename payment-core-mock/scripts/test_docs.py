#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
BOUNDARY_ROOT = SCRIPT_DIR.parent
SPEC = importlib.util.spec_from_file_location("core_mock_docs", SCRIPT_DIR / "validate_docs.py")
core_mock_docs = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(core_mock_docs)


class CoreMockDocumentationTest(unittest.TestCase):
    def test_current_documentation_is_complete_and_consistent(self) -> None:
        self.assertEqual([], core_mock_docs.validate(BOUNDARY_ROOT))

    def test_missing_required_document_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = core_mock_docs.missing_document_errors(Path(directory))
            self.assertEqual(len(core_mock_docs.REQUIRED_DOCUMENTS), len(errors))

    def test_broken_relative_link_is_rejected(self) -> None:
        path = BOUNDARY_ROOT / "README.md"
        self.assertEqual(
            ["broken link README.md -> missing.md"],
            core_mock_docs.link_errors(BOUNDARY_ROOT, path, "[missing](missing.md)"),
        )

    def test_positive_production_claim_is_rejected(self) -> None:
        errors = core_mock_docs.claim_errors("README.md", "production-ready integration")
        self.assertEqual(1, len(errors))
        self.assertIn("forbidden lifecycle claim", errors[0])

    def test_obsolete_source_dependency_is_rejected(self) -> None:
        self.assertEqual(
            ["obsolete cross-owner reference 'common/src/main' in docs/contracts.md"],
            core_mock_docs.claim_errors("docs/contracts.md", "copy common/src/main"),
        )

    def test_missing_required_contract_content_is_rejected(self) -> None:
        errors = core_mock_docs.required_content_errors("docs/contracts.md", "# Contratos\n")
        self.assertEqual(4, len(errors))

    def test_incomplete_adr_is_rejected(self) -> None:
        path = BOUNDARY_ROOT / "docs/adr/0001-non-production-deterministic-simulator.md"
        errors = core_mock_docs.adr_errors(path, "# ADR\nStatus: Proposed\n")
        self.assertEqual(6, len(errors))

    def test_all_classification_surfaces_are_enforced(self) -> None:
        self.assertEqual([], core_mock_docs.classification_errors(BOUNDARY_ROOT))


if __name__ == "__main__":
    unittest.main()
