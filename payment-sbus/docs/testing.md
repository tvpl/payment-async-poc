# Testes

## Gate rápido

`./gradlew test --no-daemon` executa unidade e checks estruturais.

## Gate de integração

`./gradlew test -PwithIT --no-daemon` cobre Kafka, PostgreSQL, Redis, Registry, segurança, concorrência, retry e DLQ com dependências reais.

## Documentação e imagem

`scripts/verify-docs.sh` valida pacote, links, claims e ADR. `deploy/verify.sh --structural` valida Dockerfile/Compose sem runtime; sem a opção, constrói e inspeciona a imagem. Dependência indisponível é `NOT_RUN`.

O baseline só cresce. Testes não podem ser apagados, ignorados ou enfraquecidos para fechar gate.
