# 03 — Tecnologias e ferramentas

Catálogo de cada tecnologia/ferramenta no formato **O que é · Por que usamos · Como configuramos ·
Onde no código**. Caminhos são relativos à raiz do repositório.

---

## Java 21 (→ 25) + Virtual Threads

- **O que é**: a linguagem/plataforma. *Virtual threads* (Project Loom, GA no Java 21) são threads
  leves gerenciadas pela JVM; bloquear em I/O custa quase nada (a carrier thread é liberada).
- **Por que usamos**: a API **bloqueia aguardando** o evento de resultado. Com virtual threads,
  milhares de requisições podem esperar simultaneamente sem esgotar threads de plataforma.
- **Como configuramos**: alvo da toolchain centralizado em uma única propriedade
  (`javaLanguageVersion`) — basta trocar para `25` e atualizar as imagens base. O handler do POST
  roda no executor `BLOCKING` da Micronaut, que no JDK 21+ é backed por virtual threads.
- **Onde no código**: [`gradle.properties`](../gradle.properties),
  [`build.gradle`](../build.gradle) (toolchain), `@ExecuteOn(TaskExecutors.BLOCKING)` em
  `api-service/src/main/java/com/example/payments/api/controller/PaymentSimulationController.java`.

> Detalhe importante: virtual threads tornam a **espera barata**, mas **não** limitam carga no Core.
> Quem faz isso é Kafka + Outbox + rate limiter. Ver [11](11-resiliencia-e-tradeoffs.md).

---

## Micronaut (framework)

- **O que é**: framework JVM com DI em tempo de compilação (sem reflexão pesada), startup rápido,
  módulos para HTTP, Kafka, Data, Redis, métricas e tracing.
- **Por que usamos**: integra todos os pilares (HTTP, Kafka, Redis, Postgres, OTel, Prometheus) com
  pouca cerimônia e suporte nativo a virtual threads.
- **Como configuramos**: plugin `io.micronaut.application` (serviços) e `io.micronaut.library`
  (`common`); configuração em `application.yml` por serviço.
- **Onde no código**: `*/build.gradle`, `*/src/main/resources/application.yml`,
  `*/src/main/java/.../Application.java`.

---

## Gradle (build multi-projeto)

- **O que é**: ferramenta de build. O repositório é um **monorepo** com 4 subprojetos.
- **Por que usamos**: build incremental, toolchains de Java, geração de código Avro, e um único
  ponto para versões.
- **Como configuramos**: subprojetos em [`settings.gradle`](../settings.gradle); versões em
  [`gradle.properties`](../gradle.properties); config comum em [`build.gradle`](../build.gradle).
- **Onde no código**: raiz + `*/build.gradle`. Wrapper: `./gradlew`.

---

## Apache Kafka (KRaft)

- **O que é**: plataforma de streaming/mensageria por tópicos particionados. KRaft = modo sem
  ZooKeeper.
- **Por que usamos**: **buffer** entre API e Core (absorve rajada), **backpressure** natural,
  desacoplamento e entrega *at-least-once*.
- **Como configuramos**: broker único KRaft no compose; tópicos criados pelo `kafka-init`
  (3 partições); particionamento por **`requestId`** (ordering por simulação); listener interno
  (`kafka:9092`) e externo (`localhost:29092`).
- **Onde no código**: serviço `kafka`/`kafka-init` em [`docker-compose.yml`](../docker-compose.yml);
  nomes em `common/src/main/java/com/example/payments/common/events/Topics.java`;
  producers/consumers Micronaut em `*/kafka/*`.

Tópicos: `payment.simulation.requested`, `…core.command`, `…core.response`, `…completed`,
`…failed`, `…dlq`. Ver [08](08-eventos-e-contratos.md).

---

## Apache Avro

- **O que é**: formato de serialização binário com schema. Schema define os campos e habilita
  evolução compatível.
- **Por que usamos**: contrato **forte** dos eventos no Kafka (em vez de JSON solto) e base para
  versionamento.
- **Como configuramos**: schemas `.avsc` (um por evento, envelope inline + payload tipado);
  o plugin Gradle `com.github.davidmc24.gradle.plugin.avro` gera as classes em build.
  Montantes monetários são **strings decimais** (evita problemas de ponto flutuante); `occurredAt`
  é epoch millis.
- **Onde no código**: [`common/src/main/avro/`](../common/src/main/avro);
  `common/build.gradle` (plugin); tradução POJO↔Avro em
  `common/src/main/java/com/example/payments/common/mapping/AvroMapper.java`.

---

## Apicurio Schema Registry

- **O que é**: registro de schemas (Avro/Protobuf/JSON Schema) com API compatível com a do Confluent.
- **Por que usamos**: armazena/serve os schemas Avro e valida **compatibilidade** na evolução.
  Escolhemos Apicurio porque os serdes do Confluent **não estão no Maven Central** (e o repositório
  deles estava bloqueado no ambiente) — o serde Apicurio está no Central.
