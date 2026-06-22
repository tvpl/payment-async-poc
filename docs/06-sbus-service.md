# 06 — SBUS service (`sbus-service`)

Porta **8081**. É a camada que **garante publicação confiável** (Outbox), **persiste** o estado no
PostgreSQL e **protege o Core**. Mantém o Core como dependência externa agnóstica.

## Mapa de classes

| Classe | Arquivo | Papel |
|---|---|---|
| `PaymentRequestedConsumer` | `.../kafka/PaymentRequestedConsumer.java` | Consome `Requested` (thin: poison→DLQ, transitório→retry) |
| `CoreResponseConsumer` | `.../kafka/CoreResponseConsumer.java` | Consome resposta do Core (idem) |
| `SimulationMessageHandler` | `.../kafka/SimulationMessageHandler.java` | Decode+rota compartilhado (main e retry) |
| `RetryPublisher` / `RetryConsumer` | `.../kafka/Retry*.java` | Retry topics dedicados + DLQ |
| `PaymentSimulationService` | `.../service/PaymentSimulationService.java` | Orquestra: serializa **fora** da TX |
| `PaymentPersistenceService` | `.../service/PaymentPersistenceService.java` | TX: estado + outbox (escrita) |
| `OutboxClaimService` | `.../outbox/OutboxClaimService.java` | Transações curtas (claim/mark) |
| `OutboxDispatcher` | `.../outbox/OutboxDispatcher.java` | Publica fora da TX, com rate limit |
| `OutboxReaper` | `.../outbox/OutboxReaper.java` | Recupera linhas presas em IN_PROGRESS |
| `OutboxHousekeeping` | `.../outbox/OutboxHousekeeping.java` | Purga publicados antigos |
| `BackoffCalculator` | `.../outbox/BackoffCalculator.java` | Backoff exponencial (testável) |
| `KafkaPublisher` / `KafkaProducerFactory` | `.../kafka/*` | Producer de bytes (Avro) |
| `InternalStatusController` | `.../controller/InternalStatusController.java` | Status durável p/ a API |
| `CoreGateway` / `KafkaCoreGateway` | `.../gateway/*` | Abstração do Core |
| Entidades + repos | `.../domain/*`, `.../repository/*` | `payment_sbus_message`, `outbox_event`, `idempotency_record` |

## Consumers: zero perda silenciosa + retry topics

Os consumers principais são finos e usam `offsetStrategy = SYNC_PER_RECORD`:

- **Mensagem venenosa** (deserialização/validação) → **DLQ** (commit).
- **Falha transitória** → publicada num **retry topic dedicado** (`.retry`) com `x-retry-attempt` e
  `x-retry-not-before`; o original é commitado. Assim a partição principal **não fica bloqueada**.
- Se a publicação no retry/DLQ falhar (broker fora), **relançamos** e o `errorStrategy=RETRY_ON_ERROR`
  faz o offset **não avançar** — nada se perde.

O `RetryConsumer` (grupo próprio `payment-sbus-retry`) respeita o delay (espera limitada), re-despacha
pelo `SimulationMessageHandler` e, ao esgotar `sbus.retry.max-attempts`, manda para a **DLQ**.
Particionamento por `requestId` garante ordem por simulação.

```mermaid
flowchart LR
    main[Consumer principal] -->|sucesso| ok((commit))
    main -->|poison| dlq[(DLQ)]
    main -->|transitório| retry[(.retry topic)]
    retry --> rc[RetryConsumer]
    rc -->|sucesso| ok
    rc -->|attempt < max| retry
    rc -->|attempt >= max| dlq
```

## Serialização fora da transação

O `PaymentSimulationService` monta e **serializa** os eventos Avro (I/O do registry) **fora** de
qualquer transação; só depois chama o `PaymentPersistenceService`, cujos métodos `@Transactional`
fazem **apenas escrita** (estado + outbox no mesmo commit). Assim nunca seguramos uma conexão de banco
durante uma chamada de rede — evitando esgotar o pool sob carga.

