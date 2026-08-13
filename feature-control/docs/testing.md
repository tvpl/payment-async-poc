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

Requer Redis real em `localhost:6379`. Cobre `library` (CAS/auditoria contra Redis real, pubsub multi-instância, cache last-known-good), `feature-demo` (fluxo HTTP completo: JWT, admin, `NonProductionGuardIT`) e `pilot-app` (adoção mínima + `NonProductionGuardIT`).

Contagem de referência, medida ao vivo em 2026-08-13: **113 testes no gate rápido, 150 no gate de integração**, ambos sem falha ou skip. O baseline só cresce — teste não pode ser apagado, ignorado ou enfraquecido para fechar gate, e `NOT_RUN` nunca vira `PASS`.

Duas condições do ambiente local fazem o gate de integração falhar sem que exista defeito, e ambas produzem erro claro:

- `JWT_SIGNATURE_SECRET` precisa estar exportado (não tem default, por decisão de segurança). Sem ele, os contextos dos exemplos não sobem.
- `pilot-app` usa a porta fixa `8085` em teste, que é a mesma porta publicada pelo Apicurio Registry do sandbox. Com o sandbox de pé, pare o registry durante a execução ou rode o gate com ele parado — o `pilot-app` não depende do Registry.

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
