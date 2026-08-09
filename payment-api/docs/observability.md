# Observabilidade

## Métricas (Prometheus, rota `/prometheus`, autenticada)

| Métrica | Tipo | Para quê |
| --- | --- | --- |
| `api_requests_total{payment_method}` | contador | taxa de chegada por método |
| `api_timeouts_total` | contador | respostas `202` por estouro do orçamento de espera |
| `api_completed_total` / `api_failed_total` | contador | desfechos terminais aplicados |
| `api_wait_latency` | timer (p50/p95/p99) | tempo bloqueado esperando o resultado |
| `api_pending` | gauge | requisições atualmente esperando |
| `api_response_retries_total` | contador | tentativas de reaplicar um evento final |
| `api_response_dead_lettered_total{stage}` | contador | eventos enviados à DLQ, por estágio |
| `api_duplicate_final_events_total` | contador | repetições que não alteraram o desfecho |

Sinais que merecem alerta estão em [../ops/alerts/api-admission-and-dlq.yml](../ops/alerts/api-admission-and-dlq.yml).

## Logs

Estruturados (`logstash-logback-encoder`), com `requestId`, `correlationId`, `traceId` no MDC
durante a submissão e, no consumo, também `eventType` e `status`. O MDC é limpo em todos os
caminhos de saída, inclusive falha de publicação e shutdown: uma thread reutilizada nunca carrega a
identidade da requisição anterior.

## Tracing

OpenTelemetry com propagação W3C. O `traceparent` é injetado na publicação Kafka pela
instrumentação do Micronaut, então o trace atravessa API → SBUS → Core. O exportador OTLP aponta
para `OTEL_EXPORTER_OTLP_ENDPOINT`.

## O que observar primeiro

1. `api_response_dead_lettered_total` acima de zero: há resultado de pagamento parado na DLQ.
2. `api_pending` crescendo com `api_wait_latency` estável: o downstream desacelerou.
3. `429` subindo com `api_requests_total` estável: um tenant está consumindo a rota.
4. `api_duplicate_final_events_total` subindo: republicação a montante, esperada após crash de SBUS.
