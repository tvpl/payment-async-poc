import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
OBSERVABILITY = ROOT / "observability"
PRODUCT_NAMES = {"api-service", "sbus-service", "core-mock", "feature-demo", "async-redis-service", "pilot-app"}


class ObservabilityOwnershipTest(unittest.TestCase):
    def test_common_configuration_does_not_claim_product_targets(self) -> None:
        configuration = "\n".join(
            path.read_text(encoding="utf-8")
            for path in OBSERVABILITY.rglob("*")
            if path.is_file()
        )
        for product_name in PRODUCT_NAMES:
            self.assertNotIn(product_name, configuration)

    def test_application_asset_manifest_starts_empty(self) -> None:
        manifest = json.loads((OBSERVABILITY / "application-assets.json").read_text(encoding="utf-8"))
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual([], manifest["assets"])


if __name__ == "__main__":
    unittest.main()
