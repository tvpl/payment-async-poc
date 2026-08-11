# Observabilidade

`GET /prometheus` expõe `async_stream_length` (backlog do stream), `async_pending` (PEL do consumer group) e `async_process_latency` (percentis 0.5/0.95/0.99 de processamento). `/health/readiness` inclui o indicador `async-redis-workers`, com `consumingWorkers`/`configuredWorkers` nos detalhes. O monitor de retenção registra `WARN` em log quando o backlog atinge `retention-alert-threshold * stream-maxlen` (RED-03) — ver o alerta [`async-redis-alerts.yml`](../ops/alerts/async-redis-alerts.yml).

Logs estruturados (Logstash encoder) incluem o nome do consumidor, o job id e o motivo de qualquer DLQ. Não registre `X-API-Key`, payload sensível ou conteúdo de `.env`.

Dashboards e alertas de produto pertencem a `async-redis-service/ops`; o sandbox só os monta no ambiente local.
