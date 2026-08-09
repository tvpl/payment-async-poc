# Runbook — rollback de release

## Quando

Regressão detectada após um deploy: erro técnico acima do orçamento, `429` sem causa de tráfego,
ou eventos finais indo para a DLQ logo após a subida.

## Pré-condição

A API não tem migração de schema nem estado local. O estado compartilhado (Redis) é compatível
entre versões adjacentes, então o rollback não exige nenhuma etapa de dados.

## Procedimento

1. Identifique a tag anterior conhecida como boa:
   ```bash
   docker image ls payment-api
   ```
2. Aponte o deploy para ela e suba:
   ```bash
   IMAGE_TAG=<tag-anterior> docker compose --env-file .env up -d api
   ```
3. Confirme a saúde:
   ```bash
   curl -fsS http://localhost:${API_HOST_PORT:-8080}/health/readiness
   ```
4. Verifique que `api_response_dead_lettered_total` parou de crescer e que a taxa de `429` voltou
   ao normal.
5. Reprocesse o que tiver ficado na DLQ durante a janela ruim seguindo
   [response-dlq.md](response-dlq.md).

## Não fazer

- Não derrube o Compose removendo volumes: eles pertencem ao sandbox e carregam estado compartilhado.
- Não reverta `payment-contracts` junto sem checar compatibilidade: o rollback da API é
  independente e deve continuar assim.
