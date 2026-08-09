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
- **Phase / Task**: Execute in progress — Phase 6 (`payment-api`) complete (T31–T37); Phase 7 (`async-redis-service`, T38–T45) next. Running under sub-agent phase-batching (user-approved): Batch 1 (T34–T37) done, Batch 2 (T38–T45), Batch 3 (T46–T53), Batch 4 (T54–T60) queued, batches run strictly sequentially, feature-level Verifier dispatches after Batch 4.
- **Completed**: T1–T37. T31–T33 landed on top of `7de39fb` (pre-existing non-atomic `payment-api` extraction); each task reconciled and audited only against its own Done-when. T34–T37 landed as four atomic commits (`4a757d9`, `839c623`, `17bc876`, `c058186`) via a dispatched batch-worker sub-agent. Full gate (`./gradlew test -PwithIT --no-daemon` with `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exported) passes **115/115** against real Kafka/Redis/Apicurio Testcontainers (baseline was 55 after T33). T34 put publish outcome (`PUBLISHED`/`PUBLISH_FAILED` + lease) on the idempotency reservation itself so a crashed/failed publish resumes under the same requestId instead of orphaning it past the TTL, and stopped replay from fabricating a `PROCESSING` claim the API never observed from Core; the state lives on the API-owned reservation rather than the published `payment-contracts` `SimulationStatus` enum, since mutating a cross-boundary published artifact (AD-002) for an API-internal concern was rejected — reasoning recorded in `payment-api`'s ADR-0001. T35 moved `MDC.clear()` into a `finally` (was unreachable on the exception path, leaking request identity across reused threads), dropped waiter registrations on shutdown, and added timeout/circuit/service-identity to the SBUS fallback client. T36 fixed the response consumer silently acking undecodable/unprocessable records (auto-commit swallowed decode failures) and a late contradictory `FAILED` overwriting an already-stored `COMPLETED`; now `SYNC_PER_RECORD` offsets plus classified failures (poison→DLQ with original bytes, apply-failure→retry then DLQ, codec-capacity→rethrow for redelivery). T37 fixed the Redis-outage rate-limiter fallback granting **every** instance the full global budget (a 4-instance fleet admitted 4× the approved burst during an outage) — now falls back to `limit/instances`; added per-tenant budget with hashed identity; shipped the full `payment-api/ops` release package (image, app-only Compose, CI+SBOM+scan, docs, ADR-0001, runbooks), mirroring T30's `payment-sbus` template. Runtime smoke needing the sandbox network up, and the load/capacity report, are recorded `NOT_RUN` (not assumed green) in `payment-api/docs/operations.md` and `docs/testing.md`. Process deviation: `spec.md` traceability for PAY-03/06/09/10 was updated once in the T37 commit rather than per-task in T34–T36 (disclosed in `tasks.md` T37; no gate affected). Details and file:line evidence in `tasks.md` T34–T37.
- **In-progress**: none
- **Next step**: Dispatch Batch 2 — Phase 7 `async-redis-service`, T38 (relocate to standalone build) through T45 (production release package), 8 tasks, strictly sequential (each depends on the previous).
- **Blockers**: none currently. Docker Desktop and the `/sandbox` shared infra were started this session (sandbox `.env` generated locally with random credentials, gitignored, not committed) and are still running.
- **Uncommitted files**: none after this session's commits (see below)
- **Branch**: `feature/optimize-eda`
