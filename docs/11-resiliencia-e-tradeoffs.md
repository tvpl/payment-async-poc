# 11 — Resiliência e trade-offs

## Pontos de resiliência

| Mecanismo | Garante | Onde |
|---|---|---|
| **Outbox** (publica fora da TX, claim/lease) | Publicação confiável; sem *dual-write* | `sbus-service/.../outbox/*` |
| **OutboxReaper** | Recupera linhas presas em `IN_PROGRESS` (publisher caiu) | `OutboxReaper.java` |
| **OutboxHousekeeping** | Tabela da outbox não cresce indefinidamente | `OutboxHousekeeping.java` |
| **Consumers `SYNC_PER_RECORD` + retry topics + DLQ** | Zero perda silenciosa; retries fora da partição principal | `*Consumer`, `RetryPublisher`, `RetryConsumer` |
| **Serialização fora da transação** | Não segura conexão de DB durante I/O de registry | `PaymentSimulationService` / `PaymentPersistenceService` |
| **Idempotência (3 camadas)** | Reprocessamento sem efeito duplicado | Redis `idem:`, `request_id` UNIQUE, `idempotency_record` |
| **`FOR UPDATE SKIP LOCKED`** | Múltiplas instâncias do SBUS publicam sem duplicar | `OutboxEventRepository` |
| **Rate limiter distribuído (Core)** | Limite **global** protege o Core | `RedisRateLimiter` + `OutboxDispatcher` |
| **Rate limiter distribuído (API, 429)** | Admissão global; virtual threads não limitam carga | `ConcurrencyLimitFilter` |
| **AuthN por API key (401)** | Endpoints de negócio protegidos | `ApiKeyFilter` |
| **Retenção/housekeeping** | Tabelas (`idempotency_record`, `payment_sbus_message`) limitadas | `RetentionHousekeeping` |
| **GET com fallback durável** | Resultado nunca se perde por TTL/instância | `SbusStatusClient` + `InternalStatusController` |
| **Redis lazy + resubscribe** | App sobe com Redis fora; pub/sub se reinscreve | `RedisStatusStore`, `ResponseCoordinator` |
| **read-after-register** | Cobre resposta ultrarrápida/replay | `ResponseCoordinator` |
| **Shutdown gracioso** | Waiters liberados (não penduram conexões) | `ResponseCoordinator` |
| **Producer idempotente** (`acks=all`, `enable.idempotence`) | Sem duplicatas por retry do producer | `KafkaProducerFactory`, `@KafkaClient` |
| **Timeout obrigatório** na espera HTTP | Nunca segura conexão indefinidamente | `ApiProperties.wait-timeout` |

## Trade-offs (prós/contras)

### Esperar evento (200) vs responder 202 imediato
- **Esperar**: melhor UX quando o Core é rápido; porém ocupa uma conexão/virtual thread por requisição.
- **202 imediato**: escala melhor sob carga, mas exige polling/callback do cliente.
- **Nossa escolha**: híbrido — espera curta (timeout) e cai para `202`. Equilibra UX e escala.

### Redis (correlação) vs memória local
- **Local**: simples, mas **quebra** com múltiplas instâncias (o evento pode chegar noutra instância).
- **Redis + pub/sub**: funciona horizontalmente; custo é dependência extra e latência de rede.

### Kafka como buffer/backpressure
- **Prós**: absorve rajada, desacopla cadências, *at-least-once*.
- **Contras**: *eventual consistency* e operação de cluster.

### Outbox no SBUS
- **Prós**: resolve *dual-write*; mantém o Core agnóstico.
- **Contras**: a tabela cresce (mitigado por housekeeping) e há latência extra (polling).

### Limites das virtual threads
- Baratas para I/O, mas **não** dão backpressure nem limitam carga — por isso o rate limit explícito.
- Cuidado com *pinning* (`synchronized`/JNI). O `AvroSerde` usa instâncias **`ThreadLocal`** (uma por
  thread), evitando lock global e *pinning* no encode/decode.

### Idempotência e reprocessamento
- *At-least-once* exige consumidores idempotentes; usamos chaves e estados terminais para evitar
  efeitos colaterais duplicados.

### Operação com múltiplas instâncias
- SBUS: `SKIP LOCKED` permite N publicadores. API: consumer group único por instância + Redis pub/sub.

## Próximas evoluções sugeridas
- Schema Registry com regra de compatibilidade explícita (ex.: `BACKWARD`) e testes de contrato.
- Particionamento/arquivamento da outbox para volumes altos.
- Circuit breaker no `CoreGateway` quando o Core for síncrono (HTTP/gRPC).
- JWT/OAuth2 + mTLS no lugar da API key; TLS/SASL no Kafka.

Checklist completo de produção em [15 Prontidão para produção](15-prontidao-producao.md).

## Ver também
- [06 SBUS](06-sbus-service.md) · [05 API](05-api-service.md) · [10 Observabilidade](10-observabilidade.md)
