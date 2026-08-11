#!/usr/bin/env python3
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile").read_text(encoding="utf-8")
COMPOSE = (ROOT / "compose.yaml").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")


class ReleasePackageTest(unittest.TestCase):
    def test_base_images_are_tagged_and_digest_pinned(self):
        images = [line for line in DOCKERFILE.splitlines() if line.startswith("FROM ")]
        self.assertEqual(2, len(images))
        self.assertTrue(all(":" in line and "@sha256:" in line for line in images))

    def test_runtime_is_nonroot_and_uses_installed_distribution(self):
        self.assertIn("USER 10001:10001", DOCKERFILE)
        self.assertIn("./gradlew installDist", DOCKERFILE)
        self.assertIn("/app/bin/async-redis-service", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"async-redis-service-[0-9].*\.jar")

    def test_healthcheck_adds_no_runtime_package(self):
        self.assertIn("HEALTHCHECK", DOCKERFILE)
        self.assertNotRegex(DOCKERFILE, r"\b(?:apk|apt-get)\b")

    def test_dockerfile_declares_no_cross_boundary_build_context(self):
        # This boundary is standalone (StandaloneBoundaryTest) - no other root's build output feeds
        # the image.
        self.assertNotIn("--from=contracts-repository", DOCKERFILE)
        self.assertNotIn("--from=feature-control-repository", DOCKERFILE)
        self.assertNotIn("additional_contexts", COMPOSE)

    def test_compose_owns_only_this_service_and_the_external_network(self):
        services = COMPOSE.split("services:\n", 1)[1].split("\nnetworks:\n", 1)[0]
        self.assertEqual(["async-redis"], re.findall(r"^  ([a-z][a-z0-9-]*):$", services, re.MULTILINE))
        self.assertIn("external: true", COMPOSE)
        self.assertNotIn("container_name:", COMPOSE)
        for infrastructure in ("postgres:", "redis:", "kafka:", "registry:", "grafana:", "jaeger:"):
            self.assertNotIn(f"  {infrastructure}", COMPOSE)

    def test_compose_restricts_privileges_and_filesystem(self):
        self.assertIn("read_only: true", COMPOSE)
        self.assertIn("no-new-privileges:true", COMPOSE)
        self.assertRegex(COMPOSE, r"cap_drop:\s+\- ALL")
        self.assertIn('user: "10001:10001"', COMPOSE)

    def test_ci_covers_build_integration_image_sbom_scan_and_docs(self):
        for marker in ("./gradlew test --no-daemon", "-PwithIT", "docker buildx build",
                       "sbom-action", "trivy-action", "scripts/verify-docs.sh", "git diff --check"):
            self.assertIn(marker, WORKFLOW)
        self.assertIn("exit-code: '1'", WORKFLOW)
        self.assertIn("severity: 'HIGH,CRITICAL'", WORKFLOW)

    def test_env_example_contains_no_assigned_secret(self):
        self.assertIn("SANDBOX_NETWORK=payment-sandbox", ENV_EXAMPLE)
        self.assertNotRegex(ENV_EXAMPLE, r"(?i)(password|secret|token)=\S+")

    def test_owned_runbooks_cover_backlog_dlq_worker_and_rollback(self):
        runbooks = (ROOT / "ops/runbooks/README.md").read_text(encoding="utf-8")
        self.assertIn("backlog-retention.md", runbooks)
        self.assertIn("poison-dlq.md", runbooks)
        self.assertIn("worker-outage-recovery.md", runbooks)
        self.assertIn("release-rollback.md", runbooks)


if __name__ == "__main__":
    unittest.main()
