# Contratos de evento

O wire Kafka usa Avro binário. HTTP e Redis usam JSON. [`manifest.json`](../schemas/manifest.json) é o mapa verificável entre `eventType`, versão, artifact do Registry, tópico, schema e classe gerada.

## Envelope

| Campo | Semântica |
| --- | --- |
| `eventId` | identidade única do evento |
| `eventType` | tipo canônico do manifest |
| `eventVersion` | versão lógica do evento |
| `occurredAt` | epoch millis no Avro |
| `requestId` | identidade da simulação e chave Kafka |
| `correlationId` | correlação ponta a ponta |
| `causationId` | evento que causou o atual |
| `traceId` | correlação de tracing |
| `source` | serviço produtor lógico |
| `payload` | payload Avro específico do evento |

## Eventos e tópicos

| eventType | Tópico | Payload |
| --- | --- | --- |
| `PaymentSimulationRequested` | `payment.simulation.requested` | `PaymentRequest` |
| `ProcessPaymentSimulationCommand` | `payment.simulation.core.command` | `ProcessPayload` |
| `CorePaymentSimulationResponse` | `payment.simulation.core.response` | `CoreResponsePayload` |
| `PaymentSimulationCompleted` | `payment.simulation.completed` | `SimulationResultPayload` |
| `PaymentSimulationFailed` | `payment.simulation.failed` | `SimulationResultPayload` |

Retry usa `payment.simulation.requested.retry` e `payment.simulation.core.response.retry`. Poison messages terminam em `payment.simulation.dlq`. O contrato de retry preserva os bytes originais.

## Headers

`x-request-id`, `x-correlation-id`, `x-causation-id`, `Idempotency-Key`, `x-event-type`, `x-event-version`, `traceparent`, `x-retry-attempt`, `x-retry-not-before` e `x-orig-topic` são nomes canônicos. Alterá-los exige evolução versionada dos produtores e consumidores.

## Evolução

- Compatibilidade é `FULL_TRANSITIVE`: cada candidate deve ser lido por todos os leitores históricos e ler todos os writers históricos.
- Campos novos usam default quando necessário para compatibilidade.
- Diretórios em [`schemas/history`](../schemas/history) são append-only.
- Mudança incompatível cria major, artifact id e tópico novos; versões coexistem durante a migração.
- Exceções exigem ADR antes da mudança.

O gate usa `SchemaCompatibility` do Apache Avro nas duas direções. A política completa está em [`compatibility-policy.json`](../schemas/compatibility-policy.json).
