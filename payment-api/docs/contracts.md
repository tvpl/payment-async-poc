# Contratos

Todos os tipos vêm dos artefatos publicados de `payment-contracts`. Esta fronteira consome; não
edita esquema, tópico nem enum de contrato.

## Tópicos Kafka

| Tópico | Direção | Chave | Conteúdo |
| --- | --- | --- | --- |
| `payment.simulation.requested` | produz | `requestId` | `PaymentSimulationRequested` (Avro) |
| `payment.simulation.completed` | consome | `requestId` | `PaymentSimulationCompleted` (Avro) |
| `payment.simulation.failed` | consome | `requestId` | `PaymentSimulationFailed` (Avro) |
| `payment.simulation.dlq` | produz | chave original | bytes originais do evento não aplicável |

A chave é sempre o `requestId`, o que preserva ordem por simulação dentro da partição.

## Headers

| Header | Uso |
| --- | --- |
| `x-request-id` | identidade da simulação |
| `x-correlation-id` | correlação da tentativa |
| `Idempotency-Key` | chave de deduplicação fornecida pelo cliente |
| `traceparent` | contexto W3C, injetado pela instrumentação OpenTelemetry do Kafka |
| `x-dlq-origin-topic` | tópico de onde o registro veio |
| `x-dlq-stage` | `decode` ou `apply` |
| `x-dlq-reason` | classe e mensagem da falha |

## Codec

`AvroSerde` é um pool limitado de serializadores Apicurio, com timeout de aquisição. Exaustão do
pool levanta `AvroCodecUnavailableException`, que é tratada como **falta de capacidade** (registro
reentregue), nunca como mensagem envenenada.

## HTTP

| Rota | Método | Respostas |
| --- | --- | --- |
| `/payment-simulations` | POST | `200` resultado, `202` ainda processando, `409` conflito de idempotência, `429` admissão, `503` falha de publicação |
| `/payment-simulations/{requestId}` | GET | `200` status, `404` desconhecido |
| `/health/liveness`, `/health/readiness` | GET | `200` anônimo |
| `/prometheus` | GET | `200` autenticado |

Erros usam `application/problem+json`.
