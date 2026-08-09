#!/usr/bin/env python3
"""Validate artifact-only contract consumption and published Maven coordinates."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as element_tree
from pathlib import Path


GROUP = "com.example.payments"
VERSION = "0.1.0"
ARTIFACTS = ("payment-contract-model", "payment-contract-avro-apicurio")


def artifact_directory(repository: Path, artifact: str, version: str = VERSION) -> Path:
    return repository / Path(*GROUP.split(".")) / artifact / version


def artifact_errors(repository: Path, version: str = VERSION) -> list[str]:
    errors: list[str] = []
    for artifact in ARTIFACTS:
        directory = artifact_directory(repository, artifact, version)
        prefix = f"{artifact}-{version}"
        required = (
            directory / f"{prefix}.pom",
            directory / f"{prefix}.jar",
            directory / f"{prefix}-sources.jar",
            directory / f"{prefix}-javadoc.jar",
        )
        errors.extend(f"missing published artifact: {path}" for path in required if not path.is_file())
        pom = required[0]
        if pom.is_file():
            try:
                root = element_tree.parse(pom).getroot()
                coordinates = (
                    _child_text(root, "groupId"),
                    _child_text(root, "artifactId"),
                    _child_text(root, "version"),
                )
                expected = (GROUP, artifact, version)
                if coordinates != expected:
                    errors.append(f"divergent POM coordinates: {coordinates} != {expected}")
            except element_tree.ParseError as invalid:
                errors.append(f"invalid POM {pom}: {invalid}")
    return errors


def fixture_errors(fixture: Path) -> list[str]:
    build = (fixture / "build.gradle").read_text(encoding="utf-8")
    settings = (fixture / "settings.gradle").read_text(encoding="utf-8")
    errors = []
    for artifact in ARTIFACTS:
        gav = f"{GROUP}:{artifact}:{VERSION}"
        if gav not in build:
            errors.append(f"fixture does not declare GAV {gav}")
    for forbidden in ("project(", "includeBuild", "../common", "../contract-model", "../contract-avro"):
        if forbidden in build or forbidden in settings:
            errors.append(f"fixture reads cross-boundary source via {forbidden}")
    if "exclusiveContent" not in build or f"includeGroup '{GROUP}'" not in build:
        errors.append(f"fixture does not reserve {GROUP} to the artifact repository")
    return errors


def _child_text(root: element_tree.Element, name: str) -> str | None:
    for child in root:
        if child.tag.rsplit("}", 1)[-1] == name:
            return child.text
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    args = parser.parse_args()
    errors = [*fixture_errors(args.fixture), *artifact_errors(args.repository)]
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("contracts-consumer: PASS (two GAVs, POM/JAR/sources/Javadoc, artifact-only fixture)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
