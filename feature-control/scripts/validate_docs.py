#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "README.md", "AGENTS.md", "docs/README.md", "docs/architecture.md",
    "docs/configuration.md", "docs/security.md", "docs/operations.md",
    "docs/testing.md", "docs/adr/README.md",
    "docs/adr/0001-nonproduction-example-startup-guard.md",
}
LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
ADR_HEADINGS = ("## Contexto", "## Decisão", "## Alternativas consideradas",
                "## Consequências", "## Supersession")
REQUIRED_MARKERS = {
    "README.md": ("## Status de produção", "## Quickstart", "## Dependências externas e contratos",
                  "## Operação e release", "## Fontes de verdade"),
    "AGENTS.md": ("## Mapa e ownership", "## Fontes de verdade", "## Invariantes",
                  "## Limites de ownership e ações proibidas", "## Gates"),
    "docs/operations.md": ("## Pré-requisitos", "## Rollback"),
    "docs/security.md": ("NonProductionExampleGuard", "SubjectHasher", "CardinalityGuard"),
    "docs/testing.md": ("## Gate rápido", "## Gate de integração", "## Documentação e imagem", "NOT_RUN"),
}
FORBIDDEN = ("project(':", 'project(":', "docker compose down -v",
             "payment-contracts/src", "payment-api/src", "payment-sbus/src",
             "async-redis-service/src", "payment-core-mock/src")
# This boundary is consumed as a published Maven artifact, so its docs are read by people who
# have no access to the workspace's own planning artefacts. A bare `tasks.md` link resolves to
# nothing here, and a task id (T50) means nothing to them.
PROCESS_ARTEFACT = re.compile(r"`tasks\.md`|\bT\d{2,3}\b")


def validate(root: Path) -> list[str]:
    errors = [f"missing required document: {path}" for path in sorted(REQUIRED)
              if not (root / path).is_file()]
    markdown = sorted([root / "README.md", root / "AGENTS.md", *(root / "docs").rglob("*.md")])
    for path in markdown:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(root).as_posix()
        for target in LINK.findall(text):
            clean = target.split("#", 1)[0]
            if clean and "://" not in clean and not (path.parent / clean).resolve().exists():
                errors.append(f"broken link {relative} -> {target}")
        for marker in REQUIRED_MARKERS.get(relative, ()):
            if marker not in text:
                errors.append(f"missing required content {marker!r} in {relative}")
        for marker in FORBIDDEN:
            if marker in text:
                errors.append(f"forbidden boundary reference {marker!r} in {relative}")
        for match in PROCESS_ARTEFACT.finditer(text):
            errors.append(f"internal process reference {match.group(0)!r} in {relative}: this "
                          "boundary ships as a published library, so its docs must stand alone "
                          "for a consumer who cannot see the workspace's task tracking")
    adr = root / "docs/adr/0001-nonproduction-example-startup-guard.md"
    if adr.is_file():
        text = adr.read_text(encoding="utf-8")
        for heading in ADR_HEADINGS:
            if heading not in text:
                errors.append(f"ADR missing heading {heading}")
        if "Status: Accepted" not in text:
            errors.append("ADR missing accepted status")
    return errors


def main() -> int:
    errors = validate(ROOT)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"feature-control-docs: PASS ({len(REQUIRED)} required documents, links, claims, ADR)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
