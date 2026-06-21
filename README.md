# Payment Simulation Platform — PoC assíncrona (Micronaut + Kafka)

Prova de conceito de um arranjo de pagamento onde uma **API HTTP** recebe rajadas de
requisições de simulação, **desacopla o processamento via Kafka**, protege um **Core**
externo contra rajadas e **aguarda a resposta assíncrona por evento** para responder ao
cliente — usando **virtual threads** (Java 21+, GA desde o 21) para espera de I/O barata.

A garantia de publicação confiável de eventos fica no **SBUS** (Service Bus) via
**Outbox Pattern**, mantendo a API desacoplada do Core e permitindo que o Core seja
qualquer serviço externo/legado no futuro.

> **Java 25**: o código de virtual threads é idêntico ao do Java 21. O alvo da toolchain
> está centralizado em `gradle.properties` (`javaLanguageVersion=21`). Para mover para o
> Java 25 basta alterar essa propriedade e as imagens base nos `Dockerfile` (`jdk21`/`21-jre`
> → `25`). Este container de desenvolvimento só tem JDK 21, por isso o alvo atual é 21.

---

## Componentes

| Serviço | Porta | Responsabilidade |
|---|---|---|
| **api-service** | 8080 | API HTTP, idempotência, espera assíncrona (virtual threads), Redis, produz/consome Kafka |
| **sbus-service** | 8081 | Consome eventos, persiste no Postgres, **Outbox Pattern**, protege o Core, DLQ |
| **core-mock** | 8082 | Core simulado: consome comando, calcula taxas/autorização, responde por evento |
| **common** | — | Contratos de evento compartilhados (`EventEnvelope`, payloads, enums, constantes) |

Infra: **Kafka** (KRaft), **Redis**, **PostgreSQL**, **OpenTelemetry Collector**,
**Jaeger**, **Prometheus**, **Grafana**.

---

## Arquitetura e fluxo ponta a ponta

```
Cliente HTTP
   │ POST /payment-simulations        (bloqueia em virtual thread até timeout T)
   ▼
┌─────────────┐  PaymentSimulationRequested   ┌───────────────────────────────────────┐
│ api-service │ ─────────────────────────────▶│ topic: payment.simulation.requested   │
│             │  Redis: status/result, idemp. └───────────────────────────────────────┘
│             │  pub/sub p/ correlação                         │ consome
│             │                                                ▼
│             │                                       ┌──────────────┐  TX Postgres:
│             │                                       │ sbus-service │  payment_sbus_message
│             │                                       │              │  + outbox_event
│             │                                       └──────────────┘
│             │   OutboxPublisher (scheduled, SKIP LOCKED, rate-limited p/ Core)
│             │                                                │
│             │   ┌───────────────────────────────────────────▼───┐
│             │   │ topic: payment.simulation.core.command         │──▶ core-mock
│             │   └────────────────────────────────────────────────┘      │
│             │   ┌────────────────────────────────────────────────┐      │
│             │   │ topic: payment.simulation.core.response         │◀─────┘
│             │   └───────────────────────────────────────────────┬┘
│             │                                       sbus consome │  TX Postgres (estado final)
│             │                                                    │  + outbox_event (final)
│             │   ┌────────────────────────────────────────────┐  │  OutboxPublisher
│  consome ◀──┼───│ payment.simulation.{completed,failed}       │◀─┘
└─────────────┘   └────────────────────────────────────────────┘
   │ grava result no Redis + publica no canal pub/sub → completa o future do waiter
   ▼
200 (COMPLETED) · 422 (FAILED) · 202 (ainda processando) · 400 (inválido) · 5xx
```

Passo a passo:

1. `POST /payment-simulations` valida o payload e resolve a `Idempotency-Key`.
2. Gera `requestId`, `correlationId`, captura `traceId` (OTel) e usa `requestId` como `causationId` inicial.
3. Grava `PENDING` no Redis e registra um waiter (`CompletableFuture`).
4. Publica `PaymentSimulationRequested` (key = `requestId`); status → `SENT_TO_SBUS`.
5. **Aguarda** o resultado na virtual thread até `payment.simulation.wait-timeout`.
6. O **SBUS** consome, abre transação, grava `payment_sbus_message` + `outbox_event` (comando ao Core) e commita.
7. O **OutboxPublisher** lê pendentes (`FOR UPDATE SKIP LOCKED`), publica no Kafka e marca `PUBLISHED` (com backoff/retry e DLQ em falha permanente). Um **rate limiter** no `core.command` protege o Core.
8. O **core-mock** processa e responde em `payment.simulation.core.response`.
9. O **SBUS** consome a resposta, grava o estado final e registra o evento final na outbox.
10. A **API** consome `completed`/`failed`, grava o resultado no Redis e **acorda o waiter** (local e, via Redis pub/sub, em qualquer instância).
11. Se chegou no prazo → `200`/`422`; senão → `202` com `statusUrl`. O `GET` reflete o estado a qualquer momento.

---

## Como executar (Docker Compose)

