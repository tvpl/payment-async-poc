#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location("equivalence", SCRIPT_DIR / "equivalence.py")
equivalence = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(equivalence)


class EquivalenceGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.expected = equivalence.load_json(SCRIPT_DIR / "baseline-manifest.json")

    def test_generation_is_deterministic(self) -> None:
        first = equivalence.dump_json(equivalence.build_manifest(REPOSITORY_ROOT))
        second = equivalence.dump_json(equivalence.build_manifest(REPOSITORY_ROOT))

        self.assertEqual(first, second)

    def test_current_repository_matches_baseline(self) -> None:
        actual = equivalence.build_manifest(REPOSITORY_ROOT)

        self.assertEqual([], equivalence.compare(self.expected, actual))

    def test_missing_artifact_is_reported(self) -> None:
        actual = copy.deepcopy(self.expected)
        removed = actual["entries"].pop(0)
        actual["counts"][removed["category"]] -= 1

        errors = equivalence.compare(self.expected, actual)

        self.assertIn(f"missing {removed['category']} entry: {removed['id']}", errors)

    def test_changed_artifact_is_reported(self) -> None:
        actual = copy.deepcopy(self.expected)
        actual["entries"][0]["sha256"] = "0" * 64

        errors = equivalence.compare(self.expected, actual)

        self.assertIn(
            f"changed {actual['entries'][0]['category']} entry: {actual['entries'][0]['id']}",
            errors,
        )

    def test_untracked_artifact_is_reported(self) -> None:
        actual = copy.deepcopy(self.expected)
        added = {"category": "schemas", "id": "new.avsc", "path": "new.avsc", "sha256": "1" * 64}
        actual["entries"].append(added)
        actual["counts"]["schemas"] += 1

        errors = equivalence.compare(self.expected, actual)

        self.assertIn("untracked schemas entry: new.avsc", errors)

    def test_duplicate_logical_entry_is_rejected(self) -> None:
        duplicate = copy.deepcopy(self.expected)
        duplicate["entries"].append(copy.deepcopy(duplicate["entries"][0]))

        errors = equivalence.validate_manifest(duplicate)

        self.assertEqual(
            [
                "duplicate logical entries: "
                + duplicate["entries"][0]["category"]
                + ":"
                + duplicate["entries"][0]["id"]
            ],
            errors,
        )

    def test_legacy_transitional_roots_are_excluded(self) -> None:
        actual = equivalence.build_manifest(REPOSITORY_ROOT)

        for entry in actual["entries"]:
            root = Path(entry["path"]).parts[0]
            self.assertNotIn(
                root,
                equivalence.LEGACY_TRANSITIONAL_ROOTS,
                f"{entry['category']}:{entry['id']} was tracked from a frozen pre-migration root",
            )

    def test_baseline_records_gate_outcomes_and_preexisting_failures(self) -> None:
        evidence = equivalence.load_json(SCRIPT_DIR / "baseline-evidence.json")

        self.assertEqual(
            [
                {"command": "./gradlew test --no-daemon", "status": "PASS"},
                {"command": "docker compose config -q", "status": "PASS"},
            ],
            evidence["checks"],
        )
        self.assertEqual([], evidence["preexisting_failures"])


if __name__ == "__main__":
    unittest.main()
