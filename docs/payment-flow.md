# Fluxo de pagamento ponta a ponta

Como uma simulação atravessa as fronteiras, hop a hop, e quando cada resposta HTTP acontece. Esta é a visão cross-boundary: cada fronteira documenta seu próprio funcionamento interno em seu `docs/architecture.md`.

Opcionalmente existe um salto **antes** do primeiro hop abaixo: com a fronteira [`gateway`](../gateway/README.md) de pé, o cliente chega via Envoy (JWT do Keycloak validado e descartado, rate limit global, circuit breaking) e só então a requisição alcança o `payment-api` exatamente como descrito aqui. Sem o gateway, o cliente chama o `payment-api` direto — o fluxo abaixo é idêntico nos dois casos, e é por isso que o gateway não aparece na sequência.

## Sequência completa

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant F as RateLimit Filter
    participant A as payment-api
    participant R as Redis
    participant K as Kafka
    participant S as payment-sbus
    participant DB as PostgreSQL
    participant Core as payment-core-mock

    C->>F: POST /payment-simulations
    alt excede taxa (tenant/rota)
        F-->>C: 429 + Retry-After
    end
    F->>A: segue
    A->>A: resolve tenant efetivo (X-Tenant-Id x binding da API key)
    alt tenant não autorizado para a credencial
        A-->>C: 403
    end
    A->>A: valida payload e Idempotency-Key obrigatória (400 se inválido)
    A->>R: reserva idempotência (idem:{tenant}:{key})
    alt mesma chave, payload diferente, mesmo tenant
        A-->>C: 409
    end
    alt mesma chave, mesmo payload, mesmo tenant
        A-->>C: replay do requestId original
    end
    A->>R: status=PENDING
    A->>A: registra waiter (CompletableFuture)
    A->>K: PaymentSimulationRequested (key=requestId)
    A->>R: status=SENT_TO_SBUS
    A->>A: read-after-register (pega resultado já pronto)
    A->>A: future.get(timeout) em virtual thread

    K->>S: consome requested (Avro)
    S->>DB: TX: payment_sbus_message(PROCESSING) + outbox_event(ProcessCommand)
    Note over S,DB: dual-write resolvido (mesmo commit)
    S-->>S: OutboxDispatcher: claim (IN_PROGRESS) e publica fora da TX
    S->>K: ProcessPaymentSimulationCommand (rate-limited para o Core)
    K->>Core: consome comando
    Core->>Core: calcula taxas/autorização (ou decline)
    Core->>K: CorePaymentSimulationResponse
    K->>S: consome resposta
    S->>DB: TX: estado final (COMPLETED/FAILED) + result + outbox_event(final)
    S-->>S: OutboxDispatcher publica
    S->>K: PaymentSimulationCompleted/Failed

    K->>A: consome evento final
    A->>R: status final + result; PUBLISH canal pub/sub
    A->>A: completa o waiter

    alt resultado chegou no prazo
        A-->>C: 200 (APPROVED) ou 422 (FAILED)
    else estourou o timeout
        A-->>C: 202 Accepted + statusUrl
    end

    C->>A: GET /payment-simulations/{requestId}
    A->>R: lê status/result
    alt ausente ou não-terminal no Redis
        A->>S: fallback durável (endpoint interno)
        S->>DB: lê payment_sbus_message
    end
    A-->>C: status atual (+ result se houver)
```

## O que cada hop faz

| Passo | Fronteira | O que acontece |
|---|---|---|
| Admissão | `payment-api` | Rate limit por tenant e rota; excedente vira `429` |
| Tenant | `payment-api` | Resolve o tenant efetivo (`X-Tenant-Id` x binding da API key); não autorizado vira `403` |
| Validação | `payment-api` | Bean Validation + `Idempotency-Key` obrigatória; inválido vira `400` (problem+json) |
| Idempotência | `payment-api` | `SET NX` no Redis, chave escopada por tenant (`idem:{tenant}:{key}`); mesmo payload replica o `requestId` original, payload diferente vira `409` |
| Registro do waiter | `payment-api` | Cria o waiter antes de publicar |
| Publicação | `payment-api` | `PaymentSimulationRequested` (Avro), key=`requestId` |
| Read-after-register | `payment-api` | Cobre resposta ultrarrápida/replay |
| Espera | `payment-api` | Bloqueia barato (virtual thread) até o timeout configurado |
| Persistência e outbox | `payment-sbus` | TX única: estado mais comando na outbox |
| Entrega ao Core | `payment-sbus` | Publica fora da TX, com rate limit sobre o `core.command` |
| Core | `payment-core-mock` | Simula autorização/taxas; responde por evento |
| Estado final | `payment-sbus` | TX: COMPLETED/FAILED + `result` + outbox final |
| Correlação de volta | `payment-api` | Grava no Redis e acorda o waiter (local e pub/sub) |
| Consulta | `payment-api` | Redis; fallback no `payment-sbus` se necessário |

Contrato de eventos (nomes, tópicos, payloads) está em [payment-contracts/docs/contracts.md](../payment-contracts/docs/contracts.md). Detalhe interno de cada hop está no `architecture.md` da fronteira correspondente.

## Matriz de respostas HTTP

| Status | Quando |
|---|---|
| **200 OK** | Resultado chegou no prazo e é `COMPLETED` (aprovado) |
| **202 Accepted** | Timeout da espera; processamento segue assíncrono. Corpo traz `requestId` e `statusUrl` |
| **400 Bad Request** | Payload inválido, `Idempotency-Key` ausente/fora do padrão, ou `X-Tenant-Id` obrigatório e ausente. Corpo `application/problem+json` |
| **403 Forbidden** | `X-Tenant-Id` declarado fora do binding da API key |
| **409 Conflict** | Mesma `(tenant, Idempotency-Key)` com payload diferente, dentro da janela de idempotência |
| **422 Unprocessable Entity** | Resultado chegou no prazo e é `FAILED` (por exemplo, recusado pelo Core) |
| **429 Too Many Requests** | Rate limit de admissão excedido (por tenant e rota). Header `Retry-After` |
| **503 Service Unavailable** | Falha ao publicar no Kafka, ou (só no `GET`) nem Redis nem o fallback durável do `payment-sbus` responderam |
| **404 Not Found** | `GET` de `requestId` desconhecido - Redis e `payment-sbus` responderam e nenhum o conhece |

## Ver também
- [Arquitetura do workspace](workspace-architecture.md) · [Contratos de dados](data-ownership.md) · [Contratos entre fronteiras](resilience-contracts.md)
- [payment-api/docs/architecture.md](../payment-api/docs/architecture.md) · [payment-sbus/docs/architecture.md](../payment-sbus/docs/architecture.md)
