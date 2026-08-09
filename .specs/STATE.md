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
- **Phase / Task**: Execute in progress — Phase 6 (`payment-api`) complete (T31–T37). Phase 7 (`async-redis-service`): T38, T39, T40 complete; **T41 mid-flight, paused, NOT gate-passed**. Running under sub-agent phase-batching (user-approved): Batch 1 (T34–T37) done, Batch 2 (T38–T45) in progress, Batch 3 (T46–T53) and Batch 4 (T54–T60) queued, batches run strictly sequentially, feature-level Verifier dispatches after Batch 4.
- **Completed**: T1–T40. T31–T33 landed on top of `7de39fb` (pre-existing non-atomic `payment-api` extraction). T34–T37 landed as four atomic commits (`4a757d9`, `839c623`, `17bc876`, `c058186`) — `payment-api` full gate passes 115/115 (see prior entry below for detail, unchanged). T38 (`862da0f`) extracted `async-redis-service` to a standalone Gradle root in place (kept `include` in root `settings.gradle` per MIG-02 until equivalence is proven, same as T19/T24/T31) — Redis stays a runtime dependency reached via `localhost:6379` (AD-003 sandbox ownership), not Testcontainers, because both Docker-socket provider strategies fail `BadRequestException 400` against the installed Docker Desktop 29.3.1 in this environment (verified, not assumed) — 10/10 tests. T39 (`79e330a`) fixed: polling returning `404 UNKNOWN` for an in-progress job (indistinguishable from a job that never existed), status persisted only after processing instead of before enqueue, zero idempotency (client retry silently double-enqueued), and no AuthN/prod guard — added `JobStatusStore.createProcessing` before enqueue, `JobFingerprint` + `SET NX PX` reservation (same pattern as `payment-api` T33's `RedisStatusStore.reserve`), `ApiKeyFilter`, `ProductionAcceptanceGuard` — 39/39 tests. T40 (`eb3cf3e`) bounded the waiter pool/admission per RED-02/CAP-03 — full gate evidence in `tasks.md`.
- **In-progress**: **T41** ("Tornar workers únicos e reconectáveis", RED-04/RED-05) — checkpointed uncommitted-work-in-progress at commit `1a59426` (`chore(async-redis): checkpoint in-progress t41 worker identity refactor`, explicitly NOT a completed task commit, `tasks.md` T41 has no `Status: Complete` marker). `JobWorker` moved from `queue/` into a new `worker/` package alongside new `WorkerIdentity`, `ReclaimCoordinator`, `WorkerReadiness`/`WorkerReadinessIndicator`, and 5 new test files (`WorkerIdentityUnitTest`, `WorkerConsumerIdentityIT`, `ReclaimCoordinatorIT`, `WorkerRecoveryIT`, `RedisGate`). **Known bug at pause time, unresolved**: a constructor side-effect left the Redis client `null` in at least one test path — the worker's own last message before being stopped was "Fixing the production code rather than the tests" for exactly this. Full gate has NOT been run green since this refactor started; do not trust anything in the `worker/` package as correct without re-verifying. This was a deliberate user-requested pause (session ending), not an agent failure or blocker.
- **Next step**: Resume Batch 2 by dispatching a fresh batch-worker for the remainder of Phase 7. First have it read commit `1a59426` and this Handoff entry, then decide whether to fix forward the T41 WIP (starting from the known null-client constructor bug) or revert `git revert 1a59426`/discard and restart T41 clean — either is acceptable engineering judgment, but T41 must end in exactly one atomic `Complete`-gated commit before T42 starts. Then continue T42 (atomic result release before ACK) → T43 (poison/DLQ durability) → T44 (PEL-safe retention) → T45 (production release package). After Batch 2 finishes: Batch 3 (Phase 8, `feature-control`, T46–T53), Batch 4 (Phase 9, workspace closeout, T54–T60), then the feature-level Verifier.
- **Blockers**: none currently. Docker Desktop and the `/sandbox` shared infra were started earlier this session (sandbox `.env` generated locally with random credentials, gitignored, not committed) and should still be running — verify before resuming, since Redis reachability at `localhost:6379` gates T39–T45's full test suite (per T38's note above).
- **Uncommitted files**: none — working tree is clean as of commit `1a59426` (verified with `git status --porcelain` immediately before this handoff was written).
- **Branch**: `feature/optimize-eda`
