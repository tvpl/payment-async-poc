import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "smoke" / "ports" / "validate_ports.py"
FIXTURES = ROOT / "smoke" / "ports" / "fixtures"


def environment() -> dict[str, str]:
    values = os.environ.copy()
    values["POSTGRES_PASSWORD"] = "structural-test-only"
    values["GRAFANA_ADMIN_PASSWORD"] = "structural-test-only"
    return values


class PortValidationTest(unittest.TestCase):
    def run_validator(self, fixture: str | None = None, unset: str | None = None) -> subprocess.CompletedProcess[str]:
        command = [str(VALIDATOR)]
        if fixture:
            command.extend(["--overlay", str(FIXTURES / fixture)])
        values = environment()
        if unset:
            values.pop(unset, None)
        return subprocess.run(command, capture_output=True, text=True, env=values)

    def test_current_configuration_has_unique_host_binds_in_all_combinations(self) -> None:
        result = self.run_validator()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("READY (4 profile combinations)", result.stdout)

    def test_synthetic_registry_collision_reports_port_and_services(self) -> None:
        result = self.run_validator("collision-8085.yml")
        self.assertEqual(1, result.returncode)
        self.assertIn("8085/tcp", result.stderr)
        self.assertIn("registry <-> synthetic-port-collision", result.stderr)

    def test_missing_required_variable_fails_materialization(self) -> None:
        result = self.run_validator("missing-variable.yml", unset="SANDBOX_REQUIRED_TEST")
        self.assertEqual(1, result.returncode)
        self.assertIn("SANDBOX_REQUIRED_TEST is required", result.stderr)


if __name__ == "__main__":
    unittest.main()
