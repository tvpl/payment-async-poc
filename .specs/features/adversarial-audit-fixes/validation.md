# Adversarial Audit Fixes Validation

**Date**: 2026-08-14
**Spec**: `.specs/features/adversarial-audit-fixes/spec.md`
**Diff range**: `5f1d23f..3fed8a0` (T1's actual first commit through T24's closing commit; the range hinted in the verification brief, `274b0ca..HEAD`, was wrong — `274b0ca` is T17's commit, not T1's; corrected by independently walking `git log --oneline main..HEAD`)
**Verifier**: independent sub-agent (author ≠ verifier). 5 parallel research sub-agents were used for read-only spec-anchored evidence gathering (results re-derived and spot-checked by this Verifier, not trusted verbatim); the discrimination sensor and live-gate re-confirmation were run directly by this Verifier, not delegated.

**Overall verdict: PASS**, with 5 reportable gaps (0 blockers, 1 major-adjacent documentation-accuracy issue, 4 minor/residual). None invalidate the correctness of the 27 AUD-xx fixes; all are either overclaimed test-methodology wording or pre-existing/cosmetic issues outside T1-T24's actual diff surface.

---

## Task Completion

| Task | Status | Notes |
| --- | --- | --- |
| T1 | ✅ Done | Latch logic correct and discrimination-confirmed (see sensor). Test methodology overclaimed — see Gap 1. |
| T2 | ✅ Done | Backoff-window IT asserts on an attempt counter, not timing, as required. |
| T3 | ✅ Done | Discrimination-confirmed. |
| T4 | ✅ Done | Noop-audit and generation-guard both precisely asserted. |
| T5 | ✅ Done | Dual-budget Lua + rollback verified against design.md's script; IT proves non-consumption of route budget. |
| T6 | ✅ Done | `/v0` anonymous-tenant pooling traced end-to-end; composes correctly with T5 (see Cross-Cutting §3). |
| T7 | ✅ Done | try/finally waiter cleanup, 3 injected-failure unit tests. |
| T8 | ✅ Done | Discrimination-confirmed (money-correctness fix, AUD-18). |
| T9 | ✅ Done | 400 problem+json and cardinality-collapse both value-precise. |
| T10 | ✅ Done | Discrimination-confirmed (AUD-01, the original critical bug). Fingerprint byte-for-byte parity with payment-api confirmed (see Cross-Cutting §2). |
| T11 | ✅ Done | Stranded-replay IT asserts terminal resolution, no orphaned PROCESSING row. |
| T12 | ✅ Done (spot-check) | `OutboxBatchResilienceIT.aSlowBatchRenewsTheRemainingClaimedRowsSoNoneIsEverLeftToOutliveItsLease` asserts `claimed_at` freshness per-row before its own turn — precise, not timing-loose. |
| T13 | ✅ Done (spot-check) | `RecoverableDeadLetterIT` asserts exact `attempts`, `nextAttemptAt` advance, and `DLQ_PENDING` status. |
| T14 | ✅ Done (spot-check) | `DependencyReadinessIT` asserts exact `HttpStatus.OK`/`SERVICE_UNAVAILABLE` transitions and retry-vs-DLQ routing by outbox topic. |
| T15 | ✅ Done | Groups/max.poll config and arithmetic confirmed. IT proves business-layer idempotency only, not a real Kafka rebalance/re-read — see Gap 2 (matches design.md's own stated bar, not a deviation from design). |
| T16 | ✅ Done | initialDelay config, LIMIT purge, index, and replay-payload `requestId` rewrite all confirmed with exact assertions. |
| T17 | ✅ Done | Concurrent-replay IT asserts exact `XLEN`/accepted-count of 1 on real Redis. |
| T18 | ✅ Done | Discrimination-confirmed (AUD-13). |
| T19 | ✅ Done (spot-check via research agent) | Lease-renewal-no-steal and denied-renewal-aborts both asserted with real competing claimant, not a mock. |
| T20 | ✅ Done (spot-check via research agent) | Connection-close verified via invocation counter; TTL validation verified via `assertThrows` at a real `@PostConstruct`. |
| T21 | ✅ Done | AD-007 registered in STATE.md; `application.yml` confirms `limit-for-period: 20` / `tenant-limit-for-period: 10`. |
| T22 | ✅ Done | `generate_report.py selftest` bad-mix fixture independently re-run: fails a synthetic 70%-429 mix exactly as the revoked 2026-08-12 report's shape would require. |
| T23 | ✅ Done | Report authenticity independently re-derived from raw k6 JSON/stdout timestamps — genuine, not fabricated. Rendered `.md` omits avg latency/tenant-split (present in raw artifacts) — see Gap 3. |
| T24 | ✅ Done | Re-ran live in this session: 8/8 stages PASS. One stage (`payment-failures`) showed 10/11 in this Verifier's own live run (within its own documented 10/11 floor) due to a pre-existing, unrelated test-harness bug — see Gap 4. |

---

## Spec-Anchored Acceptance Criteria

Evidence gathered by 5 parallel read-only research passes and independently spot-verified by this Verifier (MasterSwitchIT, IdempotencyFingerprint files, and all discrimination-sensor targets were read directly by the Verifier, not taken on the sub-agents' word).

### P1: Nenhum caminho de dinheiro errado ou segurança furada

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| AC1 (AUD-01) divergent-fingerprint replay → new sim, own result | New `simulationId`, result computed from B's own Core round-trip | `payment-sbus/.../service/PaymentPersistenceService.java:78-82` (fingerprint filter) + `payment-sbus/src/test/java/.../IdempotencyReplayIT.java:195` `sameKeyWithDivergentPayloadProcessesAsANewSimulationInsteadOfCopyingTheOriginalsResult` — `assertNotEquals(original.getSimulationId(), divergent.getSimulationId())`, own PROCESSING round-trip, own terminal result | ✅ PASS (discrimination-confirmed, see sensor #3) |
| AC2 (AUD-02) latch stays armed through Redis outage, both stale-fallback modes | `isKilled()==true` during and after `max-stale`, both `BASELINE`/`FAIL_CLOSED` | `feature-control/.../resolver/MasterSwitch.java:64-78` (`resolveViaLatch`) + `MasterSwitchIT.java:99,113,129` (`assertTrue(masterSwitch.isKilled(), ...)`) | ✅ PASS (discrimination-confirmed, see sensor #1) — ⚠️ see Gap 1 on test-methodology wording |
| AC3 (AUD-14) `find()` serves stale policy without lock/Redis during a ≥1s backoff window | ≤~1 Redis attempt/key/window, asserted via attempt counter | `feature-control/.../store/RedisFlagSource.java` (failure-backoff timestamp) + `RedisFlagSourceIT.java:215-231` — `assertEquals(1, reader.callCount())`, `assertEquals(2, ..., "reads within the failure-backoff window must not re-attempt Redis")` | ✅ PASS |
| AC4 (AUD-04) VARIANT off-variant → `isOn()==false` | Exact boolean `false` for a bucketed-into-control-arm user | `feature-control/.../resolver/FeatureResolver.java:116` (`!chosen.name().equals(def.offVariant())`) + `FeatureResolverUnitTest.java:143-145` — `assertFalse(d.isOn(), ...)` | ✅ PASS (discrimination-confirmed, see sensor #2) |
| AC5 (AUD-05) atomic dual-budget, route token returned on tenant denial | Single `EVAL`; tenant-denied request never consumes route budget | `payment-api/.../ratelimit/RedisRateLimiter.java:35-46` (Lua matches design.md §3 exactly, incl. `DECR` rollback) + `AdmissionControlIT.java:135-155` — quiet tenant still admitted for its *full* budget after noisy tenant exhausts its own | ✅ PASS |
| AC6 (AUD-05) `/v0` uses fixed anonymous tenant bucket | Rotating `X-API-Key` never mints a new bucket | `payment-api/.../filter/ConcurrencyLimitFilter.java:75-84` (`tenant()` short-circuits to `ANONYMOUS_TENANT` for `/v0/` before reading the header) + `AdmissionControlIT.java:164-192` — admission bounded at tenant budget (2), not route budget (6) | ✅ PASS |
| AC7 (AUD-03) `ENQUEUE_FAILED→PROCESSING` atomic CAS; concurrent replay loses without enqueueing | Exactly one XADD on concurrent replay | `async-redis-service/.../api/JobStatusStore.java` (`CAS_STATUS_LUA`) + `EnqueueFailedCasIT.java:125-129` — `assertEquals(1, accepted.get())`, `assertEquals(1L, conn.sync().xlen(stream))`, real 12-thread race against real Redis | ✅ PASS |
| AC8 (AUD-03) `markEnqueueFailed` after worker completion preserves terminal status | `COMPLETED` unchanged, `GET` still returns original result | `EnqueueFailedCasIT.java:146-149` — `assertInstanceOf(JobStatusView.Completed.class, ...)` + exact result equality | ✅ PASS |

### P2: Disponibilidade sob falha real (spot-checked, representative sample)

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| AC7 (AUD-11) replay racing Core response not stranded | Replay resolves to terminal `COMPLETED`, no orphaned `PROCESSING` row | `payment-sbus/.../service/PaymentSimulationService.java` (`registerReplayInFlight` re-check) + `IdempotencyReplayIT.java:253-283` — `assertTrue(nowTerminal.isPresent())`, `assertTrue(messages.findByRequestId(replayRequestId).isEmpty())` | ✅ PASS |
| AC2 (AUD-07) per-row lease renewal during a slow batch | No row outlives its lease mid-batch | `payment-sbus/.../outbox/OutboxDispatcher.java:87-100` + `OutboxBatchResilienceIT.java:197-224` — asserts `claimedAtIsRecent()` per remaining row before its own turn, via a deterministic backdate-then-check mock sequence (not a flaky sleep) | ✅ PASS |
| AC3 (AUD-08) reaper accumulates attempts/backoff → DLQ | `attempts+1`, `next_attempt_at` advanced, eventual `DLQ_PENDING` | `payment-sbus/.../repository/OutboxEventRepository.java:59,73` + `RecoverableDeadLetterIT.java:129-132` — exact `attempts==1`, `nextAttemptAt().isAfter(beforeFailure)`, `status=="DLQ_PENDING"` | ✅ PASS |
| AC9 (AUD-13) DLQ job → `FAILED`, `GET` 200 | Exact HTTP 200 + `status:"FAILED"` | `async-redis-service/.../worker/JobWorker.java:225-231,240-245` (`markFailed` before `xack`, both paths) + `AsyncFailedStatusIT.java:120-123,153-154` — `assertEquals(HttpStatus.OK, ...)`, `assertEquals("FAILED", polled.body().status())` | ✅ PASS (discrimination-confirmed, see sensor #4) |
| AC8 (AUD-12) reclaim scan renews lease per entry, aborts on denial | No second worker steals turn; denied renewal → partial processing only | `ReclaimScanLeaseRenewalIT.java:151-168,227-234` — `assertFalse(intruderEverWonTheTurn)` against a real competing claimant loop; `assertTrue(resultsPresent < jobIds.size())` after actively stealing the lease key | ✅ PASS |
| AC6 (AUD-10) distinct consumer groups, `max.poll.interval.ms` > 30min retry budget | Groups literally distinct; interval strictly greater than the computed budget | `PaymentRequestedConsumer.java:38`, `CoreResponseConsumer.java:33`, `application.yml:60` (`2100000`ms=35min) + `ConsumerErrorStrategyUnitTest.java:52-89` — budget computed live from `retryCount=900 × retryDelay=2s = 1800s = 30min exactly`; 35min gives a real, computed 5-min margin | ✅ PASS |
| AC9 (AUD-09) readiness real, Registry outage → retry not poison | Readiness DOWN, valid payload retried not dead-lettered | `payment-sbus/.../health/{Kafka,Postgres,Redis,Registry}HealthIndicator.java` + `DependencyReadinessIT.java:72,90,97,99` — exact `HttpStatus` transitions, outbox-row-exists assertions distinguishing retry topic from DLQ topic | ✅ PASS |

### P3: Recalibração de capacidade e evidência honesta

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| AC1 (AUD-30) AD-007 registered, AD-006 superseded | Exact targets: 1,000/15min, 2,000/60s spike, avg≤300ms, p99≤10s | `.specs/STATE.md` — AD-006 `Status: superseded by AD-007`; AD-007 `Status: active`, full target text matches spec verbatim | ✅ PASS |
| AC2 (AUD-30) route 20/s, tenant 10/s | Exact limiter values in YAML + docs | `payment-api/src/main/resources/application.yml:92-93` — `limit-for-period: 20`, `tenant-limit-for-period: 10`; doc-drift gate confirms no divergence (see Cross-Cutting §4) | ✅ PASS |
| AC3 (AUD-30) k6 ≥2 tenants, verdict fails `429>1%` steady | Real 2-key distribution; verdict function actually fails on bad mix | `load/k6/capacity.js:103` (`exec.scenario.iterationInInstance % API_KEYS.length` — true chronological split, replacing the buggy `(VU+ITER)%2`) + `load/capacity/generate_report.py` `selftest` — independently re-run, `bad_mix_only_passed: false`, reason `"429 rate 70.0000% > 1.0000%..."` | ✅ PASS |
| AC4 (AUD-30) k6 thresholds avg≤300ms, p99≤10000ms active | k6 threshold config present and satisfied live | Confirmed in `steady.stdout.txt` (`avg=293.69ms ... p(99)=421.64ms`, k6's own `✓` threshold check) — raw artifact, independently timestamp-verified as a genuine sequential run (before/after JSON mtimes exactly match scenario durations) | ✅ PASS |
| AC5 (AUD-30) dated report shows status mix; CAP-02 revoked and repointed | Report shows `200/202/422/429` counts; CAP-02 entry marked REVOGADO with new pointer | `load/reports/20260814-123447-capacity-report.md` shows `200=13716 202=0 422=1585 429=0`; `repository-segregation-production-hardening/spec.md` CAP-02 row contains literal `**REVOGADO (2026-08-14, ...)**` text and points to the new report | ✅ PASS — ⚠️ see Gap 3 (avg latency/tenant-split present in raw JSON but not rendered into the `.md`) |

### P4: Higiene (spot-checked)

All 12 P4 criteria (AUD-16,17,18,19,20,21,22,23,24,25,26,27) were independently confirmed with `file:line` + exact-value assertions by the boundary-scoped research passes and are not repeated in full here to keep this report scoped; full detail is in the per-boundary research already folded into the Task Completion table above. Two are highlighted for their money/security weight:

| Criterion | Spec-defined outcome | `file:line` + assertion | Result |
| --- | --- | --- | --- |
| AC2 (AUD-18) fingerprint delimiter-escaping — no collision | Two distinct payloads (`pm\|X`+`1` vs `pm`+`X\|1`) must not collide | `payment-api/.../idempotency/IdempotencyFingerprint.java:45-47` (backslash escaped before delimiter) + `IdempotencyFingerprintUnitTest.java:80-88` `doesNotCollideWhenAFieldContainsTheDelimiterItself` — literally constructs the task-specified colliding pair, `assertNotEquals(...)` | ✅ PASS (discrimination-confirmed, see sensor #5) |
| AC6 (AUD-22) all-zero VARIANT weights → off | `select()` returns `null`, not the first variant | `feature-control/.../bucketing/Bucketer.java` + `BucketerUnitTest.java:85,93` — `assertNull(Bucketer.select(zero, ...))` for both single- and multi-variant zero-weight cases | ✅ PASS |

**Status**: ✅ All 27 AUD-xx requirements covered with `file:line` evidence and precise-value assertions. 0 uncovered. 1 spec-precision gap on test-methodology wording (Gap 1, T1/AUD-02).

---

## Discrimination Sensor

Run directly by this Verifier (not delegated) in rsync-based scratch copies under `/private/tmp/claude-501/.../scratchpad/verifier-mutations/<boundary>/`, never `git stash`. Baseline `git status --porcelain` on the real tree was empty before the sensor run and confirmed identical after every mutation cycle.

| # | Boundary | File:line | Mutation | Test run | Killed? |
| - | --- | --- | --- | --- | --- |
| 1 | feature-control | `MasterSwitch.java:76` | `case UNAVAILABLE -> lastKnownKilled.get();` → `case UNAVAILABLE -> false;` (reintroduces AUD-02: outage reads as "not killed") | `:feature-control:test -PwithIT --tests "*MasterSwitchIT*"` | ✅ Killed — 3/5 tests failed (`armedLatchSurvivesAnOutageWithinMaxStaleUnderBaselineFallback`, `...BeyondMaxStale...Baseline`, `...BeyondMaxStale...FailClosed`), exactly the 3 outage-latch assertions; the 2 unrelated tests (ABSENT-disarm, cold-start) correctly kept passing |
| 2 | feature-control | `FeatureResolver.java:116` | `boolean isOn = !chosen.name().equals(def.offVariant());` → `boolean isOn = true;` (reintroduces AUD-04: off-variant reports on) | `:feature-control:test --tests "*FeatureResolverUnitTest*"` | ✅ Killed — `variantBucketedIntoTheOffVariantReportsIsOnFalse` failed (`expected: <false> but was: <true>`); 12/13 other tests correctly unaffected |
| 3 | payment-api | `IdempotencyFingerprint.java:30-37` | Removed `escape(...)` calls from all string fields (reintroduces AUD-18: delimiter-injection collision) | `./gradlew test --tests "*IdempotencyFingerprintUnitTest*"` | ✅ Killed — `doesNotCollideWhenAFieldContainsTheDelimiterItself` failed (both fingerprints identical); 7/8 other tests correctly unaffected |
| 4 | payment-sbus | `PaymentPersistenceService.java:80-81` | Removed the fingerprint-equality `.filter(...)` clause from `findReplayTarget` (reintroduces AUD-01: key-only replay match) | `./gradlew test -PwithIT --tests "*IdempotencyReplayIT*"` | ✅ Killed — `sameKeyWithDivergentPayloadProcessesAsANewSimulationInsteadOfCopyingTheOriginalsResult` AND `legacyRecordWithNullFingerprintIsTreatedAsNonReplay` both failed; 3/5 other tests correctly unaffected |
| 5 | async-redis-service | `JobWorker.java:230` | Removed `markFailed(body);` from `deadLetterExceeded` (reintroduces AUD-13: max-deliveries DLQ path never reaches FAILED) | `./gradlew test -PwithIT --tests "*AsyncFailedStatusIT*"` | ✅ Killed — `aPoisonedJobEndsUpDeadLetteredAndPolledAsFailed` failed (`expected: <OK> but was: <ACCEPTED>` — job silently stayed PROCESSING); the unmutated `deadLetterMalformed` path's test correctly still passed |

**Sensor depth**: P0/critical-path tier applied (5 mutations across all 4 boundaries — exceeds the ≥5 full-mutation-run bar for payment/security-critical code, per the skill's tiering table). Every mutation targeted the exact behavior the audit finding it fixes was about (a kill-switch that disarms, a variant that reports on, a fingerprint that collides, a replay that copies the wrong result, a DLQ job that never becomes observable).

**Result**: 5/5 killed — **PASS**.

**Isolation verification**: `git status --porcelain` on the real tree captured before the sensor run and re-diffed after every mutation cycle — identical (empty) each time. Every scratch copy was deleted immediately after its test run.

---

## Cross-Cutting Coherence Checks

**1. T15 max.poll vs. 30-minute retry budget**: `ConsumerErrorStrategyUnitTest` computes the retry budget live from the production annotations (`retryCount=900 × retryDelay=2s = 1800s = exactly 30min`), and asserts `application.yml`'s `max.poll.interval.ms: 2100000` (35min) exceeds it. The margin is real but modest (5min) — not a hardcoded/stale assumption, holds arithmetically. ✅ Coherent.

**2. T10 fingerprint parity with payment-api's T8 fix**: `payment-api/.../idempotency/IdempotencyFingerprint.java` and `payment-sbus/.../service/IdempotencyFingerprint.java` were read side-by-side by the research pass and re-confirmed by this Verifier reading `payment-api`'s copy directly during the sensor mutation. Both use identical `DELIMITER="|"`, identical field order (merchantId, amount, currency, paymentMethod, brand, installments, captureMode), identical `escape()` (backslash-then-pipe), identical `BigDecimal.stripTrailingZeros().toPlainString()` normalization, identical SHA-256+hex encoding. **Byte-for-byte match confirmed** — a legitimate identical payload cannot be misclassified as divergent across the boundary. ✅ Coherent.

**3. T5→T6 composition**: Traced end-to-end — `ConcurrencyLimitFilter.tenant()` short-circuits to the literal constant `"anonymous"` for any `/v0/` path *before* reading `X-API-Key` at all, so a rotating key can never mint a per-request bucket. The same `"anonymous"` string is shared with unauthenticated main-route traffic through the one `tenantLimiter` singleton — pooled at the tenant layer, never double-counted, never bypassing it. `resource` (route budget key) still includes the full path, so `/v0/...` keeps its own separate route budget from `/payment-simulations`. `AdmissionControlIT`'s v0-rotating-key test empirically bounds admission at the tenant budget (2 in test config), not the larger route budget (6), proving the pooling lands where the design says it should. ✅ Composes correctly.

**4. Doc-drift gates, run for real, all boundaries**: `feature-control/scripts/validate_docs.py`, `payment-sbus/scripts/validate_docs.py`, `payment-api/scripts/validate_docs.py`, `async-redis-service/scripts/validate_docs.py`, `payment-contracts/scripts/validate_docs.py`, `payment-core-mock/scripts/validate_docs.py`, `sandbox/scripts/validate_docs.py`, and the workspace-wide `scripts/docs/validate_docs.py` — all exit 0 / PASS, independently re-run this session (not trusted from a prior self-report). Config drift from AD-007's admission recalibration is correctly reflected everywhere the gates check.

---

## Live Gate Re-Confirmation

Run directly by this Verifier in this session, after the orchestrator's earlier Docker recovery (host disk freed, Docker Desktop restarted).

- **`docker ps`**: all 4 app containers (`payment-api`, `payment-sbus`, `payment-core-mock`, `async-redis-service`) `Up ... (healthy)`; all sandbox infra (Kafka, Postgres, Redis, Registry, Prometheus, Grafana, Jaeger, exporters) `Up`/`healthy`. Confirmed both before and after this Verifier's full test/sensor/gate workload — no destabilization.
- **`scripts/verify-workspace.sh` (stage=all)**, run live end-to-end by this Verifier: **8/8 stages PASS** — `equivalence: PASS (436 entries)`, `no-composite-build: PASS (4 boundaries)`, `artifact-only-consumer: PASS`, `e2e-payment: SMOKE OK`, `e2e-async-redis: SMOKE OK`, `payment-failures: PASS (10/11 scenarios, floor is 10)`, `async-redis-failures: PASS (34/34 assertions)`, `hygiene: git diff --check` clean. Final line: `verify-workspace: PASS (stage=all)`.
- **Deviation from the prior self-report**: T24's own evidence claimed `payment-failures: 11/11`; this Verifier's independent live re-run got `10/11` (still within the stage's own documented pass floor, so the overall gate still legitimately PASSes). Root-caused directly (not assumed): `outbox-crash-window-reclaim` failed with `before= after=` (both empty). Queried the actual row (`docker exec ... psql ... SELECT topic, status FROM outbox_event WHERE id=280127` → `topic=payment.simulation.failed`, i.e. `payment-core-mock` probabilistically **declined** this run's payment). `scripts/e2e/payment-failures/scenarios/crash_recovery.sh:48` asserts `[ -z "$after_auth" ]` as a failure condition, but `result.authorizationCode` legitimately does not exist on a DECLINED payment — so both `before_auth` and `after_auth` are correctly, consistently empty, and the script's assertion cannot tell "value changed" apart from "field never existed on either side." This is a **pre-existing test-harness bug, unrelated to any T1-T24 diff** (`crash_recovery.sh` was not touched by this feature) — see Gap 4.

---

## Requirement Traceability Verification

All 27 rows in `spec.md`'s traceability table were cross-checked against `git log --oneline main..HEAD`: every cited commit hash (`5f1d23f` through `3fed8a0`, 25 commits mapping to T1-T24, with T20 correctly carrying two commits `bf66337`+`434df7f`) exists in the real history in the claimed order. `0 não mapeados` confirmed. Table itself is accurate; no ranking discrepancy found.

---

## Gaps (none blocking; ranked by severity)

1. **[Documentation-accuracy, T1/AUD-02]** `tasks.md`'s T1 Done-when and `spec.md`'s P3 Success Criteria both literally claim "IT com Redis real parado" ("IT with real Redis stopped") / "Kill-switch comprovado armado durante queda real do Redis." The actual test (`MasterSwitchIT.java`) never stops the real Redis process — it wraps a real `FlagKeyReader` in `FailableFlagKeyReader`, which throws an in-process `RuntimeException` on `get()` when `setFailing(true)`, while the underlying Redis (`localhost:6379`, shared sandbox instance per AD-003) keeps running normally throughout. The discrimination sensor confirms the *code path being tested* is correct regardless of failure-injection method (the `MasterSwitch` only ever sees "the reader threw" vs. "the reader returned," so a real outage would exercise the identical branch) — so this is not a functional gap, only an overclaimed methodology description. Given AD-003 (only `/sandbox` owns infra; feature-control's tests convention is real Redis, no Testcontainers, and the shared instance can't be stopped mid-test without disrupting concurrent test runs), simulated-exception injection is a defensible engineering tradeoff — but the spec/tasks wording should say so accurately instead of claiming a literal outage. **Recommendation**: reword `tasks.md` T1 and `spec.md`'s Success Criteria to describe the test as "simulated Redis-read failure (real Redis stays up; AD-003 makes a literal outage impractical for a shared instance)" rather than "Redis real parado."
2. **[Residual coverage note, T15/AUD-10]** `ConsumerGroupReplayIsInertIT` proves business-layer idempotency (calling `handleRequested`/`handleCoreResponse` twice in-process) but does not exercise a real Kafka consumer group rebalance/offset re-read. This matches `design.md` §4's own stated (lower) bar verbatim, so it is not a deviation from what was designed — but it is weaker than the literal spec Independent Test language implies for "grupos novos... releem o histórico." Not a fix task; worth flagging to the user as an accepted, documented gap rather than silently equating "designed" with "fully proven at the Kafka mechanics level."
3. **[Cosmetic, T22/T23]** `load/capacity/generate_report.py`'s `render_scenario_table` omits avg latency and the per-tenant-key request split from the rendered `.md` report, even though both numbers are computed and present in the raw `steady.summary.json` (verified: `avg=293.6949811397313`, `cap_key_0/1_reqs.count=7651/7650`). AC P3-5 ("o relatório datado SHALL exibir o mix de status") is satisfied (status-code mix is shown), so this is not an AC failure — but a reader who audits only the `.md` (the artifact AC-5 exists to make readable) cannot verify the avg-latency and tenant-split claims that `spec.md`'s own Success Criteria quote, without opening the raw JSON. **Recommendation**: have `render_scenario_table` also print `avg` and the per-key split it already collects.
4. **[Pre-existing flake, unrelated to T1-T24]** `scripts/e2e/payment-failures/scenarios/crash_recovery.sh`'s `outbox-crash-window-reclaim` scenario fails whenever `payment-core-mock`'s probabilistic decision DECLINEs the test payment (topic resolves to `payment.simulation.failed` instead of `.completed`), because its terminal-result-stability check only inspects `result.authorizationCode`, a field that legitimately does not exist on a DECLINED response. Reproduced live in this session (topic confirmed via direct Postgres query). This file was not touched by any T1-T24 commit — it is a latent gap in a pre-existing E2E harness, currently masked by the stage's own 10/11 pass floor. **Recommendation**: fix the assertion to compare on `result.status` (or any field present on both APPROVED and DECLINED bodies) instead of a field that only exists on one outcome — this removes the flake instead of relying on the floor to absorb it.
5. **[Minor scope note, AC-1/AUD-01]** No single IT exercises the full cross-boundary "409 within the API's 15-min reservation window, SBUS-level fallback after it expires" path end-to-end; `payment-sbus`'s `IdempotencyReplayIT` (correctly, given per-boundary test scoping) only exercises the SBUS-side fallback in isolation, calling `PaymentPersistenceService`/`PaymentSimulationService` directly rather than going through `payment-api`. Architecturally sound given AD-001's boundary separation, but the literal spec Independent Test wording implies an end-to-end scenario that doesn't exist as a single test anywhere in the repo. Not a fix task; a residual coverage note.

---

## Code Quality

| Principle | Status |
| --- | --- |
| Minimum code | ✅ — every diff read was scoped to its stated `file:line`, no unrelated churn observed |
| Surgical changes | ✅ |
| No scope creep | ✅ |
| Matches patterns | ✅ — Lua CAS pattern reused consistently between AUD-03 (T17) and AUD-13 (T18) as design.md promised |
| Spec-anchored outcome check (asserted values match spec) | ✅ — see full AC table above |
| Per-layer Coverage Expectation met (domain 1:1 ACs; routes happy+edge+error) | ✅ |
| Every test maps to a spec requirement — no unclaimed tests | ✅ |
| Documented guidelines followed | ✅ — `AGENTS.md` per boundary, `scripts/*/validate_docs.py` doc-drift gates |

---

## Gate Check

- **Gate command**: `scripts/verify-workspace.sh` (stage=all) + boundary-scoped `./gradlew test [-PwithIT]` for the 5 discrimination-sensor targets
- **Result**: `verify-workspace.sh`: 8/8 stages PASS (`payment-failures` at 10/11, within its documented floor — see Gap 4). Discrimination sensor: 5/5 mutations killed.
- **Test count**: baseline growth confirmed via `equivalence verify`: 436 entries, PASS; spec.md's own claimed deltas (sources 204→211, tests 115→127, test_cases 535→592, migrations 9→11) were not independently re-derived line-by-line by this Verifier (would require diffing the full equivalence manifest) but the `equivalence: PASS` gate — whose entire purpose is to fail on any lost/shrunk baseline item — passing is itself strong evidence no regression occurred.
- **Skipped tests**: none observed.
- **Failures**: 1 (`outbox-crash-window-reclaim`, pre-existing harness bug, Gap 4) — not a regression from this feature, within the stage's own pass floor.

---

## Requirement Traceability Update

All 27 `AUD-xx` rows in `spec.md` were already marked `Verified` with commit pointers by the batch workers; this Verifier's independent re-derivation confirms every one is accurately evidenced. No status changes required.

---

## Summary

**Overall**: ✅ Ready

**Spec-anchored check**: 27/27 AUD-xx requirements confirmed with `file:line` evidence and precise-value assertions (1 spec-precision gap on T1's test-methodology wording, not on the code's correctness).

**Sensor**: 5/5 mutations killed across all 4 boundaries (feature-control ×2, payment-api, payment-sbus, async-redis-service).

**Gate**: 8/8 `verify-workspace.sh` stages PASS live, in this session, under this Verifier's own execution.

**What works**: All 27 audit findings are genuinely fixed with tests that discriminate correct from incorrect behavior (proven empirically, not assumed from names). The kill-switch latch, dual-budget admission, fingerprint escaping/parity, replay-fingerprint resolution, CAS state transitions, and the capacity gate's honesty are all real. Docker/live stack is healthy after the session's earlier disk-exhaustion recovery.

**Issues found**: 5 gaps, all non-blocking (see Ranked Gaps above) — 1 documentation-accuracy overclaim (T1), 1 residual coverage note matching design's own stated bar (T15), 1 cosmetic report-rendering gap (T22/T23), 1 pre-existing unrelated test-harness flake (payment-failures), 1 residual cross-boundary coverage note (AC-1).

**Next steps**: None required to consider `adversarial-audit-fixes` closed. Optional follow-ups (not blocking): reword T1's test-methodology claims; fix `crash_recovery.sh`'s DECLINED-payment assertion; extend `render_scenario_table` to print avg latency + tenant split.
