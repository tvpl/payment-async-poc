#!/usr/bin/env python3
"""Validate legacy docs and their section relocation ownership."""

from __future__ import annotations

import json
import re
import shlex
import sys
from pathlib import Path

from build_relocation_manifest import ROUTES, manifest


LINK = re.compile(r"(?<!!)\[[^]]+\]\(([^)]+)\)")
PORT = re.compile(r"localhost:(\d{2,5})")
VARIABLE = re.compile(r"\$\{([A-Z][A-Z0-9_]*)")
METRIC = re.compile(r"`([a-z][a-z0-9_]*(?:_total|_seconds|_latency|_pending|_failures|_published|_duration|_lag|_age))`")
CLAIM = re.compile(r"pronto para carga real|Gaps corrigidos \+ checklist de deploy|Vou levar para produção", re.I)
FENCE = re.compile(r"^```(bash|sh|shell)\s*$")
PROMQL_METRIC = re.compile(r"\b([a-z_:][a-z0-9_:]*)\s*(?=\{|\[|\)|$)")
PROMQL_LABELS = {"client_id", "consumergroup", "flag", "le", "on", "reason_kind", "status", "topic", "variant"}
EXTERNAL_METRIC_PREFIXES = ("hikaricp_", "http_", "k6_", "kafka_", "pg_", "redis_")


def load_json(path: Path) -> dict[str, object]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def markdown_paths(root: Path) -> list[Path]:
    return sorted((root / "docs").rglob("*.md"))


def manifest_errors(root: Path, recorded: dict[str, object]) -> list[str]:
    # Terminal state (T59 complete): every legacy source in ROUTES has been removed, so there is
    # nothing left to live-rescan and compare — that would just report every recorded entry as
    # "stale". Instead verify the recorded manifest is internally consistent: every entry must
    # be MIGRATED. Mid-migration (some sources still present), fall back to the original
    # live-vs-recorded comparison so a partial migration still catches drift.
    if not any((root / source).exists() for source in ROUTES):
        errors = []
        for entry in recorded.get("sections", []):
            if entry.get("status") != "MIGRATED":
                errors.append(f"unmigrated section after legacy removal: {entry['id']}")
        return errors

    current = manifest(root)
    errors = []
    if recorded.get("section_count") != current["section_count"]:
        errors.append(f"section count mismatch: {recorded.get('section_count')} != {current['section_count']}")
    recorded_entries = {entry["id"]: entry for entry in recorded.get("sections", [])}
    current_entries = {entry["id"]: entry for entry in current["sections"]}
    for identifier in sorted(current_entries.keys() - recorded_entries.keys()):
        errors.append(f"unmapped section: {identifier}")
    for identifier in sorted(recorded_entries.keys() - current_entries.keys()):
        errors.append(f"stale section mapping: {identifier}")
    for identifier in sorted(recorded_entries.keys() & current_entries.keys()):
        expected = recorded_entries[identifier]
        actual = current_entries[identifier]
        for field in ("heading", "heading_sha256"):
            if expected.get(field) != actual[field]:
                errors.append(f"changed section {identifier}: {field}")
        if expected.get("owner") not in {"workspace", *(route[0] for route in ROUTES.values())}:
            errors.append(f"invalid owner for {identifier}")
        if not expected.get("target") or expected.get("action") not in {"MOVE", "SPLIT", "REWRITE"}:
            errors.append(f"invalid destination for {identifier}")
    return errors


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


def command_lines(text: str) -> list[str]:
    commands = []
    in_shell = False
    for raw_line in text.splitlines():
        if raw_line.startswith("```"):
            if in_shell:
                in_shell = False
            else:
                in_shell = bool(FENCE.match(raw_line))
            continue
        if in_shell and raw_line and not raw_line[0].isspace() and not raw_line.startswith("#"):
            commands.append(raw_line.strip())
    return commands


def command_errors(root: Path, path: Path, text: str, allowed: set[str]) -> list[str]:
    errors = []
    for line in command_lines(text):
        if line.endswith("\\") or line.startswith(("-H ", "-d ", "--")) or line in {"do", "done"}:
            continue
        try:
            tokens = shlex.split(line, comments=True)
        except ValueError:
            continue
        while tokens and "=" in tokens[0] and not tokens[0].startswith(("./", "/")):
            tokens.pop(0)
        if not tokens or tokens[0] in {"if", "then", "fi", "for"}:
            continue
        command = tokens[0]
        if command.startswith("./"):
            candidate = (root / command[2:]).resolve()
            if not candidate.exists():
                errors.append(f"missing local command {command} in {path.relative_to(root)}")
        elif command not in allowed:
            errors.append(f"unknown command {command} in {path.relative_to(root)}")
    return errors


def known_ports(root: Path) -> set[int]:
    ports = set()
    compose_files = [*root.glob("*/compose.yaml"), *root.glob("*/compose*.yml")]
    for path in [*compose_files, *root.glob("*/src/main/resources/application*.yml")]:
        text = path.read_text(encoding="utf-8")
        ports.update(int(value) for value in re.findall(r'(?:"|port:\s*)(\d{4,5})(?::\d{2,5})?', text))
    ports.add(6379)
    return ports


