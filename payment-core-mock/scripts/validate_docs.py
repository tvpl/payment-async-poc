#!/usr/bin/env python3
"""Validate payment-core-mock documentation, ownership and lifecycle claims."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_DOCUMENTS = {
    "README.md",
    "AGENTS.md",
    "docs/README.md",
    "docs/architecture.md",
    "docs/contracts.md",
    "docs/configuration.md",
    "docs/security.md",
    "docs/operations.md",
    "docs/observability.md",
    "docs/testing.md",
    "docs/performance.md",
    "docs/adr/README.md",
    "docs/adr/0001-non-production-deterministic-simulator.md",
}
LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
FORBIDDEN_CLAIMS = (
    re.compile(r"\bproduction[- ]ready\b", re.IGNORECASE),
    re.compile(r"\bpronto para produ[cç][aã]o\b", re.IGNORECASE),
    re.compile(r"\bequivalente (?:a|à) produ[cç][aã]o\b", re.IGNORECASE),
    re.compile(r"\bsimula fielmente\b", re.IGNORECASE),
)
FORBIDDEN_OWNERSHIP = ("common/src/main", "project(':", 'project(":', "payment-contracts/src", "payment-api/src", "payment-sbus/src", "feature-control/src", "async-redis-service/src")
ADR_HEADINGS = (
    "## Contexto",
    "## Decisão",
    "## Alternativas consideradas",
    "## Consequências",
    "## Supersession",
)
REQUIRED_CONTENT = {
    "README.md": (
        "NON_PRODUCTION",
        "## Quickstart",
        "## Perfis determinísticos",
        "## Contratos e dependências",
        "## Operação e gates",
        "## Fontes de verdade",
        "## Status",
    ),
    "AGENTS.md": (
        "NON_PRODUCTION",
        "## Fontes de verdade",
        "## Invariantes",
        "## Ações proibidas",
        "## Gates",
    ),
    "docs/contracts.md": (
        "payment.simulation.core.command",
        "payment.simulation.core.response",
        "traceparent",
        "TRANSIENT_FAILURE",
    ),
    "docs/configuration.md": (
        "CORE_SEED",
        "CORE_DECLINE_PCT",
        "CORE_FAIL_PCT",
        "PAYMENT_CONTRACTS_REPOSITORY",
    ),
    "docs/operations.md": ("## Pré-requisitos", "## Diagnóstico", "## Encerramento e recovery", "NOT_RUN"),
    "docs/testing.md": ("## Gate rápido", "## Contrato e integração", "## Documentação e imagem", "NOT_RUN"),
    "docs/performance.md": ("NON_PRODUCTION", "consumer lag", "milhares de requests por minuto"),
}


def missing_document_errors(root: Path) -> list[str]:
    return [f"missing required document: {path}" for path in sorted(REQUIRED_DOCUMENTS) if not (root / path).is_file()]


def link_errors(root: Path, path: Path, text: str | None = None) -> list[str]:
    content = path.read_text(encoding="utf-8") if text is None else text
    errors: list[str] = []
    for target in LINK.findall(content):
        clean = target.split("#", 1)[0]
        if not clean or "://" in clean or clean.startswith("mailto:"):
            continue
        if not (path.parent / clean).resolve().exists():
            errors.append(f"broken link {path.relative_to(root)} -> {target}")
    return errors


def claim_errors(path: str, text: str) -> list[str]:
    errors = [f"obsolete cross-owner reference {marker!r} in {path}" for marker in FORBIDDEN_OWNERSHIP if marker in text]
    errors.extend(
        f"forbidden lifecycle claim {pattern.pattern!r} in {path}"
        for pattern in FORBIDDEN_CLAIMS
        if pattern.search(text)
    )
    return errors


def required_content_errors(path: str, text: str) -> list[str]:
    return [
        f"missing required content {marker!r} in {path}"
        for marker in REQUIRED_CONTENT.get(path, ())
        if marker not in text
    ]


def adr_errors(path: Path, text: str | None = None) -> list[str]:
    content = path.read_text(encoding="utf-8") if text is None else text
    errors = [f"ADR missing heading {heading}: {path.name}" for heading in ADR_HEADINGS if heading not in content]
    if "Status: Accepted" not in content:
        errors.append(f"ADR missing accepted status: {path.name}")
    return errors


def classification_errors(root: Path) -> list[str]:
    surfaces = {
        "README": root / "README.md",
        "startup": root / "src/main/java/com/example/payments/coremock/NonProductionStartupReporter.java",
        "image": root / "Dockerfile",
        "CI": root / ".github/workflows/ci.yml",
    }
    errors = []
    for surface, path in surfaces.items():
        if not path.is_file() or "NON_PRODUCTION" not in path.read_text(encoding="utf-8"):
            errors.append(f"{surface} surface missing NON_PRODUCTION classification")
    return errors


def validate(root: Path) -> list[str]:
    errors = missing_document_errors(root)
    markdown = sorted([root / "README.md", root / "AGENTS.md", *(root / "docs").rglob("*.md")])
    for path in markdown:
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8")
        relative = path.relative_to(root).as_posix()
        errors.extend(link_errors(root, path, content))
        errors.extend(claim_errors(relative, content))
        errors.extend(required_content_errors(relative, content))

    errors.extend(classification_errors(root))
    adr = root / "docs/adr/0001-non-production-deterministic-simulator.md"
    if adr.is_file():
        errors.extend(adr_errors(adr))
    return errors


def main() -> int:
    errors = validate(ROOT)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"core-mock-docs: PASS ({len(REQUIRED_DOCUMENTS)} required documents, links, lifecycle, ADR)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