```bash
docker compose up -d --build
# aguarde os healthchecks (kafka, postgres, redis) e o kafka-init criar os tópicos
docker compose ps
```

UIs e endpoints:

- API: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin) — dashboards em *Payment Simulation*
- Jaeger (traces): http://localhost:16686
- Métricas: `http://localhost:8080/prometheus`, `:8081/prometheus`, `:8082/prometheus`

### Exemplos curl

```bash
# 1) Simulação (pode retornar 200 se o Core responder dentro do timeout, ou 202)
curl -i -X POST http://localhost:8080/payment-simulations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pedido-123' \
  -d '{
        "merchantId": "MERCHANT-001",
        "amount": 125.50,
        "currency": "BRL",
        "paymentMethod": "CREDIT_CARD",
        "brand": "VISA",
        "installments": 3,
        "captureMode": "AUTHORIZE_AND_CAPTURE"
      }'

# 2) Consulta de status por requestId (use o requestId retornado acima)
curl -i http://localhost:8080/payment-simulations/<requestId>

# 3) Idempotência: repetir o POST com a MESMA Idempotency-Key reusa o requestId original
```

Respostas: `200` (COMPLETED) · `422` (FAILED/recusado) · `202` (segue assíncrono, com `statusUrl`)
· `400` (payload inválido) · `500/504` (erro/infra).

---

## Desenvolvimento local

```bash
./gradlew build -x test          # compila tudo (testes de integração precisam de Docker)
./gradlew :common:test :api-service:test --tests '*UnitTest*'   # testes unitários (sem Docker)
```

Subir infra e rodar um serviço fora do compose (Kafka exposto em `localhost:29092`):

```bash
docker compose up -d kafka kafka-init redis postgres otel-collector jaeger prometheus grafana
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :sbus-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :api-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :core-mock:run
```

---

## Contratos de evento

Todos os eventos usam o **envelope técnico** padrão (ver `common/.../EventEnvelope.java`)
com `eventId, eventType, eventVersion, occurredAt, requestId, correlationId, causationId,
traceId, source, payload`. Exemplos completos em [`docs/events/`](docs/events):

- `PaymentSimulationRequested` · `ProcessPaymentSimulationCommand` · `CorePaymentSimulationResponse`
- `PaymentSimulationCompleted` · `PaymentSimulationFailed`

`eventVersion` permite evolução compatível. O payload é **JSON** hoje; o mesmo envelope
suporta migração futura para **Avro/Protobuf + Schema Registry** sem mudar a lógica de correlação.

### Tópicos Kafka (particionados por `requestId`)

`payment.simulation.requested` · `…core.command` · `…core.response` · `…completed` ·
`…failed` · `…dlq`. Consumer groups: `payment-sbus` (SBUS), `payment-core-mock` (Core),
`payment-api-${random.uuid}` (API — group único por instância para que **toda** instância
veja os eventos finais).

---

## Redis (na API)

| Chave | Conteúdo |
|---|---|
| `payment-simulation:{requestId}` | `StatusEntry` (status + resultado), TTL configurável |
| `idem:{idempotencyKey}` | `requestId` dono (SET NX) para idempotência |
| canal `payment-sim-responses` | pub/sub para acordar waiters entre instâncias |

Estados: `PENDING → SENT_TO_SBUS → PROCESSING → COMPLETED | FAILED | TIMEOUT`.

## PostgreSQL (no SBUS)

Tabelas (migrations Flyway em `sbus-service/.../db/migration`): `payment_sbus_message`,
`outbox_event`, `idempotency_record`. Colunas JSON em `jsonb` (a URL usa
`stringtype=unspecified` para o driver fazer o cast a partir de `String`).

---

## Outbox Pattern (no SBUS)

Resolve o problema de **dual-write** (gravar no banco *e* publicar no Kafka atomicamente):

1. SBUS consome `PaymentSimulationRequested`.
2. Abre transação no Postgres.
3. Grava/atualiza `payment_sbus_message`.
4. Grava o evento em `outbox_event` (ex.: `ProcessPaymentSimulationCommand`).
5. Commita — banco e outbox no **mesmo** commit.
6. `OutboxPublisher` (`@Scheduled`) lê pendentes com `FOR UPDATE SKIP LOCKED` (várias instâncias em paralelo, sem colisão).
7. Publica no Kafka **replayando os headers técnicos** (inclusive `traceparent`).
8. Marca `PUBLISHED` (`published_at`).
9. Em falha: `attempts++`, `next_attempt_at` com **backoff exponencial**, `last_error`.
10. Após `max-attempts`: envia para a **DLQ** e marca `FAILED`.

O mesmo mecanismo publica os eventos finais (`PaymentSimulationCompleted/Failed`) de volta para a API.

---

## Virtual Threads

- A espera por resposta é **I/O-bound**; `future.get(timeout)` roda no executor
  `BLOCKING` da Micronaut, que no JDK 21+ é **backed por virtual threads**. Milhares de
  requisições podem aguardar sem consumir threads de plataforma.
