# 08 — Eventos e contratos

Todo evento/comando trafega no Kafka como **Avro** com um **envelope técnico** padrão. HTTP e Redis
permanecem em JSON; a tradução POJO↔Avro está em
`common/src/main/java/com/example/payments/common/mapping/AvroMapper.java`.

## Envelope técnico

Campos comuns (ver `common/.../events/EventEnvelope.java` e os schemas Avro):

| Campo | Significado |
|---|---|
| `eventId` | Id único deste evento |
| `eventType` | Tipo (ex.: `PaymentSimulationRequested`) |
| `eventVersion` | Versão do contrato (evolução) |
| `occurredAt` | Quando ocorreu (epoch millis no Avro) |
| `requestId` | Id da requisição/simulação (chave de partição) |
| `correlationId` | Amarra todos os eventos de um mesmo fluxo |
| `causationId` | Id do evento que **causou** este (cadeia de causa) |
| `traceId` | Id do trace OTel (observabilidade) |
| `source` | Serviço de origem (`payment-simulation-api`, `payment-sbus`, `payment-core-mock`) |
| `payload` | Corpo de negócio tipado |

**Correlação vs causação**: `correlationId` é o mesmo do início ao fim; `causationId` aponta para o
evento anterior (ex.: o comando ao Core é *causado* pelo `Requested`). Isso permite reconstruir a
árvore de causalidade.

## Eventos

| eventType | Tópico | payload | Produzido por |
|---|---|---|---|
| `PaymentSimulationRequested` | `payment.simulation.requested` | dados da simulação | API |
| `ProcessPaymentSimulationCommand` | `payment.simulation.core.command` | `simulationId` + request | SBUS (outbox) |
| `CorePaymentSimulationResponse` | `payment.simulation.core.response` | resultado do Core | core-mock |
| `PaymentSimulationCompleted` | `payment.simulation.completed` | `SimulationResult` | SBUS (outbox) |
| `PaymentSimulationFailed` | `payment.simulation.failed` | `SimulationResult` (erro) | SBUS (outbox) |
| (poison/falha) | `payment.simulation.dlq` | bytes originais + headers `x-dlq-*` | consumers/outbox |

Exemplos ilustrativos (JSON) do envelope/campos em [`docs/events/`](events):
[Requested](events/PaymentSimulationRequested.json) ·
[Command](events/ProcessPaymentSimulationCommand.json) ·
[CoreResponse](events/CorePaymentSimulationResponse.json) ·
[Completed](events/PaymentSimulationCompleted.json) ·
[Failed](events/PaymentSimulationFailed.json).

> Os JSON são para entendimento humano; o **formato de wire é Avro binário**.

## Avro + Schema Registry

- Um schema `.avsc` por evento em [`common/src/main/avro/`](../common/src/main/avro) — cada um com o
  **envelope inline + payload tipado** (Avro não tem genéricos). Records compartilhados: `Fees`,
  `Settlement`, `PaymentRequest`.
- O plugin Gradle gera as classes Java; o `AvroSerde`
  (`common/.../kafka/AvroSerde.java`) serializa/deserializa com o **Apicurio**, com **schema id
  embutido no payload** (headers off) — bytes auto-descritivos, essenciais para a outbox republicar.
- **Versionamento**: bump `eventVersion` + compatibilidade no registry. Campos novos opcionais
  (com `default`) mantêm compatibilidade *backward/forward*.

## Tópicos e particionamento

- Nomes em `common/.../events/Topics.java`; criados pelo `kafka-init` (3 partições).
- **Chave = `requestId`** → todos os eventos de uma simulação caem na mesma partição (ordem por simulação).
- Consumer groups: `payment-sbus` (SBUS), `payment-core-mock` (Core),
  `payment-api-${random.uuid}` (API — group único por instância, para todas verem o evento final).

## Headers técnicos

Propagados em HTTP e Kafka (`common/.../events/Headers.java`): `x-request-id`, `x-correlation-id`,
`x-causation-id`, `Idempotency-Key`, `x-event-type`, `x-event-version`, `traceparent`. A outbox
guarda e **replaya** esses headers ao publicar (`HeaderMap`).

## Ver também
- [06 SBUS](06-sbus-service.md) · [10 Observabilidade](10-observabilidade.md)
