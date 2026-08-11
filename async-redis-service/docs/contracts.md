# Contratos

Este serviço não consome nem publica GAVs de `payment-contracts` (`StandaloneBoundaryTest` garante isso). O único contrato é a própria API HTTP.

## `POST /jobs`

Headers: `Idempotency-Key` (opcional, obrigatório em produção — `idempotency-required`), `X-API-Key` (obrigatório quando `async.redis.security.enabled=true`, padrão).

Body: `{"reference": string, "amountCents": long >= 0, "note": string?}`.

Respostas:

| Status | Quando | Corpo |
| --- | --- | --- |
| `200` | resultado liberado dentro de `wait-timeout` | `{jobId, status: "COMPLETED", statusUrl, result}` |
| `202` | ainda em processamento, ou pool de espera saturado (`X-Backpressure: wait-pool-exhausted`, `Retry-After`) | `{jobId, status: "PROCESSING", statusUrl, result: null}` |
| `400` | `Idempotency-Key` ausente com `idempotency-required=true` | `{status: "IDEMPOTENCY_KEY_REQUIRED"}` |
| `401` | `X-API-Key` ausente ou desconhecida | — |
| `409` | mesma `Idempotency-Key` com payload diferente | `{jobId (original), status: "CONFLICT", statusUrl}` |
| `429` | admissão excedida (`admission-limit-per-sec`) | `Retry-After: 1` |

`Idempotency-Key` repetida com o mesmo payload não enfileira um segundo job: retorna o status atual do job original.

## `GET /jobs/{jobId}`

| Status | Significado |
| --- | --- |
| `200` | `COMPLETED`, com `result` |
| `202` | `PROCESSING` |
| `404` | `UNKNOWN` — job nunca aceito |
| `410` | `EXPIRED` — terminou, mas o resultado já saiu do TTL |

## `result`

`{jobId, reference, amountCents, feeCents, status: "PROCESSED", processedBy, processedAtEpochMs}`. `feeCents` é 2% de `amountCents`, calculado pelo worker.

## Health e métricas

`GET /health/liveness` e `/health/readiness` são anônimos; `/health/readiness` inclui o indicador `async-redis-workers` (RED-05). `GET /prometheus` expõe `async_stream_length`, `async_pending` e `async_process_latency` — ver [observabilidade](observability.md).
