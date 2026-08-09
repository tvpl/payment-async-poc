#!/usr/bin/env python3

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")


class ContainerPackageTest(unittest.TestCase):
    def test_base_images_are_tagged_and_digest_pinned(self):
        from_lines = [line for line in DOCKERFILE.splitlines() if line.startswith("FROM ")]
        self.assertEqual(2, len(from_lines))
        self.assertTrue(all(":" in line and "@sha256:" in line for line in from_lines))

    def test_runtime_is_nonroot_without_extra_health_packages(self):
        self.assertIn("USER 10001:10001", DOCKERFILE)
        self.assertIn("HEALTHCHECK", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"\b(?:apk|apt-get)\b")

    def test_distribution_has_runtime_dependencies_and_no_version_hardcoded(self):
        self.assertIn("./gradlew installDist", DOCKERFILE)
        self.assertIn("/workspace/build/install/payment-core-mock/ /app/", DOCKERFILE)
        self.assertIn('ENTRYPOINT ["/app/bin/payment-core-mock"]', DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"payment-core-mock-[0-9].*\.jar")

    def test_image_is_explicitly_nonproduction(self):
        self.assertIn('com.example.lifecycle="NON_PRODUCTION"', DOCKERFILE)
        self.assertIn('CORE_MOCK_CLASSIFICATION="NON_PRODUCTION"', DOCKERFILE)

    def test_compose_owns_only_the_application_and_external_network(self):
        services = COMPOSE.split("services:\n", 1)[1].split("\nnetworks:\n", 1)[0]
        service_names = re.findall(r"^  ([a-z][a-z0-9-]*):$", services, re.MULTILINE)
        self.assertEqual(["core-mock"], service_names)
        self.assertIn("external: true", COMPOSE)
        self.assertIn("SANDBOX_NETWORK", COMPOSE)
        self.assertNotIn("container_name:", COMPOSE)
        for infrastructure in ("postgres:", "redis:", "kafka:", "registry:", "grafana:", "jaeger:"):
            self.assertNotIn(f"  {infrastructure}", COMPOSE)

    def test_compose_restricts_privileges_and_filesystem(self):
        self.assertIn("read_only: true", COMPOSE)
        self.assertIn("no-new-privileges:true", COMPOSE)
        self.assertRegex(COMPOSE, r"cap_drop:\s+\- ALL")
        self.assertIn('user: "10001:10001"', COMPOSE)

    def test_env_example_contains_no_secret_assignment(self):
        self.assertIn("SANDBOX_NETWORK=payment-sandbox", ENV_EXAMPLE)
        self.assertNotRegex(ENV_EXAMPLE, r"(?i)(password|secret|token)=.+")


if __name__ == "__main__":
    unittest.main()
