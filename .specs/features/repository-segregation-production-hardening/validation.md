# Segregação de Repositórios e Hardening de Produção — Validation

**Date**: 2026-08-12
**Spec**: `.specs/features/repository-segregation-production-hardening/spec.md`
**Diff range**: `main..HEAD` (`feature/plan-continue`, 11 commits `434f20f..6cb17db`, 256 files, +6497/−10156)
**Verifier**: independent sub-agent (author ≠ verifier) — no authoring context, all evidence re-derived from disk

**Verdict**: ❌ **FAIL** — 1 surviving mutant, 1 live gate regression that makes a spec-required
validation vacuous, and 1 P1 acceptance criterion (CAP-02) whose "fixed" claim has no committed
evidence artifact. The workspace gate itself is green; the failures are in *what the gates prove*.

---

## Scope

Phases 1–8 (T1–T53) shipped to `main` via PR #20 in an earlier session and are treated as
stable baseline. Deep review (discrimination sensor + code-quality) is scoped to this branch's
diff surface — Phase 9, T57–T60 plus two follow-up fixes and an equivalence reconciliation. The
full 77-requirement traceability table was re-derived evidence-or-zero, with 14 rows spot-checked
by opening the cited files.

---

## Task Completion

| Task | Status | Notes |
| ---- | ------ | ----- |
| T54 (`add6749` ancestry) | ✅ Done | Cross-boundary gates; verified live via `verify-workspace.sh` |
| T55 | ✅ Done | Failure matrices; 10/11 + 34/34 reproduced live |
| T56 | ✅ Done | Async-redis recovery matrix |
| T57 `434f20f` | ✅ Done | Capacity gate + manifest; recorded a real FAIL honestly |
| T58 `348ec94` | ✅ Done | Observability verification; recorded 2 real gaps |
| follow-up `c722c46` | ✅ Done | Redis PubSub deadlock fix (`task_3801253b`) |
| follow-up `b6d5836` | ✅ Done | Correlation-id MDC wiring (`task_89c681c8`) |
| T54-reconcile `56ae3c0` | ✅ Done | Equivalence baseline 389 → 409 |
| T59 `add6749` | ⚠️ Partial | Legacy removal complete, but left `validate_docs.py`'s dashboard scanner pointed at the deleted path (Gap 2) |
| T60 `e7a6036` | ⚠️ Partial | Evidence report is accurate and honest, but reuses stale sub-artifacts (Gaps 3–4) |

---

## Spec-Anchored Acceptance Criteria

The traceability table in `spec.md` carries prose evidence naming tasks and file paths. Of 77
rows, **1 carries a `file:line` citation** (CAP-02 → `ResponseCoordinator.java:126-128`, verified
accurate). The remaining 76 cite task IDs and file paths without line anchors. Under
validate.md's strict evidence-or-zero rule this is a systemic **⚠️ spec-precision gap**, mitigated
by the fact that every one of the 14 rows sampled below resolved to real, matching code.

