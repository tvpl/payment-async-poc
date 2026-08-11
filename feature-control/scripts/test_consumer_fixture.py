#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
BOUNDARY_ROOT = SCRIPT_DIR.parent
FIXTURE = BOUNDARY_ROOT / "consumer-fixture"


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


consumer_fixture = _load("check_consumer_fixture")
api_surface = _load("verify_api_surface")


class ConsumerFixtureTest(unittest.TestCase):
    def test_fixture_declares_the_gav_without_source_substitution(self) -> None:
        self.assertEqual([], consumer_fixture.fixture_errors(FIXTURE))

    def test_missing_artifacts_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = consumer_fixture.artifact_errors(Path(directory))

            self.assertEqual(4, len(errors))
            self.assertTrue(all(error.startswith("missing published artifact:") for error in errors))

    def test_divergent_pom_coordinates_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            target = consumer_fixture.artifact_directory(repository)
            target.mkdir(parents=True)
            prefix = f"{consumer_fixture.ARTIFACT}-{consumer_fixture.VERSION}"
            (target / f"{prefix}.pom").write_text(
                "<project><groupId>com.example.platform</groupId>"
                "<artifactId>wrong-artifact</artifactId><version>0.1.0</version></project>",
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
            target = consumer_fixture.artifact_directory(repository)
            target.mkdir(parents=True)
            prefix = f"{consumer_fixture.ARTIFACT}-{consumer_fixture.VERSION}"
            (target / f"{prefix}.pom").write_text(
                "<project><groupId>com.example.platform</groupId>"
                "<artifactId>feature-control</artifactId><version>0.1.0</version></project>",
                encoding="utf-8",
            )
            (target / f"{prefix}.jar").write_bytes(b"jar")
            (target / f"{prefix}-sources.jar").write_bytes(b"sources")
            (target / f"{prefix}-javadoc.jar").write_bytes(b"javadoc")

            self.assertEqual([], consumer_fixture.artifact_errors(repository))


class ApiSurfaceCompatibilityTest(unittest.TestCase):
    """FTR-06: 'breaking API falha' — the compatibility gate must reject a removed public member."""

    def test_identical_surface_has_no_breaking_change(self) -> None:
        baseline = {"pkg.Foo": ["public void bar()", "public int baz()"]}
        current = {"pkg.Foo": ["public void bar()", "public int baz()"]}

        self.assertEqual([], api_surface.breaking_changes(baseline, current))

    def test_a_new_method_in_current_is_not_a_breaking_change(self) -> None:
        baseline = {"pkg.Foo": ["public void bar()"]}
        current = {"pkg.Foo": ["public void bar()", "public void newMethod()"]}

        self.assertEqual([], api_surface.breaking_changes(baseline, current),
                          "API growth must never be flagged as a breaking change")

    def test_a_removed_public_method_is_reported_as_breaking(self) -> None:
        baseline = {"pkg.Foo": ["public void bar()", "public int baz()"]}
        current = {"pkg.Foo": ["public void bar()"]}

        errors = api_surface.breaking_changes(baseline, current)

        self.assertEqual(1, len(errors))
        self.assertIn("pkg.Foo", errors[0])
        self.assertIn("baz()", errors[0])

    def test_a_removed_promised_class_is_reported_as_breaking(self) -> None:
        baseline = {"pkg.Foo": ["public void bar()"], "pkg.Removed": ["public void gone()"]}
        current = {"pkg.Foo": ["public void bar()"]}

        errors = api_surface.breaking_changes(baseline, current)

        self.assertEqual(1, len(errors))
        self.assertIn("pkg.Removed", errors[0])

    def test_baseline_format_round_trips_through_parse(self) -> None:
        # format_baseline sorts class names and members; pre-sort the fixture so round-trip equality holds.
        surface = {"pkg.Bar": ["public Bar()"], "pkg.Foo": ["public int baz()", "public void bar()"]}

        formatted = api_surface.format_baseline(surface)
        parsed = api_surface.parse_baseline(formatted)

        self.assertEqual(surface, parsed)

    def test_javap_method_lines_are_extracted_and_class_header_is_dropped(self) -> None:
        javap_output = (
            "public final class pkg.Foo {\n"
            "  public pkg.Foo();\n"
            "  public void   bar();\n"
            "  protected int baz();\n"
            "}\n"
        )

        members = api_surface.parse_members(javap_output)

        self.assertEqual(["public pkg.Foo()", "public void bar()", "protected int baz()"], members)

    def test_the_committed_baseline_matches_every_promised_class(self) -> None:
        baseline_path = FIXTURE / "api-surface-baseline.txt"
        baseline = api_surface.parse_baseline(baseline_path.read_text(encoding="utf-8"))

        self.assertEqual(set(api_surface.PROMISED_CLASSES), set(baseline.keys()))
        for cls, members in baseline.items():
            self.assertTrue(members, f"{cls} has no recorded public members in the baseline")


if __name__ == "__main__":
    unittest.main()
