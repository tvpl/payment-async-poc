# Arquitetura — gateway

## Posição na arquitetura do workspace

```
Gateway (esta fronteira, opcional)  >  Edge (payment-api)  >  Sbus (payment-sbus)  >  Serviços (core)
```

O gateway é a primeira camada de guardrail: tudo que ele faz, o Edge assumiria
como já feito num deploy real (autenticação de canal, corte de abuso, proteção
de conexão). Tudo que é semântico — idempotência, admissão calibrada ao Core,
espera síncrona, degradação para 202 — continua no Edge, que é onde há contexto
de negócio para decidir.

## Componentes

| Componente | Papel | Falha isolada |
| --- | --- | --- |
| Envoy | proxy de borda: JWT, rate limit, circuit breaking, retry, timeout, logs/métricas/traces | gateway fora = clientes usam o Edge direto (sandbox) |
| Keycloak | IdP local com realm `payments` importado no boot | tokens novos indisponíveis; tokens emitidos seguem válidos até expirar (JWKS em cache) |
| Rate Limit Service + Redis privado | contadores globais de janela por descritor | fail-open deliberado: tráfego passa e a admissão do Edge segue protegendo o Core |
| certs-init | gera CA/certificados do listener mTLS no primeiro boot | sem efeito após o volume populado |

## Decisões estruturais

1. **Envoy estático, sem control plane.** O pedido desta camada é guardrail
   simples, não plataforma. Um `envoy.yaml` estático e comentado é auditável e
   suficiente; Envoy Gateway/K8s Gateway API entra quando houver Kubernetes de
   verdade (ver ADR 0001).
2. **Allowlist de rotas.** O que não está listado não existe através do gateway.
   Superfícies de operador (`/admin`, `/auth`, `/prometheus`) são acessadas
   direto no Edge, nunca pela borda pública.
3. **JWT validado e descartado.** O Envoy valida issuer, audience, expiração e
   assinatura via JWKS e remove o header. O Edge não recebe o token do Keycloak
   — evita conflito com o JWT próprio do Edge (dev HS256 / prod JWKS) e mantém
   as duas identidades independentes: canal (gateway) e aplicação (X-API-Key).
4. **Retry assimétrico por método.** GET de status re-tenta 5xx/reset (é
   idempotente); POST só re-tenta falha de conexão. O gateway nunca reenvia um
   POST que pode ter chegado ao upstream — a idempotência é do Edge e o repo
   ainda permite requisição sem `Idempotency-Key` (ver análise em
   [architecture-review](../../docs/architecture-review-2026-08.md)).
5. **Timeout maior que o orçamento do Edge.** A rota de POST usa 6s contra os
   3s de `wait-timeout` do Edge: o 202 degradado deve vir sempre da aplicação,
   nunca um 504 do proxy — senão o cliente perde o `statusUrl`.
6. **Rate limit em duas camadas com papéis distintos.** Gateway: corta rajada
   anômala global (por IP e por rota, janelas largas). Edge: admissão fina por
   rota e por tenant, calibrada à capacidade do Core. Os limites do gateway são
   deliberadamente mais frouxos para nunca mascarar os 429 do Edge em teste.
7. **Circuit breaking + outlier detection no cluster do Edge.** Limites
   explícitos de conexões/requisições pendentes e ejeção por 5xx consecutivos.
   Com uma instância única, ejeção equivale a abrir o circuito por 30s.
8. **Observabilidade nas três pernas.** Access log JSON no stdout, métricas
   Prometheus no admin (9901), traces OTLP para o otel-collector do sandbox
   quando o profile observability está de pé (sem ele, o Envoy apenas loga
   falha de DNS e segue).

## O que esta camada NÃO faz (de propósito)

- **Autorização fina (RBAC/OPA).** O filtro ext_authz é o extension point
  natural; ver [security.md](security.md). Não foi incluído para manter o
  guardrail simples — hoje o realm tem roles (`payments-user`) mas o gateway
  não as inspeciona.
- **Transformação de payload, agregação, BFF.** Proxy puro.
- **TLS até o Keycloak / Kafka / Redis.** Sandbox local; a pendência de TLS
  interno continua registrada em
  [production-evidence](../../docs/production-evidence.md).