def port_errors(root: Path, path: Path, text: str) -> list[str]:
    allowed = known_ports(root)
    return [
        f"unknown port {port} in {path.relative_to(root)}"
        for port in PORT.findall(text)
        if int(port) not in allowed
    ]


def known_variables(root: Path) -> set[str]:
    variables = set()
    compose_files = [*root.glob("*/compose.yaml"), *root.glob("*/compose*.yml")]
    env_example_files = [root / ".env.example", *root.glob("*/.env.example")]
    for path in [*env_example_files, *compose_files, *root.glob("*/src/main/resources/application*.yml")]:
        if not path.exists():
            continue
        variables.update(VARIABLE.findall(path.read_text(encoding="utf-8")))
        if path.name == ".env.example":
            variables.update(
                line.split("=", 1)[0]
                for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#") and "=" in line
            )
    return variables


def variable_errors(root: Path, path: Path, text: str) -> list[str]:
    allowed = known_variables(root)
    return [
        f"unknown variable {variable} in {path.relative_to(root)}"
        for variable in VARIABLE.findall(text)
        if variable not in allowed
    ]


def executable_corpus(root: Path) -> str:
    # `**/src/main/java` (not `*/src/main/java`) — boundaries sit at varying depths
    # (payment-api/src/main/java is one level deep, feature-control/library/src/main/java and
    # feature-control/examples/feature-demo/src/main/java are two and three), and a fixed single
    # `*` silently missed the deeper ones, letting a dashboard reference an unverified metric
    # (feature_decisions_total, defined in feature-control/library/.../MicrometerDecisionListener
    # .java) pass with a false "implemented" reading. Confirmed no `*/build/*/src/main/java`
    # generated-source path exists in this repo that this broader glob would pick up instead.
    paths = [*root.glob("**/src/main/java/**/*.java")]
    return "\n".join(path.read_text(encoding="utf-8", errors="ignore") for path in paths if path.is_file())


def metric_errors(root: Path, path: Path, text: str, debts: list[dict[str, str]]) -> list[str]:
    corpus = executable_corpus(root)
    debt_keys = {(debt["path"], debt["metric"]) for debt in debts}
    relative = path.relative_to(root).as_posix()
    return [
        f"unverified metric {metric} in {relative}"
        for metric in METRIC.findall(text)
        if not implemented_metric(metric, corpus) and (relative, metric) not in debt_keys
    ]


def claim_errors(root: Path, path: Path, text: str, debts: list[dict[str, str]]) -> list[str]:
    relative = path.relative_to(root).as_posix()
    registered = {(debt["path"], debt["contains"].lower()) for debt in debts}
    return [
        f"unregistered production claim {match.group(0)!r} in {relative}"
        for match in CLAIM.finditer(text)
        if (relative, match.group(0).lower()) not in registered
    ]


def metrics_in_expression(expression: str) -> set[str]:
    without_strings = re.sub(r'"[^"]*"', '""', expression)
    return {metric for metric in PROMQL_METRIC.findall(without_strings) if metric not in PROMQL_LABELS}


def implemented_metric(metric: str, corpus: str) -> bool:
    if metric.startswith(EXTERNAL_METRIC_PREFIXES):
        return True
    candidates = {metric}
    for suffix in ("_seconds_bucket", "_seconds_count", "_seconds_sum", "_seconds", "_bucket", "_count", "_sum"):
        if metric.endswith(suffix):
            candidates.add(metric[: -len(suffix)])
    return any(candidate in corpus for candidate in candidates)


def dashboard_metric_errors(root: Path) -> list[str]:
    corpus = executable_corpus(root)
    errors = []

    def expressions(value: object) -> list[str]:
        if isinstance(value, dict):
            found = [value["expr"]] if isinstance(value.get("expr"), str) else []
            return found + [item for nested in value.values() for item in expressions(nested)]
        if isinstance(value, list):
            return [item for nested in value for item in expressions(nested)]
        return []

    for path in sorted((root / "observability/grafana/dashboards").glob("*.json")):
        dashboard = load_json(path)
        for expression in expressions(dashboard):
            for metric in sorted(metrics_in_expression(expression)):
                if not implemented_metric(metric, corpus):
                    errors.append(f"dashboard metric without source {metric} in {path.relative_to(root)}")
    return errors


def validate(root: Path) -> list[str]:
    script_dir = Path(__file__).resolve().parent
    policy = load_json(script_dir / "validation-policy.json")
    recorded = load_json(script_dir / "relocation-manifest.json")
    errors = [*manifest_errors(root, recorded), *dashboard_metric_errors(root)]
    for path in markdown_paths(root):
        text = path.read_text(encoding="utf-8")
        errors.extend(link_errors(root, path, text))
        errors.extend(command_errors(root, path, text, set(policy["allowed_commands"])))
        errors.extend(port_errors(root, path, text))
        errors.extend(variable_errors(root, path, text))
        errors.extend(metric_errors(root, path, text, policy["documented_metric_debts"]))
        errors.extend(claim_errors(root, path, text, policy["documented_claim_debts"]))
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    count = load_json(Path(__file__).resolve().parent / "relocation-manifest.json")["section_count"]
    print(f"docs: PASS ({count} sections; links, commands, ports, variables, metrics, claims)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
