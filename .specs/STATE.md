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
- **Phase / Task**: Execute in progress — Phase 6 (`payment-api`, T31–T37) complete, Phase 7 (`async-redis-service`, T38–T45) complete, Phase 8 (`feature-control`, T46–T53) complete. Only **Phase 9 (workspace closeout, T54–T60) remains** — reconciled 2026-08-10 against `tasks.md` (all T1–T53 carry `Status: Complete`, validated clean by `validate_tasks.py`) and `git log`, since the previous Handoff entry below (T41 mid-flight / Batch-2 orchestration) was stale relative to actual repo state.
- **Completed**: T1–T53. Phase 7 finished beyond what the prior Handoff recorded: T41 (worker identity/reclaim, `a2f270d` "recover unique stream workers"), T42 (`e6959f9` "atomically release job results"), T43 (`e12ab20` "make poison handling durable"), T44 (`b493837` "preserve pending stream payloads"), T45 (`846190f` "add independent production package"). Phase 8 (`feature-control`) landed as eight commits from `46e9808` ("regroup library and examples", T46) through `f542b56` ("isolate nonproduction examples", T53), covering flag validation (T47), stale/stampede bounds (T48), pubsub/convergence (T49), CAS + audit atomicity (T50), cardinality/PII limits (T51), publication/compatibility certification (T52), and example security/docs closure (T53). Detail on T1–T40 is preserved in the prior Handoff entry further below, unchanged.
- **In-progress**: none — working tree is clean, no task is mid-flight.
- **Next step**: Start Phase 9 (T54–T60, workspace closeout — 7 tasks, fits inline per the skill's ≤~8 sub-agent threshold, no offer needed unless the user wants batching anyway). Neither `scripts/e2e/`, `scripts/observability/`, nor `validation-evidence/` exist yet, so none of T54–T60 has started. Order: T54 (artifact-only E2E equivalence) → T55 (payment failure matrix) → T56 (async-redis failure matrix) → T57 (capacity gate, 10k/min×15m + 20k/min spike) → T58 (observability validation) → T59 (remove verified legacy layout) → T60 (release gate + final evidence). `/sandbox` infra (Kafka/Redis/PostgreSQL/Registry/observability, AD-003) must be brought up first — `docker ps` currently shows no running containers. After T60, dispatch the feature-level Verifier per the skill's always-on rule.
- **Blockers**: none currently, but unverified this session: Docker Desktop / `/sandbox` shared infra availability (no containers currently running — must `docker compose up` from `/sandbox` before T54+), and whether a `/sandbox/.env` with credentials still exists locally (gitignored, was generated ad hoc in an earlier session).
- **Uncommitted files**: none — working tree is clean (`git status --porcelain` empty).
- **Branch**: `claude/tlc-spec-driven-plan-67avkp` (13 commits ahead of `feature/optimize-eda`, which itself was already merged to `main` via PR #4; this branch carries the Phase 7 tail + all of Phase 8 not yet on `main`).

### Prior Handoff (superseded 2026-08-10, kept for T1–T40 detail)
