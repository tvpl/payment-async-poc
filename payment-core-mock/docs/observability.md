# Observabilidade

## Startup e logs

Cada startup emite `boundary.classification=NON_PRODUCTION`, service e finalidade. Logs de resposta incluem status, request id e simulation id; falhas registram tópico, partição, offset e key. Payloads completos e credenciais não devem ser logados.

## Health e métricas

- readiness/liveness local: `GET /health` na porta 8082;
- métricas Prometheus: `GET /prometheus`;
- métricas Micronaut habilitadas com step de 10 segundos.

Esses endpoints são probes do simulador. Health verde não certifica Kafka, Registry, fluxo ponta a ponta ou capacidade de uma dependência externa.

## Traces

O serviço exporta OTLP para o endpoint configurado e preserva o trace-id W3C do comando na resposta. O producer do tópico de resposta é excluído da instrumentação automática para impedir substituição do header explicitamente encaminhado.

## Ownership

Configuração de collector, Jaeger, Prometheus e Grafana pertence ao sandbox. Alertas e SLOs de serviços produtivos pertencem aos owners correspondentes; esta raiz não declara SLO.