- **Como configuramos**: o `AvroSerde` usa `AvroKafkaSerializer/Deserializer` com
  **headers desabilitados** → o *schema id* fica **embutido no payload**. Isso torna os bytes
  **auto-descritivos**, o que permite o SBUS guardá-los na outbox e republicá-los intactos.
  URL via `apicurio.registry.url`.
- **Onde no código**: `common/src/main/java/com/example/payments/common/kafka/AvroSerde.java`;
  serviço `apicurio-registry` em [`docker-compose.yml`](../docker-compose.yml);
  `apicurio.registry.url` em `*/src/main/resources/application.yml`.

---

## Redis (cliente Lettuce)

- **O que é**: armazenamento chave-valor em memória, com pub/sub.
- **Por que usamos** (só na API): guardar **status/resultado** temporário por `requestId`,
  o mapa de **idempotência**, e **coordenar** waiters entre múltiplas instâncias via **pub/sub**.
- **Como configuramos**: `micronaut-redis-lettuce`; conexão obtida **lazy** (app sobe mesmo com
  Redis fora); chaves com TTL; canal pub/sub para acordar quem está esperando.
- **Onde no código**: `api-service/.../redis/RedisStatusStore.java`,
  `api-service/.../coordination/ResponseCoordinator.java`; `redis.uri` em
  `api-service/src/main/resources/application.yml`. Detalhes em [09](09-dados-redis-postgres.md).

---

## PostgreSQL

- **O que é**: banco relacional.
- **Por que usamos** (no SBUS): fonte **durável** do estado da simulação, da **outbox** e dos
  registros de idempotência. É o que garante que o resultado não se perde.
- **Como configuramos**: datasource Hikari; `stringtype=unspecified` na URL para o driver fazer cast
  de `String`→`jsonb`; coluna `payload` da outbox é `bytea` (bytes Avro).
- **Onde no código**: `datasources` em `sbus-service/src/main/resources/application.yml`;
  entidades em `sbus-service/.../domain/*`. Detalhes em [09](09-dados-redis-postgres.md).

---

## Micronaut Data JDBC

- **O que é**: camada de persistência leve (repositórios gerados em compilação, sem ORM pesado).
- **Por que usamos**: CRUD tipado + queries nativas para o padrão outbox (incl. `FOR UPDATE SKIP LOCKED`).
- **Como configuramos**: `@JdbcRepository(dialect = POSTGRES)`; `@Query(nativeQuery = true)` para
  claim/reclaim/purge da outbox.
- **Onde no código**: `sbus-service/.../repository/*` (ex.: `OutboxEventRepository.java`).

---

## Flyway (migrations)

- **O que é**: versionamento de schema do banco via scripts SQL ordenados.
- **Por que usamos**: schema reproduzível e versionado; roda no boot do SBUS.
- **Como configuramos**: scripts `V1..V5` aplicados automaticamente.
- **Onde no código**: [`sbus-service/src/main/resources/db/migration/`](../sbus-service/src/main/resources/db/migration);
  `flyway` em `sbus-service/src/main/resources/application.yml`.

---

## Outbox Pattern

- **O que é**: técnica para resolver o **dual-write** (gravar no banco *e* publicar no broker
  atomicamente). Grava-se o evento numa tabela na **mesma transação** do estado; um publicador
  assíncrono envia ao broker depois.
- **Por que usamos**: garante publicação confiável mesmo se o Kafka estiver indisponível no commit;
  mantém o **Core agnóstico** (o outbox fica no SBUS).
- **Como configuramos**: tabela `outbox_event`; publicação **fora da transação** via *claim/lease*
  (`PENDING→IN_PROGRESS→PUBLISHED|FAILED`); `OutboxReaper` recupera presos; `OutboxHousekeeping`
  purga publicados.
- **Onde no código**: `sbus-service/.../outbox/*` e `sbus-service/.../domain/OutboxEvent.java`.
  Detalhes em [06](06-sbus-service.md).

---

## Rate limiting distribuído (Redis)

- **O que é**: limitador de taxa **global** entre instâncias, implementado sobre Redis (janela fixa
  via script Lua atômico), com fallback local se o Redis cair.
- **Por que usamos**: **proteger o Core** (limita publicações de `core.command`) e **admissão da API**
  (rejeita rajada com `429`). Um limiter por instância permitiria `N × limite` no agregado.
- **Como configuramos**: `RedisRateLimiter` recebe um *supplier* de comandos Redis + nome/limite/janela.
- **Onde no código**: `common/src/main/java/com/example/payments/common/ratelimit/RedisRateLimiter.java`;
  factories `sbus-service/.../config/RateLimiterFactory.java` (Core) e
  `api-service/.../config/ApiRateLimiterFactory.java` (+ `filter/ConcurrencyLimitFilter.java`, 429).

## Segurança (API key → JWT/OAuth2)

