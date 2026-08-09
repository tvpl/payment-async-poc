import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "compose.yml"
EXPECTED_SERVICES = {"kafka", "redis", "postgres", "registry"}


def render_compose() -> dict:
    environment = os.environ.copy()
    environment["POSTGRES_PASSWORD"] = "structural-test-only"
    result = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "config", "--format", "json"],
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


if __name__ == "__main__":
    unittest.main()
