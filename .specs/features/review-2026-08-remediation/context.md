# Review 2026-08 Remediation Context

**Gathered:** 2026-08-16
**Spec:** `.specs/features/review-2026-08-remediation/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Resolver todos os achados da revisão de arquitetura 2026-08
(`docs/architecture-review-2026-08.md`) — 2 críticos, 10 altos, 14 médios e os
baixos — nas fronteiras `payment-api`, `payment-sbus`, `payment-contracts` e
`sandbox`, mais a adequação da fronteira `gateway` ao cenário Kubernetes
(Envoy Gateway real via Gateway API) mantendo o compose como caminho sandbox.
O gateway continua opcional; o fluxo Edge → Sbus → serviços continua testável
sem ele.

---

## Implementation Decisions

### Idempotency-Key (crítico #2)

- Obrigatória no `POST /payment-simulations` e `POST /v0/payment-simulations`:
  ausência → `400 application/problem+json`.
- Breaking change aceito conscientemente: padrão de mercado em APIs de
  pagamento; o POC só tem clientes de teste — é a hora barata de quebrar.

### Identidade de tenant (crítico #1)

- Tenant declarado via header `X-Tenant-Id`, **validado contra um binding
  api-key → tenants permitidos** mantido pelo Edge (configuração).
- Header presente e fora do binding → `403`. Header ausente → tenant único do
  binding da credencial (se o binding tiver mais de um tenant, header é
  obrigatório → `400`).
- A âncora de confiança é sempre a credencial autenticada — o header apenas
  seleciona entre tenants autorizados. Funciona idêntico com e sem gateway
  (decisão de confiança tomada após alerta de forjabilidade).
- O gateway (quando presente) injeta `X-Tenant-Id` a partir de claim do JWT
  por conveniência (`claim_to_headers` do jwt_authn); o Edge revalida sempre.

### Kubernetes (adequação do gateway)

- Manifests **Gateway API do Envoy Gateway** (Gateway, HTTPRoute,
  SecurityPolicy, BackendTrafficPolicy, EnvoyProxy/telemetria) em
  `gateway/k8s/`, com kustomize: base + overlays (`sandbox` e
  `prod-example`).
- Validação no CI com kubeconform (client-side, sem cluster) + verificação de
  paridade semântica com o `envoy.yaml` do compose (mesma allowlist de rotas
  e limites) por script.
- Compose continua sendo o caminho oficial do sandbox local; K8s é o caminho
  quando houver cluster.

### Escopo

- **Todos** os achados: críticos, altos, médios e baixos. Nenhum vira backlog.

### Agent's Discretion

- Valor exato da janela unificada de idempotência (default proposto: 24h no
  Edge, retenção durável do Sbus permanece 7d), estratégia concreta de
  versionamento (ADR), número default de shards do canal de correlação,
  detalhes de particionamento/concorrência de consumers, formato do binding
  api-key→tenant na configuração.

### Declined / Undiscussed Gray Areas → Assumptions

- Nenhuma área foi recusada; as ambiguidades restantes estão na tabela de
  Assumptions da spec com default + racional.

---

## Specific References

- Achados numerados e recomendações: `docs/architecture-review-2026-08.md`.
- Alvo de capacidade vigente: AD-007 em `.specs/STATE.md` (1.000 req/min
  sustentado, spike 2.000/min, avg ≤ 300ms, p99 ≤ 10s) — as mudanças não podem
  regredir esse gate.
- Padrão de referência citado pelo usuário: exigência de chave de idempotência
  como em APIs de pagamento de mercado (Stripe/PSPs).

---

## Deferred Ideas

- OPA/ext_authz com políticas reais no gateway (extension point permanece
  documentado).
- Criptografia de envelope real por campo (esta feature entrega política +
  teste de guarda, não a criptografia).
- TLS interno (gateway→Edge, Kafka SASL/SSL, Redis/Postgres TLS) — pendência
  já registrada em `docs/production-evidence.md`.
- Divisor de degradação do rate limiter ciente de autoscaling (hoje
  `PAYMENT_API_INSTANCES` manual).
