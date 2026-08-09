#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
SPEC = importlib.util.spec_from_file_location("repository_secrets", SCRIPT_DIR / "check_repository_secrets.py")
repository_secrets = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(repository_secrets)


class RepositorySecretsTest(unittest.TestCase):
    def test_repository_security_configuration_is_safe(self) -> None:
        self.assertEqual([], repository_secrets.validate(REPOSITORY_ROOT))

    def test_tracked_real_environment_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".env"
            path.write_text("API_KEY=credential\n", encoding="utf-8")

            self.assertEqual(
                ["tracked environment file: .env"],
                repository_secrets.unsafe_tracked_environment([path], root),
            )

    def test_secret_value_in_example_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".env.example"
            path.write_text("API_KEY=credential\n", encoding="utf-8")

            self.assertEqual(
                ["secret-like example value for API_KEY in .env.example"],
                repository_secrets.scan_text(path, root),
            )

    def test_privileged_configuration_fallback_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "compose.yaml"
            path.write_text("API_KEY: ${API_KEY:-credential}\n", encoding="utf-8")

            self.assertEqual(
                ["privileged fallback for API_KEY in compose.yaml"],
                repository_secrets.scan_text(path, root),
            )

    def test_required_interpolation_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "compose.yaml"
            path.write_text("API_KEY: ${API_KEY:?set API_KEY}\n", encoding="utf-8")

            self.assertEqual([], repository_secrets.scan_text(path, root))

    def test_missing_docker_context_exclusions_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".dockerignore").write_text(".git\n", encoding="utf-8")

            self.assertEqual(
                [".dockerignore missing: **/build, *.key, *.pem, .env, .env.*"],
                repository_secrets.dockerignore_errors(root),
            )


if __name__ == "__main__":
    unittest.main()
