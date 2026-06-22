# 04 — Fluxo ponta a ponta

Como uma simulação atravessa o sistema, hop a hop, e quando cada resposta HTTP acontece.

## Sequência completa

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant F as RateLimit Filter
    participant A as API (controller)
    participant R as Redis
    participant K as Kafka
    participant S as SBUS
    participant DB as PostgreSQL
    participant Core as core-mock

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
    S-->>S: OutboxDispatcher: claim (IN_PROGRESS) → publica fora da TX
    S->>K: ProcessPaymentSimulationCommand (rate-limited p/ Core)
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
        A->>S: fallback durável (InternalStatusController)
        S->>DB: lê payment_sbus_message
    end
    A-->>C: status atual (+ result se houver)
```

## O que cada hop faz

| Passo | Onde | O que acontece |
|---|---|---|
| Admissão | `ConcurrencyLimitFilter` | Rate limit por taxa; excedente → `429` |
| Validação | `PaymentSimulationController` + `PaymentSimulationRequest` | Bean Validation; inválido → `400` (problem+json) |
| Idempotência | `RedisStatusStore.reserveIdempotency` | `SET NX`; duplicata replica o `requestId` original |
| Registro do waiter | `ResponseCoordinator.register` | Cria o `CompletableFuture` antes de publicar |
| Publicação | `PaymentRequestProducer` (Avro bytes) | `PaymentSimulationRequested`, key=`requestId` |
| Read-after-register | `ResponseCoordinator.completeFromStore` | Cobre resposta ultrarrápida/replay |
| Espera | `@ExecuteOn(BLOCKING)` + `future.get(timeout)` | Bloqueia barato (virtual thread) |
| Persistência + outbox | `PaymentSimulationService.handleRequested` | TX única: estado + comando na outbox |
| Entrega ao Core | `OutboxDispatcher` | Publica fora da TX, com rate limit no `core.command` |
| Core | `CoreSimulationConsumer` | Simula autorização/taxas; responde por evento |
| Estado final | `PaymentSimulationService.handleCoreResponse` | TX: COMPLETED/FAILED + `result` + outbox final |
| Correlação de volta | `PaymentResponseConsumer` | Grava no Redis e acorda o waiter (local + pub/sub) |
| Consulta | `PaymentSimulationController.get` | Redis; fallback no SBUS se necessário |

## Matriz de respostas HTTP

| Status | Quando |
|---|---|
| **200 OK** | Resultado chegou no prazo e é `COMPLETED` (APPROVED) |
| **202 Accepted** | Timeout da espera; processamento segue assíncrono. Corpo traz `requestId` + `statusUrl` |
| **400 Bad Request** | Payload inválido (Bean Validation). Corpo `application/problem+json` |
| **422 Unprocessable Entity** | Resultado chegou no prazo e é `FAILED` (ex.: recusado pelo Core) |
| **429 Too Many Requests** | Rate limit de admissão excedido. Header `Retry-After` |
| **503 Service Unavailable** | Falha ao publicar no Kafka (`PublishFailedException`) |
| **404 Not Found** | `GET` de `requestId` desconhecido (nem Redis nem SBUS) |

## Ver também
- [05 API](05-api-service.md) · [06 SBUS](06-sbus-service.md) · [09 Dados](09-dados-redis-postgres.md)
