#!/usr/bin/env python3
"""Check the local Maven artifact and artifact-only consumer contract."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


GROUP = "com.example.platform"
ARTIFACT = "feature-control"
VERSION = "0.1.0"
GAV = f"{GROUP}:{ARTIFACT}:{VERSION}"


def artifact_directory(repository: Path, version: str = VERSION) -> Path:
    return repository / Path(*GROUP.split(".")) / ARTIFACT / version


def artifact_errors(repository: Path, version: str = VERSION) -> list[str]:
    directory = artifact_directory(repository, version)
    required = [directory / f"{ARTIFACT}-{version}.pom", directory / f"{ARTIFACT}-{version}.jar"]
    return [f"missing published artifact: {path}" for path in required if not path.is_file()]


def fixture_errors(fixture: Path) -> list[str]:
    build = (fixture / "build.gradle").read_text(encoding="utf-8")
    settings = (fixture / "settings.gradle").read_text(encoding="utf-8")
    errors = []
    if GAV not in build:
        errors.append(f"fixture does not declare GAV {GAV}")
    for forbidden in ("project(", "includeBuild", "../feature-control", "../common"):
        if forbidden in build or forbidden in settings:
            errors.append(f"fixture reads cross-boundary source via {forbidden}")
    if "exclusiveContent" not in build or f"includeGroup '{GROUP}'" not in build:
        errors.append(f"fixture does not reserve {GROUP} to the artifact repository")
    return errors


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
    print(f"artifact-flow: PASS ({GAV}, POM/JAR, artifact-only fixture)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
