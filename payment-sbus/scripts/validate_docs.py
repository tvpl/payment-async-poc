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
    "docs/adr/0001-transactional-outbox-and-durable-retry.md",
    "ops/runbooks/README.md", "ops/runbooks/retry-backlog.md",
    "ops/runbooks/dlq-unconfirmed.md", "ops/runbooks/release-rollback.md",
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
    "docs/operations.md": ("## Pré-requisitos", "## Retry, DLQ e recovery", "## Rollback", "NOT_RUN"),
    "docs/security.md": ("10001:10001", "SBOM", "HIGH/CRITICAL"),
    "docs/testing.md": ("## Gate rápido", "## Gate de integração", "## Documentação e imagem", "NOT_RUN"),
}
FORBIDDEN = ("project(':", 'project(":', "docker compose down -v", "payment-contracts/src", "payment-api/src", "payment-core-mock/src", "feature-control/src", "async-redis-service/src")


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
    adr = root / "docs/adr/0001-transactional-outbox-and-durable-retry.md"
    if adr.is_file():
        text = adr.read_text(encoding="utf-8")
        for heading in ADR_HEADINGS:
            if heading not in text:
                errors.append(f"ADR missing heading {heading}")
        if "Status: Accepted" not in text:
            errors.append("ADR missing accepted status")
    errors.extend(configuration_drift(root))
    errors.extend(metric_drift(root))
    return errors


# Documented defaults must match application.yml. Without this, docs/configuration.md rots
# silently the first time someone tunes a value in the YAML — the failure mode that made this
# page narrative-only (and therefore useless for operators) in the first place.
DOCUMENTED_DEFAULTS = ("sbus.outbox", "sbus.core", "sbus.retry",
                       "sbus.housekeeping", "sbus.retention")
YAML_KEY = re.compile(r"^(\s*)([a-z0-9-]+):\s*(\S.*?)\s*$")


def yaml_defaults(path: Path) -> dict[str, str]:
    """Flatten the two-level `sbus.<group>.<key>: value` blocks. Deliberately not a YAML
    parser: this boundary has no PyYAML dependency and the shape being read is fixed."""
    values: dict[str, str] = {}
    group = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("#") or not line.strip():
            continue
        match = YAML_KEY.match(line)
        if not match:
            bare = re.match(r"^(\s*)([a-z0-9-]+):\s*$", line)
            if bare:
                indent = len(bare.group(1))
                group = f"sbus.{bare.group(2)}" if indent == 2 else (
                    bare.group(2) if indent == 0 else group)
            continue
        indent, key, raw = len(match.group(1)), match.group(2), match.group(3)
        if indent == 4 and group in DOCUMENTED_DEFAULTS:
            values[f"{group}.{key}"] = raw
    return values


def configuration_drift(root: Path) -> list[str]:
    yaml_path = root / "src/main/resources/application.yml"
    doc_path = root / "docs/configuration.md"
    if not yaml_path.is_file() or not doc_path.is_file():
        return []
    doc = doc_path.read_text(encoding="utf-8")
    defaults = yaml_defaults(yaml_path)
    if not defaults:
        return ["configuration drift check parsed 0 defaults — the application.yml shape changed"]
    errors = []
    for dotted, value in sorted(defaults.items()):
        key = dotted.rsplit(".", 1)[1]
        if f"`{key}`" not in doc:
            errors.append(f"docs/configuration.md does not document {dotted}")
        elif f"`{value}`" not in doc:
            errors.append(
                f"docs/configuration.md is stale for {dotted}: application.yml says {value!r}")
    return errors


def metric_drift(root: Path) -> list[str]:
    """Every metric SbusMetrics registers must be named in docs/observability.md — the alerts
    and dashboards key off these literal names, so a silent rename breaks operations."""
    source = root / "src/main/java/com/example/payments/sbus/metrics/SbusMetrics.java"
    doc_path = root / "docs/observability.md"
    if not source.is_file() or not doc_path.is_file():
        return []
    registered = set(re.findall(r'"(sbus_[a-z_]+)"', source.read_text(encoding="utf-8")))
    if not registered:
        return ["metric drift check found 0 metrics — SbusMetrics.java shape changed"]
    doc = doc_path.read_text(encoding="utf-8")
    return [f"docs/observability.md does not document metric {name}"
            for name in sorted(registered) if name not in doc]


def main() -> int:
    errors = validate(ROOT)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    defaults = len(yaml_defaults(ROOT / "src/main/resources/application.yml"))
    print(f"sbus-docs: PASS ({len(REQUIRED)} required documents, links, claims, ADR, "
          f"{defaults} config defaults, metric names)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
