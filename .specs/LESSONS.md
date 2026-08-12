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

## Quarantined (failed when applied - ignore)

A confirmed lesson that recurred alongside failure. Kept for the maintainer to review.

_none_
