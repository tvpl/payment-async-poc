#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "README.md",
    "AGENTS.md",
    "docs/architecture.md",
    "docs/configuration.md",
    "docs/security.md",
    "docs/operations.md",
    "docs/observability.md",
    "docs/testing.md",
    "docs/performance.md",
    "docs/adr/README.md",
    "docs/adr/0001-shared-infrastructure-and-external-network.md",
}
LINK = re.compile(r"\[[^]]+\]\(([^)]+)\)")


def failures() -> list[str]:
    errors: list[str] = []
    for relative in REQUIRED:
        if not (ROOT / relative).is_file():
            errors.append(f"missing required document: {relative}")

    for document in ROOT.rglob("*.md"):
        content = document.read_text(encoding="utf-8")
        for target in LINK.findall(content):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            path = target.split("#", 1)[0]
            if path and not (document.parent / path).resolve().exists():
                errors.append(f"broken link in {document.relative_to(ROOT)}: {target}")

    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    for command in ("make up", "make smoke", "make up-all", "make verify", "make down"):
        if command not in readme:
            errors.append(f"README misses command: {command}")
    for status in ("LOCAL_DEVELOPMENT_INFRASTRUCTURE", "não contém build, fonte ou contrato de produto"):
        if status not in readme:
            errors.append(f"README misses ownership/status claim: {status}")

    adr = (ROOT / "docs/adr/0001-shared-infrastructure-and-external-network.md").read_text(encoding="utf-8")
    for heading in ("## Contexto", "## Decisão", "## Alternativas", "## Consequências", "## Supersession"):
        if heading not in adr:
            errors.append(f"ADR misses section: {heading}")

    manifest = json.loads((ROOT / "observability/application-assets.json").read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not isinstance(manifest.get("assets"), list):
        errors.append("application asset manifest has invalid shape")
    return errors


def main() -> int:
    errors = failures()
    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"sandbox-docs: READY ({len(REQUIRED)} required documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
