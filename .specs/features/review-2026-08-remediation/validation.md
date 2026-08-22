# Review 2026-08 Remediation Validation

**Date**: 2026-08-22
**Spec**: `.specs/features/review-2026-08-remediation/spec.md`
**Diff range**: `b2623de..7f61ae3` (T1..T50, 65 commits including 2 merge commits from earlier PRs #63/#64)
**Verifier**: independent sub-agent (author ≠ verifier) — fresh session, no authoring context on this feature

---

## Task Completion

All 50 tasks (T1–T50) are marked done in `tasks.md`, each with a distinct commit that exists in `git log` on branch `claude/tlc-spec-driven-continuation-f7t6hp`. Spot-checked the full list against `git log --oneline`: every commit subject listed under `**Commit**:` in `tasks.md` has a matching real commit (a handful differ from the planned subject by minor wording only — e.g. T5 planned `Idempotency-Key obrigatória e limites de entrada` vs. actual `exige Idempotency-Key e limita entrada` — same task, cosmetic rewording, not a defect).

| Phase | Tasks | Status |
| --- | --- | --- |
| 1–9 | T1–T50 | ✅ Done, all commits present |

`git status --porcelain` on the real tree: clean, both before and after this verification (confirmed via the discrimination-sensor isolation check below).

---

## Spec-Anchored Acceptance Criteria (sampled, evidence-or-zero)

Full 47-requirement re-derivation was not exhaustively performed line-by-line for every AC (see "What was not independently re-verified" below); the sample below focuses on the two critical findings (TEN-*, IDEM-*) plus one AC per other P1 story, each traced to real, non-vacuous code and tests.

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion expression | Result |
| --- | --- | --- | --- |
| IDEM-01: missing `Idempotency-Key` → 400 | `problem+json` 400, no domain I/O | `payment-api/src/main/java/com/example/payments/api/controller/PaymentSimulationController.java:68-73` guards before `service.submit(...)`; `payment-api/src/test/java/com/example/payments/api/CrossTenantIsolationIT.java:135-148` `missingIdempotencyKeyIs400ForEitherTenant` asserts `HttpStatus.BAD_REQUEST` | ✅ PASS |
| IDEM-02: key >128 chars or outside `[A-Za-z0-9_-]+` → 400 | 400 before I/O | `payment-api/.../controller/IdempotencyKeyValidation.java:19-24` (`MAX_LENGTH=128`, `Pattern.compile("[A-Za-z0-9_-]+")`); `payment-api/src/test/java/com/example/payments/api/controller/IdempotencyKeyValidationUnitTest.java` (killed by discrimination sensor, see below) | ✅ PASS |
| TEN-01: declared `X-Tenant-Id` outside binding → 403 | 403 `problem+json` | `payment-api/.../tenant/TenantResolver.java:58-60` (`bound.contains(declared) ? Effective : Forbidden`); `TenantResolverUnitTest.java:32-44` `declaredTenantOutsideTheBindingIsForbidden`/`declaredTenantBoundToAnotherCredentialIsForbidden` — both killed by discrimination sensor mutant #1 | ✅ PASS |
| TEN-02/TEN-03: single-tenant binding uses it / multi-tenant binding demands header | `Effective` vs `MissingHeader` | `TenantResolver.java:53-57`; `TenantResolverUnitTest.java:48-67` | ✅ PASS |
| TEN-04: tenant B replay of A's key+payload → new op, no A's requestId/409 leaks | independent `requestId`, no cross-tenant 409 | `payment-api/.../redis/RedisStatusStore.java:120-151` (`idemKey(tenantId, idempotencyKey)`); `CrossTenantIsolationIT.java:80-132` — `sameKeyAndPayloadAcrossTenantsProduceIndependentRequestIds`, `replayOnlyEverReturnsTheOwningTenantsOwnRequestId`, `sameKeyDivergentPayloadOnAnotherTenantNeverConflictsAgainstTheFirstTenant` (asserts `202`, not `409`) | ✅ PASS |
| TEN-06: Sbus idempotency uniqueness by `(tenant_id, idempotency_key)`, never global | composite unique constraint | `payment-sbus/src/main/resources/db/migration/V12__tenant_scope.sql:7-8` — `DROP CONSTRAINT uq_idempotency_record_key` then `ADD CONSTRAINT uq_idem_tenant_key UNIQUE (tenant_id, idempotency_key)` | ✅ PASS |
| IDEM-04: constraint-violation persistence failure → poison/DLQ, never transient retry | DLQ, sanitized reason, no payload leak | `payment-sbus/.../kafka/SimulationMessageHandler.java:164-172` (`asPoisonIfDataIntegrityViolation`, SQLState class 22/23 only); `payment-sbus/src/test/java/com/example/payments/sbus/kafka/ConstraintViolationPoisonIT.java:84-120` — injects a 200-char key directly on Kafka (bypassing the Edge), asserts `dlqStage=="poison"` (not `"retries-exhausted"`), reason contains `SQLState 22`, reason does **not** contain the offending value or `MERCHANT-001` | ✅ PASS |
| TEN-07: gateway injects `X-Tenant-Id` from JWT claim, forged header overwritten | claim wins over client-supplied header | `gateway/envoy/envoy.yaml:74-104` — `header_mutation` removes `X-Tenant-Id` before `jwt_authn`'s `claim_to_headers` re-injects it from `tenant_id` claim; `gateway/k8s/base/securitypolicy.yaml:36-38` mirrors it; `gateway/scripts/smoke.sh:72-81` asserts a forged header does not produce a 403 (i.e., is overwritten, not merely rejected) | ✅ PASS (compose+K8s parity confirmed by `python3 gateway/scripts/check-k8s-parity.py` → `OK`) |
| BUDG-04: `/health/liveness` <500ms while admission Redis is latent ≥2s | measured <500ms | `payment-api/src/test/java/com/example/payments/api/LivenessUnderRedisLatencyIT.java` exists, not re-run live in this pass (requires Docker + latency injection; deferred per environment constraints, same class of test already exercised in the T49 consolidated `-PwithIT` pass per `STATE.md`) | ⚠️ Not independently re-run (traced to file only) |
| K8S-03: kubeconform + parity script in CI | CI job fails on divergence | `.github/workflows/ci.yml:40-52` (gateway matrix leg installs kustomize+kubeconform); `gateway/Makefile:8-17` (`config` target runs both overlays through kubeconform then `check-k8s-parity.py`); `python3 -m unittest discover -s gateway/scripts` → 8/8 OK (ran live) | ✅ PASS |

**Status**: ✅ Every sampled criterion covered by real, non-vacuous evidence — no spec-precision gaps found in the sample (spec's outcome values are already precise: specific status codes, specific SQLState classes, specific constraint shape).

**What was not independently re-verified** (time/environment-bounded, not evidence of a problem, just scope disclosure): the remaining ~39 requirement IDs (BUDG-01..03, SEC-01..08 individually, RES-02..06, OBS-01/02/04/05, SCAL-01..04/06, K8S-01/02/04/05, API-01/03) were checked only via `tasks.md`'s Done-when checkboxes + the commit existing + (where cheap) a grep for the described mechanism in the diff, not via full independent re-derivation of every test assertion. The two highest-stakes stories (tenant isolation, idempotency mandatory) got full treatment per the task brief's explicit instruction; a second verification pass with more time budget should sample 3-5 more P1/P2 items outside this set.

---

## Discrimination Sensor

Tier: **P0/critical-path** (payment, auth, tenant-isolation domain) → 5 mutations, all in an isolated `git worktree` (never `git stash`), never touching the real tree. Baseline `git status --porcelain` was empty before and after both worktree sessions.

| # | File:line | Description | Killed? |
| --- | --- | --- | --- |
| 1 | `payment-api/.../tenant/TenantResolver.java:58` | `bound.contains(declared) ? Effective : Forbidden` → unconditional `Effective` (any forged tenant header accepted) | ✅ Killed — `TenantResolverUnitTest`: 2/9 failed (`declaredTenantOutsideTheBindingIsForbidden`, `declaredTenantBoundToAnotherCredentialIsForbidden`) |
| 2 | `payment-api/.../controller/IdempotencyKeyValidation.java:19` | `isValid(...)` body replaced with `return true;` (no length/pattern/null check) | ✅ Killed — `IdempotencyKeyValidationUnitTest`: 6/9 failed |
| 3 | `payment-contracts/.../events/EnvelopeVersions.java:23` | Removed the `major != KNOWN_MAJOR` throw (unknown majors silently accepted) | ✅ Killed — `EnvelopeVersionsUnitTest`(or equivalent): 1/5 failed |
| 4 | `payment-sbus/.../outbox/BackoffCalculator.java:33` | `jitterFactor()` → constant `1.0` (no jitter) | ✅ Killed — `BackoffCalculatorUnitTest.appliesAtLeastTwentyPercentJitterAcrossABatchThatFailedTogether`: dispersion `0.0` vs required `≥20%` |
| 5 | `payment-api/.../filter/ApiKeyFilter.java:81` | `matchesAConfiguredKey` → unconditional `return true;` (any key, including wrong ones, authenticates) | ✅ Killed — `ApiKeyFilterUnitTest`: 2/7 failed (`anInvalidKeyIsRejected`, `aWrongKeyAgainstAConfiguredHashIsRejected`) |

**Sensor depth**: P0-full (5/5 targeted mutations across TEN-01, IDEM-01/02, API-02, RES-01, SEC-04)
**Result**: 5/5 killed — **PASS**

Worktree cleanup verified both times (`git worktree remove --force`, then `git status --porcelain` re-checked against the pre-sensor baseline — identical, empty).

---

## Code Quality

| Principle | Status |
| --- | --- |
| No features beyond what was asked | ✅ |
| No abstractions for single-use code | ✅ |
| Matches existing patterns (e.g. `ProductionSecurityGuard` pattern reused for tenant-binding boot guard, SHA-256 hash pattern reused from `ConcurrencyLimitFilter`) | ✅ |
| Tests map to ACs and are non-shallow (spot-checked `CrossTenantIsolationIT`, `ConstraintViolationPoisonIT`, `TenantResolverUnitTest`) | ✅ |
| Spec-anchored outcome check | ✅ for the sampled set |
| Documented guidelines followed | `docs/testing-policy.md`, root/boundary `AGENTS.md` (per `tasks.md`'s own Test Coverage Matrix header) |

One deliberate, documented design decision worth flagging (not a defect): `RedisStatusStore.statusKey(requestId)` (`payment-api/.../redis/RedisStatusStore.java:234-236`) is **not** tenant-scoped — only the idempotency reservation key (`idem:{tenant}:{key}`) is. `design.md:80` documents this explicitly ("Redis (Edge) | Chaves `idem:{tenant}:{key}` e `status:*` inalterado (status é por requestId)"), and it is safe because `requestId` is an unguessable server-generated UUID never derivable from tenant+idempotency-key alone — `CrossTenantIsolationIT` confirms a replaying tenant B never learns tenant A's `requestId` in the first place. TEN-05's spec text ("chaves de status" composed with tenant) is slightly broader than this implementation; treat as a **spec-precision note**, not a gap — the design doc's rationale is sound and tested.

---

## Edge Cases (spec.md)

- [x] Simultaneous same-key different-tenant → independent (covered by `CrossTenantIsolationIT`)
- [x] Empty/malformed tenant binding → boot fails (`ProductionSecurityGuard.validateTenants`, `@Requires(env="prod")` — confirmed this only gates `prod`, consistent with the existing `SEC-01`/`auto-register` guard pattern in the same class)
- [ ] Housekeeping time-cap behavior with remaining backlog — traced to task only, not independently re-verified this pass
- [ ] NOSCRIPT/EVALSHA reload edge case — traced to task only, not independently re-verified this pass
- [x] Gateway K8s manifests referencing CRDs — `gateway/docs/` + CI kubeconform wiring confirmed to exist

---

## Gate Check

- **Gate commands run this pass** (no Docker daemon available for `-PwithIT`; per the task instructions those were already run live by the authoring session and are not re-run here):
  - `cd payment-contracts && ./gradlew build --no-daemon` → BUILD SUCCESSFUL
  - `cd payment-core-mock && ./gradlew build --no-daemon` → BUILD SUCCESSFUL (confirms the `contractsVersion` 0.2.0 pin fix from `3a27294` holds)
  - `cd payment-sbus && ./gradlew test --no-daemon` → BUILD SUCCESSFUL, 87 unit tests, 0 failed
  - `cd payment-api && ./gradlew test --no-daemon` (with `PAYMENT_API_KEY`/`JWT_SIGNATURE_SECRET` exported per `STATE.md` lesson 4) → BUILD SUCCESSFUL, 168 unit tests, 0 failed
  - `python3 scripts/docs/validate_docs.py` → `docs: PASS (251 sections; links, commands, ports, variables, metrics, claims)`
  - `python3 scripts/workspace/check_root_governance.py` → `root-governance: PASS (7 boundaries, local ownership, links)`
  - `python3 .claude/skills/tlc-spec-driven/scripts/validate_spec.py review-2026-08-remediation` → `0 error(s), 0 warning(s)`
  - `python3 .claude/skills/tlc-spec-driven/scripts/validate_tasks.py review-2026-08-remediation` → `0 error(s), 11 warning(s)` (all 11 are the expected "Tests: none" confirmations for config/docs-only tasks, not defects)
  - `scripts/verify-workspace.sh no-composite-build` → PASS (4 boundaries)
  - `scripts/verify-workspace.sh hygiene` → PASS
  - `scripts/verify-workspace.sh internal-contract` → PASS (fixtures byte-identical)
  - `python3 -m unittest discover -s gateway/scripts -p 'test_*.py'` → 8/8 OK
  - `python3 gateway/scripts/check-k8s-parity.py` → OK
  - **`scripts/verify-workspace.sh equivalence` → FAIL, exit 1: `ERROR: changed scripts entry: load/k6/capacity.js`** (see Finding 1 below)

- **Total test count before feature**: not independently established (no pre-feature baseline captured this pass); STATE.md's prior-feature closures report prior boundary counts in the 27-141 range per boundary, consistent in order of magnitude with the 87/168 observed here.
- **Failures**: 1 — the equivalence gate (see Finding 1).
- **Skipped**: all `-PwithIT` Testcontainers suites (already run live by the authoring session per `STATE.md`'s T49 section; re-running the full ~41 IT classes across 6 boundaries was out of this pass's time budget and the task brief explicitly says not to re-run the 15-minute load test — the same reasoning was extended to the multi-hour full IT re-run). `payment-api`'s `LivenessUnderRedisLatencyIT` was traced to file only, not executed.

---

## Fix Plans

### Finding 1 (Blocker, trivially fixable): equivalence gate is broken on HEAD

- **Root cause**: `load/k6/capacity.js` was modified by commit `6485233` (`fix(load): remove runtime fetch of a remote k6-utils module from capacity.js`) *after* the equivalence manifest was reconciled by `e0ba0ef` (`chore(workspace): reconciliar manifest de equivalência pós-remediação`) and *after* T49's own "gates verdes" closing commit `63e6e0a`. The manifest at `scripts/equivalence/baseline-manifest.json:294` still records the pre-fix SHA-256 (`3868708b382d16d6e2167fe1d1fc446f5e2b2b13032e97adcf9bee43b4a55de0`); the file's real hash is now `d5aaa6434398c86a2045afaad162326374a35b667beca4b9003b179817d8f243`. Confirmed live: `scripts/verify-workspace.sh equivalence` exits 1 on current HEAD (`7f61ae3`).
- **Why it matters**: this directly contradicts `spec.md`'s own Success Criteria ("Gates preexistentes verdes: ... `scripts/verify-workspace.sh` 8/8 ...") and `STATE.md`'s closing claim ("Structural gates ... all green" / "Nothing else is outstanding"). It is a real, reproducible, currently-red gate on the branch this feature is about to be declared done from — not a flake, not environment-specific (pure SHA-256 comparison, no Docker needed).
- **Fix task**: regenerate the manifest entry for `load/k6/capacity.js` (`python3 scripts/equivalence/equivalence.py generate --root .` piped/merged into `scripts/equivalence/baseline-manifest.json`, or the project's existing reconciliation procedure referenced in `STATE.md`), verify `scripts/verify-workspace.sh equivalence` passes clean, commit as a single atomic `chore(workspace): reconciliar manifest de equivalência` follow-up, and add one line to `STATE.md`'s Handoff noting the gate was re-broken and re-fixed post-T50.
- **Priority**: Blocker for the "feature is fully done" claim (violates an explicit Success Criterion currently on HEAD), but Trivial effort (~5 minutes, no code/logic change, purely a stale-manifest regeneration).

---

## Requirement Traceability Update

No changes recommended to `spec.md`'s traceability table — all sampled rows are genuinely `Verified` with real evidence. The table's blanket "Verified" status for every row was not fully re-derived (see "What was not independently re-verified" above); recommend a note in `spec.md` distinguishing "independently re-verified by Verifier" vs. "traced to task + commit only" if the project wants that granularity in the future, but this is a nice-to-have, not a gate.

---

## Summary

**Overall**: ⚠️ Issues — one Blocker-severity but trivially-fixable gap; everything else sampled is solid.

**Spec-anchored check**: 8/9 sampled criteria independently confirmed with file:line evidence matching the spec-defined outcome exactly; 1 traced-to-file-only (BUDG-04, environment-bound).
**Sensor**: 5/5 mutations killed (P0-full tier, spanning TEN-01, IDEM-01/02, API-02, RES-01, SEC-04).
**Gate**: 13 gate commands run live this pass, 12 passed, 1 failed (workspace equivalence).

**What works**: The two critical findings from the original architecture review — cross-tenant idempotency leakage and optional Idempotency-Key — are genuinely fixed, not just claimed. `TenantResolver` correctly anchors identity to the credential (never the client-declared header) with a boot guard against empty bindings in prod; `IdempotencyKeyValidation` genuinely rejects missing/malformed keys before any I/O; `CrossTenantIsolationIT` and `ConstraintViolationPoisonIT` are real, non-vacuous Testcontainers tests that exercise the exact attack scenarios the review flagged, not tautological assertions. The Postgres migration correctly moves the idempotency uniqueness constraint from a global key to `(tenant_id, idempotency_key)`. The AD-007 capacity report's numbers (avg=298.29ms, p99=455.66ms, 153/153 reconciled, 0 lost, 0% 429 in steady) were independently cross-checked against the raw `steady.summary.json` and `steady.stdout.txt` k6 output in `load/reports/certified-target/` and match exactly — not fabricated. The two documented non-blocking gaps (`HikariPoolHealthIndicatorIT`, `PaymentRequestedConsumerConcurrencyIT`) are real, isolated, and match their described symptoms exactly in the build's own JUnit XML / historical gradle logs (`/tmp/hikari-it-*.log` show 3 byte-identical `HikariPool-1 ... total=1, active=1` failures; the concurrency IT's JUnit XML shows the exact 2.83x-vs-2.5x-threshold failure STATE.md describes) — neither is being used to paper over something bigger.

**Issues found**:
1. `scripts/verify-workspace.sh equivalence` is currently red on HEAD due to a stale manifest entry for `load/k6/capacity.js`, introduced by a legitimate late bug-fix commit that was never reconciled into the equivalence manifest — see Fix Plan above. This is a real, reproducible gate failure that contradicts the feature's own stated Success Criteria and STATE.md's closing claim, even though the fix itself is mechanical and low-risk.

**Next steps**: Apply Finding 1's fix (regenerate manifest, verify, commit), then this feature can be honestly marked closed. Everything else sampled — including the two highest-stakes P1 stories the task brief asked for extra scrutiny on — holds up under adversarial re-derivation and mutation testing.
