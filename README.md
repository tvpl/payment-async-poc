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
> Java 25 basta alterar essa propriedade e as imagens base no `Dockerfile` raiz (`jdk21`/`21-jre`
> → `25`). Este container de desenvolvimento só tem JDK 21, por isso o alvo atual é 21.

## 📚 Documentação detalhada

O diretório [`docs/`](docs/README.md) detalha cada tecnologia/ferramenta e o funcionamento do sistema
(pt-BR, com diagramas Mermaid). Comece pelo [índice](docs/README.md). Atalhos:

- [01 Visão geral](docs/01-visao-geral.md) · [02 Arquitetura](docs/02-arquitetura.md) · [04 Fluxo ponta a ponta](docs/04-fluxo-ponta-a-ponta.md)
- [03 Tecnologias e ferramentas](docs/03-tecnologias.md) (o que é / por que / como configuramos / onde no código)
- [05 API](docs/05-api-service.md) · [06 SBUS](docs/06-sbus-service.md) · [07 Core mock](docs/07-core-mock.md)
- [08 Eventos e contratos](docs/08-eventos-e-contratos.md) · [09 Dados (Redis/Postgres)](docs/09-dados-redis-postgres.md)
- [10 Observabilidade](docs/10-observabilidade.md) · [11 Resiliência e trade-offs](docs/11-resiliencia-e-tradeoffs.md)
- [12 Execução e operação](docs/12-execucao-e-operacao.md) · [13 Testes](docs/13-testes.md) · [14 Glossário](docs/14-glossario.md)
- [15 Prontidão para produção](docs/15-prontidao-producao.md) (gaps corrigidos + checklist de deploy)

---

## Componentes

| Serviço | Porta | Responsabilidade |
|---|---|---|
| **api-service** | 8080 | API HTTP, idempotência, espera assíncrona (virtual threads), Redis, produz/consome Kafka |
| **sbus-service** | 8081 | Consome eventos, persiste no Postgres, **Outbox Pattern**, protege o Core, DLQ |
| **core-mock** | 8082 | Core simulado: consome comando, calcula taxas/autorização, responde por evento |
| **common** | — | Contratos de evento compartilhados (`EventEnvelope`, payloads, enums, constantes) |

Infra: **Kafka** (KRaft), **Redis**, **PostgreSQL**, **Apicurio Schema Registry**,
**Kafka UI**, **OpenTelemetry Collector**, **Jaeger**, **Prometheus**, **Grafana**,
e exporters (**Redis/Postgres/Kafka**) para métricas server-side.

