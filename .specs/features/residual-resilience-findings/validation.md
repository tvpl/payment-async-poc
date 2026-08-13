# Residual Resilience Findings Validation

**Date**: 2026-08-13
**Spec**: `.specs/features/residual-resilience-findings/spec.md`
**Diff range**: `05ec130^..d9dff5a` (8 commits)
**Verifier**: independent sub-agent (author ≠ verifier)

---

## Task Completion

| Task | Status  | Notes |
| ---- | ------- | ----- |
| T1   | ✅ Done | `StoreUnavailableException.java` added, commit `05ec130` matches. |
| T2   | ✅ Done | `StoreUnavailableExceptionHandler.java` + unit test added, commit `515c4ab`. |
| T3   | ✅ Done | `RedisStatusStore.java` wraps all 5 Lettuce call sites (`save`, `get`, `reserve`, `markPublishState`, `publishResponse`) in `catch (RedisException e) -> StoreUnavailableException`; `RedisStatusStoreOutageIT` added, commit `854c9fa`. |
| T4   | ✅ Done | `ApiPaymentService.getStatus` preserves the SBUS durable fallback and only rethrows `StoreUnavailableException` when SBUS also has nothing, commit `df37728`. |
| T5   | ✅ Done | `PaymentResponseConsumerUnitTest` gained 2 tests for the apply-stage Redis-outage path; no production code touched, matching the task's own claim, commit `fbe9384`. |
| T6   | ✅ Done | `RedisOutageFailClosedIT.java` added (POST/GET fail closed, no infra leak, recovery without restart), commit `5535d2b`. |
| T7   | ✅ Done | `crash_recovery.sh`'s outbox query now matches `topic IN ('payment.simulation.completed','payment.simulation.failed')` and the failure message reports the simulation's terminal status, commit `9060193`. |
| T8   | ✅ Done | spec.md's Requirement Traceability + dated live-run evidence section added, commit `d9dff5a`. |

`git log --oneline 05ec130^..d9dff5a` matches the 8 commits listed in tasks.md, oldest-first, one commit per task. `git diff --stat 05ec130^..d9dff5a` touches exactly the files each task claims (`RedisStatusStore.java`, `ApiPaymentService.java`, the two new error classes, 4 new/changed test files, `crash_recovery.sh`, `baseline-manifest.json`, plus `spec.md`/`tasks.md` themselves) — no unrelated files.

---

## Spec-Anchored Acceptance Criteria

### P1: Falha fechada sob indisponibilidade do Redis

| Criterion (WHEN X THEN Y) | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| RES-01: IF Redis unreachable WHEN `POST /payment-simulations` THEN `503` with `application/problem+json` | HTTP 503, `Problem` body | `payment-api/src/test/java/com/example/payments/api/RedisOutageFailClosedIT.java:88-90` - `assertEquals(HttpStatus.SERVICE_UNAVAILABLE, postRejected.getStatus())`; media type forced by `@Produces(Problem.MEDIA_TYPE)` at `payment-api/src/main/java/com/example/payments/api/error/StoreUnavailableExceptionHandler.java:18,29` | ✅ PASS |
| RES-02: WHILE Redis unreachable, WHEN `GET` and SBUS fallback responds, THEN `200` with durable status | HTTP 200, durable status entry | `payment-api/src/test/java/com/example/payments/api/service/ApiPaymentServiceUnitTest.java:292-304` - `getStatusFallsBackToSbusWhenRedisIsUnreachable()` asserts `result.isPresent()` and `status == COMPLETED`; the unchanged controller maps a present `Optional` unconditionally to `HttpResponse.ok(...)` at `payment-api/src/main/java/com/example/payments/api/controller/PaymentSimulationController.java:65-68` | ✅ PASS (service-layer unit test + pre-existing controller mapping; no single HTTP-level IT combines a real Redis outage with an SBUS success — acceptable given the Test Coverage Matrix scopes RES-02 to the service/unit layer) |
| RES-03: IF Redis unreachable AND SBUS also has no answer WHEN `GET` THEN `503`, never `404` | HTTP 503, not 404 | `payment-api/src/test/java/com/example/payments/api/RedisOutageFailClosedIT.java:94-97` - `assertEquals(HttpStatus.SERVICE_UNAVAILABLE, getRejected.getStatus())`; unit-level in `ApiPaymentServiceUnitTest.java:306-314` - `getStatusFailsClosedWhenRedisIsUnreachableAndSbusHasNoAnswer()` asserts `assertThrows(StoreUnavailableException.class, ...)` | ✅ PASS |
| RES-04: error body SHALL NOT contain host/port/URI/driver exception text | No `redis`/`lettuce`/port/`unresolved` token in body | `payment-api/src/test/java/com/example/payments/api/RedisOutageFailClosedIT.java:108-113` - `assertNoInfrastructureLeak` checks `redis`, `lettuce`, mapped port; `payment-api/src/test/java/com/example/payments/api/error/StoreUnavailableExceptionHandlerUnitTest.java:35-38,48-51` - asserts serialized body excludes `redis`, `6379`, `unresolved`, and the raw cause message | ✅ PASS |
| RES-05: IF Redis write fails WHEN consumer processes a response THEN it SHALL propagate instead of committing the offset | Offset not committed on unrecoverable failure | `payment-api/src/test/java/com/example/payments/api/kafka/PaymentResponseConsumerUnitTest.java:117-125` - `anApplyFailureIsNeverAcknowledgedIfTheDlqAlsoFails()`: `assertThrows(IllegalStateException.class, () -> consumer.receive(record()))` (uncommitted path); `PaymentResponseConsumerUnitTest.java:104-114` - retries exhausted then DLQ'd, `receive()` returns normally only once landed in a recoverable place | ✅ PASS |
| RES-06: WHEN Redis becomes reachable again THEN `payment-api` SHALL resume accepting without restart | Same running app, next POST → 202 | `payment-api/src/test/java/com/example/payments/api/RedisOutageFailClosedIT.java:104-105` - `await().atMost(10s).untilAsserted(() -> assertEquals(HttpStatus.ACCEPTED, post().getStatus()))`, same `EmbeddedServer`/`HttpClient` instance, no restart | ✅ PASS |

