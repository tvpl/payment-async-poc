# 15 — Prontidão para produção (hardening)

Esta página resume os gaps tratados para o projeto servir de **molde a sistemas críticos** e o que
ainda é **responsabilidade de deploy/operação**.

## Gaps corrigidos no código

| Gap | Risco | Correção | Onde |
|---|---|---|---|
| Serialização Avro **dentro** da transação | Conexão de DB presa em I/O de registry → pool esgota | Serializar **fora** da TX; só escrita na TX | `service/PaymentSimulationService` + `service/PaymentPersistenceService` |
| Consumer group da API `…${random.uuid}` | Grupos órfãos infinitos + processamento N× | **Grupo estável** `payment-api`; fanout via Redis pub/sub | `kafka/PaymentResponseConsumer` |
| `AvroSerde` `synchronized` global | Teto de throughput (lock único) | Instâncias **`ThreadLocal`** (sem contenção) | `common/.../kafka/AvroSerde` |
| Crescimento ilimitado de tabelas | Volumetria/disco | **Retenção/housekeeping** de `idempotency_record` e `payment_sbus_message` terminais | `outbox/RetentionHousekeeping` |
| Perda em falha de DLQ | Mensagem some se DLQ/broker cai | Rethrow + `errorStrategy=RETRY_ON_ERROR` (não avança offset) | `kafka/*Consumer` |
| Rate limiter **por instância** | Limite global = N× | **`RedisRateLimiter`** distribuído (Core e admissão da API) | `common/.../ratelimit/RedisRateLimiter` |
| Producer da API não idempotente | Duplicatas em retry do producer | `acks=all` + `enable.idempotence=true` | `*/application.yml` |
| Marks da outbox em N transações | Sobrecarga de commits | `markPublishedBatch` (UPDATE único) | `outbox/OutboxClaimService` |
| Latência de polling 500ms | Latência fim a fim | poll-interval **200ms** (documentado LISTEN/NOTIFY) | `sbus application.yml` |
| Retry bloqueando a partição | Stall sob falha transitória | **Retry topics** dedicados (`.retry`) + DLQ ao esgotar | `kafka/RetryPublisher`, `kafka/RetryConsumer` |
| Sem autenticação | Endpoint aberto | **API key** (`X-API-Key`) → 401 | `filter/ApiKeyFilter` |

## Garantias resultantes

- **Sem perda silenciosa**: toda mensagem é processada, vai para retry, ou para a DLQ (offset só
  avança após sucesso/roteamento durável).
- **Sem dual-write**: estado + outbox no mesmo commit; publicação fora da transação (claim/lease).
- **Idempotência** em 3 camadas; redeliveries e replays não duplicam efeito.
- **Limites globais** de taxa (proteção do Core e admissão), válidos com N instâncias.
- **Tabelas limitadas** por retenção; outbox por housekeeping.

## Responsabilidade de deploy/operação (checklist)

- [ ] **Kafka multi-broker**: RF=3, `min.insync.replicas=2`, `unclean.leader.election=false`.
      Subir tópicos com `KAFKA_TOPIC_RF=3` no `kafka-init`. Exemplo:
      [`deploy/docker-compose.kafka-cluster.example.yml`](../deploy/docker-compose.kafka-cluster.example.yml)
      (ilustrativo, não testado aqui).
- [ ] **TLS**: terminar em gateway/service-mesh (recomendado) ou habilitar `micronaut.server.ssl`
      e SASL/SSL no Kafka. Hoje os listeners são PLAINTEXT (apenas dev).
- [ ] **AuthN/AuthZ**: trocar a API key por **JWT/OAuth2** (`micronaut-security-jwt`) + mTLS entre
      serviços. A API key é um exemplo funcional, não o alvo de produção.
- [ ] **Segredos**: senhas de Postgres/Redis e `PAYMENT_API_KEY` via secret manager (não em YAML).
- [ ] **Dimensionamento**: ajustar Hikari `maximum-pool-size`, concorrência dos consumers e
      `sbus.core.limit-for-period` à capacidade real do Core.
- [ ] **Schema Registry**: definir regra de compatibilidade (ex.: `BACKWARD`) e testes de contrato.
- [ ] **Alta disponibilidade**: ≥2 instâncias de cada serviço; Redis e Postgres com réplica/failover.
- [ ] **Observabilidade**: alertas de [`alerts.yml`](../observability/alerts.yml) conectados ao
      Alertmanager; reter traces/métricas conforme política.
- [ ] **Retenção**: revisar `sbus.housekeeping.*` conforme requisitos regulatórios.
- [ ] **DR/backup**: backup do Postgres (fonte durável) e plano de reprocessamento via DLQ.

## Redis HA (Sentinel/Cluster)

O store de flags (`feature-control`) e a fila do `async-redis-service` usam o **mesmo** `RedisClient`
(Lettuce) injetado — então **alta disponibilidade é pura configuração, sem mudança de código**. Basta
sobrepor a URI por env (env vence o `application.yml`):

```bash
REDIS_URI=redis-sentinel://redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379/mymaster
```

O Lettuce descobre o master atual via Sentinels e faz **failover** automático; o pool de BRPOP, os
Streams/consumer groups e o pub/sub de propagação continuam funcionando. Exemplo ilustrativo (master +
réplica + 3 Sentinels): [`deploy/docker-compose.redis-ha.example.yml`](../deploy/docker-compose.redis-ha.example.yml).
Para escala muito alta, **Redis Cluster** é a alternativa (URI `redis://n1,n2,...`); como as chaves são
por-request/por-flag, não há operação multi-chave cross-slot.

## Itens implementados mas verificáveis só com runtime

Retry topics, limiter distribuído (Redis), multi-broker e TLS são código/configuração presentes, mas a
**validação funcional completa exige Docker/cluster** (fora do container de desenvolvimento). Localmente
foram validados por compilação, testes unitários e boot do grafo de DI.

## Ver também
- [11 Resiliência e trade-offs](11-resiliencia-e-tradeoffs.md) · [06 SBUS](06-sbus-service.md) · [12 Execução](12-execucao-e-operacao.md)
