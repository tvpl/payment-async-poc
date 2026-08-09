#!/usr/bin/env bash
set -euo pipefail

boundary_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$boundary_root/scripts/test_docs.py"
python3 "$boundary_root/scripts/validate_docs.py"
