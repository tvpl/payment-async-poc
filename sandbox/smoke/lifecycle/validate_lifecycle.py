#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
IMAGE_PATTERN = re.compile(r"^[^@\s]+:[^@\s]+@sha256:[a-f0-9]{64}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate sandbox image and data lifecycle policy.")
    parser.add_argument("--base", type=Path, default=ROOT / "compose.yml")
    parser.add_argument("--profiles-file", type=Path, default=ROOT / "compose.profiles.yml")
    parser.add_argument("--overlay", action="append", type=Path, default=[])
    parser.add_argument("--lifecycle", type=Path, default=ROOT / "config" / "lifecycle.json")
    return parser.parse_args()


def render(files: list[Path]) -> dict:
    command = ["docker", "compose"]
    for compose_file in files:
        command.extend(["-f", str(compose_file)])
    command.extend(["--profile", "observability", "--profile", "tools", "config", "--format", "json"])
    result = subprocess.run(command, capture_output=True, text=True, env=os.environ.copy())
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout)


def validate(config: dict, lifecycle: dict) -> list[str]:
    failures: list[str] = []
    for name, service in config["services"].items():
        image = service.get("image", "")
        if not IMAGE_PATTERN.fullmatch(image):
            failures.append(f"service {name} image is not tag+digest pinned: {image}")

    declared_volumes = {volume["name"] for volume in config.get("volumes", {}).values()}
    if declared_volumes != set(lifecycle.get("volumes", [])):
        failures.append("lifecycle volumes do not match rendered named volumes")

    retention = lifecycle.get("retention", {})
    kafka = retention.get("kafka", {})
    if kafka.get("hours", 0) < 1 or kafka.get("bytesPerPartition", 0) < 1048576:
        failures.append("Kafka retention must be positive and bounded")
    kafka_environment = config["services"]["kafka"]["environment"]
    if str(kafka.get("hours")) != str(kafka_environment.get("KAFKA_LOG_RETENTION_HOURS")):
        failures.append("Kafka retention hours diverge from lifecycle policy")
    if str(kafka.get("bytesPerPartition")) != str(kafka_environment.get("KAFKA_LOG_RETENTION_BYTES")):
        failures.append("Kafka retention bytes diverge from lifecycle policy")

    prometheus = retention.get("prometheus", {})
    if prometheus.get("days", 0) < 1 or prometheus.get("sizeGB", 0) < 1:
        failures.append("Prometheus retention must be positive and bounded")
    command = config["services"]["prometheus"]["command"]
    if f"--storage.tsdb.retention.time={prometheus.get('days')}d" not in command:
        failures.append("Prometheus time retention diverges from lifecycle policy")
    if f"--storage.tsdb.retention.size={prometheus.get('sizeGB')}GB" not in command:
        failures.append("Prometheus size retention diverges from lifecycle policy")

    registry = retention.get("registry", {})
    if registry.get("persistent") is not False or not registry.get("classification"):
        failures.append("ephemeral Registry lifecycle must be explicit")

    reset_script = (ROOT / "scripts" / "reset-data.sh").read_text(encoding="utf-8")
    if "--confirm-destroy-sandbox-data" not in reset_script:
        failures.append("reset script lacks explicit destructive confirmation")
    for smoke_script in (ROOT / "smoke").rglob("*.sh"):
        if "reset-data" in smoke_script.read_text(encoding="utf-8"):
            failures.append(f"smoke references destructive reset: {smoke_script}")
    return failures


def main() -> int:
    args = parse_args()
    try:
        config = render([args.base, args.profiles_file, *args.overlay])
        lifecycle = json.loads(args.lifecycle.read_text(encoding="utf-8"))
    except (RuntimeError, OSError, json.JSONDecodeError) as error:
        print(f"[FAIL] lifecycle input: {error}", file=sys.stderr)
        return 1
    failures = validate(config, lifecycle)
    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}", file=sys.stderr)
        return 1
    print(f"sandbox-lifecycle: READY ({len(config['services'])} pinned images)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
