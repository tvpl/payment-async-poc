#!/usr/bin/env python3
"""Prove the internal HTTP contract fixture (API-03) stays identical on both sides of the
Edge<->Sbus boundary.

`payment-sbus/src/test/resources/contracts/internal-status.json` and
`payment-api/src/test/resources/contracts/internal-status.json` are independently maintained
copies of the same canonical `SbusStatusView`/`SbusStatusResponse` JSON shape (T14/T15). Each
module's own contract test (`SbusStatusViewContractUnitTest`, `SbusStatusResponseContractUnitTest`)
only proves ITS type still matches ITS local copy of the fixture — neither test can see that the
two copies drifted apart from each other. This script is the cross-boundary check the two unit
tests cannot do alone: a byte-for-byte comparison of the two fixture files.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SBUS_FIXTURE = Path("payment-sbus/src/test/resources/contracts/internal-status.json")
API_FIXTURE = Path("payment-api/src/test/resources/contracts/internal-status.json")


def compare_fixtures(root: Path) -> list[str]:
    sbus_path = root / SBUS_FIXTURE
    api_path = root / API_FIXTURE

    errors = []
    for relative, path in ((SBUS_FIXTURE, sbus_path), (API_FIXTURE, api_path)):
        if not path.is_file():
            errors.append(f"{relative}: missing")
    if errors:
        return errors

    sbus_bytes = sbus_path.read_bytes()
    api_bytes = api_path.read_bytes()
    if sbus_bytes != api_bytes:
        errors.append(
            f"{SBUS_FIXTURE} and {API_FIXTURE} diverged byte-for-byte — the internal contract "
            "fixture (API-03) must stay identical on both sides of the Edge<->Sbus boundary"
        )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    errors = compare_fixtures(args.root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("internal-contract: PASS (fixtures byte-identical)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
