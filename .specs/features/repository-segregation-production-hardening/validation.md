# Segregação de Repositórios e Hardening de Produção — Validation

**Date**: 2026-08-12
**Spec**: `.specs/features/repository-segregation-production-hardening/spec.md`
**Diff range**: `main..HEAD` (`feature/plan-continue`, 12 commits `434f20f..c8fff22`, 266 files, +11376/−10174)
**Verifier**: independent sub-agent (author ≠ verifier) — no authoring context, all evidence re-derived from disk
**Iteration**: fix→re-verify **2 of 3** (re-check of the 7 ranked gaps from iteration 1's FAIL report, all claimed fixed in `c8fff22`)

**Result**: PASS ✅

**Verdict**: ✅ **PASS** — all 7 iteration-1 gaps are genuinely closed, each re-verified by running the
gate live rather than reading the claim. The previously surviving mutant is now killed. One **Minor**
residual was found by an extra, narrower mutation this Verifier introduced (below); it is not a
regression from `c8fff22` and does not block release.

---

## Scope

This is a **targeted re-verification**, not a repeat of iteration 1's from-scratch pass. The
77-requirement spec-anchored table, the 14 sampled traceability rows and the test-integrity
recount were done in iteration 1 and nothing outside the 7 gaps changed (`c8fff22` touches 20
files: 5 gate/script files, 2 spec/tasks docs, 1 test, 1 manifest, 1 report + its raw artifacts).
Re-verified here: each of the 7 fixes, the discrimination sensor's previously surviving mutant,
and the full 8-stage workspace gate for side effects.

---

## Task Completion

| Task | Status | Notes |
| ---- | ------ | ----- |
| T54–T58, follow-ups, T54-reconcile | ✅ Done | Unchanged since iteration 1 |
| T59 `add6749` | ✅ Done | Iteration-1 partial (orphaned dashboard scanner) closed by `c8fff22` |
| T60 `e7a6036` | ✅ Done | Iteration-1 partial (stale sub-artifacts) closed by `c8fff22` |
| Verifier fix cycle `c8fff22` | ✅ Done | 7/7 gaps closed; recorded in `tasks.md` under "Verifier fix cycle" |

---

## Re-Verification of the 7 Ranked Gaps

| # | Gap (iteration 1) | Re-verification method | Result |
| - | ----------------- | ---------------------- | ------ |
| 1 | CAP-02 threshold unproven (Blocker) | Read `load/reports/20260812-095631-capacity-report.md`; independently re-ran the addendum's Postgres cross-check | ✅ **Closed** |
| 2 | EDG-04 validator orphaned by T59 (Blocker) | Ran `validate_docs.py` + `dashboard_validator.sh` live; re-resolved the new glob | ✅ **Closed** |
| 3 | Stale `verify-evidence.txt` (Major) | Read the regenerated artifact | ✅ **Closed** |
| 4 | ORG-08 stale entry count (Minor) | Grepped `spec.md` | ✅ **Closed** |
| 5 | Surviving mutant — SBUS MDC wiring untested (Major) | Read the new test, then **independently re-ran the sensor** in a fresh worktree | ✅ **Closed** (mutant killed) |
| 6 | Dead `LEGACY_TRANSITIONAL_ROOTS` + vacuous test (Minor) | Grepped, ran `equivalence.py verify` + the unit suite | ✅ **Closed** |
| 7 | `feature-control` library ITs never run in CI (Minor) | Read `ci.yml` + `check_ci_policy.py`, ran the policy check | ✅ **Closed** |

### Gap 1 — CAP-02 (Blocker → closed)

`load/reports/20260812-095631-capacity-report.md` `certified-target/steady`:

```
- Total requests: 150301
- Throughput: 166.47 req/s
- Status mix: 200=304 202=22162 422=31 429=127804 technical_errors=0
- Technical error rate: 0.0000%
- Latency (ms): p50=0.56 p95=3010.99 p99=3128.58 max=3885.05
```

vs. the pre-fix `20260811-185714` run's `technical_errors=11102`, `28.9764%`,
`p95=60001ms`. The `<0.1% technical error` sub-criterion now has a dated, committed artifact
produced by the gate's own `generate_report.py`. `load/k6/capacity.js:41-42,87-93` confirms
`cap_technical_errors` counts exactly what CAP-02 budgets (anything outside 200/202/422/429,
i.e. 401/5xx/network/timeouts) — so `0` is a real zero, not a definitional dodge.

**Judgment on the addendum** (`20260812-095631-cap02-addendum.md`) — asked to assess whether its
reasoning is sound and honestly presented. Every factual claim was independently reproduced:

| Addendum claim | Independent check | Holds? |
| -------------- | ----------------- | ------ |
| Both "lost" ids actually reached `COMPLETED` | `docker exec payment-sandbox-postgres-1 psql -U sbus -d sbus` — returned **byte-identical** rows to the addendum's quoted table (`aeee5820… COMPLETED 09:40:04.152626→09:40:05.022264`, `ce18cc2b… COMPLETED 09:40:02.117515→09:40:02.317824`) | ✅ |
| `status-ttl` == scenario duration | `payment-api/src/main/resources/application.yml:90` `status-ttl: 15m`; `load/capacity/manifest.yaml:101` `steady: { … duration: 15m … }` | ✅ exact |
| Reconciliation only runs after the whole scenario | `load/capacity/lib.sh:149-183` — `reconcile.py` is the last step of `run_k6_scenario`, after the k6 container exits and after the `after` snapshot | ✅ |
| Both samples were from the *start* of the run | `steady.before.json` `ts=2026-08-12T09:40:01Z`, `steady.after.json` `ts=2026-08-12T09:55:06Z`. The two ids were created at 09:40:02 and 09:40:04 — **1 and 3 seconds** into a 15m05s run, so their Redis entries TTL'd at ≈09:55:02/09:55:04, seconds *before* reconciliation began | ✅ numerically tight |

The mechanism is not just plausible, it is quantitatively forced: with a 15m TTL and a 15m
scenario, exactly the first ~2 samples (of 185, ≈1 per 5s) are unobservable by a
poll-at-the-end reconciler — which is precisely how many were reported lost. This is a real
defect in the **gate's own measurement methodology**, not in the payment path.

**Not spin.** The addendum:
- states the automated verdict is still FAIL in its own section heading;
- explicitly declines to claim CAP-02 is fully certified ("CAP-02 is not fully certified end to
  end by an unqualified automated PASS");
- separates the *proven* sub-criterion (technical error rate) from the *cross-checked but not
  automatically certified* one (zero silent loss);
- concedes the flaw is "plausibly present since T57 but masked there," rather than framing it as
  newly introduced or newly discovered virtue.

**Leaving the generated FAIL un-overridden was the right call.** `generate_report.md` is machine
output; hand-editing a verdict from FAIL to PASS would destroy the one property that makes the
whole capacity gate trustworthy and would directly violate `AGENTS.md:44`. Spawning
`task_bca58451` for the methodology fix (poll shortly after each sample, or raise `status-ttl`
above the longest scenario duration) rather than patching it inline is also correct: it is a
design change to the gate, out of scope for a fix-cycle commit, and patching it *in the same
commit that reports on it* would mean the reported run no longer matches the committed code.

### Gap 2 — EDG-04 (Blocker → closed)

```
$ python3 scripts/docs/validate_docs.py
docs: PASS (251 sections; links, commands, ports, variables, metrics, claims)   [exit 0]

$ bash scripts/observability/checks/dashboard_validator.sh                       [exit 0]
[obs] PASS: real dashboards under */ops/dashboards, sandbox/observability/dashboards and load/dashboards reference only implemented metrics
[obs] PASS: validator detects a dashboard referencing a removed/nonexistent metric (EDG-04)
[obs] PASS: scratch fixture cleaned up, real tree unchanged
```

The second line is the anti-vacuity proof — the injected fixture is flagged, so the PASS is
earned, not empty. The new glob in `scripts/docs/validate_docs.py:226-232` was re-resolved
independently and returns **10 real dashboards** (vs. 0 before the fix): all 5 `*/ops/dashboards`,
2 `sandbox/observability/dashboards`, 1 `load/dashboards` — matching the 10 T59 relocated.
`dashboard_validator.sh:12` now points `DASHBOARDS_DIR` at a directory that exists.

### Gap 3 — observability evidence (Major → closed)

`scripts/observability/verify-evidence.txt` now ends in `12 passed, 0 failed`, with **zero failing
checks** (grepping the failure marker returns nothing) and no residual
`observability/grafana/` path references. The two checks that
failed in the pre-fix artifact now pass at lines 22–23 (`payment-sbus log carries
requestId+correlationId+causationId+traceId`, `payment-api log carries causationId`), and the
regenerated file carries the corrected `dashboard_validator` messages — i.e. it is a genuine
re-run, not a hand-edit.

### Gap 4 — ORG-08 (Minor → closed)

`spec.md` ORG-08 now reads `equivalence: PASS (409 entries)`. No `389 entries` remains anywhere
in `spec.md` (the only surviving `389` is `docker-compose.yml:389`, an unrelated line-number
citation in the problem statement).

### Gap 6 — dead legacy roots (Minor → closed)

`grep -rn LEGACY_TRANSITIONAL_ROOTS scripts/` → **no matches**; the guard at
`equivalence.py:35` is now `if IGNORED_PARTS.intersection(parts)` only, and
`test_legacy_transitional_roots_are_excluded` is gone.

```
$ python3 scripts/equivalence/equivalence.py verify --manifest scripts/equivalence/baseline-manifest.json
equivalence: PASS (409 entries)   [exit 0]
$ python3 -m unittest discover -s scripts/equivalence -p 'test_*.py'
Ran 7 tests — OK
```

**Baseline-manifest audit** (checked for smuggling, since the manifest was regenerated): the
diff contains **only** 5 `sha256` changes for the 5 files the commit legitimately edited, plus
`test_cases: 502 → 503`. No entry was added or removed — confirming (a) `LEGACY_TRANSITIONAL_ROOTS`
really was dead code (removing the filter changed the scan by exactly zero entries) and (b) the
removed Python test is not double-counted: `equivalence.py:107-111` counts `test_cases` only
within `category == "tests"`, and `test_equivalence.py` is category `scripts`. So `+1` is exactly
the one new Java test. Nothing hidden.

### Gap 7 — CI integration scope (Minor → closed)

`.github/workflows/ci.yml:95` → `cd feature-control && ./gradlew test -PwithIT --no-daemon`
(root `test` task, no longer `:feature-demo:test :pilot-app:test`), and
`.github/workflows/check_ci_policy.py:35` mirrors it as
`"cd feature-control && ./gradlew test -PwithIT"`.

```
$ python3 .github/workflows/check_ci_policy.py
ci-policy: PASS (matrix, integration, outcomes, quality policies)   [exit 0]
```

---

## Discrimination Sensor (re-run)

**Isolation**: temporary git worktree at `…/scratchpad/sensor-wt2` via
`git worktree add <path> HEAD`; `payment-contracts/build/repository` copied in (gitignored, needed
for GAV resolution); removed with `git worktree remove --force`. **No `git stash` was used.**

- Baseline `git status --porcelain` on the real tree: **empty**
- After `worktree add`: **empty**
- After mutation runs + `worktree remove --force`: **empty** — ✅ isolation verified

| # | File:line | Mutation | Gate run | Killed? |
| - | --------- | -------- | -------- | ------- |
| 2 (re-run) | `payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java:63,85` | Removed **both** `Mdc.fromConsumer(record, env);` call sites — the exact iteration-1 mutation that survived | `./gradlew test --no-daemon --tests "…SimulationMessageHandlerUnitTest"` | ✅ **Killed** — `2 tests completed, 1 failed`, `BUILD FAILED`, failure at `SimulationMessageHandlerUnitTest.java:89` (the during-call `requestId` assertion) |
| 4 (new) | `…/SimulationMessageHandler.java:85` only | Removed **only** the `handleCoreResponse` call site | `./gradlew test --no-daemon` (full quick suite, **45** tests) | ❌ **Survived** — `BUILD SUCCESSFUL`, 45 tests / 0 failures → Minor fix task (below) |

**Control**: the unmutated worktree ran the same class first — `tests="2" … failures="0" errors="0"`
— so the mutant's failure is attributable to the mutation, not to the scratch environment.

Mutations 1 (`equivalence.py` dashboards glob) and 3 (`ResponseCoordinator` inline dispatch) were
killed in iteration 1 and their subject code is untouched by `c8fff22`; not re-run.

**The new test is real, not shallow.** `SimulationMessageHandlerUnitTest.java:80-92` captures MDC
**inside** a `doAnswer` on the `PaymentSimulationService` mock — i.e. at the moment the handler is
mid-processing — and asserts `MDC.get("requestId") == "req-1"` and `MDC.get("topic")` there, then
asserts `MDC.get("requestId")` is null after `handle()` returns. Capturing during the call (not
before/after) is what makes it discriminating; it mirrors the pattern iteration 1 praised in
`ApiPaymentServiceUnitTest.populatesCausationIdInMdcWhilePublishing`.

**Sensor result**: 2 mutations re-run this round, **1 killed / 1 survived** (the survivor is a new,
narrower variant, not a previously-reported one). Cumulative across both iterations: 4 mutations,
3 killed.

---

## Interactive UAT Results

Not performed — infrastructure/backend feature with no user-facing UI surface. Automated checks
are sufficient per validate.md §3.

---

## Code Quality (of `c8fff22` only)

| Principle | Status |
| --------- | ------ |
| No features beyond what was asked | ✅ — 7 gaps in, 7 gaps out; nothing else |
| No abstractions for single-use code | ✅ |
| No unnecessary "flexibility" added | ✅ |
| Only touched files required for task | ✅ — 20 files, each traceable to a numbered gap |
| Didn't "improve" unrelated code | ✅ |
| Matches existing patterns/style | ✅ — the new glob mirrors `equivalence.py:88-96` verbatim; the new test mirrors the API-side MDC test |
| Would senior engineer approve? | ✅ |
| Minimum code / surgical changes | ✅ |
| Tests map to acceptance criteria and are non-shallow | ✅ — the new test kills the mutant it was written for |
| Spec-anchored outcome check (asserted values match spec) | ✅ — CAP-02's row now cites a real dated artifact and correctly qualifies what is and is not certified |
| Per-layer Coverage Expectation met | ⚠️ — `handleRequested`'s MDC wiring is now covered; `handleCoreResponse`'s is not (Fix 1) |
| Every test maps to a spec requirement — no unclaimed tests | ✅ — the 1 new test maps to DOC-03 |
| Documented guidelines followed | ✅ — `AGENTS.md:44` ("um claim de produção exige gate e relatório datado") is now satisfied for CAP-02; the FAIL verdict was left machine-authored |

**Positives worth recording:**
- The fix cycle refused the easy dishonest path twice: it did not hand-edit the capacity report's
  FAIL to PASS, and it did not weaken any assertion to make a gate green.
- Every claimed fix reproduced when this Verifier re-ran it independently — including the
  Postgres cross-check, which returned byte-identical rows to the ones quoted in the addendum.
- The baseline-manifest regeneration is auditable and contains no smuggled entries.
- Fixing `dashboard_validator.sh`'s `DASHBOARDS_DIR` *and* re-running it to prove the fixture is
  detected again is the difference between closing EDG-04 and merely claiming to.

---

## Edge Cases

- [x] **EDG-01** artifact-only local resolution — `artifact-only-consumer: PASS`
- [x] **EDG-02** Flyway immutability — unchanged
- [x] **EDG-03** incompatible Avro requires major — unchanged
- [x] **EDG-04** dashboard on removed metric detected — ✅ **now handled** (10 dashboards scanned; fixture flagged live)
- [x] **EDG-05** unavailable dependency reported NOT_RUN, never PASS — CAP-02's FAIL left standing is a fresh example
- [x] **EDG-06** unrelated local changes preserved — tree clean before, during and after the sensor
- [x] **EDG-07** Core-mock never presented as Core-real certification — unchanged

---

## Gate Check

- **Gate command**: `bash scripts/verify-workspace.sh` (Workspace level, per tasks.md Gate Check Commands)
- **Exit code**: **0** (`verify-workspace: PASS (stage=all)`; the script is `set -euo pipefail` and
  prints that line only after all 8 stages)
- **Preconditions**: sandbox already up; 4 app stacks rebuilt and started via
  `docker compose --env-file .env up -d --build` in each of `payment-core-mock`, `payment-sbus`,
  `payment-api`, `async-redis-service` — all reached `healthy`. **Torn down again afterwards**
  (`docker ps` shows sandbox-only).

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

Identical to iteration 1 — **no side effects from `c8fff22`**. The one failing scenario is still
`redis-unavailable-api` (500 leaking `Unable to connect to redis/<unresolved>:6379` instead of a
fail-closed 503/429), pre-existing and tracked as `task_5a0df80c`. **Not counted as a Verifier
finding.**

**Test count delta**: `payment-sbus` quick suite 44 → **45** (independently counted from
`build/test-results/test/*.xml` in the scratch worktree). No test count decreased anywhere; no
assertion was weakened. The one removed test (`test_legacy_transitional_roots_are_excluded`) was
provably vacuous — its subject constant no longer exists.

---

## Fix Plans

### Fix 1 — `handleCoreResponse`'s MDC wiring is still untested (Minor)

- **Root cause**: `SimulationMessageHandlerUnitTest.handlingARequestedEventPopulatesMdcWithTheEnvelopeCorrelationIdsWhileProcessing`
  exercises only the `Topics.REQUESTED` branch (`SimulationMessageHandler.java:63`). Removing the
  *second* `Mdc.fromConsumer(record, env)` call site at
  `SimulationMessageHandler.java:85` — the `Topics.CORE_RESPONSE` branch — leaves all 45
  payment-sbus quick tests green (verified live in an isolated worktree). The core-response path
  is the one that emits SBUS's *last* log line for a request, which is exactly the line
  `scripts/observability/checks/correlation_ids.sh:23` (`tail -1`) asserts on — and that script is
  still not a `verify-workspace.sh` stage nor a CI job, so a regression there would ship silently.
- **Why Minor, not Major** (iteration 1 ranked the full version Major): the primary ingress path
  that establishes the correlation chain from the API is now guarded; `Mdc.fromConsumer` itself has
  its own unit test (`MdcUnitTest.fromConsumerPopulatesEveryCorrelationFieldFromTheEnvelope`); and
  the residual is one duplicated call site of an already-tested helper, not untested logic.
- **Fix task**: Add a sibling case to `SimulationMessageHandlerUnitTest` for the
  `Topics.CORE_RESPONSE` branch using the same `doAnswer`-on-`handleCoreResponse` capture. While
  there, widen the existing case to also assert `correlationId`/`causationId`/`traceId` during the
  call — its name promises "the envelope correlation ids" but it currently asserts only `requestId`
  and `topic`. Separately (carried over, still open): consider promoting
  `scripts/observability/verify.sh` to a `verify-workspace.sh` stage so DOC-03 has an enforced gate
  at all.
- **Priority**: Minor. Does not block release.

### Carried-over, tracked elsewhere (not Verifier findings)

| Item | Tracked as |
| ---- | ---------- |
| CAP-02 reconciliation methodology (TTL vs. scenario duration) | `task_bca58451` |
| HIGH/CRITICAL CVEs in all 4 images (SEC-08 / MIG-06) | `task_40100c4c` |
| `redis-unavailable-api` returns 500 instead of fail-closed 503/429 (PAY-09) | `task_5a0df80c` |

---

## Requirement Traceability Update

| Requirement | Previous Status (iteration 1) | New Status |
| ----------- | ----------------------------- | ---------- |
| CAP-02 | ❌ Needs Fix — no artifact met the <0.1% threshold | ✅ **Verified** — `load/reports/20260812-095631-capacity-report.md` records `technical_errors=0`, `0.0000%`; "zero silent loss" sub-criterion's automated FAIL is honestly retained and root-caused to gate methodology (`task_bca58451`) |
| EDG-04 | ❌ Needs Fix — validator scanned 0 dashboards | ✅ **Verified** — 10 dashboards scanned; injected fixture detected live |
| DOC-03 | ⚠️ Stale evidence + untested wiring | ✅ **Verified** — `verify-evidence.txt` 12/12; call-site test added and proven to kill the mutant (residual: core-response branch, Fix 1) |
| DOC-05 / DOC-06 | ⚠️ `metrics` component vacuous | ✅ **Verified** — `docs: PASS (251 sections)` with a non-vacuous metrics component |
| ORG-08 | ⚠️ Stale count (389) | ✅ **Verified** — 409, matching the live run |
| MIG-04 | ⚠️ feature-control library ITs excluded from CI | ✅ **Verified** — `ci.yml` runs the root `test -PwithIT`; `check_ci_policy.py` PASS |
| SEC-08 / MIG-06 | ❌ Known, tracked | ❌ Unchanged — `task_40100c4c` |
| PAY-09 | ❌ Known, tracked | ❌ Unchanged — `task_5a0df80c` |
| All other 70 requirements | ✅ Verified | ✅ Unchanged — outside the `c8fff22` diff surface |

---

## Summary

**Overall**: ✅ Ready

**Spec-anchored check**: 7/7 iteration-1 gaps closed and re-verified live; CAP-02 and EDG-04 —
the two blockers — both now carry real, reproducible evidence
**Sensor**: 2 mutations this round, **1 killed** (the iteration-1 survivor, now dead),
**1 survived** (new narrower variant → Minor Fix 1)
**Gate**: `scripts/verify-workspace.sh` exit **0** — 8/8 stages, unchanged from iteration 1

**What works**:
- Every claimed fix was re-derived independently and held. Nothing was taken on trust: the
  Postgres cross-check was re-queried (byte-identical rows), the dashboard glob was re-resolved
  (10 files), the mutant was re-injected in a fresh worktree (now killed), and every script was
  re-executed rather than read.
- The CAP-02 addendum is sound and honestly framed. Its root cause is not merely plausible but
  numerically forced (15m TTL vs. 15m scenario ⇒ exactly the first ~2 of 185 samples are
  unobservable; exactly 2 were reported lost). Leaving `generate_report.py`'s FAIL verdict
  un-overridden was the correct call — hand-editing it would have destroyed the property that
  makes the gate worth having.
- No smuggling: the regenerated `baseline-manifest.json` changes exactly 5 shas and `+1 test_case`,
  with zero entries added or removed.
- The full 8-stage workspace gate is byte-for-byte the same result as iteration 1 — the fixes had
  no side effects.

**Issues found**: 1 Minor (Fix 1) — the `handleCoreResponse` MDC call site remains unguarded by
unit tests. Newly surfaced by an extra mutation this Verifier ran; **not a regression introduced
by `c8fff22`**, and narrower than the Major gap it descends from.

**Next steps**: Release-ready. Route Fix 1 into the backlog alongside `task_bca58451`,
`task_40100c4c` and `task_5a0df80c` — none of which block this feature. No further fix→re-verify
iteration required (stopped at 2 of 3).