**Status**: ✅ All ACs covered (RES-02 covered via composed unit + unchanged-code evidence, noted above; not a gap).

### P2: Veredito determinístico da matriz de falhas

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| RES-07: setup SHALL locate the terminal outbox row for whichever topic was actually produced | Query matches both terminal topics | `scripts/e2e/payment-failures/scenarios/crash_recovery.sh:17` - `topic IN ('payment.simulation.completed','payment.simulation.failed')` | ✅ PASS |
| RES-08: IF no terminal outbox row exists THEN the scenario SHALL fail identifying the terminal state reached | Failure message includes the request's simulation status | `scripts/e2e/payment-failures/scenarios/crash_recovery.sh:19-24` - `log_fail "${name}" "... (simulation status: ${sim_status:-unknown})"` | ✅ PASS |
| RES-07/08 aux: scenario SHALL NOT depend on the Core's approve/decline decision | Same verdict either way | Code-read only (lightweight check, no live re-run of the full matrix per cost/time tradeoff agreed in the task brief): the query at line 17 has no topic-specific filter left, and the failure path at lines 19-24 is symmetric for both outcomes — reverting to `topic = 'payment.simulation.completed'` only (pre-fix state) would again miss every `FAILED`-topic row, confirmed by reading the pre-fix diff (`git diff 05ec130^..d9dff5a -- scripts/e2e/payment-failures/scenarios/crash_recovery.sh`) | ✅ PASS (lighter-weight evidence, as scoped) |

**Status**: ✅ All ACs covered.

### P3: Evidência datada do gate completo

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| RES-09: WHEN the failure matrix runs live after the fixes THEN result SHALL be `11/11`, dated | 11/11, dated report | `.specs/features/residual-resilience-findings/spec.md:124-146` - dated `2026-08-13`, `payment-failures: 11/11 scenarios (floor is 10)`, `verify-workspace: PASS` | ✅ PASS |

**Status**: ✅ All ACs covered.

**Overall spec-anchored tally**: 9/9 acceptance-criteria rows matched their spec-defined outcome with direct evidence. 0 spec-precision gaps, 0 uncovered criteria.

---

## RES-05 "already correct" claim — independently re-derived

Spec's Assumptions table claims `PaymentResponseConsumer` already failed closed before this feature, and T5 only added test coverage. Read `payment-api/src/main/java/com/example/payments/api/kafka/PaymentResponseConsumer.java` directly to check this claim (not the author's word):

