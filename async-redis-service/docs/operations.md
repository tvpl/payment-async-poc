# Operação

## Pré-requisitos

Sandbox saudável com Redis e a rede `payment-sandbox`; variáveis produtivas vindas do secret manager (`ASYNC_REDIS_SECURITY_API_KEYS` no mínimo).

## Startup e readiness

Falha de configuração encerra o startup (ver [configuração](configuration.md)). `/health/readiness` fica down até pelo menos um worker ler do consumer group com sucesso (RED-05); `/health/liveness` só informa que o processo está de pé. Verifique `async_stream_length`, `async_pending`, workers consumindo e a idade da DLQ antes de liberar tráfego.

## Worker, reclaim e DLQ

Use os [runbooks locais](../ops/runbooks/README.md) para outage de Redis, backlog de retenção e itens na DLQ. Nunca edite uma entrada da DLQ manualmente nem force um `XACK`; a causa (`dlqReason`) já vem preservada no corpo do item.

## Rollback

```bash
docker compose --env-file .env down
IMAGE_TAG=<tag-anterior> docker compose --env-file .env up --build --wait async-redis
curl --fail http://localhost:8084/health/liveness
```

Nenhum estado de aplicação é migrado por versão; o rollback é apenas trocar a imagem. Chaves de resultado/status/idempotência já criadas continuam válidas até seu próprio TTL.
