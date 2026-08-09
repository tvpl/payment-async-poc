# Observabilidade

Monitore consumer lag, transições, duplicatas, outbox por estado/idade, claims recuperados, retry due, falhas de publish e DLQ não confirmada. O alerta [`recoverable-dlq.yml`](../ops/alerts/recoverable-dlq.yml) dispara enquanto houver item pendente antigo.

Logs estruturados propagam request, correlação, causação e trace id. Não registre token, payload sensível, idempotency key integral ou conteúdo de `.env`.

Dashboards e alertas de produto pertencem a `payment-sbus/ops`; o sandbox somente os monta no ambiente local.
