# 13 — Testes

## Pirâmide

```mermaid
flowchart TB
    it["Integração — Testcontainers<br/>(Kafka, Postgres, Redis, Apicurio) — exigem Docker"]
    unit["Unitários — sem Docker<br/>(lógica pura, mocks)"]
    unit --- it
```

## Unitários (rodam sem Docker)

| Teste | Módulo | Cobre |
|---|---|---|
| `EventEnvelopeUnitTest` | common | `create`/`deriveAs` (correlação, causação, versão) |
| `AvroMapperUnitTest` | common | round-trip POJO↔Avro (requested e completed) |
| `ApiPaymentServiceUnitTest` | api | submit (200/202), idempotência (replay) e **read-after-register** |
| `BackoffCalculatorUnitTest` | sbus | backoff exponencial + teto |
| `RedisRateLimiterUnitTest` | common | fallback local quando o Redis está fora |
| `RetryPublisherUnitTest` | sbus | roteamento retry-topic vs DLQ por tentativa |

Rodar:
```bash
./gradlew :common:test :api-service:test :sbus-service:test --tests '*UnitTest'
```
> Use o sufixo exato `*UnitTest` (sem `*` no fim) para não acionar os `*IT`.

## Integração (Testcontainers — exigem Docker)

| Teste | Sobe | Verifica |
|---|---|---|
| `SbusFlowIT` | Postgres + Kafka + **Apicurio** | requested → `payment_sbus_message` + `outbox_event` → `core.command` publicado → injeta `core.response` → `completed` publicado + estado `COMPLETED` |
| `ApiFlowIT` | Kafka + Redis + **Apicurio** | POST → `202`; publica evento final (Avro) → consumer correlaciona → `GET` retorna `COMPLETED` |

Padrão: containers estáticos + `TestPropertyProvider` injeta `kafka.bootstrap.servers`,
`apicurio.registry.url`, datasource/redis no contexto Micronaut. O teste codifica/decodifica Avro com
um `AvroSerde` apontando para o registry do container.

Rodar tudo (precisa de Docker):
```bash
./gradlew test
```

## Carga
Ver [`load/k6-simulations.js`](../load/k6-simulations.js) e [12 Execução](12-execucao-e-operacao.md).

## Notas
- Sem Docker, os `*IT` falham com `Could not find a valid Docker environment` — esperado.
- Os arquivos: `*/src/test/java/.../*UnitTest.java` e `*/src/test/java/.../*IT.java`.

## Ver também
- [06 SBUS](06-sbus-service.md) · [05 API](05-api-service.md)
