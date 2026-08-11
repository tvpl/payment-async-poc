#!/usr/bin/env python3
"""Build and verify the migration-equivalence inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


FORMAT_VERSION = 1
IGNORED_PARTS = {".git", ".gradle", "build"}
# Pre-migration monorepo modules superseded by a standalone root (MIG-02): the workspace keeps
# them on disk only as a frozen reference until T59 removes them, so they are not live inventory.
# Counting them would double-track every relocated file and collide on logical keys that are
# unique by name rather than by path (e.g. Topics.java's topic constants).
LEGACY_TRANSITIONAL_ROOTS = {"common", "api-service", "sbus-service", "core-mock"}
TOPIC_PATTERN = re.compile(
    r'public\s+static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"'
)
TEST_PATTERN = re.compile(r"@(?:org\.junit\.jupiter\.api\.)?Test\b")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def relative_files(root: Path, patterns: Iterable[str]) -> list[Path]:
    paths: set[Path] = set()
    for pattern in patterns:
        for path in root.glob(pattern):
            if not path.is_file():
                continue
            parts = path.relative_to(root).parts
            if IGNORED_PARTS.intersection(parts) or parts[0] in LEGACY_TRANSITIONAL_ROOTS:
                continue
            paths.add(path)
    return sorted(paths, key=lambda path: path.relative_to(root).as_posix())


def file_entries(root: Path, category: str, patterns: Iterable[str]) -> list[dict[str, Any]]:
    entries = []
    for path in relative_files(root, patterns):
        relative = path.relative_to(root).as_posix()
        entries.append(
            {
                "category": category,
                "id": relative,
                "path": relative,
                "sha256": sha256_bytes(path.read_bytes()),
            }
        )
    return entries


def topic_entries(root: Path) -> list[dict[str, Any]]:
    entries = []
    for path in relative_files(root, ["**/src/main/java/**/Topics.java"]):
        relative = path.relative_to(root).as_posix()
        for name, value in TOPIC_PATTERN.findall(path.read_text(encoding="utf-8")):
            entries.append(
                {
                    "category": "topics",
                    "id": name,
                    "path": relative,
                    "sha256": sha256_bytes(f"{name}={value}".encode()),
                    "value": value,
                }
            )
    return sorted(entries, key=lambda entry: entry["id"])


def build_manifest(root: Path) -> dict[str, Any]:
    root = root.resolve()
    entries = [
        *file_entries(root, "sources", ["**/src/main/java/**/*.java"]),
        *file_entries(root, "tests", ["**/src/test/**/*.java"]),
        *file_entries(root, "migrations", ["**/src/main/resources/db/migration/*.sql"]),
        # payment-contracts moved the live Avro source from `src/main/avro/` to a top-level
        # `schemas/` directory that generateAvroJava reads directly (see contract-model/build.gradle);
        # `history/` holds frozen prior versions covered by the contract's own compatibility tests,
        # not this workspace-level loss-prevention gate.
        *file_entries(root, "schemas", ["**/src/main/avro/*.avsc", "payment-contracts/schemas/*.avsc"]),
        *topic_entries(root),
        *file_entries(root, "dashboards", ["observability/grafana/dashboards/*.json"]),
        *file_entries(
            root,
            "scripts",
            ["Makefile", "scripts/**/*.sh", "scripts/**/*.py", "load/**/*.js"],
        ),
        *file_entries(root, "documents", ["README.md", "AGENTS.md", "docs/**/*.md"]),
    ]
    entries.sort(key=lambda entry: (entry["category"], entry["id"]))
    category_counts = dict(sorted(Counter(entry["category"] for entry in entries).items()))
    test_cases = sum(
        len(TEST_PATTERN.findall((root / entry["path"]).read_text(encoding="utf-8")))
        for entry in entries
        if entry["category"] == "tests"
    )
    return {
        "format_version": FORMAT_VERSION,
        "counts": {**category_counts, "test_cases": test_cases},
        "entries": entries,
    }


def duplicate_keys(entries: list[dict[str, Any]]) -> list[str]:
    keys = [(entry.get("category"), entry.get("id")) for entry in entries]
    return [f"{category}:{identifier}" for (category, identifier), count in Counter(keys).items() if count > 1]


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    errors = []
    if manifest.get("format_version") != FORMAT_VERSION:
        errors.append(f"unsupported format_version: {manifest.get('format_version')!r}")
    entries = manifest.get("entries")
    if not isinstance(entries, list):
        return [*errors, "entries must be a list"]
    duplicates = duplicate_keys(entries)
    if duplicates:
        errors.append("duplicate logical entries: " + ", ".join(sorted(duplicates)))
    return errors


def compare(expected: dict[str, Any], actual: dict[str, Any]) -> list[str]:
    errors = validate_manifest(expected)
    if errors:
        return errors
    expected_by_key = {(entry["category"], entry["id"]): entry for entry in expected["entries"]}
    actual_by_key = {(entry["category"], entry["id"]): entry for entry in actual["entries"]}
    for key in sorted(expected_by_key.keys() - actual_by_key.keys()):
        errors.append(f"missing {key[0]} entry: {key[1]}")
    for key in sorted(actual_by_key.keys() - expected_by_key.keys()):
        errors.append(f"untracked {key[0]} entry: {key[1]}")
    for key in sorted(expected_by_key.keys() & actual_by_key.keys()):
        if expected_by_key[key]["sha256"] != actual_by_key[key]["sha256"]:
            errors.append(f"changed {key[0]} entry: {key[1]}")
    if expected.get("counts") != actual.get("counts"):
        errors.append(
            "count mismatch: expected "
            + json.dumps(expected.get("counts"), sort_keys=True)
            + ", actual "
            + json.dumps(actual.get("counts"), sort_keys=True)
        )
    return errors


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def dump_json(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def command_generate(args: argparse.Namespace) -> int:
    rendered = dump_json(build_manifest(args.root))
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0


def command_verify(args: argparse.Namespace) -> int:
    expected = load_json(args.manifest)
    actual = build_manifest(args.root)
    errors = compare(expected, actual)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"equivalence: PASS ({len(actual['entries'])} entries)")
    return 0


def parser() -> argparse.ArgumentParser:
    argument_parser = argparse.ArgumentParser(description=__doc__)
    subparsers = argument_parser.add_subparsers(dest="command", required=True)
    generate = subparsers.add_parser("generate", help="write the current inventory to stdout")
    generate.add_argument("--root", type=Path, default=Path.cwd())
    generate.add_argument("--output", type=Path)
    generate.set_defaults(handler=command_generate)
    verify = subparsers.add_parser("verify", help="compare the current inventory with a manifest")
    verify.add_argument("--root", type=Path, default=Path.cwd())
    verify.add_argument("--manifest", type=Path, required=True)
    verify.set_defaults(handler=command_verify)
    return argument_parser


def main() -> int:
    args = parser().parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