## Outbox Pattern (coração do SBUS)

### Por que
Sem outbox, "gravar no banco" e "publicar no Kafka" seriam duas ações que podem falhar
independentemente (**dual-write**). A outbox grava o evento **na mesma transação** do estado; a
publicação acontece depois, de forma confiável.

### Fluxo
1. Consome `PaymentSimulationRequested`.
2. **TX**: grava/atualiza `payment_sbus_message` **e** insere `outbox_event` (comando ao Core).
3. Commit — banco + outbox no **mesmo** commit. O `payload` já é o **byte[] Avro** auto-descritivo.
4. `OutboxDispatcher` reivindica um lote (**claim/lease**) numa TX curta: `FOR UPDATE SKIP LOCKED`,
   marca `IN_PROGRESS` + `claimed_at`. Várias instâncias podem rodar em paralelo sem colidir.
5. **Publica no Kafka fora da transação** (sem segurar locks durante o I/O), replayando os headers
   técnicos (inclusive `traceparent`). Um **rate limiter distribuído (Redis)** no `core.command`
   protege o Core — limite **global** entre instâncias (ver [03](03-tecnologias.md)).
6. **TX curta**: o lote bem-sucedido é marcado `PUBLISHED` num **único UPDATE** (`markPublishedBatch`);
   falhas são tratadas individualmente (backoff em `next_attempt_at`).
7. Após `max-attempts` → **DLQ** + `FAILED`.

O mesmo mecanismo publica os eventos finais (`Completed/Failed`) de volta para a API.

### Estados da linha da outbox

```mermaid
stateDiagram-v2
    [*] --> PENDING: gravado na TX
    PENDING --> IN_PROGRESS: claim (SKIP LOCKED + lease)
    IN_PROGRESS --> PUBLISHED: enviado ao Kafka
    IN_PROGRESS --> PENDING: falha (backoff) / reaper (lease expirou)
    PENDING --> FAILED: max-attempts → DLQ
    PUBLISHED --> [*]: housekeeping (purga)
```

- **`OutboxReaper`** (`@Scheduled`): devolve para `PENDING` linhas `IN_PROGRESS` mais velhas que o
  *lease* (o publicador caiu no meio).
- **`OutboxHousekeeping`** (`@Scheduled`): apaga `PUBLISHED` mais antigos que a retenção — evita o
  crescimento indefinido da tabela.
- **`RetentionHousekeeping`** (`@Scheduled`): purga `idempotency_record` e `payment_sbus_message`
  **terminais** antigos (em lotes), mantendo as tabelas limitadas (`sbus.housekeeping.*`).

## Idempotência (3 camadas)

1. **Redis** (`idem:`) na API.
2. **`payment_sbus_message.request_id` UNIQUE** — redelivery do mesmo `requestId` é no-op.
3. **`idempotency_record`** — chave de idempotência ponta a ponta.
Além disso, `CoreResponseConsumer` ignora respostas para simulações já terminais.

## Core como dependência externa

`CoreGateway` (interface) documenta o limite. A implementação default
(`KafkaCoreGateway`) reflete que o Core é alcançado **via outbox + tópicos** `core.command`/
`core.response`. Trocar para um Core HTTP/gRPC real não muda o resto do SBUS. Ver [07](07-core-mock.md).

## Endpoint interno (fallback da API)
`GET /internal/payment-simulations/{requestId}` retorna status + `result` durável a partir de
`payment_sbus_message`. É o que a API consulta quando o Redis não tem o resultado.

## Migrations
`V1` message · `V2` outbox · `V3` idempotency · `V4` coluna `result` · `V5` `payload`→`bytea` + `claimed_at`
· `V6` índices de retenção.
Ver [`db/migration/`](../sbus-service/src/main/resources/db/migration) e [09](09-dados-redis-postgres.md).

## Ver também
- [04 Fluxo](04-fluxo-ponta-a-ponta.md) · [08 Eventos](08-eventos-e-contratos.md) · [11 Resiliência](11-resiliencia-e-tradeoffs.md)
