# Rollback de release

1. Identifique a última tag de imagem saudável (`IMAGE_TAG`).
2. `docker compose --env-file .env down` seguido de `IMAGE_TAG=<tag-anterior> docker compose --env-file .env up --build --wait async-redis`.
3. Confirme `curl --fail http://localhost:8084/health/liveness` e `/health/readiness`.
4. Não há schema de dados migrado entre versões; chaves de resultado/status/idempotência já gravadas continuam válidas até seu próprio TTL, e o rollback não precisa de nenhum passo de dados.
5. Registre a causa do rollback e, se aplicável, um item de acompanhamento no ADR ou runbook relevante.
