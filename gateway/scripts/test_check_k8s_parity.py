#!/usr/bin/env python3
"""Unit tests para check-k8s-parity.py (T45/K8S-03).

O nome do script tem hífen (não é um identificador Python válido para `import`),
então ele é carregado por caminho via importlib — mesmo padrão de
scripts/docs/test_validate_docs.py.
"""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

import yaml

SCRIPT_DIR = Path(__file__).resolve().parent
GATEWAY_ROOT = SCRIPT_DIR.parent
SPEC = importlib.util.spec_from_file_location("check_k8s_parity", SCRIPT_DIR / "check-k8s-parity.py")
check_k8s_parity = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.path.insert(0, str(SCRIPT_DIR))
SPEC.loader.exec_module(check_k8s_parity)


def envoy_route(name: str, prefix: str, timeout: str, method: str | None = None,
                 rate_limit_key: str | None = None) -> dict:
    match: dict = {"prefix": prefix}
    if method:
        match["headers"] = [{"name": ":method", "string_match": {"exact": method}}]
    route: dict = {
        "name": name,
        "match": match,
        "route": {"cluster": "payment_api", "timeout": timeout},
    }
    if rate_limit_key:
        route["route"]["rate_limits"] = [
            {"actions": [{"remote_address": {}}]},
            {"actions": [{"generic_key": {"descriptor_value": rate_limit_key}}]},
        ]
    return route


def envoy_doc_with_routes(routes: list[dict]) -> dict:
    return {
        "static_resources": {
            "listeners": [
                {
                    "name": "edge_http",
                    "filter_chains": [
                        {
                            "filters": [
                                {
                                    "typed_config": {
                                        "route_config": {
                                            "virtual_hosts": [
                                                {"name": "payment_edge", "routes": routes}
                                            ]
                                        }
                                    }
                                }
                            ]
                        }
                    ],
                }
            ]
        }
    }


RATELIMIT_DOC = {
    "domain": "payment-gateway",
    "descriptors": [
        {"key": "remote_address", "rate_limit": {"unit": "minute", "requests_per_unit": 600}},
        {"key": "generic_key", "value": "payment-simulations",
         "rate_limit": {"unit": "second", "requests_per_unit": 100}},
    ],
}


def httproute_doc(name: str, prefix: str, timeout: str, method: str | None = None) -> dict:
    match: dict = {"path": {"type": "PathPrefix", "value": prefix}}
    if method:
        match["method"] = method
    return {
        "apiVersion": "gateway.networking.k8s.io/v1",
        "kind": "HTTPRoute",
        "metadata": {"name": name},
        "spec": {
            "parentRefs": [{"name": "payment-gateway"}],
            "rules": [{"matches": [match], "timeouts": {"request": timeout},
                       "backendRefs": [{"name": "payment-api", "port": 8080}]}],
        },
    }


def btp_doc(target: str, requests: int = 100, unit: str = "Second",
            remote_requests: int = 600, remote_unit: str = "Minute") -> dict:
    return {
        "apiVersion": "gateway.envoyproxy.io/v1alpha1",
        "kind": "BackendTrafficPolicy",
        "metadata": {"name": target},
        "spec": {
            "targetRefs": [{"group": "gateway.networking.k8s.io", "kind": "HTTPRoute", "name": target}],
            "rateLimit": {
                "type": "Global",
                "global": {
                    "rules": [
                        {"limit": {"requests": requests, "unit": unit}},
                        {"clientSelectors": [{"sourceCIDR": {"type": "Distinct", "value": "0.0.0.0/0"}}],
                         "limit": {"requests": remote_requests, "unit": remote_unit}},
                    ]
                },
            },
        },
    }


# Par de fixtures em paridade: uma rota GET com rate limit, espelhada dos dois lados.
BASE_ENVOY_ROUTE = envoy_route(
    "payment-status-get", "/payment-simulations/", "5s", method="GET",
    rate_limit_key="payment-simulations",
)
BASE_HTTPROUTE = httproute_doc("payment-status-get", "/payment-simulations/", "5s", method="GET")
BASE_BTP = btp_doc("payment-status-get")


