#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location("root_governance", SCRIPT_DIR / "check_root_governance.py")
root_governance = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(root_governance)


class RootGovernanceTest(unittest.TestCase):
    def test_root_documents_map_all_seven_boundaries(self) -> None:
        for name in root_governance.ROOT_DOCUMENTS:
            text = (REPOSITORY_ROOT / name).read_text(encoding="utf-8")

            self.assertEqual(set(), root_governance.missing_boundaries(text), name)

    def test_root_agents_delegates_product_rules_and_infrastructure(self) -> None:
        agents = (REPOSITORY_ROOT / "AGENTS.md").read_text(encoding="utf-8")

        self.assertIn("`AGENTS.md` local", agents)
        self.assertIn("Somente `sandbox` cria infraestrutura", agents)

    def test_root_documents_do_not_duplicate_product_details(self) -> None:
        for name in root_governance.ROOT_DOCUMENTS:
            text = (REPOSITORY_ROOT / name).read_text(encoding="utf-8")

            for marker in root_governance.PRODUCT_DETAIL_MARKERS:
                self.assertNotIn(marker, text, name)

    def test_all_root_relative_links_resolve(self) -> None:
        broken = []
        for name in root_governance.ROOT_DOCUMENTS:
            broken.extend(root_governance.broken_relative_links(REPOSITORY_ROOT, REPOSITORY_ROOT / name))

        self.assertEqual([], broken)


if __name__ == "__main__":
    unittest.main()
