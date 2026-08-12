#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
FIXTURE = SCRIPT_DIR / "consumer-fixture"
SPEC = importlib.util.spec_from_file_location("artifact_flow", SCRIPT_DIR / "check_artifact_flow.py")
artifact_flow = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(artifact_flow)


class ArtifactFlowTest(unittest.TestCase):
    def test_fixture_uses_only_the_versioned_gav(self) -> None:
        self.assertEqual([], artifact_flow.fixture_errors(FIXTURE))

    def test_missing_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = artifact_flow.artifact_errors(Path(directory))

            self.assertEqual(2, len(errors))
            self.assertTrue(all(error.startswith("missing published artifact:") for error in errors))

    def test_pom_and_jar_are_required_for_success(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            artifact_directory = artifact_flow.artifact_directory(repository)
            artifact_directory.mkdir(parents=True)
            (artifact_directory / "feature-control-0.1.0.pom").write_text("<project/>", encoding="utf-8")
            (artifact_directory / "feature-control-0.1.0.jar").write_bytes(b"jar")

            self.assertEqual([], artifact_flow.artifact_errors(repository))

    def test_divergent_version_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            published = artifact_flow.artifact_directory(repository)
            published.mkdir(parents=True)
            (published / "feature-control-0.1.0.pom").write_text("<project/>", encoding="utf-8")
            (published / "feature-control-0.1.0.jar").write_bytes(b"jar")

            self.assertEqual(2, len(artifact_flow.artifact_errors(repository, version="9.9.9")))

    def test_release_gate_never_uses_composite_substitution(self) -> None:
        release = (SCRIPT_DIR / "verify-artifact-only.sh").read_text(encoding="utf-8")

        self.assertNotIn("--include-build", release)

    def test_artifact_only_gate_resolves_published_and_rejects_missing(self) -> None:
        result = subprocess.run(
            [str(SCRIPT_DIR / "verify-artifact-only.sh")],
            cwd=SCRIPT_DIR.parents[1],
            text=True,
            capture_output=True,
            timeout=120,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("artifact-flow: PASS", result.stdout)
        self.assertIn("missing GAV fails", result.stdout)

    def test_no_root_gradle_build_exists_to_include(self) -> None:
        # Composite substitution across boundaries is not merely discouraged, it is structurally
        # impossible: `--include-build <root>` needs a Gradle build at the workspace root, and the
        # migration removed the aggregator. This replaces the old run-with-composite.sh execution
        # test, which asserted a capability that no longer has a subject.
        root = SCRIPT_DIR.parents[1]

        for build_file in ("settings.gradle", "settings.gradle.kts", "build.gradle", "gradlew"):
            self.assertFalse((root / build_file).exists(), f"unexpected root Gradle build file: {build_file}")


if __name__ == "__main__":
    unittest.main()
