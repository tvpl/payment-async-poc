import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "smoke" / "lifecycle" / "validate_lifecycle.py"
FIXTURES = ROOT / "smoke" / "lifecycle" / "fixtures"
RESET = ROOT / "scripts" / "reset-data.sh"


def environment() -> dict[str, str]:
    values = os.environ.copy()
    values["POSTGRES_PASSWORD"] = "structural-test-only"
    values["GRAFANA_ADMIN_PASSWORD"] = "structural-test-only"
    return values


class LifecycleValidationTest(unittest.TestCase):
    def test_current_images_and_retention_are_reproducible(self) -> None:
        result = subprocess.run([str(VALIDATOR)], capture_output=True, text=True, env=environment())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("READY (12 pinned images)", result.stdout)

    def test_unpinned_image_is_rejected(self) -> None:
        result = subprocess.run(
            [str(VALIDATOR), "--overlay", str(FIXTURES / "unpinned.yml")],
            capture_output=True,
            text=True,
            env=environment(),
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("kafka image is not tag+digest pinned", result.stderr)

    def test_incoherent_retention_is_rejected(self) -> None:
        result = subprocess.run(
            [str(VALIDATOR), "--lifecycle", str(FIXTURES / "invalid-lifecycle.json")],
            capture_output=True,
            text=True,
            env=environment(),
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("Kafka retention must be positive and bounded", result.stderr)
        self.assertIn("Prometheus retention must be positive and bounded", result.stderr)

    def test_reset_without_confirmation_refuses_before_docker(self) -> None:
        result = subprocess.run([str(RESET)], capture_output=True, text=True, env=environment())
        self.assertEqual(2, result.returncode)
        self.assertIn("Refusing destructive reset", result.stderr)


if __name__ == "__main__":
    unittest.main()
