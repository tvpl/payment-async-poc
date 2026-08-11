# Testes

## Gate rápido

`./gradlew test --no-daemon` executa unidade (`*UnitTest.java`) e o `StandaloneBoundaryTest` estrutural. Sem dependência externa.

## Gate de integração

`./gradlew test -PwithIT --no-daemon` cobre Redis real (`localhost:6379`): aceitação/idempotência, backpressure do pool de espera, identidade única de worker, reconexão com backoff, liberação atômica de resultado, DLQ durável e retenção. 96 testes ao final do Phase 7 (T38–T44).

## Documentação e imagem

`scripts/verify-docs.sh` valida pacote, links, claims e ADR. `deploy/verify.sh --structural` valida Dockerfile/Compose sem runtime; sem a opção, constrói e inspeciona a imagem — exige Docker com acesso ao registry de imagens base. Dependência indisponível é `NOT_RUN`, nunca aprovação.

O baseline só cresce. Testes não podem ser apagados, ignorados ou enfraquecidos para fechar gate.
