#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
BOUNDARY_ROOT = SCRIPT_DIR.parent
SPEC = importlib.util.spec_from_file_location("contracts_docs", SCRIPT_DIR / "validate_docs.py")
contracts_docs = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(contracts_docs)


class ContractsDocumentationTest(unittest.TestCase):
    def test_current_documentation_is_complete_and_consistent(self) -> None:
        self.assertEqual([], contracts_docs.validate(BOUNDARY_ROOT))

    def test_missing_required_document_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = contracts_docs.missing_document_errors(Path(directory))

            self.assertEqual(len(contracts_docs.REQUIRED_DOCUMENTS), len(errors))
            self.assertTrue(all(error.startswith("missing required document:") for error in errors))

    def test_broken_relative_link_is_rejected(self) -> None:
        path = BOUNDARY_ROOT / "README.md"

        self.assertEqual(
            ["broken link README.md -> missing.md"],
            contracts_docs.link_errors(BOUNDARY_ROOT, path, "[missing](missing.md)"),
        )

    def test_obsolete_cross_owner_reference_is_rejected(self) -> None:
        self.assertEqual(
            ["obsolete cross-owner reference 'common/src/main' in docs/contracts.md"],
            contracts_docs.content_errors("docs/contracts.md", "use common/src/main/avro"),
        )

    def test_missing_contract_policy_content_is_rejected(self) -> None:
        errors = contracts_docs.required_content_errors("docs/contracts.md", "# Contratos\n## Envelope\n")

        self.assertEqual(4, len(errors))
        self.assertIn("missing required content 'FULL_TRANSITIVE' in docs/contracts.md", errors)

    def test_incomplete_adr_is_rejected(self) -> None:
        path = BOUNDARY_ROOT / "docs/adr/0001-contract-artifacts-and-compatibility.md"
        errors = contracts_docs.adr_errors(path, "# ADR\nStatus: Proposed\n")

        self.assertEqual(6, len(errors))
        self.assertIn("ADR missing accepted status: 0001-contract-artifacts-and-compatibility.md", errors)


if __name__ == "__main__":
    unittest.main()
