# AGENTS — gateway

Instruções para agentes que trabalham **dentro** desta fronteira. Nada aqui descreve outra raiz.

## Propósito e status

Guardrail de borda `NON_PRODUCTION`, opcional por decisão de arquitetura. As
aplicações nunca dependem dele: qualquer teste que precise só do fluxo
Edge → Sbus → serviços deve rodar **sem** esta camada.

## Invariantes

1. Nenhuma lógica de negócio vive aqui. O gateway autentica (OIDC/JWT), limita,
   corta circuito e faz proxy — só.
2. A superfície exposta é allowlist: `/payment-simulations`, `/v0/payment-simulations`
   e `/health`. `/admin`, `/auth` e `/prometheus` do Edge ficam fora de propósito.
3. O token do Keycloak **não** é encaminhado ao Edge (o provider JWT remove o
   header após validar). A autenticação de aplicação continua sendo `X-API-Key`.
4. Retry de POST no gateway só para falhas que comprovadamente não chegaram ao
   upstream (`connect-failure`, `refused-stream`). Reenvio de 5xx é proibido:
   idempotência pertence ao Edge.
5. Rate limit do gateway é mais frouxo que a admissão do Edge. O gateway corta
   abuso; o Edge protege o Core.
6. O Redis desta fronteira pertence ao Rate Limit Service e não guarda estado de
   negócio. O Redis do sandbox continua sendo do `payment-api`.
7. Imagens pinadas por tag e digest; segredos nunca commitados (`.env` com `:?`).
   Os valores `-change-me` do realm importado são deliberadamente de teste.

## Gates locais

```bash
make config
make smoke
```

## Fontes de verdade

- Comportamento do proxy: `envoy/envoy.yaml` (comentado decisão a decisão).
- Identidade de teste: `keycloak/realm-payments.json`.
- Limites: `ratelimit/config.yaml`.
- Decisões: `docs/adr/`.
