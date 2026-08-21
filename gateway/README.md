# gateway

Camada opcional de guardrail na frente do `payment-api` (Edge), usando Envoy como
proxy de borda e Keycloak como IdP local. `NON_PRODUCTION`: existe para permitir
testes End-to-End com autenticação OIDC/JWT, mTLS, rate limit global e circuit
breaking — sem alterar nenhuma linha das aplicações.

```
cliente
  │  Bearer JWT (Keycloak) + X-API-Key
  ▼
Envoy (10000 HTTP / 10443 mTLS)
  ├── JWT ────────── Keycloak (realm payments, JWKS)
  ├── Rate limit ─── Rate Limit Service + Redis privado
  ├── Circuit breaker / outlier detection / retry / timeout
  └── Access log JSON, métricas no admin 9901, traces OTLP
  ▼
payment-api (Edge) ── Kafka ── payment-sbus ── payment-core-mock
```

O fluxo Edge → Sbus → serviços **não depende** desta camada: para testes rápidos,
simplesmente não a suba e chame o Edge direto em `localhost:8080`, como sempre.

Este README cobre o caminho **compose** (desenvolvimento local). Para o
equivalente em **Kubernetes** (Gateway API/Envoy Gateway, com paridade
semântica verificada em gate), ver [`k8s/README.md`](k8s/README.md).

## Subir

Pré-requisito: sandbox e aplicações no ar (ver [ordem completa](docs/operations.md)).

```bash
cp .env.example .env
# defina KEYCLOAK_ADMIN_PASSWORD no .env
make up
```

## Testar

```bash
export PAYMENT_API_KEY=<a mesma chave do payment-api/.env>
make smoke
```

O smoke obtém um token no Keycloak (`alice` / `alice-change-me`), prova que sem
token o gateway devolve 401, que rotas não expostas devolvem 404, e percorre o
fluxo completo POST + polling até desfecho terminal.

## Mapa

| Caminho | O que vive aqui |
| --- | --- |
| `compose.yaml` | Envoy, Keycloak, Rate Limit Service, Redis privado e gerador de certificados |
| `envoy/envoy.yaml` | listeners, filtros JWT/rate limit, rotas, circuit breaking |
| `keycloak/realm-payments.json` | realm importado no boot: clients, usuários e audience |
| `ratelimit/config.yaml` | descritores e limites do Rate Limit Service |
| `certs/generate-certs.sh` | CA + certificados de servidor/cliente para o listener mTLS |
| `scripts/smoke.sh` | prova E2E através do gateway |
| `scripts/validate-config.py` | validação estrutural usada por `make config` e pelo CI |
| `scripts/check-k8s-parity.py` | paridade semântica entre este compose e os manifests K8s |
| `k8s/` | equivalente em CRDs do Envoy Gateway para Kubernetes - ver [k8s/README.md](k8s/README.md) |
| `docs/` | arquitetura, configuração, segurança, operação e testes |

## Documentação

- [Arquitetura](docs/architecture.md) — o que a camada faz, o que não faz, e por quê
- [Configuração](docs/configuration.md) — variáveis, portas e pontos de ajuste
- [Segurança](docs/security.md) — JWT, mTLS, superfícies não expostas e limites do desenho
- [Operação](docs/operations.md) — ordem de subida, troubleshooting e observabilidade
- [Testes](docs/testing.md) — smoke, cenários manuais e validação estrutural
- [ADR 0001](docs/adr/0001-envoy-as-nonproduction-guardrail.md) — decisão e trade-offs
