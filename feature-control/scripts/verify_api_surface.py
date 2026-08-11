#!/usr/bin/env python3
"""Binary/source compatibility gate for the published feature-control artifact (FTR-06).

Compares the public API surface (via `javap -public`) of a set of promised classes — the ones
this boundary's consumer fixture exercises — against a committed baseline. A symbol present in the
baseline but missing from the current jar is a breaking change and fails the gate. New symbols
(growth) are allowed; only removal/narrowing breaks compatibility.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

PROMISED_CLASSES = (
    "com.example.platform.featurecontrol.model.FlagDefinition",
    "com.example.platform.featurecontrol.model.FeatureDecision",
    "com.example.platform.featurecontrol.model.Variant",
    "com.example.platform.featurecontrol.model.FlagType",
    "com.example.platform.featurecontrol.context.FeatureContext",
    "com.example.platform.featurecontrol.bucketing.Bucketer",
    "com.example.platform.featurecontrol.metrics.CardinalityGuard",
    "com.example.platform.featurecontrol.metrics.SubjectHasher",
)


def parse_members(javap_output: str) -> list[str]:
    """Extracts public member signature lines from `javap -public` output, normalized."""
    members = []
    for raw in javap_output.splitlines():
        line = raw.strip()
        if not line or line.startswith("public class") or line.startswith("public final class") \
                or line.startswith("public interface") or line.startswith("public enum") \
                or line.startswith("public record") or line == "}":
            continue
        if line.startswith("public") or line.startswith("protected"):
            members.append(" ".join(line.rstrip(";").split()))
    return members


def javap_surface(jar: Path, class_name: str) -> list[str]:
    result = subprocess.run(
        ["javap", "-public", "-classpath", str(jar), class_name],
        capture_output=True, text=True, check=True,
    )
    return parse_members(result.stdout)


def current_surface(jar: Path, classes: tuple[str, ...] = PROMISED_CLASSES) -> dict[str, list[str]]:
    return {cls: javap_surface(jar, cls) for cls in classes}


def format_baseline(surface: dict[str, list[str]]) -> str:
    lines = []
    for cls in sorted(surface):
        lines.append(f"# class: {cls}")
        lines.extend(sorted(surface[cls]))
    return "\n".join(lines) + "\n"


def parse_baseline(text: str) -> dict[str, list[str]]:
    surface: dict[str, list[str]] = {}
    current: str | None = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("# class:"):
            current = line[len("# class:"):].strip()
            surface[current] = []
        elif current is not None:
            surface[current].append(line)
    return surface


def breaking_changes(baseline: dict[str, list[str]], current: dict[str, list[str]]) -> list[str]:
    """@return one message per baseline class/member missing from `current` — never for additions."""
    errors = []
    for cls, members in baseline.items():
        if cls not in current:
            errors.append(f"promised class removed entirely: {cls}")
            continue
        current_members = set(current[cls])
        for member in members:
            if member not in current_members:
                errors.append(f"breaking change in {cls}: removed or changed `{member}`")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--write-baseline", action="store_true",
                         help="Regenerate the baseline from --jar instead of checking it.")
    args = parser.parse_args()

    surface = current_surface(args.jar)

    if args.write_baseline:
        args.baseline.write_text(format_baseline(surface), encoding="utf-8")
        print(f"api-surface: wrote baseline for {len(surface)} classes to {args.baseline}")
        return 0

    baseline = parse_baseline(args.baseline.read_text(encoding="utf-8"))
    errors = breaking_changes(baseline, surface)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"api-surface: PASS ({len(baseline)} promised classes, no breaking change)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
