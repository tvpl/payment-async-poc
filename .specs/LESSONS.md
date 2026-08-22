# LESSONS - auto-maintained by scripts/lessons.py

> Machine-owned. Do NOT hand-edit. Changes are overwritten on the next `lessons.py` write.
> Canonical state lives in `.specs/lessons.json`. Edit lessons only via the script.
> promote_threshold=2 distinct features · window_days=45 · quarantine_threshold=2

## Confirmed (load these at Specify/Design)

Corroborated across multiple features. Safe to apply as guidance.

_none_

## Candidates (under observation - do NOT load as guidance yet)

Seen once or not yet corroborated. Tracked, not trusted.

### L-001 - A utility's own unit test does not prove its call sites are wired — add a test asserting the caller's observable side effect (e.g. capture MDC state via doAnswer on the mocked collaborator during the call), not just the utility's isolated contract.
- signal: `surviving_mutant` · recurrence: 1 feature(s) · scope: `testing,mdc,wiring` · harmful: 0
- features: repository-segregation-production-hardening
- evidence: payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java:63,85 (testing,mdc,wiring)
- last seen: 2026-08-12T10:22:11Z

### L-002 - When a spec or task claims a test exercises a real dependency outage, verify the test actually stops the real process instead of throwing an in-process exception from a wrapper — an outage simulated by exception injection is a legitimate tradeoff under shared-infra constraints, but the spec wording must say so instead of claiming a literal stop.
- signal: `spec_precision_gap` · recurrence: 1 feature(s) · scope: `testing` · harmful: 0
- features: adversarial-audit-fixes
- evidence: spec.md P3 Success Criteria / tasks.md T1 Done-when vs feature-control/library/src/test/java/com/example/platform/featurecontrol/resolver/MasterSwitchIT.java (testing)
- last seen: 2026-08-14T17:08:32Z

### L-003 - When an E2E scenario depends on a probabilistic mock outcome (e.g. approve/decline), assert on a field present in every outcome branch, not one that only exists on a single branch — comparing an absent-on-both-sides field as if it changed produces an intermittent false failure indistinguishable from a real regression.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `e2e` · harmful: 0
- features: adversarial-audit-fixes
- evidence: scripts/e2e/payment-failures/scenarios/crash_recovery.sh:48 (outbox-crash-window-reclaim) (e2e)
- last seen: 2026-08-14T17:08:32Z

### L-004 - Um pool de teste com maximum-pool-size=1 não sobe com micronaut-flyway: o Flyway migra dentro da criação do bean DataSource e abre uma segunda conexão (getMigrationConnection) com a do schema history ainda checked-out; dimensione o pool em 2 e exaure segurando todas as conexões.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `payment-sbus/test` · harmful: 0
- features: hikari-pool-health-it-red
- evidence: payment-sbus/src/test/java/com/example/payments/sbus/health/HikariPoolHealthIndicatorIT.java:174 (payment-sbus/test)
- last seen: 2026-08-22T13:37:30Z

### L-005 - Com micronaut-data-jdbc no classpath, o bean DataSource devolve conexões context-managed cujo close() lança NoConnectionException fora de @Connectable/@Transactional; teste que precisa de checkout cru deve desembrulhar via DataSourceResolver.resolve(...) ou vaza cada conexão que pega.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `payment-sbus/test` · harmful: 0
- features: hikari-pool-health-it-red
- evidence: payment-sbus/src/test/java/com/example/payments/sbus/health/HikariPoolHealthIndicatorIT.java:159 (payment-sbus/test)
- last seen: 2026-08-22T13:37:30Z

### L-006 - Um teste de concorrência não deve provar 'processou em paralelo' comparando durações (razão de tempo contra baseline): a margem que separa serializado de concorrente encolhe num host lento/contendido até jitter comum cruzá-la. Prefira observar sobreposição real de threads (ex.: ThreadMXBean amostrando se ≥2 threads estão dentro do método-alvo no mesmo instante) — o sinal deixa de depender da velocidade do ambiente.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `payment-sbus/test` · harmful: 0
- features: payment-requested-concurrency-it-flake
- evidence: payment-sbus/src/test/java/com/example/payments/sbus/kafka/PaymentRequestedConsumerConcurrencyIT.java:118 (payment-sbus/test)
- last seen: 2026-08-22T15:50:08Z

### L-007 - Não rode -PwithIT de múltiplas fronteiras (cada uma com seus próprios Testcontainers: Postgres+Kafka+Apicurio+Redis) em paralelo neste sandbox de 4 CPUs — 3 suítes concorrentes chegaram a load average 500+ e produziram um falso negativo no PaymentRequestedConsumerConcurrencyIT (thread-overlap sampler starved, não defeito real: mesma suíte passou limpa em isolamento logo depois). Sequencie gates pesados com Testcontainers; verifique 'cat /proc/loadavg' e 'free -h' antes de rodar mais um.
- signal: `gate_fail` · recurrence: 1 feature(s) · scope: `process` · harmful: 0
- features: workspace-boundary-reverification
- evidence: sandbox/session-note:2026-08-22 (process)
- last seen: 2026-08-22T21:03:45Z

## Quarantined (failed when applied - ignore)

A confirmed lesson that recurred alongside failure. Kept for the maintainer to review.

_none_
