#!/usr/bin/env python3
"""Validate that root governance remains a cross-boundary workspace map."""

from __future__ import annotations

import re
import sys
from pathlib import Path


BOUNDARIES = {
    "payment-contracts",
    "payment-api",
    "payment-sbus",
    "payment-core-mock",
    "feature-control",
    "async-redis-service",
    "sandbox",
}
ROOT_DOCUMENTS = ("README.md", "AGENTS.md")
PRODUCT_DETAIL_MARKERS = {
    "PaymentSimulationController",
    "OutboxDispatcher",
    "FeatureResolver",
    "JobWorker",
    "POST /payment-simulations",
}
LINK_PATTERN = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")


def missing_boundaries(text: str) -> set[str]:
    return {boundary for boundary in BOUNDARIES if f"`{boundary}`" not in text}


def broken_relative_links(root: Path, path: Path) -> list[str]:
    broken = []
    for target in LINK_PATTERN.findall(path.read_text(encoding="utf-8")):
        clean_target = target.split("#", 1)[0]
        if not clean_target or "://" in clean_target or clean_target.startswith("mailto:"):
            continue
        if not (path.parent / clean_target).resolve().exists():
            broken.append(f"{path.relative_to(root)} -> {target}")
    return broken


def validate(root: Path) -> list[str]:
    errors = []
    documents = {name: (root / name).read_text(encoding="utf-8") for name in ROOT_DOCUMENTS}
    for name, text in documents.items():
        missing = missing_boundaries(text)
        if missing:
            errors.append(f"{name} missing boundaries: {', '.join(sorted(missing))}")
        markers = sorted(marker for marker in PRODUCT_DETAIL_MARKERS if marker in text)
        if markers:
            errors.append(f"{name} contains product-local details: {', '.join(markers)}")
        errors.extend(broken_relative_links(root, root / name))
    agents = documents["AGENTS.md"]
    if "AGENTS.md local" not in agents and "`AGENTS.md` local" not in agents:
        errors.append("AGENTS.md does not delegate to local agent instructions")
    if "Somente `sandbox` cria infraestrutura" not in agents:
        errors.append("AGENTS.md does not assign shared infrastructure to sandbox")
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("root-governance: PASS (7 boundaries, local ownership, links)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
