#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "README.md", "AGENTS.md", "docs/README.md", "docs/architecture.md",
    "docs/contracts.md", "docs/configuration.md", "docs/security.md",
    "docs/operations.md", "docs/observability.md", "docs/testing.md",
    "docs/performance.md", "docs/adr/README.md",
    "docs/adr/0001-synchronous-wait-over-asynchronous-flow.md",
    "ops/runbooks/README.md", "ops/runbooks/admission-saturation.md",
    "ops/runbooks/response-dlq.md", "ops/runbooks/release-rollback.md",
}
LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
ADR_HEADINGS = ("## Contexto", "## Decisão", "## Alternativas consideradas",
                "## Consequências", "## Supersession")
REQUIRED_MARKERS = {
    "README.md": ("## Status de produção", "## Quickstart", "## Dependências externas e contratos",
                  "## Operação e release", "## Fontes de verdade"),
    "AGENTS.md": ("## Mapa e ownership", "## Fontes de verdade", "## Invariantes",
                  "## Limites de ownership e ações proibidas", "## Gates"),
    "docs/contracts.md": ("payment.simulation.requested", "payment.simulation.dlq", "traceparent"),
    "docs/operations.md": ("## Pré-requisitos", "## Admissão e saturação", "## Rollback", "NOT_RUN"),
    "docs/security.md": ("10001:10001", "SBOM", "HIGH/CRITICAL"),
    "docs/testing.md": ("## Gate rápido", "## Gate de integração", "## Documentação e imagem", "NOT_RUN"),
}
FORBIDDEN = ("project(':", 'project(":', "docker compose down -v", "payment-contracts/src", "payment-sbus/src", "payment-core-mock/src", "feature-control/src", "async-redis-service/src")


def validate(root: Path) -> list[str]:
    errors = [f"missing required document: {path}" for path in sorted(REQUIRED)
              if not (root / path).is_file()]
    markdown = sorted([root / "README.md", root / "AGENTS.md", *(root / "docs").rglob("*.md"),
                       *(root / "ops" / "runbooks").rglob("*.md")])
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
    adr = root / "docs/adr/0001-synchronous-wait-over-asynchronous-flow.md"
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
    print("payment-api documentation: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
