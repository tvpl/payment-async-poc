#!/usr/bin/env python3

from __future__ import annotations

import re
import unittest
from pathlib import Path


BOUNDARY_ROOT = Path(__file__).resolve().parents[1]


class StandaloneContractsBuildTest(unittest.TestCase):
    def test_settings_declares_only_contract_boundary_modules(self) -> None:
        settings = (BOUNDARY_ROOT / "settings.gradle").read_text(encoding="utf-8")

        self.assertEqual(
            {"contract-model", "contract-avro-apicurio"},
            set(re.findall(r"include '([^']+)'", settings)),
        )

    def test_build_does_not_reference_parent_or_legacy_projects(self) -> None:
        gradle_files = [path for path in BOUNDARY_ROOT.rglob("*.gradle") if path.is_file()]
        contents = "\n".join(path.read_text(encoding="utf-8") for path in gradle_files)

        for forbidden in ("../", "common", "api-service", "sbus-service", "rootDir.parent"):
            self.assertNotIn(forbidden, contents, forbidden)

    def test_java_21_is_boundary_owned(self) -> None:
        properties = (BOUNDARY_ROOT / "gradle.properties").read_text(encoding="utf-8")
        build = (BOUNDARY_ROOT / "build.gradle").read_text(encoding="utf-8")

        self.assertIn("javaLanguageVersion=21", properties)
        self.assertIn("providers.gradleProperty('javaLanguageVersion')", build)

    def test_both_modules_publish_pom_binary_sources_and_javadoc(self) -> None:
        build = (BOUNDARY_ROOT / "build.gradle").read_text(encoding="utf-8")

        self.assertIn("withSourcesJar()", build)
        self.assertIn("withJavadocJar()", build)
        self.assertIn("from components.java", build)
        self.assertIn("verifyLocalPublication", build)


if __name__ == "__main__":
    unittest.main()
