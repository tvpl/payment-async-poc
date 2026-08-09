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
- **Phase / Task**: Execute in progress — Phase 6 / T31 and T32 complete; T33 (idempotent reservation with fingerprint) next
- **Completed**: T1–T32. Both landed on top of commit `7de39fb`, a pre-existing non-atomic commit that had already dropped a full, non-task-gated `payment-api` (spanning T31-through-T37 scope); each task here was reconciled and audited only against its own Done-when (user decision on T31, same approach carried into T32). `feature-control` is published to the local Maven repo (build output, not committed). Full gate (`./gradlew test -PwithIT --no-daemon` with `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exported) passes **34/34** against real Kafka/Redis/Apicurio Testcontainers: T31 fixed 3 pre-existing gaps (missing `AvroSerde` DI factory, `TestPropertyProvider` not overriding `application.yml` placeholders, missing Serde metadata for framework-free contract types); T32 added `ProductionSecurityGuard`, excluded `DevTokenController` from the prod bean graph (`@Requires(notEnv="prod")`), moved the HS256 signing secret out of the always-on base config into `application-dev.yml` only, tightened `intercept-url-map` per-route, and locked down management endpoints (`endpoints.all.enabled=false`, only health/prometheus, matching T25/SBUS's already-audited policy). Details and file:line evidence in `tasks.md` T31/T32.
- **In-progress**: none
- **Next step**: T33 — idempotent reservation with fingerprint in `payment-api` (atomically associate key/requestId/canonical fingerprint/state machine; same key+payload replays identity, divergent payload returns conflict with zero publish; ≥10 new tests). Note: commit `7de39fb` already contains a `RedisStatusStore`/`reserveIdempotency` implementation — audit it against T33's own Done-when before trusting it, same as T31/T32.
- **Blockers**: none currently. Docker Desktop and the `/sandbox` shared infra were started this session (sandbox `.env` generated locally with random credentials, gitignored, not committed) and are still running.
- **Uncommitted files**: none after this session's commits (see below)
- **Branch**: `feature/optimize-eda`