class ParityTest(unittest.TestCase):
    def test_repository_manifests_are_in_parity(self) -> None:
        """Os arquivos reais do repositório (envoy.yaml, ratelimit/config.yaml,
        gateway/k8s/base/{httproutes,backendtrafficpolicy}.yaml) não têm nenhuma
        divergência - a regressão que este gate existe para pegar."""
        envoy_doc = yaml.safe_load((GATEWAY_ROOT / "envoy/envoy.yaml").read_text())
        ratelimit_doc = yaml.safe_load((GATEWAY_ROOT / "ratelimit/config.yaml").read_text())
        httproute_docs = [d for d in yaml.safe_load_all(
            (GATEWAY_ROOT / "k8s/base/httproutes.yaml").read_text()) if d]
        btp_docs = [d for d in yaml.safe_load_all(
            (GATEWAY_ROOT / "k8s/base/backendtrafficpolicy.yaml").read_text()) if d]

        self.assertEqual(
            [],
            check_k8s_parity.compute_parity_errors(envoy_doc, ratelimit_doc, httproute_docs, btp_docs),
        )

    def test_route_removed_from_k8s_side_fails_the_gate(self) -> None:
        envoy_doc = envoy_doc_with_routes([BASE_ENVOY_ROUTE])

        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc, RATELIMIT_DOC, httproute_docs=[], btp_docs=[],
        )

        self.assertEqual(
            ["allowlist: rota do compose sem HTTPRoute equivalente: "
             "('GET', '/payment-simulations/')"],
            errors,
        )

    def test_route_removed_from_compose_side_fails_the_gate(self) -> None:
        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([]), RATELIMIT_DOC,
            httproute_docs=[BASE_HTTPROUTE], btp_docs=[BASE_BTP],
        )

        self.assertEqual(
            ["allowlist: HTTPRoute sem rota equivalente no compose: "
             "('GET', '/payment-simulations/')"],
            errors,
        )

    def test_matching_routes_with_no_divergence_pass(self) -> None:
        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([BASE_ENVOY_ROUTE]), RATELIMIT_DOC,
            httproute_docs=[BASE_HTTPROUTE], btp_docs=[BASE_BTP],
        )

        self.assertEqual([], errors)

    def test_diverging_timeout_fails_the_gate(self) -> None:
        httproute = httproute_doc("payment-status-get", "/payment-simulations/", "9s", method="GET")

        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([BASE_ENVOY_ROUTE]), RATELIMIT_DOC,
            httproute_docs=[httproute], btp_docs=[BASE_BTP],
        )

        self.assertEqual(
            ["timeout: ('GET', '/payment-simulations/') diverge "
             "(compose='5s', k8s='9s')"],
            errors,
        )

    def test_diverging_rate_limit_budget_fails_the_gate(self) -> None:
        btp = btp_doc("payment-status-get", requests=50, unit="Second")

        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([BASE_ENVOY_ROUTE]), RATELIMIT_DOC,
            httproute_docs=[BASE_HTTPROUTE], btp_docs=[btp],
        )

        self.assertEqual(
            ["rate limit: orçamento de 'payment-status-get' diverge "
             "(compose=(100, 'second'), k8s=(50, 'second'))"],
            errors,
        )

    def test_diverging_remote_address_cap_fails_the_gate(self) -> None:
        btp = btp_doc("payment-status-get", remote_requests=1000, remote_unit="Minute")

        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([BASE_ENVOY_ROUTE]), RATELIMIT_DOC,
            httproute_docs=[BASE_HTTPROUTE], btp_docs=[btp],
        )

        self.assertEqual(
            ["rate limit: teto por endereço de 'payment-status-get' diverge "
             "(compose=(600, 'minute'), k8s=(1000, 'minute'))"],
            errors,
        )

    def test_not_exposed_catch_all_is_not_part_of_the_allowlist(self) -> None:
        """A rota "not_exposed" (direct_response, sem cluster upstream) do
        envoy.yaml é o default-deny, não uma entrada real da allowlist - não deve
        gerar uma exigência de HTTPRoute equivalente."""
        not_exposed = {
            "name": "not_exposed",
            "match": {"prefix": "/"},
            "direct_response": {"status": 404, "body": {"inline_string": "{}"}},
        }

        errors = check_k8s_parity.compute_parity_errors(
            envoy_doc_with_routes([BASE_ENVOY_ROUTE, not_exposed]), RATELIMIT_DOC,
            httproute_docs=[BASE_HTTPROUTE], btp_docs=[BASE_BTP],
        )

        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