### Sampled rows (opened and confirmed against the cited artifact)

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --------- | -------------------- | ----------------------- | ------ |
| ORG-08 — equivalence gate detects loss | Gate detects lost file/contract/test/migration/dashboard/script | `scripts/equivalence/equivalence.py` — live run `equivalence: PASS (409 entries)`; sensor M1 proved detection | ⚠️ Evidence stale: row claims "389 entries", actual and T60 both say **409** |
| SBX-04 — duplicate host port fails before startup | Validation fails pre-startup on port collision | `sandbox/tests/test_ports.py:35-37` — `run_validator("collision-8085.yml")`, `assertIn("8085/tcp", result.stderr)` | ✅ PASS |
| SEC-06 — `.env.example` versioned, `.env` ignored | Real `.env` never tracked | `.gitignore:12` (`git check-ignore` confirms `payment-api/.env`); 6 `.env.example` tracked | ✅ PASS |
| SEC-07 — non-root runtime container | Unprivileged user | All 4 app `Dockerfile`s contain UID `10001`; `payment-api/compose.yaml` `user: "10001:10001"`, `read_only: true`, `cap_drop: ALL` | ✅ PASS |
| SEC-08 — CI blocks by severity policy | SBOM + scan, block HIGH/CRITICAL | `validation-evidence/2026-08-12-release-gate.md` §3 — 25–31 HIGH/CRITICAL per image | ❌ Known FAIL, tracked `task_40100c4c` (not a new finding) |
| PAY-01 — atomic key+fingerprint association | Single atomic reservation | `payment-api/.../idempotency/IdempotencyFingerprint.java`, `IdempotencyOutcome.java:3-22` (REPLAY / lapsed-lease / CONFLICT states) | ✅ PASS |
| PAY-08 — future `not-before` never processed early | Retry blocked until due, no partition sleep | `payment-sbus/.../repository/OutboxEventRepository.java:25` — `WHERE status IN ('PENDING','DLQ_PENDING') AND next_attempt_at <= :now`; live: `due-retry-does-not-block-live-traffic` PASS | ✅ PASS |
| PAY-09 — dependency outage policy | Timeout/retry/circuit/readiness per dependency | Live matrix: kafka/postgres/registry PASS | ❌ Partial — `redis-unavailable-api` FAIL (known, `task_5a0df80c`) |
| RED-04 — unique consumer identity per instance+worker | `<instance-id>-w<index>` | `async-redis-service/.../worker/WorkerIdentity.java:45-46` — `return instanceId + "-w" + workerIndex;`; live: 4 distinct consumers for 2 instances | ✅ PASS |
| RED-07 — DLQ at exactly maxDeliveries, ACK after DLQ | `>=`, not `>` | `async-redis-service/.../dlq/DeadLetterWriter.java:44` — `return redeliveryCount >= props.getMaxDeliveries();`; live: `exceeded-deliveries-dlq` PASS | ✅ PASS |
| CAP-02 — sustained load, <0.1% technical error, zero silent loss | Technical error rate < 0.1% | `load/reports/20260811-185714-capacity-report.md:32,60,87,114` — **4.1%–44.4%**; `certified-target/cpucheck.summary.json` — `cap_technical_errors.count = 760 / 7622` (~10%), `http_req_duration.max = 60003ms` | ❌ **GAP — no committed artifact shows the criterion met** (see Gap 1) |
| CAP-07 — gate fails on breached threshold, report preserved | Non-zero exit + preserved report | `load/capacity/generate_report.py:267` `selftest()`; `load/reports/run_gate.txt` — `aggregate exit=1` | ✅ PASS |
| FTR-05 — bounded metric cardinality, no PII | Per-dimension cap + hashed subject | `feature-control/library/.../metrics/CardinalityGuard.java`, `.../metrics/SubjectHasher.java` | ✅ PASS |
| DOC-05 — every doc section has a destination | All recorded sections MIGRATED | `scripts/docs/relocation-manifest.json` — `section_count = 251`; `validate_docs.py` → `docs: PASS (251 sections)` | ⚠️ PASS but the `metrics` component is vacuous (Gap 2) |
| EDG-04 — dashboard referencing removed metric is detected | Validation detects the reference | `scripts/docs/validate_docs.py:226` scans `observability/grafana/dashboards/*.json` — **directory deleted by T59; scans 0 of 10 real dashboards** | ❌ **GAP** (see Gap 2) |

**Status**: ❌ Gaps present — 2 failed ACs (CAP-02, EDG-04), 1 known-tracked partial (PAY-09),
1 known-tracked FAIL (SEC-08), 1 stale evidence value (ORG-08), plus a systemic
`file:line` citation gap across 76/77 rows.

---

## Discrimination Sensor

**Isolation**: temporary git worktree at
`…/scratchpad/sensor-wt` via `git worktree add <path> HEAD`; local Maven repos copied in
(scratch-only writes); removed with `git worktree remove --force`. **No `git stash` was used.**

- Baseline `git status --porcelain` on the real tree: **empty**
- Post-sensor `git status --porcelain` on the real tree: **empty** — ✅ isolation verified, real tree never mutated

