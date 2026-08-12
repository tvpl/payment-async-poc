#!/usr/bin/env python3
"""Reject tracked secrets, privileged fallbacks, and unsafe Docker contexts."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


SENSITIVE_VARIABLES = {
    "API_KEY",
    "PAYMENT_API_KEY",
    "JWT_SIGNATURE_SECRET",
    # payment-sbus's dev JWT signer holds the same class of value as JWT_SIGNATURE_SECRET; it was
    # escaping the rule on its name alone, not on being any safer.
    "SBUS_DEV_JWT_SECRET",
    "ASYNC_REDIS_SECURITY_API_KEYS",
    "POSTGRES_PASSWORD",
    "GRAFANA_ADMIN_PASSWORD",
}
CONFIG_NAMES = {"Makefile"}
# Name-matched instead of an exact set: the workspace uses compose.yaml, compose.yml,
# compose.profiles.yml and docker-compose.*.example.yml. The old literal set silently skipped
# every sandbox and deploy compose file, so their interpolations were never scanned at all.
CONFIG_SUFFIXES = (".yml", ".yaml")
PRIVATE_KEY_MARKER = "-----BEGIN " + "PRIVATE KEY-----"
HIGH_CONFIDENCE_TOKENS = (
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
)
INTERPOLATION = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?:(:-|:\?)([^}]*))?\}")


def tracked_and_pending_paths(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return [root / item.decode() for item in result.stdout.split(b"\0") if item]


def unsafe_tracked_environment(paths: list[Path], root: Path) -> list[str]:
    unsafe = []
    for path in paths:
        relative = path.relative_to(root).as_posix()
        name = path.name
        if name == ".env" or (name.startswith(".env.") and name != ".env.example"):
            unsafe.append(f"tracked environment file: {relative}")
        if path.suffix in {".key", ".pem"}:
            unsafe.append(f"tracked private-key file: {relative}")
    return unsafe


def configuration_files(paths: list[Path]) -> list[Path]:
    return [
        path
        for path in paths
        if path.name in CONFIG_NAMES
        or (
            path.suffix in CONFIG_SUFFIXES
            and (path.name.startswith(("application", "compose")) or "docker-compose" in path.name)
        )
        or path.name == ".env.example"
    ]


def scan_text(path: Path, root: Path) -> list[str]:
    errors = []
    text = path.read_text(encoding="utf-8")
    relative = path.relative_to(root).as_posix()
    if PRIVATE_KEY_MARKER in text:
        errors.append(f"private key material in {relative}")
    for pattern in HIGH_CONFIDENCE_TOKENS:
        if pattern.search(text):
            errors.append(f"credential token in {relative}")
    for variable, operator, value in INTERPOLATION.findall(text):
        if variable in SENSITIVE_VARIABLES and operator == ":-" and value:
            errors.append(f"privileged fallback for {variable} in {relative}")
    if path.name == ".env.example":
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            variable, value = stripped.split("=", 1)
            if variable in SENSITIVE_VARIABLES and value.strip():
                errors.append(f"secret-like example value for {variable} in {relative}")
    return errors


def dockerignore_errors(root: Path) -> list[str]:
    path = root / ".dockerignore"
    if not path.exists():
        return ["missing .dockerignore"]
    patterns = {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}
    required = {".env", ".env.*", "*.key", "*.pem", ".git", "**/build"}
    missing = sorted(required - patterns)
    return [".dockerignore missing: " + ", ".join(missing)] if missing else []


def declared_variables(path: Path) -> set[str]:
    return {
        line.split("=", 1)[0].strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    }


def example_errors(root: Path, paths: list[Path]) -> list[str]:
    """Every sensitive variable a compose file consumes must be declared in its own boundary's
    `.env.example`, so an operator sees what to fill in.

    Checked per boundary, not against the workspace root: each root owns its own `.env.example`
    now, and the previous root-only rule was a leftover of the deleted monolithic compose — it
    demanded that the root declare secrets belonging to boundaries it no longer composes.
    """
    errors = []
    if not (root / ".dockerignore").exists():
        errors.append("missing .dockerignore")
    for path in paths:
        name = path.name
        is_compose = path.suffix in CONFIG_SUFFIXES and (
            name.startswith("compose") or "docker-compose" in name
        )
        if not is_compose or ".example." in name:
            continue
        used = {
            variable
            for variable, _, _ in INTERPOLATION.findall(path.read_text(encoding="utf-8"))
            if variable in SENSITIVE_VARIABLES
        }
        if not used:
            continue
        example = path.parent / ".env.example"
        relative = path.relative_to(root).as_posix()
        if not example.exists():
            errors.append(f"missing .env.example next to {relative}")
            continue
        missing = sorted(used - declared_variables(example))
        if missing:
            errors.append(
                f"{example.relative_to(root).as_posix()} missing variables used by "
                f"{relative}: " + ", ".join(missing)
            )
    return errors


def validate(root: Path, paths: list[Path] | None = None) -> list[str]:
    paths = tracked_and_pending_paths(root) if paths is None else paths
    errors = [
        *unsafe_tracked_environment(paths, root),
        *dockerignore_errors(root),
        *example_errors(root, paths),
    ]
    for path in configuration_files(paths):
        errors.extend(scan_text(path, root))
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("repository-secrets: PASS (tracked files, examples, fallbacks, Docker context)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
