#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))
SPEC = importlib.util.spec_from_file_location("validate_docs", SCRIPT_DIR / "validate_docs.py")
validate_docs = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(validate_docs)


class DocumentationValidationTest(unittest.TestCase):
    def test_current_documentation_and_manifest_are_valid(self) -> None:
        self.assertEqual([], validate_docs.validate(REPOSITORY_ROOT))

    def test_every_current_section_has_an_exact_destination(self) -> None:
        recorded = validate_docs.load_json(SCRIPT_DIR / "relocation-manifest.json")

        self.assertEqual([], validate_docs.manifest_errors(REPOSITORY_ROOT, recorded))
        self.assertEqual(251, recorded["section_count"])

    def test_broken_relative_link_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["broken link docs/README.md -> missing.md"],
            validate_docs.link_errors(REPOSITORY_ROOT, path, "[missing](missing.md)"),
        )

    def test_unknown_command_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["unknown command missing-command in docs/README.md"],
            validate_docs.command_errors(REPOSITORY_ROOT, path, "```bash\nmissing-command run\n```", {"curl"}),
        )

    def test_unknown_port_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["unknown port 65534 in docs/README.md"],
            validate_docs.port_errors(REPOSITORY_ROOT, path, "http://localhost:65534"),
        )

    def test_unknown_variable_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["unknown variable UNKNOWN_DOCUMENT_VARIABLE in docs/README.md"],
            validate_docs.variable_errors(REPOSITORY_ROOT, path, "${UNKNOWN_DOCUMENT_VARIABLE}"),
        )

    def test_unverified_metric_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["unverified metric imaginary_failures_total in docs/README.md"],
            validate_docs.metric_errors(REPOSITORY_ROOT, path, "`imaginary_failures_total`", []),
        )

    def test_unregistered_production_claim_is_rejected(self) -> None:
        path = REPOSITORY_ROOT / "docs/README.md"

        self.assertEqual(
            ["unregistered production claim 'pronto para carga real' in docs/README.md"],
            validate_docs.claim_errors(REPOSITORY_ROOT, path, "pronto para carga real", []),
        )

    def test_dashboard_metric_without_implementation_is_rejected(self) -> None:
        corpus = validate_docs.executable_corpus(REPOSITORY_ROOT)

        self.assertEqual({"imaginary_pending"}, validate_docs.metrics_in_expression("sum(imaginary_pending)"))
        self.assertFalse(validate_docs.implemented_metric("imaginary_pending", corpus))


if __name__ == "__main__":
    unittest.main()
