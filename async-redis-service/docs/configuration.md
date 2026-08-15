# Configuração

`application.yml` contém os defaults locais sob `async.redis.*` e `redis.uri`. Toda propriedade é sobrescrevível por variável de ambiente (convenção padrão do Micronaut: `async.redis.pool-max-total` -> `ASYNC_REDIS_POOL_MAX_TOTAL`); as mais operacionais têm alias explícito no YAML (`ASYNC_LATENCY_MIN_MS`, `ASYNC_INSTANCE_ID`, `ASYNC_STREAM_MAXLEN`, `ASYNC_RETENTION_ALERT_THRESHOLD`, `ASYNC_MAX_DELIVERIES`, `ASYNC_ADMISSION_LIMIT`, `ASYNC_POOL_MAX_TOTAL`).

`AsyncRedisProperties` valida no startup: `status-ttl >= result-ttl` (senão um job terminado vira indistinguível de desconhecido assim que o status expira), `status-ttl >= idempotency-ttl` (AUD-20 — senão a reserva de idempotência sobrevive ao próprio status, e uma repetição dentro da janela da reserva resolve contra uma chave de status que já não existe mais), `pool-max-total > 0` e `pool-max-wait` positivo (sem eles não há orçamento de backpressure), `reclaim-lease > reclaim-interval` (senão dois workers podem reclamar o mesmo turno), `connect-backoff-min/max` positivos e coerentes, e `retention-alert-threshold` em `(0, 1]`. Configuração incoerente recusa startup.

`ASYNC_REDIS_SECURITY_ENABLED`/`ASYNC_REDIS_SECURITY_API_KEYS` controlam a autenticação `X-API-Key` dos endpoints de job (padrão: habilitada, com a chave de desenvolvimento `dev-key-change-me`). Em `MICRONAUT_ENVIRONMENTS=prod`, `ProductionAcceptanceGuard` recusa o startup se autenticação estiver desligada, a chave for a de desenvolvimento ou estiver em branco, `idempotency-required` estiver falso, ou `admission-limit-per-sec <= 0` (RED-08).

`.env.example` não contém credencial real. O `.env` local é ignorado e produção recebe segredos pelo mecanismo do ambiente.

O build da imagem é standalone: não recebe contexto BuildKit de outra fronteira, porque este serviço não depende de `payment-contracts` nem de nenhuma outra raiz do workspace.
