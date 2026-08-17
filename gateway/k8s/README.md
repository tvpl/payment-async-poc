# Gateway API — manifests Kubernetes

Equivalente em CRDs do Envoy Gateway ao guardrail estático de
[`gateway/envoy/envoy.yaml`](../envoy/envoy.yaml): mesma allowlist de rotas, mesmo
JWT do Keycloak, mesmas políticas de tráfego (rate limit, circuit breaking, retry,
timeout) e mesma telemetria. Ver [`gateway/docs/architecture.md`](../docs/architecture.md)
para quando usar isto em vez do compose.

## Layout

```
base/                        # Gateway, HTTPRoutes, SecurityPolicy, BackendTrafficPolicy, EnvoyProxy
overlays/sandbox/            # cluster local (kind)
overlays/prod-example/       # exemplo ilustrativo — nunca aplique como está
```

## Pré-requisitos (qualquer overlay)

1. Controller do [Envoy Gateway](https://gateway.envoyproxy.io/) instalado no cluster
   (o Helm chart cria a `GatewayClass` "eg", referenciada por `base/gateway.yaml`).
2. Um Service `payment-api` na porta 8080, no mesmo namespace do overlay —
   equivalente ao alias `api:8080` do compose. Estes manifests não o criam.
3. Um Keycloak com o mesmo realm de [`gateway/keycloak/realm-payments.json`](../keycloak/realm-payments.json)
   (incluindo o claim `tenant_id`, ver T46/TEN-07), alcançável pela URL configurada
   em `SecurityPolicy.spec.jwt.providers[0]`.

## Renderizar

```bash
kustomize build gateway/k8s/overlays/sandbox
kustomize build gateway/k8s/overlays/prod-example
```

## Diferenças entre overlays

| Aspecto | `sandbox` | `prod-example` |
| --- | --- | --- |
| Namespace | `payment-gateway-sandbox` | `payment-gateway` |
| Listener | só HTTP (porta 80), igual ao base | soma um listener HTTPS (porta 443, `tls.mode: Terminate`) via `certificateRefs` para um Secret `payment-gateway-tls` — **placeholder**: o Secret não é criado aqui, normalmente vem de cert-manager |
| Exposição do Envoy | `EnvoyProxy.provider.kubernetes.envoyService.type: NodePort` — kind não tem load balancer de nuvem; mapeie a NodePort para o host via `extraPortMappings` na config do kind | `type: LoadBalancer` (default do CRD) + anotação ilustrativa de provider de nuvem |
| Réplicas | default do CRD (1) | `envoyDeployment.replicas: 3` |
| Issuer/JWKS do JWT | `http://keycloak.payment-gateway-sandbox.svc.cluster.local/realms/payments` — Keycloak no mesmo cluster/namespace | `https://keycloak.example.com/realms/payments` — **placeholder**, troque pelo domínio real do IdP |

`prod-example` é ilustrativo por definição: os placeholders (`example.com`, o nome
do Secret TLS) precisam ser substituídos por valores reais do ambiente antes de
qualquer aplicação fora de um teste descartável.

## Validação

- `kustomize build` sem erro (ambos os overlays, gate estrutural).
- `kubeconform` contra os schemas vendorizados em [`gateway/k8s/schemas/`](schemas/)
  (T44) — roda em `make config`.
- Paridade semântica com o compose: [`gateway/scripts/check-k8s-parity.py`](../scripts/check-k8s-parity.py) (T45).

## Limite conhecido

O listener mTLS do compose (`edge_mtls`, porta 10443, certificado de cliente
obrigatório) não tem equivalente aqui — o mapeamento K8S-01/02 cobre allowlist,
JWT e políticas de tráfego, não mTLS de borda. Ver
[`gateway/docs/architecture.md`](../docs/architecture.md).
