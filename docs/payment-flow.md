# Fluxo de pagamento ponta a ponta

Como uma simulação atravessa as fronteiras, hop a hop, e quando cada resposta HTTP acontece. Esta é a visão cross-boundary: cada fronteira documenta seu próprio funcionamento interno em seu `docs/architecture.md`.

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
    alt excede taxa
        F-->>C: 429 + Retry-After
    end
    F->>A: segue
    A->>A: valida payload (400 se inválido)
    A->>R: reserva idempotência (idem:{key})
    alt chave já usada
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
| Admissão | `payment-api` | Rate limit por taxa; excedente vira `429` |
| Validação | `payment-api` | Bean Validation; inválido vira `400` (problem+json) |
| Idempotência | `payment-api` | `SET NX` no Redis; duplicata replica o `requestId` original |
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
| **400 Bad Request** | Payload inválido (Bean Validation). Corpo `application/problem+json` |
| **422 Unprocessable Entity** | Resultado chegou no prazo e é `FAILED` (por exemplo, recusado pelo Core) |
| **429 Too Many Requests** | Rate limit de admissão excedido. Header `Retry-After` |
| **503 Service Unavailable** | Falha ao publicar no Kafka |
| **404 Not Found** | `GET` de `requestId` desconhecido (nem Redis nem `payment-sbus`) |

## Ver também
- [Arquitetura do workspace](workspace-architecture.md) · [Contratos de dados](data-ownership.md) · [Contratos entre fronteiras](resilience-contracts.md)
- [payment-api/docs/architecture.md](../payment-api/docs/architecture.md) · [payment-sbus/docs/architecture.md](../payment-sbus/docs/architecture.md)
