#!/usr/bin/env python3
"""Paridade semântica entre o compose (envoy.yaml/ratelimit/config.yaml) e os
manifests Gateway API (gateway/k8s/base/): allowlist (método+prefixo), timeouts
por rota e limites de rate limit. Mesmo padrão de gateway/scripts/validate-config.py
(parse direto com pyyaml, sem depender de `kustomize build`/kubeconform).

K8S-03: um manifest sem a rota equivalente, com timeout diferente, ou com limite
de rate limit divergente do compose derruba o gate.
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import yaml

ROOT = Path(__file__).resolve().parents[1]


class Route:
    def __init__(self, name: str, method: Optional[str], prefix: str,
                 timeout: Optional[str], rate_limit_key: Optional[str]):
        self.name = name
        self.method = method
        self.prefix = prefix
        self.timeout = timeout
        self.rate_limit_key = rate_limit_key

    @property
    def identity(self) -> tuple:
        return (self.method, self.prefix)


def envoy_routes(envoy_doc: dict) -> list[Route]:
    """Rotas expostas do listener edge_http (exclui o catch-all direct_response)."""
    routes: list[Route] = []
    for listener in envoy_doc["static_resources"]["listeners"]:
        if listener["name"] != "edge_http":
            continue
        hcm = listener["filter_chains"][0]["filters"][0]["typed_config"]
        for vhost in hcm["route_config"]["virtual_hosts"]:
            for route in vhost["routes"]:
                if "route" not in route:
                    continue  # catch-all direct_response ("not_exposed"), não é allowlist
                method = None
                for header in route["match"].get("headers", []):
                    if header.get("name") == ":method":
                        method = header["string_match"]["exact"]
                rate_limit_key = None
                for limit in route.get("rate_limits", []):
                    for action in limit.get("actions", []):
                        generic = action.get("generic_key")
                        if generic:
                            rate_limit_key = generic["descriptor_value"]
                routes.append(Route(
                    name=route["name"],
                    method=method,
                    prefix=route["match"]["prefix"],
                    timeout=route["route"].get("timeout"),
                    rate_limit_key=rate_limit_key,
                ))
        return routes
    return routes


def envoy_rate_limits(ratelimit_doc: dict) -> dict[str, tuple[int, str]]:
    """generic_key value -> (requests_per_unit, unit em minúsculas)."""
    limits: dict[str, tuple[int, str]] = {}
    for descriptor in ratelimit_doc.get("descriptors", []):
        if descriptor.get("key") == "generic_key":
            rl = descriptor["rate_limit"]
            limits[descriptor["value"]] = (rl["requests_per_unit"], rl["unit"].lower())
    return limits


def k8s_routes(httproute_docs: list[dict]) -> list[Route]:
    routes: list[Route] = []
    for doc in httproute_docs:
        if doc.get("kind") != "HTTPRoute":
            continue
        rule = doc["spec"]["rules"][0]
        match = rule["matches"][0]
        routes.append(Route(
            name=doc["metadata"]["name"],
            method=match.get("method"),
            prefix=match["path"]["value"],
            timeout=rule.get("timeouts", {}).get("request"),
            rate_limit_key=None,  # limites vêm da BackendTrafficPolicy, não da HTTPRoute
        ))
    return routes


def k8s_route_budgets(btp_docs: list[dict]) -> dict[str, tuple[int, str]]:
    """Nome da HTTPRoute alvo -> (requests, unit em minúsculas) da regra global
    sem clientSelectors (o orçamento por rota, não o teto por endereço)."""
    budgets: dict[str, tuple[int, str]] = {}
    for doc in btp_docs:
        if doc.get("kind") != "BackendTrafficPolicy":
            continue
        rate_limit = doc["spec"].get("rateLimit")
        if not rate_limit:
            continue
        target = doc["spec"]["targetRefs"][0]["name"]
        for rule in rate_limit["global"]["rules"]:
            if "clientSelectors" not in rule:
                limit = rule["limit"]
                budgets[target] = (limit["requests"], limit["unit"].lower())
    return budgets


def k8s_client_caps(btp_docs: list[dict]) -> dict[str, tuple[int, str]]:
    """Nome da HTTPRoute alvo -> (requests, unit) da regra por endereço (Distinct
    sourceCIDR) — equivalente ao descritor remote_address do compose."""
    caps: dict[str, tuple[int, str]] = {}
    for doc in btp_docs:
        if doc.get("kind") != "BackendTrafficPolicy":
            continue
        rate_limit = doc["spec"].get("rateLimit")
        if not rate_limit:
            continue
        target = doc["spec"]["targetRefs"][0]["name"]
        for rule in rate_limit["global"]["rules"]:
            if "clientSelectors" in rule:
                limit = rule["limit"]
                caps[target] = (limit["requests"], limit["unit"].lower())
    return caps


def compute_parity_errors(envoy_doc: dict, ratelimit_doc: dict,
                           httproute_docs: list[dict], btp_docs: list[dict]) -> list[str]:
    errors: list[str] = []

    envoy_rts = envoy_routes(envoy_doc)
    k8s_rts = k8s_routes(httproute_docs)
    envoy_by_identity = {r.identity: r for r in envoy_rts}
    k8s_by_identity = {r.identity: r for r in k8s_rts}

    missing_in_k8s = set(envoy_by_identity) - set(k8s_by_identity)
    missing_in_envoy = set(k8s_by_identity) - set(envoy_by_identity)
    for identity in sorted(missing_in_k8s, key=str):
        errors.append(f"allowlist: rota do compose sem HTTPRoute equivalente: {identity}")
    for identity in sorted(missing_in_envoy, key=str):
        errors.append(f"allowlist: HTTPRoute sem rota equivalente no compose: {identity}")

    for identity, envoy_route in envoy_by_identity.items():
        k8s_route = k8s_by_identity.get(identity)
        if k8s_route is None:
            continue
        if envoy_route.timeout != k8s_route.timeout:
            errors.append(
                f"timeout: {identity} diverge (compose={envoy_route.timeout!r}, "
                f"k8s={k8s_route.timeout!r})"
            )

    envoy_limits = envoy_rate_limits(ratelimit_doc)
    route_budgets = k8s_route_budgets(btp_docs)
    client_caps = k8s_client_caps(btp_docs)

    for identity, envoy_route in envoy_by_identity.items():
        k8s_route = k8s_by_identity.get(identity)
        if k8s_route is None:
            continue
        if envoy_route.rate_limit_key is None:
            continue
        expected = envoy_limits.get(envoy_route.rate_limit_key)
        if expected is None:
            errors.append(
                f"rate limit: compose referencia '{envoy_route.rate_limit_key}' sem "
                f"descritor em ratelimit/config.yaml"
            )
            continue
        actual = route_budgets.get(k8s_route.name)
        if actual != expected:
            errors.append(
                f"rate limit: orçamento de '{k8s_route.name}' diverge "
                f"(compose={expected}, k8s={actual})"
            )
        # remote_address (600/min) é global: toda rota com rate limit no compose
        # carrega o mesmo teto por endereço no descritor remote_address.
        remote_address = ratelimit_doc.get("descriptors", [])
        remote_cap = next(
            ((d["rate_limit"]["requests_per_unit"], d["rate_limit"]["unit"].lower())
             for d in remote_address if d.get("key") == "remote_address"),
            None,
        )
        if remote_cap is not None:
            actual_cap = client_caps.get(k8s_route.name)
            if actual_cap != remote_cap:
                errors.append(
                    f"rate limit: teto por endereço de '{k8s_route.name}' diverge "
                    f"(compose={remote_cap}, k8s={actual_cap})"
                )

    return errors


def _load_yaml(path: Path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _load_yaml_all(path: Path) -> list[dict]:
    return [doc for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")) if doc]


def main() -> int:
    envoy_doc = _load_yaml(ROOT / "envoy/envoy.yaml")
    ratelimit_doc = _load_yaml(ROOT / "ratelimit/config.yaml")
    httproute_docs = _load_yaml_all(ROOT / "k8s/base/httproutes.yaml")
    btp_docs = _load_yaml_all(ROOT / "k8s/base/backendtrafficpolicy.yaml")

    errors = compute_parity_errors(envoy_doc, ratelimit_doc, httproute_docs, btp_docs)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("gateway k8s parity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
