import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "compose.yml"
PROFILE_COMPOSE = ROOT / "compose.profiles.yml"
EXPECTED_SERVICES = {"kafka", "redis", "postgres", "registry"}


def render_compose(*profiles: str) -> dict:
    environment = os.environ.copy()
    environment["POSTGRES_PASSWORD"] = "structural-test-only"
    environment["GRAFANA_ADMIN_PASSWORD"] = "structural-test-only"
    command = ["docker", "compose", "-f", str(COMPOSE)]
    if profiles:
        command.extend(["-f", str(PROFILE_COMPOSE)])
    for profile in profiles:
        command.extend(["--profile", profile])
    command.extend(["config", "--format", "json"])
    result = subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=True,
        env=environment,
    )
    return json.loads(result.stdout)


class SandboxComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = render_compose()

    def test_minimal_profile_contains_only_shared_dependencies(self) -> None:
        self.assertEqual(EXPECTED_SERVICES, set(self.config["services"]))

    def test_services_do_not_build_products_or_fix_container_names(self) -> None:
        for service in self.config["services"].values():
            self.assertNotIn("build", service)
            self.assertNotIn("container_name", service)

    def test_every_service_joins_named_sandbox_network(self) -> None:
        self.assertEqual("payment-sandbox", self.config["networks"]["sandbox"]["name"])
        for service in self.config["services"].values():
            self.assertIn("sandbox", service["networks"])

    def test_stateful_dependencies_use_named_volumes(self) -> None:
        self.assertEqual(
            {"payment-sandbox-kafka", "payment-sandbox-redis", "payment-sandbox-postgres"},
            {volume["name"] for volume in self.config["volumes"].values()},
        )

    def test_observability_profile_contains_only_common_telemetry(self) -> None:
        services = set(render_compose("observability")["services"])
        self.assertEqual(
            EXPECTED_SERVICES
            | {"jaeger", "otel-collector", "prometheus", "grafana", "redis-exporter", "postgres-exporter", "kafka-exporter"},
            services,
        )

    def test_tools_profile_adds_only_kafka_ui(self) -> None:
        services = set(render_compose("tools")["services"])
        self.assertEqual(EXPECTED_SERVICES | {"kafka-ui"}, services)


if __name__ == "__main__":
    unittest.main()
