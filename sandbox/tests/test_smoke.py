from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "smoke" / "verify.sh"


class SandboxSmokeContractTest(unittest.TestCase):
    def test_catalog_exposes_at_least_six_dependency_specific_probes(self) -> None:
        result = subprocess.run(
            [str(VERIFY), "--list"],
            check=True,
            capture_output=True,
            text=True,
        )
        probes = set(result.stdout.splitlines())
        self.assertGreaterEqual(len(probes), 6)
        self.assertTrue({"kafka.metadata", "redis.ping", "postgres.query", "registry.info"} <= probes)

    def test_smoke_contains_actionable_failure_command(self) -> None:
        script = VERIFY.read_text(encoding="utf-8")
        self.assertIn("docker compose -f $ROOT_DIR/compose.yml logs $dependency", script)


if __name__ == "__main__":
    unittest.main()
