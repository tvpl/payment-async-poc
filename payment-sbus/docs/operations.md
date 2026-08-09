# Operação

## Pré-requisitos

Sandbox saudável com Kafka, PostgreSQL, Redis, Registry e rede `payment-sandbox`; contratos publicados; variáveis produtivas vindas do secret manager.

## Startup e readiness

Falha de configuração encerra o startup. Readiness deve ficar down quando uma dependência obrigatória não sustenta a garantia; liveness informa somente o processo. Verifique consumer lag, pool JDBC, Redis, Registry, outbox e idade de DLQ antes de liberar tráfego.

## Retry, DLQ e recovery

Use os [runbooks locais](../ops/runbooks/README.md). Nunca edite linha ou confirme offset manualmente sem registrar request id, tópico/partição/offset e preservar deduplicação.

## Rollback

Pare novas instâncias, restaure a imagem anterior compatível e mantenha migrations append-only. Não reverta schema aplicado. Confirme que outbox/retry/DLQ continuam drenando e que terminais existentes não mudam. Ausência de Docker ou dependência externa é `NOT_RUN`.
