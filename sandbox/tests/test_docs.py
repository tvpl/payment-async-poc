from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts" / "validate_docs.py"


class SandboxDocumentationTest(unittest.TestCase):
    def test_local_documentation_gate_passes(self) -> None:
        result = subprocess.run([str(VALIDATOR)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("READY (11 required documents)", result.stdout)

    def test_agent_guide_names_exact_local_gates_and_prohibitions(self) -> None:
        guide = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        self.assertIn("make verify-structural", guide)
        self.assertIn("make verify-runtime", guide)
        self.assertIn("Não adicionar build, fonte, migration, schema ou mock de aplicação", guide)
        self.assertIn("Não alterar ou remover volumes sem autorização explícita", guide)

    def test_operations_keeps_reset_outside_make_and_warns_irrecoverability(self) -> None:
        operations = (ROOT / "docs" / "operations.md").read_text(encoding="utf-8")
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn("./scripts/reset-data.sh --confirm-destroy-sandbox-data", operations)
        self.assertIn("A remoção não é recuperável sem backup", operations)
        self.assertNotIn("reset:", makefile)


if __name__ == "__main__":
    unittest.main()
