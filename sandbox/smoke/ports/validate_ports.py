#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASE = ROOT / "compose.yml"
DEFAULT_PROFILES = ROOT / "compose.profiles.yml"
COMBINATIONS = ((), ("observability",), ("tools",), ("observability", "tools"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate host binds across sandbox profile combinations.")
    parser.add_argument("--base", type=Path, default=DEFAULT_BASE)
    parser.add_argument("--profiles-file", type=Path, default=DEFAULT_PROFILES)
    parser.add_argument("--overlay", action="append", type=Path, default=[])
    return parser.parse_args()


def render(files: list[Path], profiles: tuple[str, ...]) -> dict:
    command = ["docker", "compose"]
    for compose_file in files:
        command.extend(["-f", str(compose_file)])
    for profile in profiles:
        command.extend(["--profile", profile])
    command.extend(["config", "--format", "json"])
    result = subprocess.run(command, capture_output=True, text=True, env=os.environ.copy())
    if result.returncode != 0:
        label = "+".join(profiles) or "minimal"
        raise RuntimeError(f"profile {label} did not materialize: {result.stderr.strip()}")
    return json.loads(result.stdout)


def global_bind(host_ip: str | None) -> bool:
    return host_ip in (None, "", "0.0.0.0", "::")


def conflicts(left: tuple[str | None, str, str], right: tuple[str | None, str, str]) -> bool:
    left_host, left_port, left_protocol = left
    right_host, right_port, right_protocol = right
    return (
        left_port == right_port
        and left_protocol == right_protocol
        and (left_host == right_host or global_bind(left_host) or global_bind(right_host))
    )


def find_collisions(config: dict) -> list[str]:
    bindings: list[tuple[str, tuple[str | None, str, str]]] = []
    collisions: list[str] = []
    for service_name, service in config.get("services", {}).items():
        for port in service.get("ports", []):
            binding = (
                port.get("host_ip"),
                str(port["published"]),
                port.get("protocol", "tcp"),
            )
            for previous_service, previous_binding in bindings:
                if previous_service != service_name and conflicts(previous_binding, binding):
                    collisions.append(
                        f"host port {binding[1]}/{binding[2]} conflicts: {previous_service} <-> {service_name}"
                    )
            bindings.append((service_name, binding))
    return collisions


def main() -> int:
    args = parse_args()
    files = [args.base, args.profiles_file, *args.overlay]
    failures: list[str] = []
    for profiles in COMBINATIONS:
        label = "+".join(profiles) or "minimal"
        try:
            config = render(files, profiles)
        except RuntimeError as error:
            failures.append(str(error))
            break
        for collision in find_collisions(config):
            failures.append(f"profile {label}: {collision}")

    if failures:
        for failure in dict.fromkeys(failures):
            print(f"[FAIL] {failure}", file=sys.stderr)
        return 1

    print(f"sandbox-ports: READY ({len(COMBINATIONS)} profile combinations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
