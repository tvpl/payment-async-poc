#!/usr/bin/env python3
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")
APPLICATION_YML = (ROOT / "src/main/resources/application.yml").read_text(encoding="utf-8")


class ReleasePackageTest(unittest.TestCase):
    def test_base_images_are_tagged_and_digest_pinned(self):
        images = [line for line in DOCKERFILE.splitlines() if line.startswith("FROM ")]
        self.assertEqual(2, len(images))
        self.assertTrue(all(":" in line and "@sha256:" in line for line in images))

    def test_runtime_is_nonroot_and_uses_installed_distribution(self):
        self.assertIn("USER 10001:10001", DOCKERFILE)
        self.assertIn("./gradlew installDist", DOCKERFILE)
        self.assertIn("/app/bin/payment-api", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"payment-api-[0-9].*\.jar")

    def test_build_consumes_published_dependencies_only(self):
        for context in ("contracts-repository", "feature-control-repository"):
            self.assertIn(f"COPY --from={context}", DOCKERFILE)
        self.assertNotIn("project(':", DOCKERFILE)

    def test_healthcheck_adds_no_runtime_package(self):
        self.assertIn("HEALTHCHECK", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"\b(?:apk|apt-get)\b")

    def test_compose_owns_only_the_api_and_external_network(self):
        services = COMPOSE.split("services:\n", 1)[1].split("\nnetworks:\n", 1)[0]
        self.assertEqual(["api"], re.findall(r"^  ([a-z][a-z0-9-]*):$", services, re.MULTILINE))
        self.assertIn("external: true", COMPOSE)
        self.assertNotIn("container_name:", COMPOSE)
        for infrastructure in ("postgres:", "redis:", "kafka:", "registry:", "grafana:", "jaeger:"):
            self.assertNotIn(f"  {infrastructure}", COMPOSE)

    def test_compose_restricts_privileges_and_filesystem(self):
        self.assertIn("read_only: true", COMPOSE)
        self.assertIn("no-new-privileges:true", COMPOSE)
        self.assertRegex(COMPOSE, r"cap_drop:\s+\- ALL")
        self.assertIn('user: "10001:10001"', COMPOSE)

    def test_scaling_the_service_also_scales_the_admission_divisor(self):
        """A replica that does not know the fleet size would grant itself the whole budget."""
        self.assertIn("PAYMENT_API_INSTANCES", COMPOSE)
        self.assertIn("PAYMENT_API_INSTANCES", ENV_EXAMPLE)
        self.assertIn("instances: ${PAYMENT_API_INSTANCES:1}", APPLICATION_YML)

    def test_ci_covers_build_integration_image_sbom_scan_and_docs(self):
        for marker in ("./gradlew test --no-daemon", "-PwithIT", "docker buildx build",
                       "--build-context contracts-repository",
                       "--build-context feature-control-repository",
                       "sbom-action", "trivy-action",
                       "scripts/verify-docs.sh", "git diff --check"):
            self.assertIn(marker, WORKFLOW)
        self.assertIn("exit-code: '1'", WORKFLOW)
        self.assertIn("severity: 'HIGH,CRITICAL'", WORKFLOW)

    def test_env_example_contains_no_assigned_secret(self):
        self.assertIn("SANDBOX_NETWORK=payment-sandbox", ENV_EXAMPLE)
        self.assertNotRegex(ENV_EXAMPLE, r"(?i)(password|secret|token)=\S+")
        self.assertIn("PAYMENT_API_KEY=\n", ENV_EXAMPLE)

    def test_owned_runbooks_cover_admission_dlq_and_rollback(self):
        runbooks = (ROOT / "ops/runbooks/README.md").read_text(encoding="utf-8")
        self.assertIn("admission-saturation.md", runbooks)
        self.assertIn("response-dlq.md", runbooks)
        self.assertIn("release-rollback.md", runbooks)


if __name__ == "__main__":
    unittest.main()
