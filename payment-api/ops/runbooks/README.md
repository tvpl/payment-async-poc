# Runbooks — payment-api

Procedimentos owned por esta fronteira. Cada um começa pelo sinal observável, não pela hipótese.

- [admission-saturation.md](admission-saturation.md) — `429` em alta, saturação e o orçamento degradado sem Redis
- [response-dlq.md](response-dlq.md) — evento final na DLQ: diagnóstico e reprocessamento
- [release-rollback.md](release-rollback.md) — voltar para a versão anterior

Alertas correspondentes: [../alerts/api-admission-and-dlq.yml](../alerts/api-admission-and-dlq.yml).