- `@KafkaListener(..., offsetStrategy = OffsetStrategy.SYNC_PER_RECORD, ...)` (line 50) — Micronaut Kafka commits the offset only after `receive()` returns normally.
- `receive()` (lines 85-98): a decode failure not classified as capacity goes to `deadLetters.route(...)` and returns normally (intentional — decoded-garbage case, out of RES-05's scope); everything else calls `applyWithinBudget`.
- `applyWithinBudget` (lines 112-128): retries `apply()` up to `maxAttempts`, and if all attempts fail, calls `deadLetters.route(record, STAGE_APPLY, lastFailure)` — no swallow, no early return on exhaustion beyond that call.
- `ResponseDeadLetters.route()` (`payment-api/src/main/java/com/example/payments/api/kafka/ResponseDeadLetters.java:13,33`) - "cannot confirm the record, the caller rethrows and the offset stays uncommitted" - confirmed the DLQ write itself propagates on failure rather than swallowing.

Net: a `StoreUnavailableException` from `apply()` during a Redis outage either lands in the DLQ (offset commits, result safe) or the DLQ write itself fails (exception propagates out of `receive()`, offset does not commit). No path acks silently. The claim holds — this was a genuine pre-existing correctness property, not something T5 introduced. No `payment-api` production file outside the diff's declared scope needed a fix here.

---

## Discrimination Sensor

Ran in an isolated scratch (full repo copy under `/private/tmp/.../scratchpad/repo-scratch{,2}`, built via `rsync -a --exclude=build/ --exclude=.gradle/`, sibling Maven repos `payment-contracts/build/repository` and `feature-control/build/repo` copied in read-only since `payment-api`'s build resolves them via relative paths). `git stash` was never used. Baseline `git status --porcelain` on the real `payment-api` tree was empty before sensor work and confirmed empty again after — the real tree was never touched.

| Mutation | File:line | Description | Killed? |
| --- | --- | --- | --- |
| 1 | `payment-api/src/main/java/com/example/payments/api/redis/RedisStatusStore.java:85-91` (`get`) | Removed the `try/catch (RedisException e) -> StoreUnavailableException` around `commands().get(...)`, letting the raw Lettuce exception escape | ✅ Killed — `RedisStatusStoreOutageIT > getFailsClosedWhenRedisIsUnreachable()` FAILED; the other 4 tests in the same class (`save`, `reserve`, `markPublishState`, `publishResponse`) still PASSED, confirming the failure is precisely localized to the mutated method, not a broad environment problem |
| 2 | `payment-api/src/main/java/com/example/payments/api/service/ApiPaymentService.java` (`getStatus`, the `if (storeFailure != null) throw storeFailure;` rethrow) | Removed the rethrow, so a Redis outage with no SBUS answer silently returns the empty `local` Optional instead of propagating `StoreUnavailableException` | ✅ Killed — `ApiPaymentServiceUnitTest > getStatusFailsClosedWhenRedisIsUnreachableAndSbusHasNoAnswer()` FAILED; all 17 other tests in the class still PASSED |
| 3 (lightweight, code-read only) | `scripts/e2e/payment-failures/scenarios/crash_recovery.sh:17` | Reasoned mutation: revert the topic filter to `topic = 'payment.simulation.completed'` only (the pre-fix state, visible directly in the T7 diff) | Reasoned, not re-run live: with a Core decline, the terminal topic is `payment.simulation.failed`, so the single-topic filter finds no `PUBLISHED` row and the setup fails spuriously — exactly the defect this feature closes. Full live matrix re-run was judged not worth the cost per the task brief; the diff itself is the proof the old filter is a strict subset of the new one |

**Sensor depth**: lightweight (2 full Java mutations run and killed in isolation, 1 reasoned check on the bash gate script per the task's own cost/time guidance).
**Result**: 2/2 executable mutations killed - PASS. Real worktree confirmed byte-identical before/after (`git status --porcelain` empty both times).

---

## Code Quality

Spot-checked: `StoreUnavailableException.java`, `StoreUnavailableExceptionHandler.java`, `RedisStatusStore.java`, `ApiPaymentService.java`, `crash_recovery.sh`.

| Principle | Status |
| --- | --- |
| Minimum code | ✅ — `StoreUnavailableException`/`Handler` are minimal, mirror `PublishFailedException`/`PublishFailedExceptionHandler` almost line-for-line (same package, same `@Requires`/`@Produces` shape); `RedisStatusStore` only wraps existing call sites, no new abstractions |
| Surgical changes | ✅ — `RedisStatusStore.java` diff (`+79/-`) is confined to wrapping the 5 Lettuce calls in try/catch plus a javadoc update; no unrelated formatting or refactor noise |
| No scope creep | ✅ — `ApiPaymentService.getStatus` change is a single method; controller, `RedisRateLimiter`, and health/readiness endpoints are untouched, matching the spec's explicit Out-of-Scope table |
| Matches patterns | ✅ — new exception/handler pair follows the existing `PublishFailedException` convention; `RedisOutageFailClosedIT` follows the existing `AdmissionRedisOutageIT` pattern for stopping/pausing a real Redis container |
| Spec-anchored outcome check | ✅ — see Spec-Anchored Acceptance Criteria table above; every assertion targets the exact status code / body content the spec names |
| Per-layer Coverage Expectation met | ✅ — error handler has unit coverage of both branches (leak-free body, cause never surfaced); Redis store has IT coverage of every public method's error path; service has unit coverage of all 3 branches (SBUS answers / SBUS silent / Redis healthy); consumer has unit coverage of success + DLQ-success + DLQ-failure |
| Every test maps to a spec AC | ✅ — no unclaimed tests found; each new test file's javadoc cites the RES-* ID(s) it covers |
| Documented guidelines followed | ✅ — `payment-api/AGENTS.md` §Gates and "tests are the executable spec" followed; test count only grew (111→114 files, 519→532 manifest test_cases), never shrank |

---

## Edge Cases

- [x] Redis falls mid-flight between idempotency reservation and Kafka publish → still 503 via the existing `PUBLISH_FAILED` path (unchanged code, not part of this diff, but not regressed — `ApiPaymentServiceUnitTest > marksTheReservationPublishFailedWhenTheBrokerRejectsTheSend()` still passes)
- [x] Redis returns mid-request → `RedisOutageFailClosedIT` proves reconnection without restart
- [x] `RedisRateLimiter` degraded-admission fallback unchanged → `RedisRateLimiterUnitTest` (5 tests) all pass unmodified, confirming the explicit Out-of-Scope decision was honored
- [x] Core declines the simulation in the outbox scenario → `crash_recovery.sh:17` query now covers `payment.simulation.failed`

---

## Gate Check

- **Gate command**: `cd payment-api && PAYMENT_API_KEY=local-test-key-not-a-real-secret JWT_SIGNATURE_SECRET=test-only-api-signing-secret-with-at-least-32-bytes ./gradlew test -PwithIT --no-daemon`
- **Result**: 134 passed, 0 failed, 0 skipped. `BUILD SUCCESSFUL in 4m 54s`.
- **Note on methodology**: the first invocation returned `BUILD SUCCESSFUL in 3s` with every task `UP-TO-DATE` — Gradle had reused a prior run's cached result rather than actually executing the tests. Re-ran with `--rerun-tasks` to force genuine execution (also stopped the pre-existing `payment-api-api-1` container bound to port 8080 first, per the known false-failure mode, and restarted it afterward). The 134/0/0 figures above are from the forced fresh run.
- **Test count before feature** (equivalence manifest): `tests` category 111 files / `test_cases` 519
- **Test count after feature** (equivalence manifest, this diff): `tests` category 114 files / `test_cases` 532
- **Delta**: +3 test files (`RedisStatusStoreOutageIT`, `RedisOutageFailClosedIT`, `StoreUnavailableExceptionHandlerUnitTest`), +13 test_cases (manifest-wide count, spans more than just payment-api's own JUnit run)
- **Skipped tests**: none
- **Failures**: none

---

## Requirement Traceability Update

spec.md's own table already marked all 9 requirements `Verified` before this validation ran. Independently re-derived and confirmed accurate — no entry needed correction.

| Requirement | Previous Status | New Status |
| --- | --- | --- |
| RES-01 | Verified | ✅ Verified (confirmed) |
| RES-02 | Verified | ✅ Verified (confirmed) |
| RES-03 | Verified | ✅ Verified (confirmed) |
| RES-04 | Verified | ✅ Verified (confirmed) |
| RES-05 | Verified (já correto) | ✅ Verified (confirmed — claim independently re-derived from source, holds) |
| RES-06 | Verified | ✅ Verified (confirmed) |
| RES-07 | Verified | ✅ Verified (confirmed) |
| RES-08 | Verified | ✅ Verified (confirmed) |
| RES-09 | Verified | ✅ Verified (confirmed) |

---

## Summary

**Overall**: ✅ Ready

**Spec-anchored check**: 9/9 ACs matched spec outcome, 0 spec-precision gaps
**Sensor**: 2/2 executable mutations killed (1 additional reasoned check on the bash gate script, as scoped)
**Gate**: 134 passed, 0 failed, 0 skipped

**What works**: `RedisStatusStore` fails closed on every public method against a genuinely stopped/paused Redis; `StoreUnavailableExceptionHandler` maps that to 503 problem+json with no infrastructure leak; `ApiPaymentService.getStatus` keeps the SBUS durable fallback alive during a Redis outage and only surfaces 503 (never 404) when both stores are silent; `PaymentResponseConsumer`'s pre-existing offset-safety was independently confirmed, not just asserted; the outbox-reclaim gate scenario no longer depends on the Core's approve/decline coin flip; the live 11/11 run is recorded with a date.

**Issues found**: none blocking. One soft note: RES-02 has no single HTTP-level IT that combines a real Redis outage with a live SBUS success response in one test (only unit-level service coverage + unchanged controller code prove the 200 path) — acceptable given the Test Coverage Matrix's own layer assignment, but a future hardening pass could add a dedicated IT if the team wants belt-and-suspenders HTTP-level proof.

**Next steps**: none required to close this feature. Optional follow-up (not a gap): an HTTP-level IT for RES-02's success path, if desired.
