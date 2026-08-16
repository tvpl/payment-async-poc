# Observabilidade

O profile `observability` sobe Jaeger, OTel Collector, Prometheus, Grafana e exporters de Kafka,
Redis e PostgreSQL. `make verify-profiles` valida readiness/query do Prometheus, serviços do
Jaeger, health do Grafana e do Kafka UI.

## Escopo

O sandbox possui o pipeline de coleta e visualização (coletor, backend de traces, Prometheus,
Grafana, exporters de infra). Métricas de negócio, logs estruturados e instrumentação de
aplicação pertencem a cada app (ver `Ver também`). Dashboards e alertas de produto **não são
copiados** para esta fronteira: entram só como referência versionada no manifest
[`observability/application-assets.json`](../observability/application-assets.json), com
`owner`, `version` e `path`.

## Tracing (OpenTelemetry → Jaeger)

`otel-collector` recebe OTLP (`grpc :4317`, `http :4318`), processa em batch e exporta:
- traces para o **Jaeger** (`otlp/jaeger`, UI em `:16686`);
- métricas do próprio coletor para o **Prometheus** (`:8889`, scrape job `otel-collector`).

Config: [`observability/otel-collector.yml`](../observability/otel-collector.yml). Cada app
aponta seu exporter OTLP para o coletor pela própria configuração (`OTEL_EXPORTER_OTLP_ENDPOINT`
ou equivalente); o sandbox não instrumenta código de aplicação, só hospeda o coletor e o backend.

## Métricas (Prometheus)

[`observability/prometheus.yml`](../observability/prometheus.yml) faz scrape de duas famílias de
alvos:

| Família | Alvos | Mecanismo |
|---|---|---|
| Infra do sandbox | `prometheus` (self), `otel-collector:8889`, `redis-exporter:9121`, `postgres-exporter:9187`, `kafka-exporter:9308` | `static_configs` |
| Aplicações | serviços registrados em [`observability/application-targets.json`](../observability/application-targets.json) | `file_sd_configs`, refresh 10s, `metrics_path: /prometheus` (convenção Micronaut de todos os apps do workspace) |

### Exporters de infra

| Exporter | Alvo | Métricas (ex.) |
|---|---|---|
| `redis-exporter` | Redis do sandbox | `redis_connected_clients`, `redis_commands_processed_total`, `redis_memory_used_bytes` |
| `postgres-exporter` | PostgreSQL do sandbox | `pg_up`, `pg_stat_database_numbackends`, `pg_stat_database_xact_commit` |
| `kafka-exporter` | Kafka do sandbox | `kafka_consumergroup_lag`, `kafka_topic_partition_current_offset` |

Nenhum dos três expõe porta no host: são scrapeados só dentro da rede `payment-sandbox`.

### Gaps de scrape conhecidos

Registrados em [`application-assets.json`](../observability/application-assets.json), por app:

| Job | Status |
|---|---|
| `payment-simulation-api`, `payment-sbus` | gap: `/prometheus` exige bearer JWT autenticado; sem credencial de scrape de longa duração provisionada ainda, então o job aparece com `up == 0` até o gap fechar |
| `async-redis-service` | scrape limpo, mas **sem autenticação**: `ApiKeyFilter` cobre só `/jobs/**` e o boundary não tem `micronaut-security` no classpath, então `/prometheus` é anônimo — gap de exposição, não de credencial |
| `payment-core-mock` | ok: `/prometheus` sem auth (`NON_PRODUCTION`), scrape limpo |

Pendência aberta: provisionar credencial de scrape de longa duração para `payment-api` e
`payment-sbus` (hoje `up == 0`), e decidir a proteção do `/prometheus` do `async-redis-service`.
Ao fechar, atualizar esta tabela e o `scrape_auth_status` de `observability/application-assets.json`.

### Alertas

`rule_files: /etc/prometheus/rules/*.yml` carrega regras **montadas somente leitura** (não
copiadas) a partir do `ops/alerts/` de cada app, declaradas em `compose.profiles.yml`:
`payment-api` (`api-admission-and-dlq.yml`), `payment-sbus` (`recoverable-dlq.yml`) e
`async-redis-service` (`async-redis-alerts.yml`). `payment-core-mock` não publica regras de
alerta. O conteúdo das regras é responsabilidade de cada owner.

## Dashboards (Grafana)

Datasources provisionados ([`datasource.yml`](../observability/grafana/provisioning/datasources/datasource.yml)):
**Prometheus** (default, `http://prometheus:9090`) e **Jaeger** (`http://jaeger:16686`).

O datasource Prometheus não declara `exemplarTraceIdDestinations`, então não há salto
métrica→trace por exemplar no Grafana; a correlação hoje é manual, pelo `traceId` do log.

Dashboards são **montados somente leitura** (provider `application-owned`, uma pasta por owner via
`foldersFromFilesStructure`) a partir do `ops/dashboards/` de cada app, nunca copiados:

| Owner | Dashboard |
|---|---|
| `payment-api` | API Overview, API Waiters |
| `payment-sbus` | SBUS Overview, Outbox Overview, Postgres Pool |
| `async-redis-service` | Async Redis |
| `feature-control` | Feature Decisions |
| `load` (workspace) | k6 Load |
| `sandbox` (próprio) | Infra Exporters (Redis/Postgres/Kafka), Kafka Overview |

Só os dois últimos (`sandbox/observability/dashboards/`) são de fato do sandbox; os demais
existem apenas como referência do manifest. Acesso em `:3000`
(`GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` do `.env`, ver
[configuração](configuration.md)).

## Kafka UI (inspeção de mensageria)

Profile `tools`, porta `:8088`. Resolve schemas Avro via o endpoint Confluent-compatível do
Registry (`http://registry:8080/apis/ccompat/v6`), então o payload das mensagens aparece
decodificado. Útil para acompanhar tópicos, partições e consumer group lag ao vivo.

## Verificação

`make verify-profiles` roda cinco probes: `prometheus.ready`, `prometheus.query` (`up`),
`jaeger.services`, `grafana.health` e `kafka-ui.http`. Falha se Docker estiver indisponível ou o
profile não tiver sido iniciado; nunca reporta `PASS` sem saída real.

## Ver também

- [Configuração](configuration.md) · [Arquitetura](architecture.md) · [Operação](operations.md)
- Métricas de negócio, logs estruturados e tracing de aplicação:
  [`payment-api/docs/observability.md`](../../payment-api/docs/observability.md) ·
  [`payment-sbus/docs/observability.md`](../../payment-sbus/docs/observability.md) ·
  [`async-redis-service/docs/observability.md`](../../async-redis-service/docs/observability.md) ·
  [`feature-control/docs/`](../../feature-control/docs/).
