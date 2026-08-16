#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
BOUNDARY_ROOT = SCRIPT_DIR.parent
FIXTURE = BOUNDARY_ROOT / "consumer-fixture"
SPEC = importlib.util.spec_from_file_location("consumer_fixture", SCRIPT_DIR / "check_consumer_fixture.py")
consumer_fixture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(consumer_fixture)


class ConsumerFixtureTest(unittest.TestCase):
    def test_fixture_declares_both_gavs_without_source_substitution(self) -> None:
        self.assertEqual([], consumer_fixture.fixture_errors(FIXTURE))

    def test_missing_artifacts_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = consumer_fixture.artifact_errors(Path(directory))

            self.assertEqual(8, len(errors))
            self.assertTrue(all(error.startswith("missing published artifact:") for error in errors))

    def test_divergent_pom_coordinates_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for artifact in consumer_fixture.ARTIFACTS:
                target = consumer_fixture.artifact_directory(repository, artifact)
                target.mkdir(parents=True)
                prefix = f"{artifact}-{consumer_fixture.VERSION}"
                pom_artifact = "wrong-artifact" if artifact == consumer_fixture.ARTIFACTS[0] else artifact
                (target / f"{prefix}.pom").write_text(
                    "<project><groupId>com.example.payments</groupId>"
                    f"<artifactId>{pom_artifact}</artifactId><version>0.2.0</version></project>",
                    encoding="utf-8",
                )
                (target / f"{prefix}.jar").write_bytes(b"jar")
                (target / f"{prefix}-sources.jar").write_bytes(b"sources")
                (target / f"{prefix}-javadoc.jar").write_bytes(b"javadoc")

            errors = consumer_fixture.artifact_errors(repository)

            self.assertEqual(1, len(errors))
            self.assertTrue(errors[0].startswith("divergent POM coordinates:"))

    def test_valid_publication_layout_and_coordinates_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for artifact in consumer_fixture.ARTIFACTS:
                target = consumer_fixture.artifact_directory(repository, artifact)
                target.mkdir(parents=True)
                prefix = f"{artifact}-{consumer_fixture.VERSION}"
                (target / f"{prefix}.pom").write_text(
                    "<project><groupId>com.example.payments</groupId>"
                    f"<artifactId>{artifact}</artifactId><version>0.2.0</version></project>",
                    encoding="utf-8",
                )
                (target / f"{prefix}.jar").write_bytes(b"jar")
                (target / f"{prefix}-sources.jar").write_bytes(b"sources")
                (target / f"{prefix}-javadoc.jar").write_bytes(b"javadoc")

            self.assertEqual([], consumer_fixture.artifact_errors(repository))


if __name__ == "__main__":
    unittest.main()
