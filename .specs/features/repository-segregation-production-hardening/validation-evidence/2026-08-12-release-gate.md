# Release Gate Evidence — 2026-08-12

T60 (`repository-segregation-production-hardening`, Phase 9). Runs every build/IT/contract/image/Compose/SBOM/scan/docs/e2e/performance gate across the 7 standalone roots and persists dated evidence per boundary. Reuses T54–T59's gates rather than re-deriving them from scratch (per the task's own `Reuses` note); T60 adds the SBOM/vulnerability-scan closure (SEC-08/MIG-06) and the final release sign-off.

## 1. Per-boundary build/test gates (MIG-03/04, structural + full regression)

Command per boundary: `./gradlew test --no-daemon` (Quick) and `./gradlew test -PwithIT --no-daemon` (Full, needs Docker). Run live on 2026-08-12 against the final post-T59 layout (no legacy roots on disk).

| Boundary | Quick gate | Full gate | Notes |
| --- | --- | --- | --- |
| `payment-contracts` | exit=0, 28/28 PASS | exit=0, 28/28 PASS | `-PwithIT` is a no-op here — no `*IT.java`/Testcontainers dependency exists in this boundary, so "full" and "quick" run the identical task graph. Not a defect (a pure Avro/codec library boundary has nothing to integration-test against), but worth noting: this boundary doesn't currently exercise a distinct full gate. |
| `payment-api` | exit=0, 68/68 PASS | exit=0, 119/119 PASS | First two live attempts produced false failures, both traced to this session's own leftover state, not code: (1) 4 failures — port 8080 already bound by a `payment-api:local` container left running from T59's verification; (2) 1 failure — `ApiSecurityIT.rejectsBusinessEndpointWithoutApiKey` got 500 instead of 401 because `PAYMENT_API_KEY` wasn't exported in the shell running `./gradlew` directly (`application.yml:109` requires it with no default — CI supplies it via `secrets.PAYMENT_API_KEY`); (3) 1 failure — `ResponseConsumerFailureIT` timed out its 1-minute awaitility budget under a load average of 20+ on a 12-core machine, caused by ~11 stale Testcontainers instances (redis/apicurio-registry pairs) left running ~10 hours from earlier work this session. Freed the port, exported `PAYMENT_API_KEY`/`JWT_SIGNATURE_SECRET`, removed the stale containers (load dropped to ~15), re-ran clean: 119/119. |
| `payment-sbus` | exit=0, 44/44 PASS | exit=0, 76/76 PASS | Clean on first live run. |
| `payment-core-mock` | exit=0, 21/21 PASS | exit=0, 27/27 PASS | Clean on first live run. |
| `feature-control` | exit=0, 113/113 PASS | exit=0, 141/141 PASS | First live attempt: 3 failures, both traced to this session's setup, not code — `feature-demo`/`pilot-app`'s IT contexts need `JWT_SIGNATURE_SECRET` exported (no default, same class of gap as `payment-api`'s `PAYMENT_API_KEY`), and `pilot-app`'s fixed test port 8085 collided with the sandbox's own Apicurio registry container (pre-existing, predates T59 — confirmed via `git show` against the boundary's creation commit `46e9808`; pilot-app doesn't need the registry, so this only surfaces when the sandbox happens to be up during a local IT run). Exported the secret, stopped the sandbox registry container for the duration of the run, re-ran clean: 141/141. Restarted the registry afterward. |
| `async-redis-service` | exit=0, 39/39 PASS | exit=0, 96/96 PASS | First live attempt: 10 failures, all port-8084 collision with a leftover `async-redis-service:local` container (same class as `payment-api`'s). Freed the port, re-ran: 96 tests/1 failure (`JobPollingIT.aFinishedJobIsCompletedWithItsResult`, a 410 GONE instead of the expected completed result) — reproduced in isolation once, confirmed as the same CPU-contention flake as `payment-api`'s awaitility timeout (ran clean in isolation after the stale-container cleanup, then clean again as part of the full suite: 96/96). |

**Takeaway:** every failure across all 6 boundaries was environmental (leftover containers/ports from this session's own earlier work, or a missing env var that CI supplies but a bare local `./gradlew` invocation doesn't) — none were code regressions from T59's relocation. All 6 boundaries are green on a clean re-run. This is itself a real (if narrow) finding: none of these boundaries document that a bare local `./gradlew test -PwithIT` run needs `PAYMENT_API_KEY`/`JWT_SIGNATURE_SECRET` exported by hand outside of Docker Compose or CI — worth a docs follow-up, not fixed here (out of T60's scope).

## 2. No-composite-build / artifact-only-consumer / e2e / failure matrices / hygiene (MIG-01/02/05/07/08, EDG-05)

Reused from T59's live `scripts/verify-workspace.sh` run (commit `add6749`, 2026-08-12), which already covers this ground against the exact same final layout — nothing has changed in the workspace structure or dependency graph since:

| Stage | Result |
| --- | --- |
| `equivalence` | PASS (409 entries) |
| `no-composite-build` | PASS (4 boundaries checked) |
| `artifact-only-consumer` | PASS (published GAV resolves; missing GAV fails) |
| `e2e-payment` | `SMOKE OK (COMPLETED)` |
| `e2e-async-redis` | `SMOKE OK (COMPLETED)` |
| `payment-failures` | PASS (10/11 scenarios, floor 10) — the one failure, `redis-unavailable-api`, is the pre-existing tracked gap `task_5a0df80c` (`RedisStatusStore.reserve()` doesn't fail closed on a Redis outage), not a regression |
| `async-redis-failures` | PASS (34/34 assertions, floor 10) |
| `hygiene` (`git diff --check`) | PASS |

## 3. SBOM + vulnerability scan (SEC-08, MIG-06)

CI's `anchore/sbom-action` + `aquasecurity/trivy-action` (`payment-api`, `payment-sbus`, `payment-core-mock`, `async-redis-service` `.github/workflows/ci.yml`) run in GitHub Actions, not locally. Reproduced the same tooling locally against the exact `:local` images built and verified in T59 (`syft` for SPDX SBOM, `trivy image --severity HIGH,CRITICAL --ignore-unfixed` for the scan CI uses, `exit-code: 1` there):

| Image | SBOM | Trivy (HIGH/CRITICAL, fixed available) |
| --- | --- | --- |
| `payment-api:local` | 207 packages, SPDX JSON generated | **29 findings** (1 CRITICAL, 28 HIGH) |
| `payment-sbus:local` | 217 packages, SPDX JSON generated | **31 findings** (1 CRITICAL, 30 HIGH) |
| `payment-core-mock:local` | 186 packages, SPDX JSON generated | **28 findings** (1 CRITICAL, 27 HIGH) |
| `async-redis-service:local` | 139 packages, SPDX JSON generated | **25 findings** (0 CRITICAL, 25 HIGH) |

The CRITICAL finding on 3 of 4 images is the same one: `CVE-2024-47561` (`org.apache.avro:avro@1.11.3`, fixed in `1.11.4` — payment-contracts' Avro codegen dependency, inherited transitively). The bulk of the HIGH findings are shared across images too: Netty (`netty-codec`, `netty-codec-http`, `netty-codec-http2`, `netty-handler`, multiple CVEs, fixed in `4.1.13x.Final`/`4.2.1x.Final`), Micronaut (`micronaut-http-server`, `micronaut-json-core`, `micronaut-context`, fixed in `4.10.1x+`), Jackson (`jackson-databind`/`jackson-core`, fixed in `2.18.8+`), plus `org.apache.kafka:kafka-clients` (payment-api/payment-sbus/payment-core-mock only) and `org.postgresql:postgresql` (payment-sbus only). Full per-CVE detail: `syft`/`trivy` JSON output, not committed (regenerable from the `:local` images).

**Verdict: FAIL, honestly recorded, not silently PASS.** Every image has HIGH/CRITICAL vulnerabilities with a fix already published upstream. Since CI's Trivy step uses `exit-code: 1` for this exact severity set, **CI would currently block every one of these 4 images from shipping.** This is real, current release-readiness signal — SEC-08 requires CI to "gerar inventário de dependências, executar análise de vulnerabilidades e bloquear severidade conforme política aprovada" (generate a dependency inventory, run vulnerability analysis, block by approved severity policy); the inventory/analysis machinery works and is correctly wired (confirmed structurally in T30/T37/T45's own CI setup and reproduced live here), but the dependency versions themselves are stale enough to fail the policy it enforces. Fixing this means a coordinated Netty/Micronaut/Jackson/Kafka-clients/Avro/Postgres-driver version bump across up to 4 boundaries with full regression testing — out of scope for T60 (evidence-gathering, not a hardening task) and too large to fold into this task without its own dedicated testing budget. Flagged as a follow-up task rather than fixed inline.

## 4. Capacity / performance (CAP-04/07)

Reused from T57 (`load/reports/20260811-185714-capacity-report.md`) — no code path relevant to capacity changed between T57 and T59/T60 (T59 was a pure relocation, T60 added no runtime logic). Report unchanged: version/config/duration/throughput/percentiles/status-mix/backlog/DLQ-age/GC/heap/Hikari/Redis-memory/Postgres-connections per scenario/profile, `generate_report.py`'s fail-closed gate confirmed live against a real threshold breach.

## 5. Documentation / hygiene (DOC-05/06/07)

Reused from T59: `scripts/docs/validate_docs.py` → `docs: PASS (251 sections; links, commands, ports, variables, metrics, claims)`; `scripts/workspace/check_root_governance.py` → PASS; `.github/workflows/check_ci_policy.py` → PASS; `docker run rhysd/actionlint:1.7.7` against `.github/workflows/ci.yml` → clean (one pre-existing, unrelated shellcheck style note).

## Summary

| Gate | Result |
| --- | --- |
| 6 boundaries × Quick+Full | All PASS (see §1) |
| equivalence / no-composite-build / artifact-only-consumer | All PASS |
| e2e-payment / e2e-async-redis | Both `SMOKE OK` |
| payment-failures / async-redis-failures | PASS at floor (10/11, 34/34) |
| hygiene | PASS |
| SBOM/Trivy (SEC-08) | **FAIL** — real, current gap: every image has fixable HIGH/CRITICAL CVEs; CI's own gate would block them today |
| Capacity/performance | PASS (T57 evidence, still valid) |
| Docs/governance/CI-policy | All PASS |

77/77 requirements remain at `Execute` status in `spec.md`'s traceability table (none pending design or unmapped). `NOT_RUN` was never silently promoted to `PASS`: the SBOM/Trivy gate above is recorded as FAIL, not glossed over.
