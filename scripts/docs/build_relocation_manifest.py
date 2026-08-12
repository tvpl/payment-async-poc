#!/usr/bin/env python3
"""Generate the section-level relocation manifest for legacy documentation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
ROUTES = {
    "docs/README.md": ("workspace", "README.md", "REWRITE"),
    "docs/01-visao-geral.md": ("workspace", "docs/workspace-overview.md", "REWRITE"),
    "docs/02-arquitetura.md": ("workspace", "docs/workspace-architecture.md", "REWRITE"),
    "docs/03-tecnologias.md": ("workspace", "docs/technology-policy.md", "SPLIT"),
    "docs/04-fluxo-ponta-a-ponta.md": ("workspace", "docs/payment-flow.md", "MOVE"),
    "docs/05-api-service.md": ("payment-api", "payment-api/docs/architecture.md", "MOVE"),
    "docs/06-sbus-service.md": ("payment-sbus", "payment-sbus/docs/architecture.md", "MOVE"),
    "docs/07-core-mock.md": ("payment-core-mock", "payment-core-mock/docs/architecture.md", "MOVE"),
    "docs/08-eventos-e-contratos.md": ("payment-contracts", "payment-contracts/docs/contracts.md", "MOVE"),
    "docs/09-dados-redis-postgres.md": ("workspace", "docs/data-ownership.md", "SPLIT"),
    "docs/10-observabilidade.md": ("sandbox", "sandbox/docs/observability.md", "SPLIT"),
    "docs/11-resiliencia-e-tradeoffs.md": ("workspace", "docs/resilience-contracts.md", "SPLIT"),
    "docs/12-execucao-e-operacao.md": ("sandbox", "sandbox/docs/operations.md", "SPLIT"),
    "docs/13-testes.md": ("workspace", "docs/testing-policy.md", "SPLIT"),
    "docs/14-glossario.md": ("workspace", "docs/glossary.md", "MOVE"),
    "docs/15-prontidao-producao.md": ("workspace", "docs/production-evidence.md", "REWRITE"),
    "docs/16-feature-control-lib.md": ("feature-control", "feature-control/docs/architecture.md", "MOVE"),
    "docs/17-async-sync-redis.md": ("async-redis-service", "async-redis-service/docs/architecture.md", "REWRITE"),
    "docs/18-operacao-features.md": ("feature-control", "feature-control/docs/operations.md", "MOVE"),
    "docs/19-adocao.md": ("feature-control", "feature-control/docs/adoption.md", "MOVE"),
}


def section_entries(root: Path) -> list[dict[str, object]]:
    entries = []
    for source, (owner, target, action) in sorted(ROUTES.items()):
        path = root / source
        if not path.exists():
            # The whole point of this manifest is tracking legacy docs on their way to a new
            # home (T59) — once a source is gone, its sections are migrated by definition, not
            # an error. See validate_docs.py#manifest_errors for the terminal-state check this
            # enables (verifies the recorded manifest itself, not a live re-scan of nothing).
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = HEADING.match(line)
            if not match:
                continue
            heading = match.group(2)
            identifier = f"{source}:{line_number}"
            entries.append(
                {
                    "id": identifier,
                    "source": source,
                    "line": line_number,
                    "heading": heading,
                    "heading_sha256": hashlib.sha256(heading.encode()).hexdigest(),
                    "owner": owner,
                    "target": target,
                    "action": action,
                    "status": "PLANNED",
                }
            )
    return entries


def manifest(root: Path) -> dict[str, object]:
    entries = section_entries(root)
    return {"format_version": 1, "section_count": len(entries), "sections": entries}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(manifest(args.root), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