> **Serialização**: os eventos no Kafka usam **Avro + Apicurio Schema Registry**
> (os serdes do Confluent não estão disponíveis no Maven Central / repo bloqueado;
> Apicurio é compatível e está no Central). O schema-id fica embutido no payload
> (headers desabilitados), tornando os bytes auto-descritivos — é o que permite o SBUS
> guardá-los na outbox e republicá-los intactos. HTTP e Redis continuam em JSON; a
> tradução POJO↔Avro fica em `common/.../mapping/AvroMapper.java`.

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
docker compose up -d --build      # ou: make up
# aguarde os healthchecks (kafka, postgres, redis, apicurio + os 3 apps via /health)
docker compose ps                 # ou: make ps
```

> **Atalhos** ([`Makefile`](Makefile)): `make demo` (up + espera health + smoke) · `make up` /
> `make up-core` (enxuto, sem observabilidade) · `make smoke` · `make load` / `load-heavy` /
> `load-ramp` / `load-poll` (k6 via container → métricas no Grafana) · `make logs` · `make urls`
> · `make down` / `make clean`. Tunables em [`.env`](.env). Veja [docs/12](docs/12-execucao-e-operacao.md).

UIs e endpoints:

- API: http://localhost:8080  · OpenAPI: `http://localhost:8080/swagger/payment-simulation-api-1.0.yml`
- Kafka UI (tópicos/mensagens/lag): http://localhost:8088
- Apicurio Schema Registry: http://localhost:8085 (UI/API)
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin) — dashboards API/SBUS/Outbox/Infra/**k6 Load Test**
- Jaeger (traces): http://localhost:16686
- Métricas: `http://localhost:8080/prometheus`, `:8081/prometheus`, `:8082/prometheus`
  · exporters: Redis `:9121`, Postgres `:9187`, Kafka `:9308`

### Exemplos curl

```bash
# 1) Simulação (200 se o Core responder no prazo, ou 202). Requer X-API-Key (default dev).
curl -i -X POST http://localhost:8080/payment-simulations \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-key-change-me' \
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
curl -i -H 'X-API-Key: dev-key-change-me' http://localhost:8080/payment-simulations/<requestId>

# 3) Idempotência: repetir o POST com a MESMA Idempotency-Key reusa o requestId original

# 4) Carga (rajada) — exercita rate limit (429), 200/202 e virtual threads.
#    A auth fica ON por padrão, então o k6 envia X-API-Key (default dev-key-change-me).
k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=dev-key-change-me \
       -e RATE=300 -e DURATION=1m load/k6-simulations.js
# ou, sem instalar k6:  make load   /   make load-heavy
```

Respostas: `200` (COMPLETED) · `422` (FAILED/recusado) · `202` (segue assíncrono, com `statusUrl`)
· `400` (payload inválido, corpo `application/problem+json`) · `429` (rate limit, com `Retry-After`)
· `503` (falha de publicação) · `500` (erro). O `GET` cai para o **SBUS (durável)** quando o
Redis não tem o resultado, então uma resposta finalizada nunca se perde.

---

## Desenvolvimento local

```bash
./gradlew build -x test   # compila tudo
./gradlew test            # testes unitários (sem Docker; os *IT são excluídos por padrão)
./gradlew test -PwithIT   # inclui os testes de integração (precisam de Docker + Apicurio)
```

Subir infra e rodar um serviço fora do compose (Kafka exposto em `localhost:29092`).
Use o profile **`dev`** para desativar o export OTLP quando não há collector:

```bash
docker compose up -d kafka kafka-init redis postgres apicurio-registry otel-collector jaeger prometheus grafana
export MICRONAUT_ENVIRONMENTS=dev
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :sbus-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :api-service:run
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 ./gradlew :core-mock:run
```

> **Java 25**: alvo centralizado em `gradle.properties` (`javaLanguageVersion`). O código de
> virtual threads é idêntico ao 21; para subir, troque a propriedade e as imagens base no `Dockerfile` raiz.

---

## Contratos de evento

Todos os eventos usam o **envelope técnico** padrão (ver `common/.../EventEnvelope.java`)
com `eventId, eventType, eventVersion, occurredAt, requestId, correlationId, causationId,
traceId, source, payload`. Exemplos completos em [`docs/events/`](docs/events):

- `PaymentSimulationRequested` · `ProcessPaymentSimulationCommand` · `CorePaymentSimulationResponse`
- `PaymentSimulationCompleted` · `PaymentSimulationFailed`

No Kafka os eventos trafegam em **Avro** (schemas em `common/src/main/avro/*.avsc`, um por
evento com o envelope inline + payload tipado), registrados no **Apicurio Schema Registry**.
`eventVersion` + compatibilidade de schema no registry permitem evolução segura. Os JSON em
`docs/events/` são ilustrativos do envelope/campos (o formato de wire é Avro binário).

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

Tabelas (migrations Flyway em `sbus-service/.../db/migration`): `payment_sbus_message`
(inclui `result` jsonb — fonte durável para o fallback do GET), `outbox_event`
(`payload` em **`bytea`** = bytes Avro; `status`, `claimed_at` para o lease), `idempotency_record`.
A URL usa `stringtype=unspecified` para o driver fazer o cast de `String` para `jsonb`.

---

## Outbox Pattern (no SBUS)

Resolve o problema de **dual-write** (gravar no banco *e* publicar no Kafka atomicamente):

1. SBUS consome `PaymentSimulationRequested`.
2. Abre transação no Postgres.
3. Grava/atualiza `payment_sbus_message`.
4. Grava o evento em `outbox_event` (ex.: `ProcessPaymentSimulationCommand`).
5. Commita — banco e outbox no **mesmo** commit (o payload já é o **byte[] Avro** auto-descritivo).
6. **Claim/lease**: `OutboxClaimService.claimBatch()` (Tx1 curta) reivindica pendentes com
   `FOR UPDATE SKIP LOCKED` e marca `IN_PROGRESS` + `claimed_at` (várias instâncias em paralelo, sem colisão).
7. `OutboxDispatcher` publica no Kafka **fora da transação** (sem segurar locks durante o I/O),
   replayando os headers técnicos (inclusive `traceparent`).
8. Tx2: marca `PUBLISHED` (`published_at`) ou, em falha, `attempts++` + `next_attempt_at` com
   **backoff exponencial** (`BackoffCalculator`).
9. Após `max-attempts`: envia para a **DLQ** e marca `FAILED`.
10. `OutboxReaper` devolve linhas `IN_PROGRESS` presas (publisher caiu) para `PENDING`;
    `OutboxHousekeeping` purga `PUBLISHED` antigos (retenção configurável) para a tabela não crescer.

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
- Rate limit na API: **`ConcurrencyLimitFilter`** (Resilience4j) na admissão do `POST` →
  `429` + `Retry-After` quando a taxa configurada (`payment.simulation.rate-limit.*`) é excedida.

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

- **Outbox** garante publicação confiável mesmo com Kafka indisponível no momento do commit
  (publica fora da transação via claim/lease; `OutboxReaper` recupera linhas presas).
- **Consumers sem perda silenciosa**: `SYNC_PER_RECORD`; falha transitória → **retry topics** dedicados
  (`.retry`, fora da partição principal), venenosa → **DLQ**; falha de publicação relança (offset não avança).
- **Serialização Avro fora da transação** (sem segurar conexão de DB em I/O de registry).
- **Idempotência** em três camadas: Redis (`idem:`), `payment_sbus_message.request_id` (UNIQUE) e `idempotency_record`.
- **`SKIP LOCKED`** permite múltiplas instâncias do SBUS publicando a outbox sem duplicar.
- **Rate limiter distribuído (Redis)** no `core.command` (Core) e na **admissão da API** (→ 429) — limite global entre instâncias.
- **AuthN por API key** (`X-API-Key` → 401); produção: JWT/OAuth2 + mTLS.
- **GET com fallback durável** (SBUS/Postgres) → resultado nunca se perde por TTL/instância.
- **Consumer group estável** da API (`payment-api`) + Redis pub/sub (sem grupos órfãos nem trabalho N×).
- **Retenção/housekeeping** (outbox, idempotency, mensagens) mantém tabelas limitadas.
- **Redis lazy + resubscribe**; **shutdown gracioso**; **producer idempotente** (`acks=all`); **timeout** obrigatório na espera.

Detalhes e checklist de produção em [docs/15-prontidao-producao.md](docs/15-prontidao-producao.md).

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

- **Unitários** (sem Docker): `EventEnvelopeUnitTest` e `AvroMapperUnitTest` (common, round-trip
  POJO↔Avro), `ApiPaymentServiceUnitTest` (API, inclui a corrida *read-after-register*),
  `BackoffCalculatorUnitTest` (SBUS).
- **Integração (Testcontainers, exigem Docker)**: `SbusFlowIT` (Postgres+Kafka+Apicurio: requested →
  persistência+outbox → core.command → core.response → completed) e `ApiFlowIT`
  (Kafka+Redis+Apicurio: `202` → correlação do evento final → `GET COMPLETED`).

```bash
./gradlew test            # unitários (sem Docker; os *IT são excluídos por padrão)
./gradlew test -PwithIT   # inclui os *IT (precisam de Docker + Apicurio)
```

---

## Estrutura

```
payment-async-poc/
├── settings.gradle, build.gradle, gradle.properties
├── docker-compose.yml, Dockerfile (multi-target), Makefile, .env
├── .github/workflows/ # ci.yml (build + unit tests + compose lint)
├── scripts/           # smoke.sh (teste ponta a ponta rápido)
├── common/            # contratos: schemas Avro (src/main/avro), AvroMapper, AvroSerde, envelope/POJOs
├── api-service/       # API HTTP + virtual threads + Redis + Kafka (Avro) + rate limit + fallback GET
├── sbus-service/      # consumers (Avro) + Outbox (claim/lease + reaper + housekeeping) + Postgres (Flyway)
├── core-mock/         # Core simulado (comportamento configurável: latência/recusa/falha)
├── observability/     # prometheus.yml, alerts.yml, otel-collector.yml, grafana/ (dashboards + datasources)
├── load/              # k6-simulations.js (síncrono) e k6-poll.js (assíncrono)
└── docs/events/       # exemplos de contratos de evento (JSON ilustrativo)
```
