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
| `503` | reserva de idempotência persistida, mas o `XADD` falhou (Redis indisponível no meio do accept) | `{jobId, status: "UNAVAILABLE", statusUrl}`, header `Retry-After: 1` |

`Idempotency-Key` repetida com o mesmo payload não enfileira um segundo job: retorna o status atual do job original. Uma repetição que cai num `jobId` em `ENQUEUE_FAILED` (ver `503` acima) tenta o `XADD` de novo em vez de devolver o `Replay` congelado — a transição é uma CAS de único `EVAL` (AUD-03): entre duas repetições concorrentes da mesma chave, só uma vence e reenfileira; a outra nunca enfileira um segundo job.

## `GET /jobs/{jobId}`

| Status | Significado |
| --- | --- |
| `200` | `COMPLETED`, com `result` |
| `200` | `FAILED` — o worker desistiu do job (poison ou payload malformado) e o moveu para a DLQ. Terminal: nenhuma nova tentativa contra o mesmo `jobId` terá sucesso (AUD-13) |
| `202` | `PROCESSING` |
| `404` | `UNKNOWN` — job nunca aceito, ou status expirado |
| `410` | `EXPIRED` — terminou, mas o resultado já saiu do TTL |
| `503` | `UNAVAILABLE` — reserva existe, mas o `XADD` nunca aconteceu; repita o `POST` original com a mesma `Idempotency-Key` |

`FAILED` é um estado terminal observável (AUD-13): antes desse fix, um job dead-lettered ficava com aparência de `202 PROCESSING` até o `status-ttl` expirar e então virava `404 UNKNOWN`, como se nunca tivesse existido. Hoje `GET /jobs/{jobId}` responde `200` com `status: "FAILED"` assim que o worker grava a DLQ, sem esperar o TTL. `result` vem `null` nesse caso — o corpo do job que falhou está na DLQ (`dlqReason`), não no resultado.

## `result`

`{jobId, reference, amountCents, feeCents, status: "PROCESSED", processedBy, processedAtEpochMs}`. `feeCents` é 2% de `amountCents`, calculado pelo worker.

## Health e métricas

`GET /health/liveness` e `/health/readiness` são anônimos; `/health/readiness` inclui o indicador `async-redis-workers` (RED-05). `GET /prometheus` expõe `async_stream_length`, `async_pending` e `async_process_latency` — ver [observabilidade](observability.md).