- **Timeout obrigatório** no `get` (`payment.simulation.wait-timeout`).
- **Limite de concorrência / backpressure** *não* vem das virtual threads: virtual threads
  só tornam a espera barata; **não** limitam carga no Core. Quem absorve rajada e dá
  backpressure é **Kafka** (buffer), a **outbox** e o **rate limiter** do `core.command`.
- Estratégia de rate limit na API: documentada (limite global / por merchant via gateway ou
  filtro); o backpressure de fato vem do pipeline assíncrono a jusante.

---

## Observabilidade

- **Tracing**: `traceparent` (W3C) propagado HTTP → API → Kafka → SBUS → Kafka → API; OTLP →
  `otel-collector` → **Jaeger**. Spans automáticos de HTTP e Kafka (instrumentação Micronaut OTel).
- **Métricas** (`/prometheus`): HTTP RPS/latência, `api_wait_latency`, `api_pending`,
  `api_timeouts_total`, `sbus_outbox_pending`, `sbus_outbox_published_total`,
  `sbus_outbox_publish_failures_total`, `sbus_dlq_total`, `sbus_end_to_end_latency`,
  consumer lag, HikariCP, JVM.
- **Logs**: JSON estruturado (logstash-logback-encoder) com MDC `requestId, correlationId,
  causationId, traceId, eventType, topic, partition, offset, status`.
- **Dashboards Grafana** (provisionados): API, SBUS, Outbox, Kafka, Redis, PostgreSQL.
- **Alertas** (`observability/alerts.yml`): outbox pendente alto, falhas de publicação,
  DLQ recebendo, timeout/latência p99 da API, consumer lag.

---

## Pontos de resiliência

- **Outbox** garante publicação confiável mesmo com Kafka indisponível no momento do commit.
- **Idempotência** em três camadas: Redis (`idem:`), `payment_sbus_message.request_id` (UNIQUE) e `idempotency_record`.
- **Retry com backoff** + **DLQ** para mensagens venenosas (parse/validação) e falhas permanentes.
- **`SKIP LOCKED`** permite múltiplas instâncias do SBUS publicando a outbox sem duplicar.
- **Rate limiter** no `core.command` (Resilience4j) protege o Core de rajadas.
- **Producer idempotente** (`acks=all`, `enable.idempotence=true`).
- **Timeout** na espera HTTP evita segurar conexões indefinidamente.

---

## Trade-offs

- **Esperar evento (200) vs 202 imediato**: esperar dá melhor UX síncrona quando o Core é
  rápido, mas consome uma conexão/virtual thread; o `202` escala melhor sob carga e exige
  polling/callback. Aqui é **híbrido**: espera até um timeout curto, depois cai para `202`.
- **Redis vs memória local** para correlação: memória local não funciona com múltiplas
  instâncias (o evento pode chegar em outra instância). Redis + pub/sub coordena todas; custo
  é uma dependência extra e latência de rede.
- **Kafka como buffer/backpressure**: absorve rajadas e desacopla a cadência da API da
  capacidade do Core; custo é complexidade operacional e *eventual consistency*.
- **Outbox no SBUS**: elimina dual-write e mantém o Core agnóstico; custo é a tabela crescer
  (precisa housekeeping: arquivar/particionar `PUBLISHED`, índice em `(status, next_attempt_at)`).
- **Limites de virtual threads**: baratas para I/O, mas não substituem rate limit; *pinning*
  em `synchronized`/JNI pode degradar — preferir locks e I/O não bloqueante onde possível.
- **Idempotência e reprocessamento**: at-least-once exige consumidores idempotentes; chaves e
  estados terminais evitam efeitos colaterais duplicados.
- **Crescimento da outbox**: monitorar `sbus_outbox_pending`; job de limpeza para linhas antigas.
- **Múltiplas instâncias**: `SKIP LOCKED` (SBUS), consumer groups e Redis pub/sub (API)
  tornam o sistema horizontalmente escalável.

---

## Testes

- **Unitários** (sem Docker): `EventEnvelopeUnitTest` (common), `ApiPaymentServiceUnitTest` (API).
- **Integração (Testcontainers, exigem Docker)**: `SbusFlowIT` (Postgres+Kafka: requested →
  persistência+outbox → core.command → core.response → completed) e `ApiFlowIT`
  (Kafka+Redis: `202` → correlação do evento final → `GET COMPLETED`).

```bash
./gradlew test            # roda tudo (precisa de Docker para os *IT)
```

---

## Estrutura

```
payment-async-poc/
├── settings.gradle, build.gradle, gradle.properties
├── docker-compose.yml
├── common/            # contratos de evento
├── api-service/       # API HTTP + virtual threads + Redis + Kafka
├── sbus-service/      # consumers + Outbox + Postgres (Flyway)
├── core-mock/         # Core simulado
├── observability/     # prometheus.yml, alerts.yml, otel-collector.yml, grafana/
└── docs/events/       # exemplos de contratos de evento
```
