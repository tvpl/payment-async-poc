# STATE

## Decisions

### AD-001 — Fronteiras do workspace

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** o workspace terá sete raízes autossuficientes: `payment-contracts`, `payment-api`, `payment-sbus`, `payment-core-mock`, `feature-control`, `async-redis-service` e `sandbox`; exemplos ficam dentro de `feature-control`.
- **Rationale:** separa ownership, release e documentação sem promover demonstrações a produtos.

### AD-002 — Dependências cross-boundary

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** contratos e bibliotecas serão consumidos por coordenadas Maven versionadas; composite build será somente uma conveniência local explícita e os gates de release testarão artefatos publicados.
- **Rationale:** reproduz repositórios independentes e impede acoplamento invisível ao source tree.

### AD-003 — Ownership da infraestrutura local

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** somente `/sandbox` criará Kafka, Redis, PostgreSQL, Registry e observabilidade; aplicações usarão Composes próprios conectados a uma rede externa do sandbox.
- **Rationale:** evita infraestrutura duplicada e permite iniciar/escalar aplicações de forma independente.

### AD-004 — Estratégia de migração

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** a migração será incremental e orientada a artefatos, com equivalência e rollback por fronteira; repositórios remotos serão criados somente em iniciativa posterior.
- **Rationale:** reduz blast radius e torna regressões atribuíveis.

### AD-005 — Critério de produção

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** `payment-api`, `payment-sbus`, `payment-contracts`, `feature-control` e `async-redis-service` terão gates produtivos; `payment-core-mock`, `feature-demo` e `pilot-app` serão explicitamente `NON_PRODUCTION`. Nenhum claim de prontidão vale sem relatório datado.
- **Rationale:** separa evidência operacional de finalidade didática.

### AD-006 — Meta de capacidade e saturação

- **Status:** active
- **Date:** 2026-08-08
- **Decision:** o gate alvo será 10.000 req/min por 15 minutos e spike de 20.000 req/min por 60 segundos; excesso terá `429`, `202` ou buffering limitado, nunca perda silenciosa aceita.
- **Rationale:** transforma “milhares por minuto” em capacidade mensurável e trata limites downstream como restrição real.

## Handoff

- **Feature**: `repository-segregation-production-hardening`
- **Phase / Task**: Execute in progress — Phase 6 / T31 complete; T32 (production auth/management hardening) next
- **Completed**: T1–T31. T31's standalone `payment-api` root landed outside this skill's atomic-commit protocol as commit `7de39fb` (one non-atomic commit spanning T31-through-T37 scope); on resume it was reconciled and audited against T31's Done-when only (user decision). `feature-control` was published to the local Maven repo (needed by `payment-api`'s build, not committed — build output). Full gate (`./gradlew test -PwithIT --no-daemon` with `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exported) now passes 10/10 for real against Kafka/Redis/Apicurio Testcontainers, after fixing three pre-existing gaps (confirmed identical in the old `api-service` root, not introduced this session): missing `AvroSerde` DI factory, `ApiFlowIT`'s `TestPropertyProvider` not overriding `application.yml` placeholder defaults, and missing Serde metadata for framework-free `payment-contracts` model types. Details and file:line evidence in `tasks.md` T31.
- **In-progress**: none
- **Next step**: T32 — close production auth/management surfaces in `payment-api` (remove dev token issuer from PRD bean graph, require asymmetric JWT + issuer/audience, restrict routes/management; ≥10 new ITs). Note: commit `7de39fb` already contains partial, non-gated groundwork for T32-T37 (auth filter, idempotency store, rate limiting, response consumer) — audit each against its own task's Done-when before trusting it, the same way T31 was audited.
- **Blockers**: none currently. Docker Desktop and the `/sandbox` shared infra were started this session (sandbox `.env` generated locally with random credentials, gitignored, not committed).
- **Uncommitted files**: none after this session's commit (see below)
- **Branch**: `feature/optimize-eda`
