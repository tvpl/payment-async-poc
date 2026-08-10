# Testes

## Gate rápido

```bash
./gradlew test --no-daemon
```

Unidade apenas (`*UnitTest.java`, `*Test.java` sem sufixo `IT`); exclui integração por padrão (`excludeTestsMatching '*IT'` no `build.gradle` raiz).

## Gate de integração

```bash
./gradlew test -PwithIT --no-daemon
```

Requer Redis real em `localhost:6379`. Cobre `library` (CAS/auditoria contra Redis real, pubsub multi-instância, cache last-known-good), `feature-demo` (fluxo HTTP completo: JWT, admin, `NonProductionGuardIT`) e `pilot-app` (adoção mínima + `NonProductionGuardIT`). Baseline mínimo: 31 testes pré-existentes de `feature-control` + exemplos nunca diminui silenciosamente; a contagem atual, com T50-T53, soma bem além disso (ver `tasks.md` para o detalhe por tarefa) — `NOT_RUN` nunca vira `PASS`.

## Documentação e imagem

```bash
scripts/verify-docs.sh
bash scripts/verify-consumer-fixture.sh
```

`verify-docs.sh` roda `validate_docs.py` (documentos obrigatórios, links, marcadores de conteúdo, cabeçalhos de ADR) e a suíte `test_docs.py`. `verify-consumer-fixture.sh` roda o gate completo de publicação e compatibilidade (ver [configuração](configuration.md#publicação-e-consumer-fixture)).

## Suíte Python

```bash
python3 -m unittest discover -s scripts -p "test_*.py"
```