- **O que é**: autenticação dos endpoints de negócio via header `X-API-Key` (exemplo do PoC).
- **Por que usamos**: não deixar a API aberta; base para evoluir a JWT/OAuth2 + mTLS.
- **Como configuramos**: `payment.security.enabled` + `payment.security.api-keys`; filtro retorna `401`
  problem+json; health/metrics/swagger ficam livres.
- **Onde no código**: `api-service/.../filter/ApiKeyFilter.java`, `.../config/SecurityProperties.java`.
  Caminho de produção em [15 Prontidão para produção](15-prontidao-producao.md).

---

## OpenTelemetry (OTel)

- **O que é**: padrão de instrumentação para **traces** e métricas; propaga contexto via header
  W3C `traceparent`.
- **Por que usamos**: rastrear uma simulação ponta a ponta (HTTP→Kafka→SBUS→Kafka→API), inclusive
  através do broker.
- **Como configuramos**: instrumentação HTTP e Kafka da Micronaut; exporter OTLP para o Collector;
  o `traceparent` é guardado/replayado pela outbox. Profile `dev` desliga o export (sem collector).
- **Onde no código**: deps `micronaut-tracing-opentelemetry-*` em `*/build.gradle`; bloco `otel` em
  `*/application.yml`; `application-dev.yml`.

---

## OpenTelemetry Collector

- **O que é**: processo que recebe telemetria (OTLP) e exporta para backends.
- **Por que usamos**: ponto único de coleta; encaminha **traces** ao Jaeger e expõe métricas.
- **Como configuramos**: pipeline OTLP→Jaeger no arquivo de config.
- **Onde no código**: [`observability/otel-collector.yml`](../observability/otel-collector.yml);
  serviço `otel-collector` no compose.

---

## Jaeger

- **O que é**: backend e UI de **tracing distribuído**.
- **Por que usamos**: visualizar o trace de uma simulação atravessando os serviços.
- **Como configuramos**: all-in-one com OTLP habilitado; UI em `:16686`.
- **Onde no código**: serviço `jaeger` em [`docker-compose.yml`](../docker-compose.yml).

---

## Prometheus

- **O que é**: banco de séries temporais que faz *scrape* de métricas e avalia alertas.
- **Por que usamos**: coletar métricas dos serviços e disparar alertas (outbox, DLQ, latência…).
- **Como configuramos**: micrometer expõe `/prometheus`; `prometheus.yml` faz scrape;
  `alerts.yml` define regras.
- **Onde no código**: [`observability/prometheus.yml`](../observability/prometheus.yml),
  [`observability/alerts.yml`](../observability/alerts.yml); dep `micronaut-micrometer-registry-prometheus`.
  Lista de métricas em [10](10-observabilidade.md).

---

## Grafana

- **O que é**: visualização de métricas (dashboards).
- **Por que usamos**: painéis de API, SBUS, Outbox, Kafka, Redis e PostgreSQL.
- **Como configuramos**: datasource Prometheus e dashboards **provisionados** automaticamente.
- **Onde no código**: [`observability/grafana/`](../observability/grafana); serviço `grafana` no compose.

---

## Logback + JSON estruturado

- **O que é**: framework de logging; com `logstash-logback-encoder` os logs saem em **JSON**.
- **Por que usamos**: logs correlacionáveis (cada linha carrega `requestId`, `correlationId`,
  `causationId`, `traceId`, `eventType`, `topic`, `partition`, `offset`, `status` via MDC).
- **Como configuramos**: `logback.xml` por serviço; o MDC é populado nos consumers/serviços.
- **Onde no código**: `*/src/main/resources/logback.xml`; `sbus-service/.../support/Mdc.java`.

---

## Docker Compose

- **O que é**: orquestra os contêineres locais (infra + serviços).
- **Por que usamos**: subir o stack inteiro com um comando.
- **Como configuramos**: healthchecks, `depends_on`, build multi-stage por serviço, criação de tópicos.
- **Onde no código**: [`docker-compose.yml`](../docker-compose.yml), `*/Dockerfile`. Ver [12](12-execucao-e-operacao.md).

---

## Testcontainers

- **O que é**: sobe dependências reais (Kafka, Postgres, Redis, Apicurio) em contêineres durante os testes.
- **Por que usamos**: testes de **integração** fiéis ao runtime, sem mocks de infra.
- **Como configuramos**: containers estáticos + `TestPropertyProvider` injetando URLs no contexto Micronaut.
- **Onde no código**: `*/src/test/java/.../*IT.java`. Ver [13](13-testes.md).

---

## k6

- **O que é**: ferramenta de teste de carga (scripts em JavaScript).
- **Por que usamos**: validar o comportamento sob **rajada** (200/202, 429, virtual threads).
- **Como configuramos**: cenário *constant-arrival-rate* configurável por env.
- **Onde no código**: [`load/k6-simulations.js`](../load/k6-simulations.js).

## Ver também
- [05 API](05-api-service.md) · [06 SBUS](06-sbus-service.md) · [10 Observabilidade](10-observabilidade.md)
