#!/usr/bin/env python3
"""Validate payment-contracts documentation ownership, links and required claims."""

from __future__ import annotations

import re
import sys
from pathlib import Path


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
    "docs/adr/0001-contract-artifacts-and-compatibility.md",
}
LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
FORBIDDEN_CONTENT = ("project(':", 'project(":', "docker compose up", "payment-api/src", "payment-sbus/src", "payment-core-mock/src", "feature-control/src", "async-redis-service/src")
ADR_HEADINGS = ("## Contexto", "## Decisão", "## Alternativas consideradas", "## Consequências", "## Supersession")
REQUIRED_CONTENT = {
    "README.md": (
        "## Quickstart",
        "## Fontes de verdade",
        "## Dependências externas",
        "## Status",
        "com.example.payments:payment-contract-model:0.1.0",
        "com.example.payments:payment-contract-avro-apicurio:0.1.0",
    ),
    "AGENTS.md": (
        "## Fontes de verdade",
        "## Invariantes",
        "## Ações proibidas",
        "## Gates",
        "FULL_TRANSITIVE",
        "autoRegister=false",
    ),
    "docs/contracts.md": ("## Envelope", "## Eventos e tópicos", "## Headers", "## Evolução", "FULL_TRANSITIVE"),
    "docs/configuration.md": (
        "payments.avro.registry-url",
        "payments.avro.codec-pool-size",
        "payments.avro.codec-acquire-timeout",
        "payments.avro.auto-register",
    ),
    "docs/operations.md": ("## Publicação local", "## Evolução e dry run", "## Mudança incompatível"),
    "docs/testing.md": ("## Gate rápido", "## Compatibilidade", "## Publicação e consumo", "NOT_RUN"),
}


def missing_document_errors(root: Path) -> list[str]:
    return [f"missing required document: {path}" for path in sorted(REQUIRED_DOCUMENTS) if not (root / path).is_file()]


def link_errors(root: Path, path: Path, text: str | None = None) -> list[str]:
    text = path.read_text(encoding="utf-8") if text is None else text
    errors = []
    for target in LINK.findall(text):
        clean = target.split("#", 1)[0]
        if not clean or "://" in clean or clean.startswith("mailto:"):
            continue
        if not (path.parent / clean).resolve().exists():
            errors.append(f"broken link {path.relative_to(root)} -> {target}")
    return errors


def content_errors(path: str, text: str) -> list[str]:
    return [f"obsolete cross-owner reference {marker!r} in {path}" for marker in FORBIDDEN_CONTENT if marker in text]


def required_content_errors(path: str, text: str) -> list[str]:
    return [
        f"missing required content {marker!r} in {path}"
        for marker in REQUIRED_CONTENT.get(path, ())
        if marker not in text
    ]


def adr_errors(path: Path, text: str | None = None) -> list[str]:
    text = path.read_text(encoding="utf-8") if text is None else text
    errors = [f"ADR missing heading {heading}: {path.name}" for heading in ADR_HEADINGS if heading not in text]
    if "Status: Accepted" not in text:
        errors.append(f"ADR missing accepted status: {path.name}")
    return errors


def validate(root: Path) -> list[str]:
    errors = missing_document_errors(root)
    markdown = sorted([root / "README.md", root / "AGENTS.md", *(root / "docs").rglob("*.md")])
    for path in markdown:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        errors.extend(link_errors(root, path, text))
        errors.extend(content_errors(path.relative_to(root).as_posix(), text))
        errors.extend(required_content_errors(path.relative_to(root).as_posix(), text))

    readme = (root / "README.md").read_text(encoding="utf-8") if (root / "README.md").is_file() else ""
    for gav in (
        "com.example.payments:payment-contract-model:0.1.0",
        "com.example.payments:payment-contract-avro-apicurio:0.1.0",
    ):
        if gav not in readme:
            errors.append(f"README missing GAV: {gav}")
    if any((root / name).exists() for name in ("Dockerfile", "compose.yaml", "docker-compose.yml")):
        errors.append("runtime container definition is forbidden for payment-contracts")

    adr = root / "docs/adr/0001-contract-artifacts-and-compatibility.md"
    if adr.is_file():
        errors.extend(adr_errors(adr))
    return errors


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"contracts-docs: PASS ({len(REQUIRED_DOCUMENTS)} required documents, links, ownership, ADR)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