| # | File:line | Mutation | Gate run | Killed? |
| - | --------- | -------- | -------- | ------- |
| 1 | `scripts/equivalence/equivalence.py:99` | Removed `"*/ops/dashboards/*.json"` from the dashboards glob (simulates losing a real dashboard category) | `equivalence.py verify` | ✅ **Killed** — exit 1, named all 7 missing dashboards + count mismatch `dashboards: 10 → 3` |
| 2 | `payment-sbus/.../kafka/SimulationMessageHandler.java:63,85` | Removed both `Mdc.fromConsumer(record, env);` call sites (required side effect per DOC-03's fix) | `./gradlew test` (44) **and** `./gradlew test -PwithIT` (76) | ❌ **SURVIVED** — both gates BUILD SUCCESSFUL |
| 3 | `payment-api/.../coordination/ResponseCoordinator.java:127` | Reverted `notificationDispatcher.execute(() -> complete(requestId))` to inline `complete(requestId)` (undoes the `task_3801253b` fix) | `./gradlew test --tests '*ResponseCoordinatorUnitTest*'` | ✅ **Killed** by `onMessageDispatchesAsynchronouslyInsteadOfBlockingTheCallingThread` |

**Sensor depth**: lightweight (3 mutations) — appropriate tier, though P0-full (≥5) is arguably
warranted for a payment path; the 3 chosen target the highest-risk new logic in the diff.

**Result**: 2/3 killed — ❌ **FAIL**

**Note on mutation 3**: the companion test
`aStuckNotificationForOneRequestNeverBlocksAnothers` **passed under the mutant**. It is
sequential and asserts no time bound, so inline dispatch merely makes it slow (~5s) rather than
failing. Only its sibling discriminates. Not a blocker (the mutant is killed), but the test does
not test what its name and docstring claim.

---

## Interactive UAT Results

Not performed — infrastructure/backend feature with no user-facing UI surface. Automated checks
are sufficient per validate.md §3.

---

## Code Quality

Reviewed against the T57–T60 diff surface.

| Principle | Status |
| --------- | ------ |
| No features beyond what was asked | ✅ |
| No abstractions for single-use code | ✅ |
| No unnecessary "flexibility" added | ✅ |
| Only touched files required for task | ✅ |
| Didn't "improve" unrelated code | ✅ |
| Matches existing patterns/style | ✅ |
| Would senior engineer approve? | ✅ (with the gaps below fixed) |
| Minimum code / surgical changes | ✅ |
| Tests map to acceptance criteria and are non-shallow | ⚠️ — see mutation 3 note and Gap 2 |
| Spec-anchored outcome check (asserted values match spec) | ❌ — CAP-02's asserted outcome is unproven (Gap 1) |
| Per-layer Coverage Expectation met | ⚠️ — `SimulationMessageHandler`'s MDC wiring has no call-site test (Gap 5) |
| Every test maps to a spec requirement — no unclaimed tests | ✅ — all 6 new tests map (CAP-02 ×3, DOC-03 ×3) |
| Documented guidelines followed | ⚠️ — `AGENTS.md:44` ("um claim de produção exige gate e relatório datado") is violated by CAP-02's undated, unrecorded post-fix claim |

**Positives worth recording:**
- The CI/dependabot/settings rewrites are mechanical and exactly scoped to T59's relocation — no scope creep.
- `RedisClientTuning` and the `ResponseCoordinator` fix are minimal, well-commented, and explain *why* rather than *what*.
- `ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing` is a genuinely strong test — it captures MDC at the real publish moment via `doAnswer` and asserts it equals the captured `requestId`. This is the right pattern; its absence on the sbus side is Gap 5.
- T57/T58/T60 all recorded real FAILs rather than promoting them to PASS. That discipline is the reason these gaps are findable at all.

---

## Edge Cases

- [x] **EDG-01** artifact-only local resolution — `artifact-only-consumer: PASS`
- [x] **EDG-02** Flyway immutability — V1…V9 append-only, no renumbering
- [x] **EDG-03** incompatible Avro requires major — T10 gate
- [ ] **EDG-04** dashboard on removed metric detected — ❌ **NOT handled** (Gap 2)
- [x] **EDG-05** unavailable dependency reported NOT_RUN, never PASS — SEC-08 recorded FAIL honestly
- [x] **EDG-06** unrelated local changes preserved — tree clean throughout
- [x] **EDG-07** Core-mock never presented as Core-real certification — manifest labels profiles explicitly

---

## Gate Check

- **Gate command**: `bash scripts/verify-workspace.sh` (Workspace level, per tasks.md Gate Check Commands)
- **Exit code**: **0**
- **Preconditions**: sandbox already up; 4 app stacks built and started via `docker compose --env-file .env up -d --build` in each of `payment-core-mock`, `payment-sbus`, `payment-api`, `async-redis-service` — all reached `healthy`.

| Stage | Result |
| ----- | ------ |
| `equivalence` | ✅ PASS (409 entries) |
| `no-composite-build` | ✅ PASS (4 boundaries checked) |
| `artifact-only-consumer` | ✅ PASS (published GAV resolves; missing GAV fails) |
| `e2e-payment` | ✅ `SMOKE OK (COMPLETED)` |
| `e2e-async-redis` | ✅ `SMOKE OK (COMPLETED)` |
| `payment-failures` | ✅ PASS — **10/11**, floor 10 |
| `async-redis-failures` | ✅ PASS — 34/34 assertions, 10 scenarios, floor 10 |
| `hygiene` (`git diff --check`) | ✅ PASS |

**The one failing scenario** — `redis-unavailable-api`: `500` leaking
`Unable to connect to redis/<unresolved>:6379` instead of a fail-closed `503`/`429`. Pre-existing,
tracked as `task_5a0df80c`, documented in `validation-evidence/2026-08-12-release-gate.md`.
**Not counted as a Verifier finding.**

**Test integrity check** — structural `@Test` counts re-derived independently and compared with
T60's claimed counts:

| Boundary | T60 claim (Quick/Full) | Independently counted | Match |
| -------- | ---------------------- | --------------------- | ----- |
| `payment-contracts` | 28 / 28 | 28 / 28 | ✅ exact |
| `payment-api` | 68 / 119 | 68 / 119 | ✅ exact |
| `payment-sbus` | 44 / 76 | 44 / 76 | ✅ exact |
| `payment-core-mock` | 21 / 27 | 21 / 27 | ✅ exact |
| `async-redis-service` | 39 / 96 | 39 / 96 | ✅ exact |
| `feature-control` | 113 / 141 | 113 / **150** | ⚠️ full off by 9 |

Five of six match to the digit, including the `settings.gradle`-excluded `consumer-fixture`
modules — strong evidence T60's report was measured, not fabricated. `feature-control`'s full
count is 9 short of the structural total (library 133 + feature-demo 12 + pilot-app 5 = 150);
most likely one IT class did not execute in that run. No test count decreased; no assertions
were weakened.

---

## Fix Plans

### Gap 1 — CAP-02 has no evidence that its threshold is met (Blocker)

- **Root cause**: The `task_3801253b` code fix is real and unit-tested, but the only committed
  capacity artifacts are the *pre-fix* runs. `load/reports/20260811-185714-capacity-report.md`
  records 4.1%–44.4% technical error rate against a 0.1% threshold, and
  `load/reports/certified-target/cpucheck.*` is the *reproduction* run
  (2026-08-11T20:18–20:20Z, `cap_technical_errors.count = 760/7622`,
  `http_req_duration.max = 60003ms`, plus `RedisCommandTimeoutException` stack traces at
  `ResponseCoordinator.java:119` ← `ResponseCoordinator$1.message:78`, i.e. the inline dispatch).
  spec.md CAP-02 claims a post-fix re-run with "0 technical errors" — **no artifact records it.**
  `AGENTS.md:44` requires a dated report for any production claim.
- **Fix task**: Re-run `load/capacity/run_gate.sh` (or at minimum the `certified-target` steady
  scenario) against the fixed images, commit the resulting dated report under `load/reports/`,
  and update CAP-02's traceability row to cite it. If the threshold still isn't met, record that
  honestly and re-scope CAP-02.
- **Priority**: Blocker — CAP-02 is a P1 MVP acceptance criterion.

### Gap 2 — T59 orphaned the dashboard-metric validator; EDG-04 is now vacuous (Blocker)

- **Root cause**: `scripts/docs/validate_docs.py:226` still globs
  `observability/grafana/dashboards/*.json`. T59 deleted that directory and relocated all 10
  dashboards to `*/ops/dashboards/`, `sandbox/observability/dashboards/` and `load/dashboards/`.
  `dashboard_metric_errors()` now scans **0 files and returns `[]`**, so
  `validate_docs.py` still prints `docs: PASS (… metrics …)` while validating no dashboard at
  all. Downstream, `scripts/observability/checks/dashboard_validator.sh:12-13` writes its
  detection fixture into the deleted directory, so its EDG-04 discrimination check now **fails**
  (`injected fixture was not flagged`) — reproduced live.
  This is precisely the bug class T59 *did* fix for `known_ports()`/`known_variables()` (per
  DOC-06's own row); the third scanner of the same class was missed.
- **Fix task**: Update `dashboard_metric_errors()` to the three real dashboard roots (mirroring
  `equivalence.py`'s already-corrected glob), point `dashboard_validator.sh`'s `DASHBOARDS_DIR`
  at a real one, re-run both, and confirm the fixture is flagged again.
- **Priority**: Blocker — EDG-04 is an unmet acceptance criterion and the gate reports a false PASS.

### Gap 3 — `scripts/observability/verify-evidence.txt` contradicts the claim it backs (Major)

- **Root cause**: The committed artifact is the pre-fix 10/12 run. Lines 22–23 still record
  `FAIL: payment-sbus log carries requestId+correlationId+causationId+traceId` and
  `FAIL: payment-api log carries causationId … payment-api never puts causationId into MDC`,
  both of which the `b6d5836` fix resolved. spec.md DOC-03 claims "12/12 checks pass (previously
  10/12)". Line 33 also references the T59-deleted `observability/grafana/dashboards/` path.
- **Fix task**: Re-run `scripts/observability/verify.sh` against the current images (after Gap 2
  is fixed) and commit the regenerated evidence.
- **Priority**: Major.

### Gap 4 — ORG-08's entry count is stale (Minor)

- **Root cause**: spec.md ORG-08 says `equivalence.py verify` passes "(389 entries)". The live
  run and `baseline-manifest.json` both say **409**. 389 was T54's count before the `56ae3c0`
  reconciliation; T60's evidence correctly says 409.
- **Fix task**: Update ORG-08's row to 409.
- **Priority**: Minor.

### Gap 5 — no call-site test for the SBUS MDC wiring (Major — this is surviving mutant #2)

- **Root cause**: `MdcUnitTest` proves the `Mdc` utility's own contract but nothing asserts that
  `SimulationMessageHandler` *calls* it. Removing both `Mdc.fromConsumer(record, env)` call sites
  leaves all 44 quick and all 76 full payment-sbus tests green. The wiring is covered only by
  `scripts/observability/verify.sh`, which is **not** a stage in `scripts/verify-workspace.sh`
  and **not** in any CI job — so a regression here ships silently.
  `payment-api` got exactly the right test for the mirror-image fix
  (`ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing`); sbus did not.
- **Fix task**: Add a `SimulationMessageHandlerUnitTest` case that captures MDC inside a
  `doAnswer` on the `PaymentSimulationService` mock and asserts `requestId`/`correlationId`/
  `causationId`/`traceId` match the envelope, plus one asserting MDC is cleared after the call.
  Separately, consider adding `scripts/observability/verify.sh` as a `verify-workspace.sh` stage.
- **Priority**: Major.

### Gap 6 — dead code and a now-vacuous test (Minor)

- **Root cause**: `equivalence.py:22` `LEGACY_TRANSITIONAL_ROOTS = {"common","api-service","sbus-service","core-mock"}`
  and its guard at line 40 are dead after T59 deleted those directories; the comment still says
  "until T59 removes them". `test_equivalence.py:80` `test_legacy_transitional_roots_are_excluded`
  can no longer fail for any implementation.
- **Fix task**: Remove the constant, its guard and the test, or document why they're retained.
- **Priority**: Minor.

### Gap 7 — `feature-control` library ITs never run in CI (Minor, pre-existing)

- **Root cause**: `.github/workflows/ci.yml` integration matrix runs
  `cd feature-control && ./gradlew :feature-demo:test :pilot-app:test -PwithIT` — excluding the
  library's own 20 ITs (`VersionedFlagStoreIT`, `RedisFlagSourceIT`,
  `FlagChangeSubscriberConvergenceIT`), which are the integration evidence behind FTR-02/03/04.
  Pre-existing on `main`, not a Phase 9 regression, but it undercuts MIG-04's
  "unit, integration, contract … com contagem esperada de testes".
- **Fix task**: Change the command to `cd feature-control && ./gradlew test -PwithIT`.
- **Priority**: Minor.

---

## Requirement Traceability Update

| Requirement | Previous Status | New Status |
| ----------- | --------------- | ---------- |
| CAP-02 | Execute (claimed fixed) | ❌ Needs Fix — no artifact meets the <0.1% threshold (Gap 1) |
| EDG-04 | Execute (claimed proven end-to-end) | ❌ Needs Fix — validator scans 0 dashboards (Gap 2) |
| DOC-03 | Execute (claimed 12/12) | ⚠️ Evidence stale (Gap 3) + untested wiring (Gap 5) |
| DOC-05 / DOC-06 | Execute | ⚠️ `metrics` component of the PASS is vacuous (Gap 2) |
| ORG-08 | Execute (389 entries) | ⚠️ Correct to 409 (Gap 4) |
| SEC-08 / MIG-06 | Execute (FAIL recorded) | ❌ Known, tracked `task_40100c4c` — unchanged |
| PAY-09 | Execute (partial) | ❌ Known, tracked `task_5a0df80c` — unchanged |
| All other 70 requirements | Execute | ✅ Verified (sampled evidence resolved; no contradicting signal found) |

---

## Summary

**Overall**: ❌ Not Ready

**Spec-anchored check**: 2 failed ACs (CAP-02, EDG-04); 2 known-tracked failures excluded by
scope (SEC-08, PAY-09); 1 stale evidence value (ORG-08); 76/77 rows lack `file:line` citations
(systemic spec-precision gap, mitigated — 14/14 sampled rows resolved to real code)
**Sensor**: 3 mutations, **2 killed, 1 survived**
**Gate**: `scripts/verify-workspace.sh` exit **0** — 8/8 stages pass (payment-failures 10/11 at floor)

**What works**:
- All 8 workspace gate stages green against a live 4-app fleet on the sandbox network.
- Multi-instance failure matrices are genuinely strong: 10/11 payment scenarios and 34/34
  async-redis assertions, covering crash-window reclaim, hard container kill, poison→DLQ,
  Kafka/Postgres/Registry outage + recovery, PEL ownership theft, and shared admission limits.
- Test counts independently reproduce T60's claims exactly on 5 of 6 boundaries — the evidence
  report is measured, not fabricated.
- The `ResponseCoordinator` virtual-thread fix and `RedisClientTuning` are correct, minimal, and
  discriminated by a real regression test.
- Equivalence gate genuinely detects loss (proved by mutation 1).
- T57/T58/T60 consistently recorded real FAILs instead of promoting them to PASS.

**Issues found**: Gaps 1–7 above, ranked. Two blockers (CAP-02 unproven; EDG-04 validator
orphaned by T59), three major (stale observability evidence; untested SBUS MDC wiring —
the surviving mutant; both feeding DOC-03), two minor (stale ORG-08 count, dead legacy-roots
code, CI IT scope).

**Next steps**: Route Gaps 1, 2 and 5 to fix tasks first — they are the ones where a gate
currently reports success for something it does not actually verify. Gaps 3, 4, 6, 7 are
bookkeeping and can batch. Re-verify after; max 3 fix→re-verify iterations before escalating.
