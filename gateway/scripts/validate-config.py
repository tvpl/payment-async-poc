#!/usr/bin/env python3
"""Validação estrutural dos arquivos de configuração do gateway (sem subir nada).

- realm-payments.json precisa ser JSON válido com realm/clients esperados;
- envoy.yaml e ratelimit/config.yaml precisam ser YAML válido (se pyyaml existir)
  e os descritores de rate limit precisam casar com as actions das rotas.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

realm = json.loads((ROOT / "keycloak/realm-payments.json").read_text(encoding="utf-8"))
if realm.get("realm") != "payments":
    errors.append("realm-payments.json: realm deve ser 'payments'")
client_ids = {client["clientId"] for client in realm.get("clients", [])}
if "payments-cli" not in client_ids:
    errors.append("realm-payments.json: client payments-cli ausente")

try:
    import yaml  # type: ignore
except ImportError:
    print("aviso: pyyaml ausente, validação YAML pulada")
    yaml = None

if yaml is not None:
    envoy = yaml.safe_load((ROOT / "envoy/envoy.yaml").read_text(encoding="utf-8"))
    ratelimit = yaml.safe_load((ROOT / "ratelimit/config.yaml").read_text(encoding="utf-8"))

    if ratelimit.get("domain") != "payment-gateway":
        errors.append("ratelimit/config.yaml: domain deve ser payment-gateway")

    configured_generic_keys = {
        descriptor.get("value")
        for descriptor in ratelimit.get("descriptors", [])
        if descriptor.get("key") == "generic_key"
    }

    route_generic_keys: set[str] = set()
    text = json.dumps(envoy)
    for listener in envoy["static_resources"]["listeners"]:
        for chain in listener["filter_chains"]:
            for network_filter in chain["filters"]:
                route_config = network_filter["typed_config"].get("route_config", {})
                for vhost in route_config.get("virtual_hosts", []):
                    for route in vhost.get("routes", []):
                        for limit in route.get("rate_limits", []):
                            for action in limit.get("actions", []):
                                generic = action.get("generic_key")
                                if generic:
                                    route_generic_keys.add(generic["descriptor_value"])

    missing = route_generic_keys - configured_generic_keys
    if missing:
        errors.append(f"descritores generic_key sem limite configurado: {sorted(missing)}")
    if "payment-gateway" not in text:
        errors.append("envoy.yaml: domínio de rate limit não referenciado")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    sys.exit(1)
print("gateway config: OK")
